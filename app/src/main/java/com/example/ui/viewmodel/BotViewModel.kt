package com.example.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.NeuroBotApplication
import com.example.data.db.BallTelemetryEntity
import com.example.data.db.ExperienceEntity
import com.example.data.db.QTableEntryEntity
import com.example.data.db.ShotPhysicsEntity
import com.example.data.db.TrainingProfileEntity
import com.example.data.db.TrainingSessionEntity
import com.example.data.preferences.BotUserPreferences
import com.example.domain.rl.BotAction
import com.example.domain.rl.ReplayResult
import com.example.domain.vision.PoolFrameAnalysis
import com.example.domain.vision.PoolVisionAnalyzer
import com.example.service.BotServiceBridge
import com.example.service.LiveBotTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ScreenshotTestState(
    val previewBitmap: Bitmap? = null,
    val poolAnalysis: PoolFrameAnalysis? = null,
    val statusBanner: String? = null
)

class BotViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NeuroBotApplication
    private val repository = app.repository
    private val engine = app.trainingEngine
    private val prefsStore = app.preferencesStore

    val userPreferences: StateFlow<BotUserPreferences> = prefsStore.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BotUserPreferences())

    val profiles: StateFlow<List<TrainingProfileEntity>> = repository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeProfile: StateFlow<TrainingProfileEntity?> = combine(
        profiles,
        userPreferences
    ) { list, prefs ->
        list.firstOrNull { it.id == prefs.activeProfileId } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    @Suppress("OPT_IN_USAGE")
    val qEntries: StateFlow<List<QTableEntryEntity>> = activeProfile
        .flatMapLatest { profile ->
            if (profile == null) flowOf(emptyList()) else repository.observeQEntries(profile.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @Suppress("OPT_IN_USAGE")
    val recentExperiences: StateFlow<List<ExperienceEntity>> = activeProfile
        .flatMapLatest { profile ->
            if (profile == null) flowOf(emptyList()) else repository.observeRecentExperiences(profile.id, 60)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @Suppress("OPT_IN_USAGE")
    val experienceCount: StateFlow<Int> = activeProfile
        .flatMapLatest { profile ->
            if (profile == null) flowOf(0) else repository.observeExperienceCount(profile.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    @Suppress("OPT_IN_USAGE")
    val sessions: StateFlow<List<TrainingSessionEntity>> = activeProfile
        .flatMapLatest { profile ->
            if (profile == null) flowOf(emptyList()) else repository.observeSessions(profile.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @Suppress("OPT_IN_USAGE")
    val shotPhysicsLogs: StateFlow<List<ShotPhysicsEntity>> = activeProfile
        .flatMapLatest { profile ->
            if (profile == null) flowOf(emptyList()) else repository.observeShotPhysics(profile.id, 40)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @Suppress("OPT_IN_USAGE")
    val ballRecords: StateFlow<List<BallTelemetryEntity>> = activeProfile
        .flatMapLatest { profile ->
            if (profile == null) flowOf(emptyList()) else repository.observeBallRecords(profile.id, 120)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val liveTelemetry: StateFlow<LiveBotTelemetry> = BotServiceBridge.telemetry

    private val _screenshotTestState = MutableStateFlow(ScreenshotTestState())
    val screenshotTestState: StateFlow<ScreenshotTestState> = _screenshotTestState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            repository.ensureDefaultProfiles()
        }
    }

    fun toggleLanguage() {
        viewModelScope.launch {
            prefsStore.setUrduLanguage(!userPreferences.value.isUrduLanguage)
        }
    }

    fun toggleTheme() {
        viewModelScope.launch {
            prefsStore.setDarkTheme(!userPreferences.value.isDarkTheme)
        }
    }

    fun selectProfile(profileId: Long) {
        viewModelScope.launch {
            prefsStore.setActiveProfileId(profileId)
            withContext(Dispatchers.IO) {
                engine.ensureLoaded(profileId)
            }
        }
    }

    fun setBotMode(botMode: String) {
        val profile = activeProfile.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateProfile(
                profile.copy(
                    botMode = botMode,
                    isLandscapeGame = botMode == "POOL_AIM",
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun setBotSeatSide(seatSide: String) {
        val normalized = seatSide.uppercase()
        BotServiceBridge.setManualSeatSideOverride(normalized)
        val profile = activeProfile.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateProfile(
                profile.copy(
                    botSeatSide = normalized,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun setPreferredSuit(suit: String) {
        val normalized = suit.uppercase()
        BotServiceBridge.setManualSuitOverride(normalized)
        val profile = activeProfile.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateProfile(
                profile.copy(
                    preferredSuit = normalized,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun setWatchHumanLearn(enabled: Boolean) {
        val profile = activeProfile.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateProfile(
                profile.copy(
                    watchHumanLearn = enabled,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun triggerExperienceReplay() {
        val profile = activeProfile.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val actions = if (profile.botMode == "POOL_AIM") {
                BotAction.POOL_SHOT_ACTIONS
            } else {
                BotAction.LIVE_SCREEN_ACTIONS
            }
            val result: ReplayResult = engine.runExperienceReplay(
                profileId = profile.id,
                learningRate = profile.learningRate,
                discountFactor = profile.discountFactor,
                availableActions = actions,
                batchSize = 80,
                epochs = 4
            )
            val latest = repository.getProfile(profile.id) ?: profile
            repository.updateProfile(
                latest.copy(
                    totalReplayUpdates = latest.totalReplayUpdates + result.qUpdatesApplied,
                    updatedAt = System.currentTimeMillis()
                )
            )
            _screenshotTestState.update {
                it.copy(
                    statusBanner = "Self-trained ${result.qUpdatesApplied} Q-updates from ${result.experiencesReplayed} saved memories (TD err ${"%.3f".format(result.averageTdError)})"
                )
            }
        }
    }

    fun clearStatusBanner() {
        _screenshotTestState.update { it.copy(statusBanner = null) }
    }

    fun testVisionOnScreenshotUri(uri: Uri) {
        val profile = activeProfile.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                app.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                }
                val maxDim = maxOf(options.outWidth, options.outHeight).coerceAtLeast(1)
                val sampleSize = (maxDim / 960).coerceAtLeast(1)
                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                val bitmap = app.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, decodeOpts)
                }
                if (bitmap != null) {
                    val analysis = PoolVisionAnalyzer.analyze(bitmap, profile)
                    _screenshotTestState.update {
                        it.copy(
                            previewBitmap = bitmap,
                            poolAnalysis = analysis,
                            statusBanner = analysis.summary
                        )
                    }
                }
            } catch (e: Exception) {
                _screenshotTestState.update {
                    it.copy(statusBanner = "Could not analyze screenshot: ${e.message}")
                }
            }
        }
    }

    fun giveManualLiveFeedback(rewardValue: Float) {
        val profile = activeProfile.value ?: return
        val telemetry = liveTelemetry.value
        val action = telemetry.latestAction ?: if (profile.botMode == "POOL_AIM") {
            BotAction.SHOT_MED_EXACT
        } else {
            BotAction.TAP_CENTER
        }
        val stateHash = telemetry.latestStateHash.takeIf { it != "IDLE" } ?: "POOL_MANUAL"
        val availableActions = if (profile.botMode == "POOL_AIM") {
            BotAction.POOL_SHOT_ACTIONS
        } else {
            BotAction.LIVE_SCREEN_ACTIONS
        }

        viewModelScope.launch(Dispatchers.IO) {
            engine.ensureLoaded(profile.id)
            engine.recordStep(
                profileId = profile.id,
                episodeNumber = telemetry.liveEpisodeCount.coerceAtLeast(1),
                stepIndex = telemetry.liveStepCount + 1,
                stateHash = stateHash,
                stateSummary = "Human teacher reinforcement",
                action = action,
                reward = rewardValue,
                nextStateHash = stateHash,
                isTerminal = rewardValue < 0f,
                availableActions = availableActions,
                learningRate = profile.learningRate,
                discountFactor = profile.discountFactor,
                source = "HUMAN_TEACHER",
                screenNotes = "Manual feedback (${rewardValue})"
            )
            engine.flushToDatabase(profile.id)
            BotServiceBridge.appendLog("Saved teacher feedback ${if (rewardValue > 0) "+5.0" else "-2.0"} for ${action.shortCode}")
        }
    }

    fun updateHyperparameters(
        learningRate: Float,
        discountFactor: Float,
        explorationRate: Float,
        autoDecayEpsilon: Boolean,
        actionIntervalMs: Long
    ) {
        val profile = activeProfile.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateProfile(
                profile.copy(
                    learningRate = learningRate.coerceIn(0.05f, 0.85f),
                    discountFactor = discountFactor.coerceIn(0.40f, 0.99f),
                    explorationRate = explorationRate.coerceIn(0.02f, 0.90f),
                    autoDecayEpsilon = autoDecayEpsilon,
                    actionIntervalMs = actionIntervalMs.coerceIn(200L, 3000L),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun updatePoolAndTapCalibration(
        tableLeft: Float,
        tableTop: Float,
        tableRight: Float,
        tableBottom: Float,
        powerBarX: Float,
        turnBoxX: Float,
        turnBoxY: Float,
        tapLeftX: Float,
        tapCenterY: Float,
        tapRightX: Float
    ) {
        val profile = activeProfile.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val updated = profile.copy(
                tableLeft = tableLeft,
                tableTop = tableTop,
                tableRight = tableRight,
                tableBottom = tableBottom,
                powerBarX = powerBarX,
                turnBoxX = turnBoxX,
                turnBoxY = turnBoxY,
                tapLeftX = tapLeftX,
                tapCenterY = tapCenterY,
                tapRightX = tapRightX,
                updatedAt = System.currentTimeMillis()
            )
            repository.updateProfile(updated)
            val bmp = _screenshotTestState.value.previewBitmap
            if (bmp != null) {
                val reAnalyzed = PoolVisionAnalyzer.analyze(bmp, updated)
                _screenshotTestState.update {
                    it.copy(
                        poolAnalysis = reAnalyzed,
                        statusBanner = "Calibration saved • ${reAnalyzed.summary}"
                    )
                }
            } else {
                _screenshotTestState.update {
                    it.copy(statusBanner = "Calibration saved to ${profile.name}")
                }
            }
        }
    }

    fun createProfile(name: String, targetPackage: String, description: String, botMode: String = "POOL_AIM") {
        if (name.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val newId = repository.createProfile(
                TrainingProfileEntity(
                    name = name.trim(),
                    targetPackage = targetPackage.trim().ifEmpty { "any.game.screen" },
                    description = description.trim().ifEmpty { "External game auto-play profile." },
                    botMode = botMode,
                    isLandscapeGame = botMode == "POOL_AIM"
                )
            )
            prefsStore.setActiveProfileId(newId)
        }
    }

    fun resetActiveProfileMemory() {
        val profile = activeProfile.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            engine.clearCacheForProfile(profile.id)
            repository.resetProfileMemory(profile.id)
            _screenshotTestState.update {
                it.copy(statusBanner = "Cleared saved memory for ${profile.name}")
            }
        }
    }

    suspend fun exportBrainJson(): String {
        val profile = activeProfile.value ?: return "{}"
        return withContext(Dispatchers.IO) {
            engine.flushToDatabase(profile.id)
            repository.exportProfileBrainJson(profile.id)
        }
    }
}
