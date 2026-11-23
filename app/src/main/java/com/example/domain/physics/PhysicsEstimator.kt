package com.example.domain.physics

import com.example.data.db.BallTelemetryEntity
import com.example.data.db.ShotPhysicsEntity
import com.example.data.db.TrainingProfileEntity
import com.example.domain.rl.BotAction
import com.example.domain.vision.BallSuit
import com.example.domain.vision.DetectedBall
import com.example.domain.vision.DetectedBallKind
import com.example.domain.vision.PlannedPoolShot
import com.example.domain.vision.PoolFrameAnalysis
import com.example.domain.vision.PoolPocket
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot

data class PhysicsUpdateOutcome(
    val shotLog: ShotPhysicsEntity,
    val updatedProfile: TrainingProfileEntity,
    val outcomeLabel: String,
    val ballRecords: List<BallTelemetryEntity> = emptyList(),
    val meanPredictionError: Float = 0f
)

data class HumanObservationOutcome(
    val shotLog: ShotPhysicsEntity,
    val updatedProfile: TrainingProfileEntity,
    val matchedAction: BotAction,
    val inferredStateHash: String,
    val inferredStateSummary: String,
    val reward: Float,
    val pocketId: String,
    val inferredPowerFraction: Float,
    val ballRecords: List<BallTelemetryEntity> = emptyList(),
    val meanPredictionError: Float = 0f
)

/**
 * Estimates real table physics (friction, power-to-distance scale, cut deflection bias,
 * ball-to-ball restitution, and cushion bounce) in isotropic screen coordinates.
 * Tracks EVERY ball on the table pre-shot vs predicted vs post-shot, records per-ball
 * telemetry into Room DB, and self-trains physics parameters from multi-ball prediction errors.
 */
object PhysicsEstimator {

    fun computeCalibratedPower(basePower: Float, profile: TrainingProfileEntity): Float {
        val safeScale = profile.learnedPowerScale.coerceIn(0.78f, 1.22f)
        return (basePower * safeScale).coerceIn(0.28f, 0.94f)
    }

