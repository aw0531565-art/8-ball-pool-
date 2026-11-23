package com.example.service

import com.example.domain.rl.BotAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LiveBotTelemetry(
    val isAccessibilityConnected: Boolean = false,
    val isOwnAppForeground: Boolean = true,
    val isScreenCaptureActive: Boolean = false,
    val isLiveBotRunning: Boolean = false,
    val isLiveBotPaused: Boolean = false,
    val captureSizeLabel: String = "--",
    val activeForegroundPackage: String = "None",
    val clickableNodeCount: Int = 0,
    val detectedUiTexts: List<String> = emptyList(),
    val latestVisionGrid: List<Float> = List(36) { 0f },
    val latestStateHash: String = "IDLE",
    val latestAction: BotAction? = null,
    val latestReward: Float = 0f,
    val liveEpisodeCount: Int = 0,
    val liveStepCount: Int = 0,
    val liveEpisodeReward: Float = 0f,
    val isMyTurnDetected: Boolean = false,
    val turnOwnerLabel: String = "WAIT",
    val detectedSuitLabel: String = "OPEN",
    val effectiveSuitLabel: String = "OPEN",
    val detectedTargetBallsCount: Int = 0,
    val isCueBallDetected: Boolean = false,
    val poolAnalysisSummary: String = "Waiting for external game screen...",
    val cueBallX: Float? = null,
    val cueBallY: Float? = null,
    val targetBallX: Float? = null,
    val targetBallY: Float? = null,
    val plannedPocketId: String? = null,
    val plannedCutAngleDeg: Float = 0f,
    val plannedPowerFraction: Float = 0f,
    val lastShotPowerFraction: Float = 0f,
    val estimatedFriction: Float = 0.52f,
    val manualPowerOverride: Float? = null,
    val manualSeatSideOverride: String? = null, // null | "LEFT" | "RIGHT" | "WATCH"
    val manualSuitOverride: String? = null,     // null | "AUTO" | "SOLIDS" | "STRIPES"
    val humanShotsObserved: Int = 0,
    val lastOutcomeLabel: String = "READY",
    val mistakesCount: Int = 0,
    val simulationSummary: String = "No shot prediction yet",
    val ballsTrackedCount: Int = 0,
    val avgPredictionError: Float = 0f,
    val isTableLocked: Boolean = false,
    val tableLockLabel: String = "Scanning table...",
    val aimFeedbackLabel: String = "Idle",
    val unlockTableSignal: Long = 0L,
    val liveLogLines: List<String> = emptyList()
)

object BotServiceBridge {

    private val _telemetry = MutableStateFlow(LiveBotTelemetry())
    val telemetry: StateFlow<LiveBotTelemetry> = _telemetry.asStateFlow()

    fun setOwnAppForeground(inForeground: Boolean) {
        _telemetry.update { it.copy(isOwnAppForeground = inForeground) }
    }

    fun setCaptureSizeLabel(label: String) {
        _telemetry.update { it.copy(captureSizeLabel = label) }
    }

    fun setAccessibilityConnected(connected: Boolean) {
        _telemetry.update { it.copy(isAccessibilityConnected = connected) }
        appendLog(if (connected) "Accessibility gesture controller connected." else "Accessibility service disconnected.")
    }

    fun setScreenCaptureState(active: Boolean, running: Boolean, paused: Boolean) {
        _telemetry.update {
            it.copy(
                isScreenCaptureActive = active,
                isLiveBotRunning = running,
                isLiveBotPaused = paused
            )
        }
    }

    fun setManualPowerOverride(fraction: Float?) {
        _telemetry.update {
            it.copy(manualPowerOverride = fraction?.coerceIn(0.20f, 0.96f))
        }
        val label = if (fraction == null) "AUTO (AI + Physics)" else "${(fraction * 100).toInt()}%"
        appendLog("Shot Force set to $label")
    }

    fun setManualSeatSideOverride(seat: String?) {
        val normalized = seat?.uppercase()?.takeIf { it in listOf("LEFT", "RIGHT", "WATCH") }
        _telemetry.update { it.copy(manualSeatSideOverride = normalized) }
        appendLog("Bot Seat set to ${normalized ?: "PROFILE"}")
    }

    fun setManualSuitOverride(suit: String?) {
        val normalized = suit?.uppercase()?.takeIf { it in listOf("AUTO", "SOLIDS", "STRIPES") }
        _telemetry.update { it.copy(manualSuitOverride = normalized) }
        appendLog("Suit Lock set to ${normalized ?: "AUTO"}")
    }

