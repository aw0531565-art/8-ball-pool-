package com.example.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.NeuroBotApplication
import com.example.R
import com.example.data.db.TrainingProfileEntity
import com.example.data.db.TrainingSessionEntity
import com.example.domain.physics.PhysicsEstimator
import com.example.domain.physics.PoolPhysicsSimulator
import com.example.domain.rl.BotAction
import com.example.domain.vision.AimFeedbackStatus
import com.example.domain.vision.AssignedSuit
import com.example.domain.vision.BallSuit
import com.example.domain.vision.LockedTableGeometry
import com.example.domain.vision.PoolFrameAnalysis
import com.example.domain.vision.PoolVisionAnalyzer
import com.example.domain.vision.ScreenFrameAnalyzer
import com.example.domain.vision.ScreenPerception
import com.example.domain.vision.TurnOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.example.action.START_BOT_CAPTURE"
        const val ACTION_TOGGLE_PAUSE = "com.example.action.TOGGLE_PAUSE_BOT"
        const val ACTION_RUN_REPLAY = "com.example.action.RUN_REPLAY_BOT"
        const val ACTION_SHOOT_NOW = "com.example.action.SHOOT_NOW_BOT"
        const val ACTION_STOP = "com.example.action.STOP_BOT_CAPTURE"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_PROFILE_ID = "extra_profile_id"

        private const val CHANNEL_ID = "neurobot_live_channel"
        private const val NOTIF_ID = 4201
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var automationJob: Job? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    private var currentCaptureW = 0
    private var currentCaptureH = 0
    private var cachedFrameBitmap: Bitmap? = null

    @Volatile
    private var isPaused = false

    @Volatile
    private var manualShotRequested = false

    private var activeProfileId = 1L
    private var lastPerception: ScreenPerception? = null
    private var lastAction: BotAction? = null
    private var currentEpisode = 1
    private var currentStep = 0
    private var currentEpisodeReward = 0f
    private var episodeStartMs = System.currentTimeMillis()
    private var staleFrames = 0
    private var humanPreShotAnalysis: PoolFrameAnalysis? = null
    private val sessionFailedShotPenalties = ConcurrentHashMap<String, Float>()
    private var lockedTableGeometry: LockedTableGeometry? = null
    private var lastObservedUnlockSignal = 0L
    private var sessionConfirmedSuit: AssignedSuit? = null
    private val blockedForbiddenTargets = mutableListOf<Pair<Float, Float>>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                activeProfileId = intent.getLongExtra(EXTRA_PROFILE_ID, 1L)

                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForegroundWithType(buildNotification("Starting live external game reader..."))
                startProjection(resultCode, resultData)
            }

            ACTION_TOGGLE_PAUSE -> {
                isPaused = !isPaused
                BotServiceBridge.setScreenCaptureState(
                    active = mediaProjection != null,
                    running = !isPaused,
                    paused = isPaused
                )
                BotServiceBridge.appendLog(if (isPaused) "Bot switched OFF (Paused)." else "Bot switched ON (Active).")
                updateNotification(if (isPaused) "Paused — tap ON in floating widget or notification." else "Watching game screen & learning...")
            }

            ACTION_SHOOT_NOW -> {
                manualShotRequested = true
                BotServiceBridge.appendLog("Manual Shoot requested from floating panel.")
            }

            ACTION_RUN_REPLAY -> {
                serviceScope.launch {
                    val app = application as NeuroBotApplication
                    val profile = app.repository.getProfile(activeProfileId)
                    if (profile != null) {
                        val actions = if (profile.botMode == "POOL_AIM") {
                            BotAction.POOL_SHOT_ACTIONS
                        } else {
                            BotAction.LIVE_SCREEN_ACTIONS
                        }
                        val result = app.trainingEngine.runExperienceReplay(
                            profileId = profile.id,
                            learningRate = profile.learningRate,
                            discountFactor = profile.discountFactor,
                            availableActions = actions
                        )
                        app.repository.updateProfile(
                            profile.copy(
                                totalReplayUpdates = profile.totalReplayUpdates + result.qUpdatesApplied,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                        BotServiceBridge.appendLog(
                            "Self-Train Replay: ${result.qUpdatesApplied} Q-updates from ${result.experiencesReplayed} saved memories."
                        )
                    }
                }
            }

            ACTION_STOP -> stopCaptureAndService()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithType(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        releaseProjectionResources()
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mgr.getMediaProjection(resultCode, data) ?: run {
            stopCaptureAndService()
            return
        }
        mediaProjection = projection

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                BotServiceBridge.appendLog("Screen projection stopped by system.")
                stopCaptureAndService()
            }

            override fun onCapturedContentResize(width: Int, height: Int) {
                super.onCapturedContentResize(width, height)
                reconfigureVirtualDisplay(width, height)
            }
        }, Handler(Looper.getMainLooper()))

        val (screenW, screenH, densityDpi) = getDisplayMetricsInfo()
        val (captureW, captureH) = computeCaptureDimensions(screenW, screenH)
        currentCaptureW = captureW
        currentCaptureH = captureH

        imageReader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "NeuroBotVision",
            captureW,
            captureH,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        registerOrientationWatcher()

        isPaused = false
        BotServiceBridge.setScreenCaptureState(active = true, running = true, paused = false)
        BotServiceBridge.appendLog("Screen Reader active (${captureW}x${captureH}). Switch to your game now!")
        startAutomationLoop()
    }

    private fun computeCaptureDimensions(screenW: Int, screenH: Int): Pair<Int, Int> {
        val isLandscape = screenW >= screenH
        return if (isLandscape) {
            val w = (screenW / 2).coerceIn(480, 960)
            val h = ((w.toFloat() / screenW.coerceAtLeast(1)) * screenH).roundToInt().coerceIn(240, 640)
            w to h
        } else {
            val h = (screenH / 2).coerceIn(480, 960)
            val w = ((h.toFloat() / screenH.coerceAtLeast(1)) * screenW).roundToInt().coerceIn(240, 640)
            w to h
        }
    }

    private fun registerOrientationWatcher() {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayListener?.let { dm.unregisterDisplayListener(it) }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                val (screenW, screenH, _) = getDisplayMetricsInfo()
                if ((screenW > screenH) != (currentCaptureW > currentCaptureH)) {
                    reconfigureVirtualDisplay(screenW, screenH)
                }
            }
        }
        displayListener = listener
        dm.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
    }

    private fun reconfigureVirtualDisplay(newWidth: Int, newHeight: Int) {
        val display = virtualDisplay ?: return
        val (captureW, captureH) = computeCaptureDimensions(newWidth, newHeight)
        if (captureW == currentCaptureW && captureH == currentCaptureH) return
        val densityDpi = resources.displayMetrics.densityDpi
        try {
            val newReader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)
            display.resize(captureW, captureH, densityDpi)
            display.surface = newReader.surface
            imageReader?.close()
            imageReader = newReader
            currentCaptureW = captureW
            currentCaptureH = captureH
            lockedTableGeometry = null
            BotServiceBridge.updatePoolObservation(
                visionGrid = BotServiceBridge.telemetry.value.latestVisionGrid,
                stateHash = BotServiceBridge.telemetry.value.latestStateHash,
                isMyTurn = false,
                targetsCount = 0,
                cueX = null,
                cueY = null,
                targetX = null,
                targetY = null,
                pocketId = null,
                summary = "Screen rotated (${captureW}x${captureH}) — unlocking table bounds...",
                isTableLocked = false,
                tableLockLabel = "Re-locking (${captureW}x${captureH})..."
            )
            BotServiceBridge.appendLog("Screen rotated/resized (${captureW}x${captureH}) — auto re-detecting table.")
        } catch (_: Exception) {
        }
    }

    private fun startAutomationLoop() {
        automationJob?.cancel()
        automationJob = serviceScope.launch {
            val app = application as NeuroBotApplication
            app.trainingEngine.ensureLoaded(activeProfileId)
            val initialProfile = app.repository.getProfile(activeProfileId)
            currentEpisode = (initialProfile?.totalEpisodes ?: 0) + 1
            currentStep = 0
            currentEpisodeReward = 0f
            episodeStartMs = System.currentTimeMillis()
            lastPerception = null
            lastAction = null
            humanPreShotAnalysis = null
            sessionFailedShotPenalties.clear()
            lockedTableGeometry = null
            sessionConfirmedSuit = null
            blockedForbiddenTargets.clear()
            if (initialProfile != null) {
                BotServiceBridge.setHumanShotsObserved(initialProfile.humanShotsObserved)
            }

            while (isActive) {
                val (screenW, screenH, _) = getDisplayMetricsInfo()
                if ((screenW > screenH) != (currentCaptureW > currentCaptureH)) {
                    reconfigureVirtualDisplay(screenW, screenH)
                    delay(180L)
                }

                val profile = app.repository.getProfile(activeProfileId)
                val interval = (profile?.actionIntervalMs ?: 600L).coerceIn(200L, 3000L)

                if (profile != null) {
                    val triggerManual = manualShotRequested
                    if (!isPaused || triggerManual) {
                        if (profile.botMode == "POOL_AIM") {
                            runPoolShotCycle(app, profile, forceManualShot = triggerManual)
                        } else {
                            runGeneralTapCycle(app, profile)
                        }
                        if (triggerManual) {
                            manualShotRequested = false
                        }
                    }
                }
                delay(interval)
            }
        }
    }

    private fun isNonGameSystemPackage(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return pkg == packageName ||
            pkg == "None" ||
            lower.contains("systemui") ||
            lower.contains("upslide") ||
            lower.contains("launcher") ||
            lower.contains("settings") ||
            lower.contains("inputmethod") ||
            lower.contains("keyguard")
    }

    private suspend fun runPoolShotCycle(
        app: NeuroBotApplication,
        profile: TrainingProfileEntity,
        forceManualShot: Boolean = false
    ) {
        val bitmap = captureLatestBitmap()
        if (bitmap == null) {
            BotServiceBridge.updatePoolObservation(
                visionGrid = BotServiceBridge.telemetry.value.latestVisionGrid,
                stateHash = "NO_FRAME",
                isMyTurn = false,
                targetsCount = 0,
                cueX = null,
                cueY = null,
                targetX = null,
                targetY = null,
                pocketId = null,
                summary = "Waiting for screen frame..."
            )
            return
        }

        val telemetry = BotServiceBridge.telemetry.value
        val effectiveSeat = (telemetry.manualSeatSideOverride ?: profile.botSeatSide).uppercase()

        if (telemetry.unlockTableSignal != lastObservedUnlockSignal) {
            lastObservedUnlockSignal = telemetry.unlockTableSignal
            lockedTableGeometry = null
        }

        // Auto-unlock if screen rotated or capture resolution changed
        val currentLock = lockedTableGeometry
        if (currentLock != null && (currentLock.captureW != bitmap.width || currentLock.captureH != bitmap.height)) {
            lockedTableGeometry = null
            BotServiceBridge.appendLog("Orientation/size changed -> auto re-detecting table.")
        }

        // Respect manual suit override first; otherwise use aim/HUD-locked match suit when clear
        val activeSuitOverride = when {
            telemetry.manualSuitOverride in listOf("SOLIDS", "STRIPES") -> telemetry.manualSuitOverride
            sessionConfirmedSuit == AssignedSuit.SOLIDS || sessionConfirmedSuit == AssignedSuit.STRIPES -> sessionConfirmedSuit!!.name
            else -> telemetry.manualSuitOverride
        }

        BotServiceBridge.setCaptureSizeLabel("${bitmap.width}x${bitmap.height}")
        val analysis = PoolVisionAnalyzer.analyze(
            bitmap = bitmap,
            profile = profile,
            seatOverride = telemetry.manualSeatSideOverride,
            suitOverride = activeSuitOverride,
            failedShotPenalties = sessionFailedShotPenalties,
            lockedTable = lockedTableGeometry,
            blockedTargetsIso = blockedForbiddenTargets
        )
        bitmap.recycle()

        // Lock table bounds once on first valid detection
        if (lockedTableGeometry == null && analysis.detectedGeometry != null) {
            lockedTableGeometry = analysis.detectedGeometry
            BotServiceBridge.appendLog(
                "Table size LOCKED (${bitmap.width}x${bitmap.height}) — fixed until screen rotates."
            )
        }

        // Reset match-scoped suit lock on fresh break rack
        if (analysis.botRackCount == 0 && analysis.oppRackCount == 0 && analysis.allTableTargets.size >= 14) {
            sessionConfirmedSuit = null
            blockedForbiddenTargets.clear()
        } else if (sessionConfirmedSuit == null &&
            analysis.detectedSuit in listOf(AssignedSuit.SOLIDS, AssignedSuit.STRIPES) &&
            analysis.botRackCount in 1..7 && analysis.oppRackCount in 1..7
        ) {
            sessionConfirmedSuit = analysis.detectedSuit
        }

        if (!analysis.isMyTurn && blockedForbiddenTargets.isNotEmpty()) {
            blockedForbiddenTargets.clear()
        }

        BotServiceBridge.setHumanShotsObserved(profile.humanShotsObserved)

        val shot = analysis.plannedShot
        val plannedDecision = if (shot != null) {
            app.trainingEngine.selectAction(
                profileId = profile.id,
                stateHash = shot.stateHash,
                availableActions = BotAction.POOL_SHOT_ACTIONS,
                epsilon = profile.explorationRate
            )
        } else null

        val plannedPowerFrac = if (shot != null && plannedDecision != null) {
            telemetry.manualPowerOverride ?: run {
                val rawPower = (shot.basePowerFraction + plannedDecision.action.powerFraction) * 0.5f
                PhysicsEstimator.computeCalibratedPower(rawPower, profile)
            }
        } else {
            telemetry.manualPowerOverride ?: 0f
        }

        val turnOwnerLabel = when {
            effectiveSeat == "WATCH" -> "WATCH (${analysis.turnOwner.name.take(5)})"
            analysis.isMyTurn -> "BOT TURN ($effectiveSeat)"
            analysis.turnOwner != TurnOwner.NONE -> "HUMAN TURN"
            else -> "WAIT / ROLLING"
        }

        val tableLockLabel = if (lockedTableGeometry != null) {
            "LOCKED (${bitmap.width}x${bitmap.height})"
        } else {
            "Scanning table..."
        }

        BotServiceBridge.updatePoolObservation(
            visionGrid = analysis.visionGrid,
            stateHash = shot?.stateHash ?: "POOL_SCANNING",
            isMyTurn = analysis.isMyTurn,
            targetsCount = analysis.targetBalls.size,
            cueX = analysis.cueBall?.normX,
            cueY = analysis.cueBall?.normY,
            targetX = shot?.targetBall?.normX,
            targetY = shot?.targetBall?.normY,
            pocketId = shot?.pocket?.id,
            cutAngleDeg = shot?.cutAngleDeg ?: 0f,
            plannedPowerFraction = plannedPowerFrac,
            summary = "${bitmap.width}x${bitmap.height} • ${analysis.summary}",
            turnOwnerLabel = turnOwnerLabel,
            detectedSuitLabel = analysis.detectedSuit.name,
            effectiveSuitLabel = analysis.effectiveSuit.name,
            simulationSummary = analysis.simulationPrediction?.summaryText ?: "Scanning table...",
            isTableLocked = lockedTableGeometry != null,
            tableLockLabel = tableLockLabel
        )

        val a11y = GameBotAccessibilityService.instance

        // Block ONLY if NeuroBot's own UI is visibly resumed in foreground or Accessibility isn't connected.
        if (telemetry.isOwnAppForeground || a11y == null) {
            return
        }

        // Real-time Human Shot Observation & Multi-Ball Tracking (passive, zero screen touch!)
        val prevHuman = humanPreShotAnalysis
        if (profile.watchHumanLearn && prevHuman != null) {
            val preCue = prevHuman.cueBall
            val currCue = analysis.cueBall
            val cueDisplaced = preCue != null && currCue != null &&
                hypot(currCue.normX - preCue.normX, currCue.normY - preCue.normY) > 0.026f
            val powerBarReleased = prevHuman.powerBarVisible && !analysis.powerBarVisible
            val turnTransitioned = analysis.turnOwner != prevHuman.turnOwner
            val ballsReduced = analysis.allTableTargets.size < prevHuman.allTableTargets.size

            if (cueDisplaced || powerBarReleased || turnTransitioned || ballsReduced) {
                BotServiceBridge.appendLog("Watching human shot... measuring all balls & outcome")
                delay(3200L)
                val postBmp = captureLatestBitmap()
                val postAnalysis = if (postBmp != null) {
                    val parsed = PoolVisionAnalyzer.analyze(
                        bitmap = postBmp,
                        profile = profile,
                        seatOverride = telemetry.manualSeatSideOverride,
                        suitOverride = activeSuitOverride,
                        failedShotPenalties = sessionFailedShotPenalties,
                        lockedTable = lockedTableGeometry
                    )
                    postBmp.recycle()
                    parsed
                } else {
                    analysis
                }

                val humanKeptTurn = (postAnalysis.turnOwner == prevHuman.turnOwner)
                val humanScratched = postAnalysis.cueBall == null ||
                    (!humanKeptTurn && postAnalysis.cueBall != null &&
                        postAnalysis.pockets.any {
                            hypot((postAnalysis.cueBall.normX - it.normX) * postAnalysis.aspectRatio, postAnalysis.cueBall.normY - it.normY) < 0.048f
                        })
                val oppRackDecreased = prevHuman.oppRackCount > 0 && postAnalysis.oppRackCount < prevHuman.oppRackCount
                val botRackDecreased = prevHuman.botRackCount > 0 && postAnalysis.botRackCount < prevHuman.botRackCount
                val openedRacks = prevHuman.botRackCount == 0 && prevHuman.oppRackCount == 0 &&
                    (postAnalysis.botRackCount > 0 || postAnalysis.oppRackCount > 0)
                val humanPotted = !humanScratched && (
                    (postAnalysis.allTableTargets.size < prevHuman.allTableTargets.size) ||
                        oppRackDecreased || botRackDecreased || openedRacks
                    )

                val obs = PhysicsEstimator.evaluateHumanShotObservation(
                    profile = profile,
                    shotNumber = profile.physicsSamplesCount + 1,
                    preAnalysis = prevHuman,
                    postAnalysis = postAnalysis,
                    pottedBall = humanPotted,
                    keptTurn = humanKeptTurn,
                    scratched = humanScratched
                )
                humanPreShotAnalysis = null
                if (obs != null) {
                    app.repository.insertShotPhysics(obs.shotLog)
                    app.repository.insertBallRecords(obs.ballRecords)
                    app.repository.updateProfile(obs.updatedProfile)
                    BotServiceBridge.setHumanShotsObserved(obs.updatedProfile.humanShotsObserved)
                    BotServiceBridge.updateShotForceAndPhysics(
                        shotPowerFraction = obs.inferredPowerFraction,
                        estimatedFriction = obs.updatedProfile.learnedFrictionCoef,
                        ballsTrackedCount = obs.updatedProfile.totalBallsRecorded,
                        avgPredictionError = obs.updatedProfile.avgPredictionError
                    )
                    currentStep++
                    app.trainingEngine.recordStep(
                        profileId = profile.id,
                        episodeNumber = currentEpisode,
                        stepIndex = currentStep,
                        stateHash = obs.inferredStateHash,
                        stateSummary = obs.inferredStateSummary,
                        action = obs.matchedAction,
                        reward = obs.reward,
                        nextStateHash = postAnalysis.plannedShot?.stateHash ?: "POOL_POST",
                        isTerminal = !humanKeptTurn,
                        availableActions = BotAction.POOL_SHOT_ACTIONS,
                        learningRate = profile.learningRate,
                        discountFactor = profile.discountFactor,
                        source = "HUMAN_OBSERVED",
                        screenNotes = "Watched Human -> ${obs.pocketId} | Power ${(obs.inferredPowerFraction * 100).toInt()}% | Balls:${obs.ballRecords.size} | Err:${"%.3f".format(obs.meanPredictionError)}"
                    )
                    app.trainingEngine.flushToDatabase(profile.id)
                    BotServiceBridge.updateLiveDecision(
                        visionGrid = prevHuman.visionGrid,
                        stateHash = obs.inferredStateHash,
                        action = obs.matchedAction,
                        reward = obs.reward,
                        episodeCount = currentEpisode,
                        stepCount = currentStep,
                        episodeReward = currentEpisodeReward,
                        note = "Human #${obs.updatedProfile.humanShotsObserved}: [${obs.matchedAction.shortCode}] -> ${obs.pocketId} | ${obs.ballRecords.size} balls tracked (err ${"%.3f".format(obs.meanPredictionError)})"
                    )
                    updateNotification("Human Shot #${obs.updatedProfile.humanShotsObserved} • ${obs.ballRecords.size} balls recorded")
                }
                return
            } else if (!analysis.isMyTurn && analysis.turnOwner != TurnOwner.NONE && analysis.cueBall != null) {
                humanPreShotAnalysis = analysis
            }
        } else if (profile.watchHumanLearn) {
            val isWatchedHumanTurn = (effectiveSeat == "WATCH" && analysis.turnOwner != TurnOwner.NONE) ||
                (!analysis.isMyTurn && analysis.turnOwner != TurnOwner.NONE)
            if (isWatchedHumanTurn && analysis.cueBall != null) {
                humanPreShotAnalysis = analysis
                return
            }
        }

        // Strict Turn Guard: NEVER touch the screen on Human's turn or in WATCH mode (unless user explicitly tapped Shoot)
        if (effectiveSeat == "WATCH" && !forceManualShot) {
            return
        }
        if ((!analysis.isMyTurn && !forceManualShot) || shot == null || plannedDecision == null) {
            return
        }

        val combinedTrimDeg = plannedDecision.action.angleTrimDeg + profile.learnedCutBiasDeg
        val b = analysis.detectedTableBounds
        val fL = b.getOrElse(0) { 0.18f }
        val fT = b.getOrElse(1) { 0.21f }
        val fR = b.getOrElse(2) { 0.80f }
        val fB = b.getOrElse(3) { 0.91f }
        val (aimX, aimY) = PoolVisionAnalyzer.computeAimPointWithTrim(
            shot = shot,
            angleTrimDeg = combinedTrimDeg,
            aspectRatio = analysis.aspectRatio,
            fLeft = fL,
            fTop = fT,
            fRight = fR,
            fBottom = fB
        )

        val powerFrac = plannedPowerFrac.coerceIn(0.22f, 0.94f)
        val preShotSimulation = PoolPhysicsSimulator.simulateShot(
            cueBall = shot.cueBall,
            allBalls = analysis.allTableTargets,
            primaryTarget = shot.targetBall,
            aimPointX = aimX,
            aimPointY = aimY,
            powerFraction = powerFrac,
            pockets = analysis.pockets,
            tableBounds = analysis.detectedTableBounds,
            aspectRatio = analysis.aspectRatio,
            profile = profile
        )

        // 1. Tap along the isotropic ghost-ball ray on table felt to orient the cue stick accurately
        a11y.dispatchNormalizedTapAwait(aimX, aimY, holdMs = 55L)
        delay(260L)

        // 1b. Live Aim Check: Inspect Red ⊘ (NOT my ball) vs White aiming ring & guideline (IS my ball & aim on target)
        val aimCheckBmp = captureLatestBitmap()
        if (aimCheckBmp != null) {
            val feedback = PoolVisionAnalyzer.inspectAimFeedback(
                bitmap = aimCheckBmp,
                cueBall = shot.cueBall,
                targetBall = shot.targetBall,
                intendedPocket = shot.pocket,
                aspectRatio = analysis.aspectRatio
            )
            aimCheckBmp.recycle()

            when (feedback.status) {
                AimFeedbackStatus.FORBIDDEN_RED_MARKER -> {
                    BotServiceBridge.updateAimFeedback("RED ⊘ NOT MY BALL")
                    BotServiceBridge.appendLog(
                        "Aim Guard: ${feedback.note} on ${shot.targetBall.colorTag} ${shot.targetBall.suit.name} -> switching target!"
                    )
                    blockedForbiddenTargets.add(shot.targetBall.normX to shot.targetBall.normY)
                    if (telemetry.manualSuitOverride !in listOf("SOLIDS", "STRIPES")) {
                        when (shot.targetBall.suit) {
                            BallSuit.SOLID -> sessionConfirmedSuit = AssignedSuit.STRIPES
                            BallSuit.STRIPE -> sessionConfirmedSuit = AssignedSuit.SOLIDS
                            else -> Unit
                        }
                    }
                    return
                }
                AimFeedbackStatus.CONFIRMED_LEGAL_WHITE_GUIDE -> {
                    BotServiceBridge.updateAimFeedback("WHITE GUIDE ✓ (${shot.targetBall.suit.name})")
                    if (sessionConfirmedSuit == null && telemetry.manualSuitOverride !in listOf("SOLIDS", "STRIPES")) {
                        when (shot.targetBall.suit) {
                            BallSuit.SOLID -> sessionConfirmedSuit = AssignedSuit.SOLIDS
                            BallSuit.STRIPE -> sessionConfirmedSuit = AssignedSuit.STRIPES
                            else -> Unit
                        }
                    }
                    // Micro-trim cue stick if white guide line is slightly off pocket line
                    if (!forceManualShot && abs(feedback.guideAngleErrorDeg) in 2.5f..18f) {
                        val (adjX, adjY) = PoolVisionAnalyzer.computeAimPointWithTrim(
                            shot = shot,
                            angleTrimDeg = combinedTrimDeg - feedback.guideAngleErrorDeg * 0.45f,
                            aspectRatio = analysis.aspectRatio,
                            fLeft = fL,
                            fTop = fT,
                            fRight = fR,
                            fBottom = fB
                        )
                        a11y.dispatchNormalizedTapAwait(adjX, adjY, holdMs = 45L)
                        delay(180L)
                    }
                }
                AimFeedbackStatus.NO_CLEAR_AIM_FEEDBACK -> {
                    BotServiceBridge.updateAimFeedback("Re-aiming onto ball...")
                    a11y.dispatchNormalizedTapAwait(aimX, aimY, holdMs = 55L)
                    delay(220L)
                    val recheckBmp = captureLatestBitmap()
                    if (recheckBmp != null) {
                        val recheck = PoolVisionAnalyzer.inspectAimFeedback(
                            bitmap = recheckBmp,
                            cueBall = shot.cueBall,
                            targetBall = shot.targetBall,
                            intendedPocket = shot.pocket,
                            aspectRatio = analysis.aspectRatio
                        )
                        recheckBmp.recycle()
                        if (recheck.status == AimFeedbackStatus.FORBIDDEN_RED_MARKER) {
                            BotServiceBridge.updateAimFeedback("RED ⊘ NOT MY BALL")
                            BotServiceBridge.appendLog("Aim Guard (2nd check): Red ⊘ -> NOT my ball! Aborting shot.")
                            blockedForbiddenTargets.add(shot.targetBall.normX to shot.targetBall.normY)
                            if (telemetry.manualSuitOverride !in listOf("SOLIDS", "STRIPES")) {
                                when (shot.targetBall.suit) {
                                    BallSuit.SOLID -> sessionConfirmedSuit = AssignedSuit.STRIPES
                                    BallSuit.STRIPE -> sessionConfirmedSuit = AssignedSuit.SOLIDS
                                    else -> Unit
                                }
                            }
                            return
                        } else if (recheck.status == AimFeedbackStatus.CONFIRMED_LEGAL_WHITE_GUIDE) {
                            BotServiceBridge.updateAimFeedback("WHITE GUIDE ✓ (${shot.targetBall.suit.name})")
                        }
                    }
                }
            }
        }

        // 2. Pull the left vertical cue power slider down proportionally to Shot Force and release to fire
        val startBarY = (profile.powerBarTopY + 0.045f).coerceIn(0.24f, 0.42f)
        val endBarY = (startBarY + powerFrac * (profile.powerBarBottomY - startBarY).coerceAtLeast(0.35f))
            .coerceIn(startBarY + 0.08f, 0.90f)

        BotServiceBridge.updateShotForceAndPhysics(
            shotPowerFraction = powerFrac,
            estimatedFriction = profile.learnedFrictionCoef
        )
        BotServiceBridge.appendLog(
            "Aim -> ${shot.pocket.id} (${shot.cutAngleDeg.toInt()}°) ${(powerFrac * 100).toInt()}% | Pred: ${preShotSimulation.summaryText}"
        )

        a11y.dispatchNormalizedSwipeAwait(
            startNormX = profile.powerBarX,
            startNormY = startBarY,
            endNormX = profile.powerBarX,
            endNormY = endBarY,
            durationMs = 540L
        )

        // 3. Wait for balls to roll & settle, then inspect EVERY ball's actual position vs prediction
        delay(3600L)
        val postBitmap = captureLatestBitmap()
        var stepReward = 0.2f
        var nextStateHash = shot.stateHash
        var outcomeNote = "Shot settled"
        var roundEnded = false
        var pottedBall = false
        var keptTurn = false
        var scratched = false
        var postCueBall: com.example.domain.vision.DetectedBall? = null
        var postAnalysis: PoolFrameAnalysis? = null

        if (postBitmap != null) {
            val parsedPost = PoolVisionAnalyzer.analyze(
                bitmap = postBitmap,
                profile = profile,
                seatOverride = telemetry.manualSeatSideOverride,
                suitOverride = activeSuitOverride,
                failedShotPenalties = sessionFailedShotPenalties,
                lockedTable = lockedTableGeometry
            )
            postBitmap.recycle()
            postAnalysis = parsedPost
            postCueBall = parsedPost.cueBall
            nextStateHash = parsedPost.plannedShot?.stateHash ?: "POOL_POST"

            val cueDisappeared = postCueBall == null
            val cueInPocketMouth = postCueBall != null && !parsedPost.isMyTurn &&
                parsedPost.pockets.any {
                    hypot((postCueBall.normX - it.normX) * parsedPost.aspectRatio, postCueBall.normY - it.normY) < 0.048f
                }
            scratched = cueDisappeared || cueInPocketMouth

            // In 8-Ball Pool HUD, rack slots DECREASE when a ball is potted (or populate from 0/0 on open break)
            val botRackDecreased = analysis.botRackCount > 0 && parsedPost.botRackCount < analysis.botRackCount
            val openedRacks = analysis.botRackCount == 0 && analysis.oppRackCount == 0 &&
                (parsedPost.botRackCount > 0 || parsedPost.oppRackCount > 0)
            val targetsReduced = parsedPost.allTableTargets.size < analysis.allTableTargets.size
            keptTurn = !scratched && parsedPost.isMyTurn
            pottedBall = !scratched && (botRackDecreased || openedRacks || (keptTurn && targetsReduced))

            when {
                scratched -> {
                    stepReward = -4.5f
                    outcomeNote = "SCRATCH: White cue ball pocketed! (-4.5)"
                    roundEnded = true
                    sessionFailedShotPenalties[shot.stateHash] =
                        ((sessionFailedShotPenalties[shot.stateHash] ?: 0f) + 35f).coerceAtMost(90f)
                }
                pottedBall -> {
                    stepReward = +5.0f
                    outcomeNote = "Ball potted! Turn kept (+5.0)"
                    sessionFailedShotPenalties.remove(shot.stateHash)
                }
                keptTurn -> {
                    stepReward = +1.4f
                    outcomeNote = "Turn retained (+1.4)"
                }
                else -> {
                    stepReward = -2.0f
                    outcomeNote = "Missed pocket & turn lost (-2.0)"
                    roundEnded = true
                    sessionFailedShotPenalties[shot.stateHash] =
                        ((sessionFailedShotPenalties[shot.stateHash] ?: 0f) + 22f).coerceAtMost(75f)
                }
            }
        }

        currentStep++
        currentEpisodeReward += stepReward

        val physicsOutcome = PhysicsEstimator.evaluatePostShotPhysics(
            profile = profile,
            shotNumber = profile.physicsSamplesCount + 1,
            preShot = shot,
            powerFraction = powerFrac,
            angleTrimDeg = combinedTrimDeg,
            postCueBall = postCueBall,
            pottedBall = pottedBall,
            keptTurn = keptTurn,
            reward = stepReward,
            scratched = scratched,
            aspectRatio = analysis.aspectRatio,
            preAnalysis = analysis,
            postAnalysis = postAnalysis,
            simulationPrediction = preShotSimulation
        )
        app.repository.insertShotPhysics(physicsOutcome.shotLog)
        app.repository.insertBallRecords(physicsOutcome.ballRecords)
        app.repository.updateProfile(physicsOutcome.updatedProfile)
        BotServiceBridge.updateShotForceAndPhysics(
            shotPowerFraction = powerFrac,
            estimatedFriction = physicsOutcome.updatedProfile.learnedFrictionCoef,
            ballsTrackedCount = physicsOutcome.updatedProfile.totalBallsRecorded,
            avgPredictionError = physicsOutcome.updatedProfile.avgPredictionError
        )
        BotServiceBridge.updateOutcomeAndMistakes(
            outcomeLabel = physicsOutcome.outcomeLabel,
            mistakesCount = physicsOutcome.updatedProfile.totalMistakesLogged
        )

        app.trainingEngine.recordStep(
            profileId = profile.id,
            episodeNumber = currentEpisode,
            stepIndex = currentStep,
            stateHash = shot.stateHash,
            stateSummary = shot.stateSummary,
            action = plannedDecision.action,
            reward = stepReward,
            nextStateHash = nextStateHash,
            isTerminal = roundEnded,
            availableActions = BotAction.POOL_SHOT_ACTIONS,
            learningRate = profile.learningRate,
            discountFactor = profile.discountFactor,
            source = "LIVE_POOL",
            screenNotes = "$outcomeNote | Force ${(powerFrac * 100).toInt()}% | Balls:${physicsOutcome.ballRecords.size} | PredErr:${"%.3f".format(physicsOutcome.meanPredictionError)}"
        )

        BotServiceBridge.updateLiveDecision(
            visionGrid = analysis.visionGrid,
            stateHash = shot.stateHash,
            action = plannedDecision.action,
            reward = stepReward,
            episodeCount = currentEpisode,
            stepCount = currentStep,
            episodeReward = currentEpisodeReward,
            note = "Shot #$currentStep [${plannedDecision.action.shortCode}] ${(powerFrac * 100).toInt()}% -> ${shot.pocket.id} | ${physicsOutcome.ballRecords.size} balls (err ${"%.3f".format(physicsOutcome.meanPredictionError)})"
        )

        updateNotification("Shot #$currentStep • Force ${(powerFrac * 100).toInt()}% • ${shot.pocket.id}")

        if (roundEnded || currentStep % 4 == 0) {
            finalizeLiveEpisode(app, profile.id, currentEpisodeReward, BotAction.POOL_SHOT_ACTIONS, "LIVE_POOL")
            if (roundEnded) {
                currentEpisode++
                currentStep = 0
                currentEpisodeReward = 0f
                episodeStartMs = System.currentTimeMillis()
            }
        }
    }

    private suspend fun runGeneralTapCycle(
        app: NeuroBotApplication,
        profile: TrainingProfileEntity
    ) {
        val telemetry = BotServiceBridge.telemetry.value
        val pkg = telemetry.activeForegroundPackage
        // Never pollute memory or touch NeuroBot itself or system UI/notification/Vivo upslide
        if (isNonGameSystemPackage(pkg)) {
            return
        }

        val bitmap = captureLatestBitmap() ?: return
        val perception = ScreenFrameAnalyzer.analyzeBitmap(
            bitmap = bitmap,
            previous = lastPerception,
            hudTopY = profile.hudTopY,
            hudBottomY = profile.hudBottomY
        )
        bitmap.recycle()

        val prev = lastPerception
        val prevAction = lastAction

        if (perception.fullDelta < 0.01f) staleFrames++ else staleFrames = 0

        var stepReward = 0f
        if (prev != null && prevAction != null) {
            val assessment = ScreenFrameAnalyzer.evaluateScreenTransition(
                previous = prev,
                current = perception,
                uiTexts = telemetry.detectedUiTexts,
                staleFramesCount = staleFrames
            )
            stepReward = assessment.reward
            currentEpisodeReward += stepReward

            app.trainingEngine.recordStep(
                profileId = profile.id,
                episodeNumber = currentEpisode,
                stepIndex = currentStep,
                stateHash = prev.stateHash,
                stateSummary = prev.summary,
                action = prevAction,
                reward = stepReward,
                nextStateHash = perception.stateHash,
                isTerminal = assessment.isTerminal,
                availableActions = BotAction.LIVE_SCREEN_ACTIONS,
                learningRate = profile.learningRate,
                discountFactor = profile.discountFactor,
                source = "LIVE_SCREEN",
                screenNotes = "${pkg}: ${assessment.reason}"
            )

            if (assessment.isTerminal) {
                finalizeLiveEpisode(app, profile.id, currentEpisodeReward, BotAction.LIVE_SCREEN_ACTIONS, "LIVE_SCREEN")
                currentEpisode++
                currentStep = 0
                currentEpisodeReward = 0f
                episodeStartMs = System.currentTimeMillis()
            }
        }

        val decision = app.trainingEngine.selectAction(
            profileId = profile.id,
            stateHash = perception.stateHash,
            availableActions = BotAction.LIVE_SCREEN_ACTIONS,
            epsilon = profile.explorationRate
        )

        val a11y = GameBotAccessibilityService.instance
        if (a11y != null) {
            when (decision.action) {
                BotAction.MOVE_LEFT -> a11y.dispatchNormalizedTapAwait(profile.tapLeftX, profile.tapLeftY)
                BotAction.MOVE_RIGHT -> a11y.dispatchNormalizedTapAwait(profile.tapRightX, profile.tapRightY)
                BotAction.TAP_CENTER -> a11y.dispatchNormalizedTapAwait(profile.tapCenterX, profile.tapCenterY)
                BotAction.SWIPE_UP -> a11y.dispatchNormalizedSwipeAwait(
                    profile.tapCenterX, 0.72f, profile.tapCenterX, 0.34f
                )
                BotAction.SWIPE_DOWN -> a11y.dispatchNormalizedSwipeAwait(
                    profile.tapCenterX, 0.38f, profile.tapCenterX, 0.76f
                )
                else -> Unit
            }
        }

        currentStep++
        lastPerception = perception
        lastAction = decision.action

        val logLine = "Round $currentEpisode #$currentStep [${decision.action.shortCode}] r=${"%.2f".format(stepReward)} (${pkg.substringAfterLast('.')})"
        BotServiceBridge.updateLiveDecision(
            visionGrid = perception.gridCells,
            stateHash = perception.stateHash,
            action = decision.action,
            reward = stepReward,
            episodeCount = currentEpisode,
            stepCount = currentStep,
            episodeReward = currentEpisodeReward,
            note = logLine
        )

        if (currentStep % 10 == 0) {
            app.trainingEngine.flushToDatabase(profile.id)
            updateNotification("Round $currentEpisode • Step $currentStep • ${decision.action.labelEn}")
        }
    }

    private suspend fun finalizeLiveEpisode(
        app: NeuroBotApplication,
        profileId: Long,
        episodeReward: Float,
        actions: List<BotAction>,
        source: String
    ) {
        app.trainingEngine.flushToDatabase(profileId)
        val profile = app.repository.getProfile(profileId) ?: return
        val approxScore = (episodeReward * 10f).roundToInt().coerceAtLeast(0)
        val nextEpsilon = if (profile.autoDecayEpsilon) {
            (profile.explorationRate * 0.95f).coerceAtLeast(0.05f)
        } else {
            profile.explorationRate
        }
        app.repository.insertSession(
            TrainingSessionEntity(
                profileId = profileId,
                episodeNumber = currentEpisode,
                stepsCount = currentStep,
                totalReward = episodeReward,
                score = approxScore,
                explorationRateUsed = profile.explorationRate,
                source = source,
                durationMs = System.currentTimeMillis() - episodeStartMs
            )
        )
        val replay = app.trainingEngine.runExperienceReplay(
            profileId = profileId,
            learningRate = profile.learningRate,
            discountFactor = profile.discountFactor,
            availableActions = actions,
            batchSize = 36,
            epochs = 2
        )
        app.repository.updateProfile(
            profile.copy(
                totalEpisodes = profile.totalEpisodes + 1,
                totalSteps = profile.totalSteps + currentStep,
                bestScore = maxOf(profile.bestScore, approxScore),
                lastScore = approxScore,
                explorationRate = nextEpsilon,
                totalReplayUpdates = profile.totalReplayUpdates + replay.qUpdatesApplied,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun captureLatestBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage()
        if (image != null) {
            val fresh = try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * image.width
                val fullBitmap = Bitmap.createBitmap(
                    image.width + rowPadding / pixelStride,
                    image.height,
                    Bitmap.Config.ARGB_8888
                )
                fullBitmap.copyPixelsFromBuffer(buffer)
                if (rowPadding == 0) {
                    fullBitmap
                } else {
                    val cropped = Bitmap.createBitmap(fullBitmap, 0, 0, image.width, image.height)
                    fullBitmap.recycle()
                    cropped
                }
            } catch (_: Exception) {
                null
            } finally {
                image.close()
            }
            if (fresh != null) {
                cachedFrameBitmap?.recycle()
                cachedFrameBitmap = fresh.copy(Bitmap.Config.ARGB_8888, false)
                return fresh
            }
        }
        // Fallback to cached latest static screen (when game isn't redrawing while waiting on player's turn)
        return cachedFrameBitmap?.let { cached ->
            if (!cached.isRecycled) cached.copy(Bitmap.Config.ARGB_8888, false) else null
        }
    }

    private fun getDisplayMetricsInfo(): Triple<Int, Int, Int> {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val densityDpi = resources.displayMetrics.densityDpi
        if (display != null) {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                return Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
            }
        }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            Triple(bounds.width().coerceAtLeast(360), bounds.height().coerceAtLeast(640), densityDpi)
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            Triple(metrics.widthPixels.coerceAtLeast(360), metrics.heightPixels.coerceAtLeast(640), metrics.densityDpi)
        }
    }

    private fun stopCaptureAndService() {
        automationJob?.cancel()
        automationJob = null
        releaseProjectionResources()
        BotServiceBridge.setScreenCaptureState(active = false, running = false, paused = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun releaseProjectionResources() {
        displayListener?.let { listener ->
            try {
                val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                dm.unregisterDisplayListener(listener)
            } catch (_: Exception) {}
        }
        displayListener = null
        cachedFrameBitmap?.recycle()
        cachedFrameBitmap = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_bot_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_bot_desc)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(contentText: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(contentText))
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val replayIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_RUN_REPLAY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseLabel = if (isPaused) getString(R.string.action_resume_bot) else getString(R.string.action_pause_bot)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title_active))
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, pauseLabel, pauseIntent)
            .addAction(0, getString(R.string.action_train_replay), replayIntent)
            .addAction(0, getString(R.string.action_stop_bot), stopIntent)
            .build()
    }

    override fun onDestroy() {
        automationJob?.cancel()
        releaseProjectionResources()
        serviceScope.cancel()
        super.onDestroy()
    }
}