    fun evaluatePostShotPhysics(
        profile: TrainingProfileEntity,
        shotNumber: Int,
        preShot: PlannedPoolShot,
        powerFraction: Float,
        angleTrimDeg: Float,
        postCueBall: DetectedBall?,
        pottedBall: Boolean,
        keptTurn: Boolean,
        reward: Float,
        scratched: Boolean = false,
        aspectRatio: Float = 2.0f,
        preAnalysis: PoolFrameAnalysis? = null,
        postAnalysis: PoolFrameAnalysis? = null,
        simulationPrediction: ShotSimulationPrediction? = null
    ): PhysicsUpdateOutcome {
        val postX = postCueBall?.normX ?: preShot.cueBall.normX
        val postY = postCueBall?.normY ?: preShot.cueBall.normY

        val cueTravelDist = hypot((postX - preShot.cueBall.normX) * aspectRatio, postY - preShot.cueBall.normY)

        val expectedAngleDeg = atan2(
            (preShot.aimPointY - preShot.cueBall.normY).toDouble(),
            ((preShot.aimPointX - preShot.cueBall.normX) * aspectRatio).toDouble()
        ) * 180.0 / PI

        val actualAngleDeg = if (cueTravelDist > 0.03f && !scratched) {
            atan2(
                (postY - preShot.cueBall.normY).toDouble(),
                ((postX - preShot.cueBall.normX) * aspectRatio).toDouble()
            ) * 180.0 / PI
        } else {
            expectedAngleDeg
        }

        val deflectionErr = normalizeAngleDeg((actualAngleDeg - expectedAngleDeg).toFloat())

        val safeCurrentFriction = profile.learnedFrictionCoef.coerceIn(0.28f, 0.78f)
        val safeCurrentScale = profile.learnedPowerScale.coerceIn(0.78f, 1.22f)
        val safeRestitution = profile.learnedRestitution.coerceIn(0.76f, 0.98f)
        val safeCushion = profile.learnedCushionBounce.coerceIn(0.58f, 0.95f)

        val estimatedFriction: Float
        var nextFriction: Float
        var nextPowerScale: Float
        if (cueTravelDist >= 0.03f && !scratched) {
            val travelPerPower = (cueTravelDist / powerFraction.coerceAtLeast(0.18f)).coerceIn(0.08f, 1.6f)
            estimatedFriction = (0.95f - (travelPerPower * 0.42f)).coerceIn(0.26f, 0.80f)
            val emaAlpha = 0.18f
            nextFriction = ((1f - emaAlpha) * safeCurrentFriction + emaAlpha * estimatedFriction)
                .coerceIn(0.28f, 0.78f)

            val targetScaleAdjustment = when {
                pottedBall -> safeCurrentScale
                cueTravelDist < preShot.plannedDistanceSafe() * 0.40f -> (safeCurrentScale + 0.04f)
                cueTravelDist > preShot.plannedDistanceSafe() * 1.35f -> (safeCurrentScale - 0.04f)
                else -> safeCurrentScale
            }.coerceIn(0.78f, 1.22f)
            nextPowerScale = ((1f - emaAlpha) * safeCurrentScale + emaAlpha * targetScaleAdjustment)
                .coerceIn(0.78f, 1.22f)
        } else {
            estimatedFriction = safeCurrentFriction
            nextFriction = safeCurrentFriction
            nextPowerScale = safeCurrentScale
        }

        val clampedBiasSample = deflectionErr.coerceIn(-10f, 10f)
        val nextCutBias = if (!pottedBall && !scratched && cueTravelDist > 0.045f && abs(deflectionErr) < 25f) {
            ((1f - 0.12f) * profile.learnedCutBiasDeg + 0.12f * (-clampedBiasSample * 0.28f)).coerceIn(-6f, 6f)
        } else {
            profile.learnedCutBiasDeg
        }

        // Track EVERY ball on the table & learn multi-ball physics (restitution, cushion, friction)
        val sim = simulationPrediction ?: if (preAnalysis != null) {
            PoolPhysicsSimulator.simulateShot(
                cueBall = preShot.cueBall,
                allBalls = preAnalysis.allTableTargets,
                primaryTarget = preShot.targetBall,
                aimPointX = preShot.aimPointX,
                aimPointY = preShot.aimPointY,
                powerFraction = powerFraction,
                pockets = preAnalysis.pockets,
                tableBounds = preAnalysis.detectedTableBounds,
                aspectRatio = aspectRatio,
                profile = profile
            )
        } else null

        val tracking = if (preAnalysis != null && postAnalysis != null && sim != null) {
            matchAndTrainAllBalls(
                profileId = profile.id,
                shotNumber = shotNumber,
                shotSource = "BOT",
                preAnalysis = preAnalysis,
                postAnalysis = postAnalysis,
                sim = sim,
                currentFriction = nextFriction,
                currentScale = nextPowerScale,
                currentRestitution = safeRestitution,
                currentCushion = safeCushion
            )
        } else null

        if (tracking != null) {
            nextFriction = tracking.updatedFriction
            nextPowerScale = tracking.updatedScale
        }

        val outcomeLabel = when {
            scratched -> "CUE_SCRATCH"
            pottedBall -> "POTTED"
            keptTurn -> "KEPT_TURN"
            cueTravelDist < 0.025f -> "MISSED_NO_HIT"
            abs(deflectionErr) > 16f -> "MISSED_AIM_ANGLE"
            else -> "MISSED_TURN_LOST"
        }

        val isMistake = scratched || (!pottedBall && !keptTurn)
        val predCueX = sim?.cuePath?.endX ?: postX
        val predCueY = sim?.cuePath?.endY ?: postY
        val predTargetX = sim?.targetPath?.endX ?: preShot.targetBall.normX
        val predTargetY = sim?.targetPath?.endY ?: preShot.targetBall.normY
        val cueErr = tracking?.cuePredError ?: hypot((predCueX - postX) * aspectRatio, predCueY - postY)
        val tgtErr = tracking?.targetPredError ?: 0f
        val meanErr = tracking?.meanError ?: cueErr
        val nextAvgErr = if (profile.totalBallsRecorded == 0) {
            meanErr
        } else {
            (0.82f * profile.avgPredictionError + 0.18f * meanErr)
        }

        val log = ShotPhysicsEntity(
            profileId = profile.id,
            shotNumber = shotNumber,
            preCueX = preShot.cueBall.normX,
            preCueY = preShot.cueBall.normY,
            preTargetX = preShot.targetBall.normX,
            preTargetY = preShot.targetBall.normY,
            pocketId = preShot.pocket.id,
            cutAngleDeg = preShot.cutAngleDeg,
            plannedDistance = preShot.totalDistance,
            powerFraction = powerFraction,
            angleTrimDeg = angleTrimDeg,
            postCueX = postX,
            postCueY = postY,
            cueTravelDist = cueTravelDist,
            estimatedFriction = estimatedFriction,
            angleDeflectionErrorDeg = deflectionErr,
            pottedBall = pottedBall,
            keptTurn = keptTurn,
            reward = reward,
            outcomeLabel = outcomeLabel,
            predCueX = predCueX,
            predCueY = predCueY,
            predTargetX = predTargetX,
            predTargetY = predTargetY,
            predTargetPotted = sim?.targetPocketedId != null,
            cuePredError = cueErr,
            targetPredError = tgtErr,
            ballsTrackedCount = tracking?.records?.size ?: 0,
            collisionsCount = sim?.collisions?.size ?: 0
        )

        val updatedProfile = profile.copy(
            learnedPowerScale = nextPowerScale,
            learnedFrictionCoef = nextFriction,
            learnedCutBiasDeg = nextCutBias,
            learnedRestitution = tracking?.updatedRestitution ?: safeRestitution,
            learnedCushionBounce = tracking?.updatedCushion ?: safeCushion,
            avgPredictionError = nextAvgErr,
            totalBallsRecorded = profile.totalBallsRecorded + (tracking?.records?.size ?: 0),
            physicsSamplesCount = profile.physicsSamplesCount + 1,
            totalMistakesLogged = profile.totalMistakesLogged + (if (isMistake) 1 else 0),
            scratchesCount = profile.scratchesCount + (if (scratched) 1 else 0),
            updatedAt = System.currentTimeMillis()
        )

        return PhysicsUpdateOutcome(
            shotLog = log,
            updatedProfile = updatedProfile,
            outcomeLabel = outcomeLabel,
            ballRecords = tracking?.records ?: emptyList(),
            meanPredictionError = meanErr
        )
    }