    fun setHumanShotsObserved(count: Int) {
        _telemetry.update { it.copy(humanShotsObserved = count) }
    }

    fun updateOutcomeAndMistakes(outcomeLabel: String, mistakesCount: Int) {
        _telemetry.update { it.copy(lastOutcomeLabel = outcomeLabel, mistakesCount = mistakesCount) }
    }

    fun updateForegroundUiContext(
        packageName: String,
        clickableNodes: Int,
        uiTexts: List<String>
    ) {
        _telemetry.update {
            it.copy(
                activeForegroundPackage = packageName,
                clickableNodeCount = clickableNodes,
                detectedUiTexts = uiTexts
            )
        }
    }

    fun requestTableUnlock() {
        _telemetry.update {
            it.copy(
                unlockTableSignal = System.currentTimeMillis(),
                isTableLocked = false,
                tableLockLabel = "Re-scanning table..."
            )
        }
        appendLog("Table lock cleared — re-scanning table on next frame.")
    }

    fun updateAimFeedback(label: String) {
        _telemetry.update { it.copy(aimFeedbackLabel = label) }
    }

    fun updatePoolObservation(
        visionGrid: List<Float>,
        stateHash: String,
        isMyTurn: Boolean,
        targetsCount: Int,
        cueX: Float?,
        cueY: Float?,
        targetX: Float?,
        targetY: Float?,
        pocketId: String?,
        cutAngleDeg: Float = 0f,
        plannedPowerFraction: Float = 0f,
        summary: String,
        turnOwnerLabel: String = if (isMyTurn) "BOT TURN" else "WAIT",
        detectedSuitLabel: String = "OPEN",
        effectiveSuitLabel: String = "OPEN",
        simulationSummary: String = "Scanning...",
        isTableLocked: Boolean = false,
        tableLockLabel: String = if (isTableLocked) "LOCKED" else "Scanning..."
    ) {
        _telemetry.update {
            it.copy(
                latestVisionGrid = visionGrid,
                latestStateHash = stateHash,
                isMyTurnDetected = isMyTurn,
                turnOwnerLabel = turnOwnerLabel,
                detectedSuitLabel = detectedSuitLabel,
                effectiveSuitLabel = effectiveSuitLabel,
                detectedTargetBallsCount = targetsCount,
                isCueBallDetected = cueX != null && cueY != null,
                cueBallX = cueX,
                cueBallY = cueY,
                targetBallX = targetX,
                targetBallY = targetY,
                plannedPocketId = pocketId,
                plannedCutAngleDeg = cutAngleDeg,
                plannedPowerFraction = plannedPowerFraction,
                poolAnalysisSummary = summary,
                simulationSummary = simulationSummary,
                isTableLocked = isTableLocked,
                tableLockLabel = tableLockLabel
            )
        }
    }

    fun updateShotForceAndPhysics(
        shotPowerFraction: Float,
        estimatedFriction: Float,
        ballsTrackedCount: Int = _telemetry.value.ballsTrackedCount,
        avgPredictionError: Float = _telemetry.value.avgPredictionError
    ) {
        _telemetry.update {
            it.copy(
                lastShotPowerFraction = shotPowerFraction,
                estimatedFriction = estimatedFriction,
                ballsTrackedCount = ballsTrackedCount,
                avgPredictionError = avgPredictionError
            )
        }
    }

    fun updateLiveDecision(
        visionGrid: List<Float>,
        stateHash: String,
        action: BotAction,
        reward: Float,
        episodeCount: Int,
        stepCount: Int,
        episodeReward: Float,
        note: String
    ) {
        _telemetry.update { current ->
            val updatedLogs = (listOf(note) + current.liveLogLines).take(25)
            current.copy(
                latestVisionGrid = visionGrid,
                latestStateHash = stateHash,
                latestAction = action,
                latestReward = reward,
                liveEpisodeCount = episodeCount,
                liveStepCount = stepCount,
                liveEpisodeReward = episodeReward,
                liveLogLines = updatedLogs
            )
        }
    }

    fun appendLog(message: String) {
        _telemetry.update { current ->
            val updated = (listOf(message) + current.liveLogLines).take(25)
            current.copy(liveLogLines = updated)
        }
    }
}
