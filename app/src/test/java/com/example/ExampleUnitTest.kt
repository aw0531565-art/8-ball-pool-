package com.example

import com.example.data.db.BallTelemetryEntity
import com.example.data.db.ExperienceEntity
import com.example.data.db.NeuroBotDao
import com.example.data.db.QTableEntryEntity
import com.example.data.db.ShotPhysicsEntity
import com.example.data.db.TrainingProfileEntity
import com.example.data.db.TrainingSessionEntity
import com.example.data.repository.BotMemoryRepository
import com.example.domain.physics.PhysicsEstimator
import com.example.domain.physics.PoolPhysicsSimulator
import com.example.domain.rl.BotAction
import com.example.domain.rl.SelfTrainingEngine
import com.example.domain.vision.AssignedSuit
import com.example.domain.vision.BallSuit
import com.example.domain.vision.DetectedBall
import com.example.domain.vision.DetectedBallKind
import com.example.domain.vision.PlannedPoolShot
import com.example.domain.vision.PoolFrameAnalysis
import com.example.domain.vision.PoolPocket
import com.example.domain.vision.PoolVisionAnalyzer
import com.example.domain.vision.TurnOwner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun poolVisionAnalyzer_computesTrimmedAimPointWithinTableBounds() {
        val shot = PlannedPoolShot(
            cueBall = DetectedBall(0.65f, 0.58f, DetectedBallKind.CUE, 0),
            targetBall = DetectedBall(0.49f, 0.65f, DetectedBallKind.TARGET, 0, BallSuit.SOLID),
            pocket = PoolPocket("BL", 0.18f, 0.91f),
            aimPointX = 0.51f,
            aimPointY = 0.64f,
            cutAngleDeg = 12f,
            totalDistance = 0.58f,
            basePowerFraction = 0.72f,
            stateHash = "POOL_A0_D2_BL",
            stateSummary = "Test shot"
        )
        val (ax, ay) = PoolVisionAnalyzer.computeAimPointWithTrim(shot, angleTrimDeg = -2.8f)
        assertTrue(ax in 0.16f..0.84f)
        assertTrue(ay in 0.18f..0.94f)
    }

    @Test
    fun poolVisionAnalyzer_strictlyFiltersOwnSuitAndAvoidsObstacles() {
        val cue = DetectedBall(0.50f, 0.55f, DetectedBallKind.CUE, 0)
        val solidBall = DetectedBall(0.66f, 0.36f, DetectedBallKind.TARGET, 0, BallSuit.SOLID)
        val stripeBall = DetectedBall(0.32f, 0.32f, DetectedBallKind.TARGET, 0, BallSuit.STRIPE)
        val eightBall = DetectedBall(0.45f, 0.70f, DetectedBallKind.EIGHT_BALL, 0, BallSuit.EIGHT_BALL)

        val solidsOnly = PoolVisionAnalyzer.filterLegalTargets(
            effectiveSuit = AssignedSuit.SOLIDS,
            targets = listOf(solidBall, stripeBall),
            eightBall = eightBall
        )
        assertEquals(1, solidsOnly.size)
        assertEquals(BallSuit.SOLID, solidsOnly.first().suit)

        val stripesOnly = PoolVisionAnalyzer.filterLegalTargets(
            effectiveSuit = AssignedSuit.STRIPES,
            targets = listOf(solidBall, stripeBall),
            eightBall = eightBall
        )
        assertEquals(1, stripesOnly.size)
        assertEquals(BallSuit.STRIPE, stripesOnly.first().suit)

        // When own suit is completely cleared, only 8-ball is legal
        val eightOnly = PoolVisionAnalyzer.filterLegalTargets(
            effectiveSuit = AssignedSuit.EIGHT_BALL,
            targets = listOf(stripeBall),
            eightBall = eightBall
        )
        assertEquals(1, eightOnly.size)
        assertEquals(DetectedBallKind.EIGHT_BALL, eightOnly.first().kind)

        // Verify obstacle avoidance prefers unblocked pocket/target over straight blocked one
        val pockets = listOf(
            PoolPocket("TL", 0.19f, 0.22f),
            PoolPocket("TR", 0.80f, 0.22f)
        )
        val blockedSolid = DetectedBall(0.30f, 0.30f, DetectedBallKind.TARGET, 0, BallSuit.SOLID)
        val blockerStripe = DetectedBall(0.40f, 0.42f, DetectedBallKind.TARGET, 0, BallSuit.STRIPE)
        val clearSolid = DetectedBall(0.68f, 0.32f, DetectedBallKind.TARGET, 0, BallSuit.SOLID)

        val planned = PoolVisionAnalyzer.computeBestShot(
            cue = cue,
            targets = listOf(blockedSolid, clearSolid),
            pockets = pockets,
            fLeft = 0.18f,
            fTop = 0.20f,
            fRight = 0.81f,
            fBottom = 0.92f,
            allObstacles = listOf(blockedSolid, clearSolid, blockerStripe, eightBall),
            aspectRatio = 2.0f
        )
        assertNotNull(planned)
        assertEquals("TR", planned!!.pocket.id)

        // Verify failed-shot penalty pushes bot to avoid repeating a previously failed pocket/state
        val penalized = PoolVisionAnalyzer.computeBestShot(
            cue = cue,
            targets = listOf(blockedSolid, clearSolid),
            pockets = pockets,
            fLeft = 0.18f,
            fTop = 0.20f,
            fRight = 0.81f,
            fBottom = 0.92f,
            allObstacles = listOf(blockedSolid, clearSolid),
            aspectRatio = 2.0f,
            failedShotPenalties = mapOf(planned.stateHash to 80f)
        )
        assertNotNull(penalized)
        assertEquals("TL", penalized!!.pocket.id)
    }

    @Test
    fun physicsEstimator_guardsZeroTravelFramesAndLearnsFromHumanObservation() = runTest {
        val fakeDao = InMemoryNeuroBotDao()
        val repo = BotMemoryRepository(fakeDao)
        repo.ensureDefaultProfiles()
        val profile = repo.getProfile(1L)!!

        val shot = PlannedPoolShot(
            cueBall = DetectedBall(0.60f, 0.55f, DetectedBallKind.CUE, 0),
            targetBall = DetectedBall(0.42f, 0.62f, DetectedBallKind.TARGET, 0, BallSuit.SOLID),
            pocket = PoolPocket("BL", 0.18f, 0.91f),
            aimPointX = 0.44f,
            aimPointY = 0.61f,
            cutAngleDeg = 14f,
            totalDistance = 0.48f,
            basePowerFraction = 0.65f,
            stateHash = "POOL_A0_D2_BL",
            stateSummary = "Cut 14"
        )

        // Zero-travel stalled frame must NOT drift friction to 0.92 or inflate powerScale
        val zeroOutcome = PhysicsEstimator.evaluatePostShotPhysics(
            profile = profile,
            shotNumber = 1,
            preShot = shot,
            powerFraction = 0.70f,
            angleTrimDeg = 0f,
            postCueBall = DetectedBall(0.60f, 0.55f, DetectedBallKind.CUE, 0),
            pottedBall = false,
            keptTurn = false,
            reward = -1.6f
        )
        assertEquals(profile.learnedFrictionCoef, zeroOutcome.updatedProfile.learnedFrictionCoef, 0.0001f)
        assertEquals(profile.learnedPowerScale, zeroOutcome.updatedProfile.learnedPowerScale, 0.0001f)
        assertEquals(1, zeroOutcome.updatedProfile.totalMistakesLogged)

        // White cue ball scratch must classify explicitly as CUE_SCRATCH and increment scratchesCount
        val scratchOutcome = PhysicsEstimator.evaluatePostShotPhysics(
            profile = zeroOutcome.updatedProfile,
            shotNumber = 2,
            preShot = shot,
            powerFraction = 0.75f,
            angleTrimDeg = 0f,
            postCueBall = null,
            pottedBall = false,
            keptTurn = false,
            reward = -4.5f,
            scratched = true
        )
        assertEquals("CUE_SCRATCH", scratchOutcome.outcomeLabel)
        assertEquals(1, scratchOutcome.updatedProfile.scratchesCount)
        assertEquals(2, scratchOutcome.updatedProfile.totalMistakesLogged)

        // Now test passive Human Shot Observation & Imitation Learning
        val pockets = listOf(
            PoolPocket("TL", 0.19f, 0.22f),
            PoolPocket("BR", 0.80f, 0.90f)
        )
        val humanTarget = DetectedBall(0.68f, 0.78f, DetectedBallKind.TARGET, 0, BallSuit.STRIPE)
        val preAnalysis = PoolFrameAnalysis(
            isMyTurn = false,
            turnOwner = TurnOwner.RIGHT_PLAYER,
            leftGreenRatio = 0.005f,
            rightGreenRatio = 0.085f,
            powerBarVisible = false,
            greenTurnRatio = 0.005f,
            pottedOccupiedRatio = 0.28f,
            detectedSuit = AssignedSuit.SOLIDS,
            effectiveSuit = AssignedSuit.SOLIDS,
            cueBall = DetectedBall(0.52f, 0.62f, DetectedBallKind.CUE, 0),
            targetBalls = listOf(humanTarget),
            eightBall = DetectedBall(0.40f, 0.40f, DetectedBallKind.EIGHT_BALL, 0, BallSuit.EIGHT_BALL),
            pockets = pockets,
            plannedShot = null,
            visionGrid = List(36) { 0.1f },
            summary = "HUMAN TURN"
        )
        val postAnalysis = preAnalysis.copy(
            cueBall = DetectedBall(0.71f, 0.81f, DetectedBallKind.CUE, 0),
            targetBalls = emptyList(), // Human potted the stripe ball!
            pottedOccupiedRatio = 0.42f
        )

        val humanObs = PhysicsEstimator.evaluateHumanShotObservation(
            profile = profile,
            shotNumber = 2,
            preAnalysis = preAnalysis,
            postAnalysis = postAnalysis,
            pottedBall = true,
            keptTurn = true
        )
        assertNotNull(humanObs)
        assertEquals(1, humanObs!!.updatedProfile.humanShotsObserved)
        assertEquals("BR", humanObs.pocketId)
        assertTrue(humanObs.reward > 5.0f)

        repo.insertShotPhysics(humanObs.shotLog)
        repo.updateProfile(humanObs.updatedProfile)
        val report = repo.exportProfileBrainJson(1L)
        assertTrue(report.contains("\"humanShotsObserved\": 1"))
        assertTrue(report.contains("\"botSeat\": \"LEFT\""))
    }

    @Test
    fun selfTrainingEngine_learnsPoolShotPolicyAndReplaysSavedMemory() = runTest {
        val fakeDao = InMemoryNeuroBotDao()
        val repo = BotMemoryRepository(fakeDao)
        repo.ensureDefaultProfiles()

        val engine = SelfTrainingEngine(repo)
        engine.ensureLoaded(1L)

        val qGood = engine.recordStep(
            profileId = 1L,
            episodeNumber = 1,
            stepIndex = 1,
            stateHash = "POOL_A1_D2_BL",
            stateSummary = "Cut 15 deg -> BL",
            action = BotAction.SHOT_MED_EXACT,
            reward = 5.5f,
            nextStateHash = "POOL_A0_D1_TR",
            isTerminal = false,
            availableActions = BotAction.POOL_SHOT_ACTIONS,
            learningRate = 0.4f,
            discountFactor = 0.9f,
            source = "HUMAN_OBSERVED"
        )
        val qMiss = engine.recordStep(
            profileId = 1L,
            episodeNumber = 1,
            stepIndex = 2,
            stateHash = "POOL_A1_D2_BL",
            stateSummary = "Cut 15 deg -> BL",
            action = BotAction.SHOT_POWER_EXACT,
            reward = -1.6f,
            nextStateHash = "POOL_WAIT",
            isTerminal = true,
            availableActions = BotAction.POOL_SHOT_ACTIONS,
            learningRate = 0.4f,
            discountFactor = 0.9f,
            source = "LIVE_POOL"
        )

        assertTrue(qGood > 0f)
        assertTrue(qMiss < 0f)

        val decision = engine.selectAction(
            profileId = 1L,
            stateHash = "POOL_A1_D2_BL",
            availableActions = BotAction.POOL_SHOT_ACTIONS,
            epsilon = 0.0f
        )
        assertEquals(BotAction.SHOT_MED_EXACT, decision.action)

        engine.flushToDatabase(1L)
        assertEquals(2, repo.getQEntriesOnce(1L).size)
        assertEquals(2, repo.getRecentExperiencesOnce(1L, 50).size)

        val replayResult = engine.runExperienceReplay(
            profileId = 1L,
            learningRate = 0.3f,
            discountFactor = 0.9f,
            availableActions = BotAction.POOL_SHOT_ACTIONS,
            batchSize = 10,
            epochs = 2
        )
        assertEquals(2, replayResult.experiencesReplayed)
        assertEquals(4, replayResult.qUpdatesApplied)
    }

    @Test
    fun repository_repairsDriftedEightBallPoolProfileAndClampsPhysicsScalars() = runTest {
        val fakeDao = InMemoryNeuroBotDao()
        fakeDao.insertProfile(
            TrainingProfileEntity(
                id = 1L,
                name = "8-Ball Pool Auto-Bot",
                targetPackage = "com.miniclip.eightballpool",
                botMode = "GENERAL_TAP",
                learnedPowerScale = 1.34f,
                learnedFrictionCoef = 0.90f,
                physicsSamplesCount = 0
            )
        )
        val repo = BotMemoryRepository(fakeDao)
        repo.ensureDefaultProfiles()
        val repaired = repo.getProfile(1L)!!
        assertEquals("POOL_AIM", repaired.botMode)
        assertTrue(repaired.isLandscapeGame)
        assertTrue(repaired.learnedPowerScale <= 1.22f)
        assertTrue(repaired.learnedFrictionCoef <= 0.78f)
    }

    @Test
    fun serviceBridge_updatesSeatSuitAndShotPhysicsTelemetry() {
        com.example.service.BotServiceBridge.setManualPowerOverride(0.65f)
        com.example.service.BotServiceBridge.setManualSeatSideOverride("RIGHT")
        com.example.service.BotServiceBridge.setManualSuitOverride("STRIPES")
        com.example.service.BotServiceBridge.setHumanShotsObserved(4)
        com.example.service.BotServiceBridge.updateShotForceAndPhysics(0.65f, 0.48f)
        val telemetry = com.example.service.BotServiceBridge.telemetry.value
        assertEquals(0.65f, telemetry.manualPowerOverride!!, 0.001f)
        assertEquals("RIGHT", telemetry.manualSeatSideOverride)
        assertEquals("STRIPES", telemetry.manualSuitOverride)
        assertEquals(4, telemetry.humanShotsObserved)
        assertEquals(0.65f, telemetry.lastShotPowerFraction, 0.001f)
        assertEquals(0.48f, telemetry.estimatedFriction, 0.001f)
        com.example.service.BotServiceBridge.setManualPowerOverride(null)
        com.example.service.BotServiceBridge.setManualSeatSideOverride(null)
        com.example.service.BotServiceBridge.setManualSuitOverride(null)
        com.example.service.BotServiceBridge.setHumanShotsObserved(0)
    }

    @Test
    fun poolPhysicsSimulator_predictsCueTargetAndSecondaryCollisionPaths() {
        val pockets = listOf(
            PoolPocket("TL", 0.19f, 0.22f),
            PoolPocket("TM", 0.50f, 0.21f),
            PoolPocket("TR", 0.80f, 0.22f),
            PoolPocket("BL", 0.19f, 0.90f),
            PoolPocket("BM", 0.50f, 0.91f),
            PoolPocket("BR", 0.80f, 0.90f)
        )
        val cue = DetectedBall(0.45f, 0.56f, DetectedBallKind.CUE, 0, colorTag = "WHITE")
        val target = DetectedBall(0.65f, 0.36f, DetectedBallKind.TARGET, 0, BallSuit.SOLID, colorTag = "RED")
        // Secondary ball placed slightly offset behind/right of target so cue or target caroms into it
        val secondary = DetectedBall(0.70f, 0.39f, DetectedBallKind.TARGET, 0, BallSuit.STRIPE, colorTag = "BLUE")
        val staticEight = DetectedBall(0.28f, 0.72f, DetectedBallKind.EIGHT_BALL, 0, BallSuit.EIGHT_BALL, colorTag = "BLACK")

        // Ghost aim point behind target along line to TR pocket
        val aimX = 0.635f
        val aimY = 0.375f

        val prediction = PoolPhysicsSimulator.simulateShotWithParams(
            cueBall = cue,
            allBalls = listOf(target, secondary, staticEight),
            primaryTarget = target,
            aimPointX = aimX,
            aimPointY = aimY,
            powerFraction = 0.82f,
            pockets = pockets,
            tableBounds = listOf(0.18f, 0.20f, 0.81f, 0.91f),
            aspectRatio = 2.0f
        )

        assertTrue(prediction.collisions.isNotEmpty())
        assertTrue(prediction.cuePath.travelDist > 0.15f)
        assertNotNull(prediction.targetPath)
        assertTrue(prediction.targetPath!!.travelDist > 0.05f)
        val secPath = prediction.allBallPaths.first { it.colorTag == "BLUE" }
        assertTrue(secPath.wasCollided || prediction.collisions.size >= 1)
        val eightPath = prediction.allBallPaths.first { it.kind == DetectedBallKind.EIGHT_BALL }
        assertEquals("EIGHT_BALL", eightPath.role)
        assertEquals(0f, eightPath.travelDist, 0.001f)
    }

    @Test
    fun physicsEstimator_recordsEveryBallAndLearnsFromMultiBallResiduals() = runTest {
        val fakeDao = InMemoryNeuroBotDao()
        val repo = BotMemoryRepository(fakeDao)
        repo.ensureDefaultProfiles()
        val profile = repo.getProfile(1L)!!

        val pockets = listOf(
            PoolPocket("TL", 0.19f, 0.22f),
            PoolPocket("TR", 0.80f, 0.22f),
            PoolPocket("BL", 0.19f, 0.90f),
            PoolPocket("BR", 0.80f, 0.90f)
        )
        val cue = DetectedBall(0.42f, 0.60f, DetectedBallKind.CUE, 0, colorTag = "WHITE")
        val target = DetectedBall(0.60f, 0.42f, DetectedBallKind.TARGET, 0, BallSuit.SOLID, colorTag = "YELLOW")
        val secondary = DetectedBall(0.56f, 0.34f, DetectedBallKind.TARGET, 0, BallSuit.STRIPE, colorTag = "GREEN")
        val staticBall = DetectedBall(0.26f, 0.78f, DetectedBallKind.TARGET, 0, BallSuit.SOLID, colorTag = "RED")
        val eightBall = DetectedBall(0.32f, 0.30f, DetectedBallKind.EIGHT_BALL, 0, BallSuit.EIGHT_BALL, colorTag = "BLACK")

        val preAnalysis = PoolFrameAnalysis(
            isMyTurn = true,
            turnOwner = TurnOwner.LEFT_PLAYER,
            leftGreenRatio = 0.08f,
            rightGreenRatio = 0.004f,
            powerBarVisible = true,
            greenTurnRatio = 0.08f,
            pottedOccupiedRatio = 0.70f,
            botRackCount = 7,
            oppRackCount = 7,
            detectedSuit = AssignedSuit.SOLIDS,
            effectiveSuit = AssignedSuit.SOLIDS,
            cueBall = cue,
            targetBalls = listOf(target, secondary, staticBall),
            eightBall = eightBall,
            pockets = pockets,
            plannedShot = null,
            visionGrid = List(36) { 0.1f },
            summary = "BOT TURN"
        )

        val sim = PoolPhysicsSimulator.simulateShot(
            cueBall = cue,
            allBalls = preAnalysis.allTableTargets,
            primaryTarget = target,
            aimPointX = 0.585f,
            aimPointY = 0.435f,
            powerFraction = 0.80f,
            pockets = pockets,
            tableBounds = preAnalysis.detectedTableBounds,
            aspectRatio = 2.0f,
            profile = profile
        )

        val planned = PlannedPoolShot(
            cueBall = cue,
            targetBall = target,
            pocket = pockets[1],
            aimPointX = 0.585f,
            aimPointY = 0.435f,
            cutAngleDeg = 8f,
            totalDistance = 0.62f,
            basePowerFraction = 0.80f,
            stateHash = "POOL_A0_D2_TR",
            stateSummary = "Test multi-ball"
        )

        // Post-shot: target was potted (absent), cue moved, secondary shifted slightly, static & 8 stayed put
        val postAnalysis = preAnalysis.copy(
            cueBall = DetectedBall(0.68f, 0.38f, DetectedBallKind.CUE, 0, colorTag = "WHITE"),
            targetBalls = listOf(
                DetectedBall(0.60f, 0.27f, DetectedBallKind.TARGET, 0, BallSuit.STRIPE, colorTag = "GREEN"),
                staticBall
            ),
            eightBall = eightBall
        )

        val outcome = PhysicsEstimator.evaluatePostShotPhysics(
            profile = profile,
            shotNumber = 1,
            preShot = planned,
            powerFraction = 0.80f,
            angleTrimDeg = 0f,
            postCueBall = postAnalysis.cueBall,
            pottedBall = true,
            keptTurn = true,
            reward = 5.0f,
            preAnalysis = preAnalysis,
            postAnalysis = postAnalysis,
            simulationPrediction = sim
        )

        // All 5 balls (Cue + 3 targets + 8-ball) must be recorded!
        assertEquals(5, outcome.ballRecords.size)
        assertEquals(5, outcome.updatedProfile.totalBallsRecorded)
        assertTrue(outcome.ballRecords.any { it.ballRole == "CUE" })
        assertTrue(outcome.ballRecords.any { it.ballRole == "PRIMARY_TARGET" && it.actualPotted })
        assertTrue(outcome.ballRecords.any { it.ballRole == "COLLIDED_SECONDARY" && it.actualMovedDist > 0.04f })
        assertTrue(outcome.ballRecords.any { it.ballRole == "STATIC" && !it.wasCollided })

        repo.insertShotPhysics(outcome.shotLog)
        repo.insertBallRecords(outcome.ballRecords)
        repo.updateProfile(outcome.updatedProfile)

        assertEquals(5, repo.getBallRecordsOnce(1L).size)
        val report = repo.exportProfileBrainJson(1L)
        assertTrue(report.contains("\"ballTelemetryRecords\""))
        assertTrue(report.contains("\"ballRestitution\""))
        assertTrue(report.contains("\"cushionBounce\""))
    }

    private class InMemoryNeuroBotDao : NeuroBotDao {
        private val profilesList = mutableListOf<TrainingProfileEntity>()
        private val qList = mutableListOf<QTableEntryEntity>()
        private val expList = mutableListOf<ExperienceEntity>()
        private val sessionList = mutableListOf<TrainingSessionEntity>()
        private val physicsList = mutableListOf<ShotPhysicsEntity>()
        private val ballRecordsList = mutableListOf<BallTelemetryEntity>()
        private var nextProfileId = 1L
        private var nextExpId = 1L
        private var nextSessionId = 1L
        private var nextPhysicsId = 1L
        private var nextBallRecordId = 1L

        override fun observeProfiles(): Flow<List<TrainingProfileEntity>> = MutableStateFlow(profilesList.toList())
        override suspend fun getProfilesOnce(): List<TrainingProfileEntity> = profilesList.toList()
        override suspend fun getProfileById(profileId: Long): TrainingProfileEntity? =
            profilesList.firstOrNull { it.id == profileId }

        override suspend fun insertProfile(profile: TrainingProfileEntity): Long {
            val id = if (profile.id == 0L) nextProfileId++ else profile.id
            profilesList.removeAll { it.id == id }
            profilesList.add(profile.copy(id = id))
            return id
        }

        override suspend fun updateProfile(profile: TrainingProfileEntity) {
            profilesList.removeAll { it.id == profile.id }
            profilesList.add(profile)
        }

        override suspend fun deleteProfile(profileId: Long) {
            profilesList.removeAll { it.id == profileId }
        }

        override fun observeQEntries(profileId: Long): Flow<List<QTableEntryEntity>> =
            MutableStateFlow(qList.filter { it.profileId == profileId })

        override suspend fun getQEntriesForProfile(profileId: Long): List<QTableEntryEntity> =
            qList.filter { it.profileId == profileId }

        override suspend fun upsertQEntries(entries: List<QTableEntryEntity>) {
            entries.forEach { item ->
                qList.removeAll {
                    it.profileId == item.profileId &&
                        it.stateHash == item.stateHash &&
                        it.actionName == item.actionName
                }
                qList.add(item)
            }
        }

        override suspend fun clearQEntries(profileId: Long) {
            qList.removeAll { it.profileId == profileId }
        }

        override fun observeRecentExperiences(profileId: Long, limit: Int): Flow<List<ExperienceEntity>> =
            MutableStateFlow(expList.filter { it.profileId == profileId }.takeLast(limit))

        override suspend fun getRecentExperiencesOnce(profileId: Long, limit: Int): List<ExperienceEntity> =
            expList.filter { it.profileId == profileId }.takeLast(limit)

        override suspend fun insertExperiences(items: List<ExperienceEntity>) {
            items.forEach { expList.add(it.copy(id = nextExpId++)) }
        }

        override fun observeExperienceCount(profileId: Long): Flow<Int> =
            MutableStateFlow(expList.count { it.profileId == profileId })

        override suspend fun clearExperiences(profileId: Long) {
            expList.removeAll { it.profileId == profileId }
        }

        override fun observeSessions(profileId: Long): Flow<List<TrainingSessionEntity>> =
            MutableStateFlow(sessionList.filter { it.profileId == profileId })

        override suspend fun insertSession(session: TrainingSessionEntity): Long {
            val id = nextSessionId++
            sessionList.add(session.copy(id = id))
            return id
        }

        override suspend fun clearSessions(profileId: Long) {
            sessionList.removeAll { it.profileId == profileId }
        }

        override fun observeShotPhysics(profileId: Long, limit: Int): Flow<List<ShotPhysicsEntity>> =
            MutableStateFlow(physicsList.filter { it.profileId == profileId }.takeLast(limit))

        override suspend fun getShotPhysicsOnce(profileId: Long, limit: Int): List<ShotPhysicsEntity> =
            physicsList.filter { it.profileId == profileId }.takeLast(limit)

        override suspend fun insertShotPhysics(item: ShotPhysicsEntity): Long {
            val id = nextPhysicsId++
            physicsList.add(item.copy(id = id))
            return id
        }

        override suspend fun clearShotPhysics(profileId: Long) {
            physicsList.removeAll { it.profileId == profileId }
        }

        override fun observeBallRecords(profileId: Long, limit: Int): Flow<List<BallTelemetryEntity>> =
            MutableStateFlow(ballRecordsList.filter { it.profileId == profileId }.takeLast(limit))

        override suspend fun getBallRecordsOnce(profileId: Long, limit: Int): List<BallTelemetryEntity> =
            ballRecordsList.filter { it.profileId == profileId }.takeLast(limit)

        override suspend fun insertBallRecords(items: List<BallTelemetryEntity>) {
            items.forEach { ballRecordsList.add(it.copy(id = nextBallRecordId++)) }
        }

        override suspend fun clearBallRecords(profileId: Long) {
            ballRecordsList.removeAll { it.profileId == profileId }
        }
    }
}