    /**
     * Infers shot geometry, force, pocket, and policy action directly from watching the human play,
     * simulates the multi-ball physics prediction, tracks every ball on the table, and trains
     * physics scalars from the prediction residuals.
     */
    fun evaluateHumanShotObservation(
        profile: TrainingProfileEntity,
        shotNumber: Int,
        preAnalysis: PoolFrameAnalysis,
        postAnalysis: PoolFrameAnalysis,
        pottedBall: Boolean,
        keptTurn: Boolean,
        scratched: Boolean = false
    ): HumanObservationOutcome? {
        val aspectRatio = preAnalysis.aspectRatio
        val preCue = preAnalysis.cueBall ?: return null
        val postCue = postAnalysis.cueBall
        val postX = postCue?.normX ?: preCue.normX
        val postY = postCue?.normY ?: preCue.normY
        val cueTravel = hypot((postX - preCue.normX) * aspectRatio, postY - preCue.normY)

        val preTargets = preAnalysis.allTableTargets
        val postTargets = postAnalysis.allTableTargets
        if (preTargets.isEmpty()) return null

        val disappearedBall = preTargets.firstOrNull { preBall ->
            postTargets.none { hypot((it.normX - preBall.normX) * aspectRatio, it.normY - preBall.normY) < 0.042f }
        }
        val chosenTarget = disappearedBall
            ?: preAnalysis.plannedShot?.targetBall
            ?: preTargets.minByOrNull { hypot((it.normX - preCue.normX) * aspectRatio, it.normY - preCue.normY) }
            ?: return null

        val pockets = preAnalysis.pockets
        val chosenPocket: PoolPocket = pockets.minByOrNull { pocket ->
            val ctDx = (chosenTarget.normX - preCue.normX) * aspectRatio
            val ctDy = chosenTarget.normY - preCue.normY
            val ctDist = hypot(ctDx, ctDy).coerceAtLeast(0.01f)
            val tpDx = (pocket.normX - chosenTarget.normX) * aspectRatio
            val tpDy = pocket.normY - chosenTarget.normY
            val tpDist = hypot(tpDx, tpDy).coerceAtLeast(0.01f)
            val dot = ((ctDx / ctDist) * (tpDx / tpDist) + (ctDy / ctDist) * (tpDy / tpDist)).coerceIn(-1f, 1f)
            val cutDeg = (acos(dot) * 180.0 / PI).toFloat()
            cutDeg + tpDist * 22f
        } ?: PoolPocket("BR", 0.80f, 0.90f)

        val ctDx = (chosenTarget.normX - preCue.normX) * aspectRatio
        val ctDy = chosenTarget.normY - preCue.normY
        val ctDist = hypot(ctDx, ctDy).coerceAtLeast(0.01f)
        val tpDx = (chosenPocket.normX - chosenTarget.normX) * aspectRatio
        val tpDy = chosenPocket.normY - chosenTarget.normY
        val tpDist = hypot(tpDx, tpDy).coerceAtLeast(0.01f)
        val totalDist = ctDist + tpDist
        val dot = ((ctDx / ctDist) * (tpDx / tpDist) + (ctDy / ctDist) * (tpDy / tpDist)).coerceIn(-1f, 1f)
        val cutAngleDeg = (acos(dot) * 180.0 / PI).toFloat()

        val inferredPower = when {
            cueTravel >= 0.03f && !scratched -> (0.34f + cueTravel * 0.72f).coerceIn(0.32f, 0.92f)
            pottedBall -> (0.40f + totalDist * 0.36f).coerceIn(0.36f, 0.88f)
            else -> 0.56f
        }

        val matchedAction = BotAction.POOL_SHOT_ACTIONS.minByOrNull { act ->
            abs(act.powerFraction - inferredPower) + abs(act.angleTrimDeg) * 0.04f
        } ?: BotAction.SHOT_MED_EXACT

        val angleBucket = (cutAngleDeg / 15f).toInt().coerceIn(0, 4)
        val distBucket = (totalDist * 3.5f).toInt().coerceIn(0, 4)
        val stateHash = "POOL_A${angleBucket}_D${distBucket}_${chosenPocket.id}"
        val stateSummary = "Human shot ${cutAngleDeg.toInt()}° | Dist ${"%.2f".format(totalDist)} -> ${chosenPocket.id}"

        val reward = when {
            scratched -> -3.5f
            pottedBall -> +5.5f
            keptTurn -> +1.6f
            else -> -0.8f
        }
        val outcomeLabel = when {
            scratched -> "HUMAN_SCRATCH"
            pottedBall -> "HUMAN_POTTED"
            keptTurn -> "HUMAN_KEPT"
            else -> "HUMAN_MISSED"
        }

        val safeCurrentFriction = profile.learnedFrictionCoef.coerceIn(0.28f, 0.78f)
        val safeCurrentScale = profile.learnedPowerScale.coerceIn(0.78f, 1.22f)
        val safeRestitution = profile.learnedRestitution.coerceIn(0.76f, 0.98f)
        val safeCushion = profile.learnedCushionBounce.coerceIn(0.58f, 0.95f)

        val estimatedFriction = if (cueTravel >= 0.03f && !scratched) {
            val travelPerPower = (cueTravel / inferredPower.coerceAtLeast(0.20f)).coerceIn(0.08f, 1.6f)
            (0.95f - (travelPerPower * 0.42f)).coerceIn(0.26f, 0.80f)
        } else {
            safeCurrentFriction
        }
        var nextFriction = if (cueTravel >= 0.03f && !scratched) {
            ((1f - 0.14f) * safeCurrentFriction + 0.14f * estimatedFriction).coerceIn(0.28f, 0.78f)
        } else {
            safeCurrentFriction
        }

        var nextScale = if (pottedBall && inferredPower > 0.25f) {
            val idealBase = (0.38f + totalDist * 0.36f + (cutAngleDeg / 180f) * 0.14f).coerceIn(0.32f, 0.88f)
            val observedRatio = (inferredPower / idealBase).coerceIn(0.78f, 1.22f)
            ((1f - 0.15f) * safeCurrentScale + 0.15f * observedRatio).coerceIn(0.78f, 1.22f)
        } else {
            safeCurrentScale
        }

        // Compute ghost aim point for human shot & run full multi-ball simulation to evaluate all balls
        val ghostAimX = ((chosenTarget.normX * aspectRatio) - (tpDx / tpDist) * (PoolPhysicsSimulator.BALL_RADIUS_ISO * 2.0f)) / aspectRatio
        val ghostAimY = chosenTarget.normY - (tpDy / tpDist) * (PoolPhysicsSimulator.BALL_RADIUS_ISO * 2.0f)
        val sim = PoolPhysicsSimulator.simulateShot(
            cueBall = preCue,
            allBalls = preTargets,
            primaryTarget = chosenTarget,
            aimPointX = ghostAimX,
            aimPointY = ghostAimY,
            powerFraction = inferredPower,
            pockets = pockets,
            tableBounds = preAnalysis.detectedTableBounds,
            aspectRatio = aspectRatio,
            profile = profile
        )

        val tracking = matchAndTrainAllBalls(
            profileId = profile.id,
            shotNumber = shotNumber,
            shotSource = "HUMAN",
            preAnalysis = preAnalysis,
            postAnalysis = postAnalysis,
            sim = sim,
            currentFriction = nextFriction,
            currentScale = nextScale,
            currentRestitution = safeRestitution,
            currentCushion = safeCushion
        )
        nextFriction = tracking.updatedFriction
        nextScale = tracking.updatedScale

        val nextAvgErr = if (profile.totalBallsRecorded == 0) {
            tracking.meanError
        } else {
            (0.84f * profile.avgPredictionError + 0.16f * tracking.meanError)
        }

        val log = ShotPhysicsEntity(
            profileId = profile.id,
            shotNumber = shotNumber,
            preCueX = preCue.normX,
            preCueY = preCue.normY,
            preTargetX = chosenTarget.normX,
            preTargetY = chosenTarget.normY,
            pocketId = chosenPocket.id,
            cutAngleDeg = cutAngleDeg,
            plannedDistance = totalDist,
            powerFraction = inferredPower,
            angleTrimDeg = 0f,
            postCueX = postX,
            postCueY = postY,
            cueTravelDist = cueTravel,
            estimatedFriction = estimatedFriction,
            angleDeflectionErrorDeg = 0f,
            pottedBall = pottedBall,
            keptTurn = keptTurn,
            reward = reward,
            outcomeLabel = outcomeLabel,
            predCueX = sim.cuePath.endX,
            predCueY = sim.cuePath.endY,
            predTargetX = sim.targetPath?.endX ?: chosenTarget.normX,
            predTargetY = sim.targetPath?.endY ?: chosenTarget.normY,
            predTargetPotted = sim.targetPocketedId != null,
            cuePredError = tracking.cuePredError,
            targetPredError = tracking.targetPredError,
            ballsTrackedCount = tracking.records.size,
            collisionsCount = sim.collisions.size
        )

        val updatedProfile = profile.copy(
            learnedPowerScale = nextScale,
            learnedFrictionCoef = nextFriction,
            learnedRestitution = tracking.updatedRestitution,
            learnedCushionBounce = tracking.updatedCushion,
            avgPredictionError = nextAvgErr,
            totalBallsRecorded = profile.totalBallsRecorded + tracking.records.size,
            physicsSamplesCount = profile.physicsSamplesCount + 1,
            humanShotsObserved = profile.humanShotsObserved + 1,
            updatedAt = System.currentTimeMillis()
        )

        return HumanObservationOutcome(
            shotLog = log,
            updatedProfile = updatedProfile,
            matchedAction = matchedAction,
            inferredStateHash = stateHash,
            inferredStateSummary = stateSummary,
            reward = reward,
            pocketId = chosenPocket.id,
            inferredPowerFraction = inferredPower,
            ballRecords = tracking.records,
            meanPredictionError = tracking.meanError
        )
    }

    private data class MultiBallTrackingResult(
        val records: List<BallTelemetryEntity>,
        val cuePredError: Float,
        val targetPredError: Float,
        val meanError: Float,
        val updatedFriction: Float,
        val updatedScale: Float,
        val updatedRestitution: Float,
        val updatedCushion: Float
    )

    private fun matchAndTrainAllBalls(
        profileId: Long,
        shotNumber: Int,
        shotSource: String,
        preAnalysis: PoolFrameAnalysis,
        postAnalysis: PoolFrameAnalysis,
        sim: ShotSimulationPrediction,
        currentFriction: Float,
        currentScale: Float,
        currentRestitution: Float,
        currentCushion: Float
    ): MultiBallTrackingResult {
        val aspect = preAnalysis.aspectRatio.coerceIn(0.45f, 2.50f)
        val pockets = preAnalysis.pockets
        val records = mutableListOf<BallTelemetryEntity>()

        // 1. Track Cue Ball
        val cuePath = sim.cuePath
        val postCue = postAnalysis.cueBall
        val cuePottedActual = postCue == null || pockets.any {
            hypot((postCue.normX - it.normX) * aspect, postCue.normY - it.normY) < 0.044f
        }
        val cueActualPocket = if (cuePottedActual) {
            val refX = postCue?.normX ?: cuePath.endX
            val refY = postCue?.normY ?: cuePath.endY
            pockets.minByOrNull { hypot((refX - it.normX) * aspect, refY - it.normY) }?.id ?: "PKT"
        } else ""
        val actCueX = postCue?.normX ?: cuePath.endX
        val actCueY = postCue?.normY ?: cuePath.endY
        val cueErr = hypot((cuePath.endX - actCueX) * aspect, cuePath.endY - actCueY)
        val cueMoved = hypot((actCueX - cuePath.startX) * aspect, actCueY - cuePath.startY)

        records.add(
            BallTelemetryEntity(
                profileId = profileId,
                shotNumber = shotNumber,
                shotSource = shotSource,
                ballRole = "CUE",
                ballSuit = "CUE",
                ballColorTag = "WHITE",
                preX = cuePath.startX,
                preY = cuePath.startY,
                predEndX = cuePath.endX,
                predEndY = cuePath.endY,
                predPotted = cuePath.potted,
                predPocketId = cuePath.pottedPocketId ?: "",
                postX = actCueX,
                postY = actCueY,
                actualMovedDist = cueMoved,
                actualPotted = cuePottedActual,
                actualPocketId = cueActualPocket,
                predictionErrorDist = cueErr,
                wasCollided = true
            )
        )

        // 2. Match every non-cue ball from simulation paths against postAnalysis.allTableTargets
        val remainingPost = postAnalysis.allTableTargets.toMutableList()
        val nonCuePaths = sim.allBallPaths.drop(1)

        // Pass A: First lock stationary balls (didn't move between pre and post)
        val unmatchedPaths = mutableListOf<SimulatedBallPath>()
        val pathToPost = HashMap<Int, DetectedBall?>()

        for (path in nonCuePaths) {
            val staticIdx = remainingPost.indexOfFirst { post ->
                val d = hypot((post.normX - path.startX) * aspect, post.normY - path.startY)
                d < 0.016f && (post.suit == path.suit || post.kind == path.kind)
            }
            if (staticIdx >= 0 && !path.wasCollided) {
                pathToPost[path.ballIndex] = remainingPost.removeAt(staticIdx)
            } else {
                unmatchedPaths.add(path)
            }
        }

        // Pass B: Global minimum-cost bipartite matching across all remaining balls so a
        // potted ball cannot steal another ball's exact suit/color/position match.
        data class MatchCandidate(val pathIdx: Int, val postIdx: Int, val cost: Float)
        val candidates = mutableListOf<MatchCandidate>()
        unmatchedPaths.forEachIndexed { pIdx, path ->
            remainingPost.forEachIndexed { postIdx, post ->
                val distToPred = hypot((post.normX - path.endX) * aspect, post.normY - path.endY)
                val distToStart = hypot((post.normX - path.startX) * aspect, post.normY - path.startY)
                val suitMismatch = (path.suit != BallSuit.UNKNOWN && post.suit != BallSuit.UNKNOWN && path.suit != post.suit)
                val suitPenalty = if (suitMismatch) 0.28f else 0f
                val colorPenalty = if (post.colorTag != path.colorTag && path.colorTag != "UNK" && post.colorTag != "UNK") 0.08f else 0f
                val pottedBias = if (path.potted) 0.08f else 0f
                val baseDist = minOf(distToPred, distToStart * 1.15f)
                val cost = baseDist + suitPenalty + colorPenalty + pottedBias
                val maxAllowedCost = if (suitMismatch) 0.24f else 0.48f
                if (cost <= maxAllowedCost) {
                    candidates.add(MatchCandidate(pIdx, postIdx, cost))
                }
            }
        }
        candidates.sortBy { it.cost }
        val usedPaths = BooleanArray(unmatchedPaths.size)
        val usedPosts = BooleanArray(remainingPost.size)
        for (cand in candidates) {
            if (!usedPaths[cand.pathIdx] && !usedPosts[cand.postIdx]) {
                usedPaths[cand.pathIdx] = true
                usedPosts[cand.postIdx] = true
                pathToPost[unmatchedPaths[cand.pathIdx].ballIndex] = remainingPost[cand.postIdx]
            }
        }
        unmatchedPaths.forEachIndexed { idx, path ->
            if (!usedPaths[idx]) {
                pathToPost[path.ballIndex] = null
            }
        }

        var targetErr = 0f
        var movedErrorSum = cueErr
        var movedCount = 1

        var predTravelMovedSum = cuePath.travelDist
        var actTravelMovedSum = cueMoved
        var collidedPredSum = 0f
        var collidedActSum = 0f
        var collidedCount = 0
        var bounceErrorRatioSum = 0f
        var bounceCount = 0

        for (path in nonCuePaths) {
            val matchedPost = pathToPost[path.ballIndex]
            val actualPotted = matchedPost == null
            val actualPocketId = if (actualPotted) {
                pockets.minByOrNull { hypot((path.endX - it.normX) * aspect, path.endY - it.normY) }?.id ?: "PKT"
            } else ""
            val postX = matchedPost?.normX ?: path.endX
            val postY = matchedPost?.normY ?: path.endY
            val actualMoved = hypot((postX - path.startX) * aspect, postY - path.startY)
            val errDist = if (actualPotted && path.potted && actualPocketId == path.pottedPocketId) {
                0f
            } else if (actualPotted && path.potted) {
                0.04f
            } else {
                hypot((path.endX - postX) * aspect, path.endY - postY)
            }

            val actuallyCollided = path.wasCollided || actualMoved > 0.018f || actualPotted
            val finalRole = when {
                path.role == "PRIMARY_TARGET" -> "PRIMARY_TARGET"
                path.kind == DetectedBallKind.EIGHT_BALL -> "EIGHT_BALL"
                actuallyCollided -> "COLLIDED_SECONDARY"
                else -> "STATIC"
            }

            if (finalRole == "PRIMARY_TARGET") {
                targetErr = errDist
            }

            if (actuallyCollided) {
                movedErrorSum += errDist
                movedCount++
                predTravelMovedSum += path.travelDist
                actTravelMovedSum += actualMoved

                if (path.ballIndex != 0 && !actualPotted && !path.potted && path.travelDist > 0.03f) {
                    collidedPredSum += path.travelDist
                    collidedActSum += actualMoved
                    collidedCount++
                }
                if (path.waypoints.size >= 4 && !actualPotted && !path.potted && path.travelDist > 0.08f) {
                    bounceErrorRatioSum += (actualMoved / path.travelDist.coerceAtLeast(0.05f)).coerceIn(0.6f, 1.4f)
                    bounceCount++
                }
            }

            records.add(
                BallTelemetryEntity(
                    profileId = profileId,
                    shotNumber = shotNumber,
                    shotSource = shotSource,
                    ballRole = finalRole,
                    ballSuit = path.suit.name,
                    ballColorTag = path.colorTag,
                    preX = path.startX,
                    preY = path.startY,
                    predEndX = path.endX,
                    predEndY = path.endY,
                    predPotted = path.potted,
                    predPocketId = path.pottedPocketId ?: "",
                    postX = postX,
                    postY = postY,
                    actualMovedDist = actualMoved,
                    actualPotted = actualPotted,
                    actualPocketId = actualPocketId,
                    predictionErrorDist = errDist,
                    wasCollided = actuallyCollided
                )
            )
        }

        // 3. Self-train physics scalars from multi-ball prediction residuals
        var nextFriction = currentFriction
        var nextScale = currentScale
        var nextRestitution = currentRestitution
        var nextCushion = currentCushion

        if (predTravelMovedSum > 0.08f && actTravelMovedSum > 0.04f) {
            val rollRatio = (actTravelMovedSum / predTravelMovedSum).coerceIn(0.60f, 1.45f)
            // If balls rolled farther than predicted -> reduce friction slightly & raise powerScale slightly
            if (rollRatio > 1.08f) {
                nextFriction = (currentFriction - 0.015f).coerceIn(0.28f, 0.78f)
                nextScale = (currentScale + 0.010f).coerceIn(0.78f, 1.22f)
            } else if (rollRatio < 0.92f) {
                nextFriction = (currentFriction + 0.015f).coerceIn(0.28f, 0.78f)
                nextScale = (currentScale - 0.010f).coerceIn(0.78f, 1.22f)
            }
        }

        if (collidedCount > 0 && collidedPredSum > 0.04f) {
            val transferRatio = (collidedActSum / collidedPredSum).coerceIn(0.65f, 1.35f)
            val targetRestitution = (currentRestitution * transferRatio).coerceIn(0.76f, 0.98f)
            nextRestitution = (0.85f * currentRestitution + 0.15f * targetRestitution).coerceIn(0.76f, 0.98f)
        }

        if (bounceCount > 0) {
            val avgBounceRatio = bounceErrorRatioSum / bounceCount
            val targetCushion = (currentCushion * avgBounceRatio).coerceIn(0.58f, 0.95f)
            nextCushion = (0.85f * currentCushion + 0.15f * targetCushion).coerceIn(0.58f, 0.95f)
        }

        val meanErr = movedErrorSum / movedCount.coerceAtLeast(1)
        return MultiBallTrackingResult(
            records = records,
            cuePredError = cueErr,
            targetPredError = targetErr,
            meanError = meanErr,
            updatedFriction = nextFriction,
            updatedScale = nextScale,
            updatedRestitution = nextRestitution,
            updatedCushion = nextCushion
        )
    }

    private fun PlannedPoolShot.plannedDistanceSafe(): Float = totalDistance.coerceAtLeast(0.10f)

    private fun normalizeAngleDeg(angle: Float): Float {
        var a = angle
        while (a > 180f) a -= 360f
        while (a < -180f) a += 360f
        return a
    }
}
