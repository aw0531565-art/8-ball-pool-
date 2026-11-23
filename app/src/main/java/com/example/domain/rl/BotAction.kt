package com.example.domain.rl

enum class BotAction(
    val labelEn: String,
    val labelUr: String,
    val shortCode: String,
    val powerFraction: Float = 0.65f,
    val angleTrimDeg: Float = 0f
) {
    // General Tap & Swipe actions
    MOVE_LEFT("Tap Left", "بائیں ٹیپ", "LEFT"),
    STAY("Hold / Wait", "انتظار", "STAY"),
    MOVE_RIGHT("Tap Right", "دائیں ٹیپ", "RIGHT"),
    TAP_CENTER("Tap Center", "درمیان ٹیپ", "TAP"),
    SWIPE_UP("Swipe Up", "اوپر سوائپ", "UP"),
    SWIPE_DOWN("Swipe Down", "نیچے سوائپ", "DOWN"),

    // Pool / Aim-Shot self-calibrated shot policies
    SHOT_SOFT_EXACT("Soft Precision", "ہلکا درست شاٹ", "SOFT", powerFraction = 0.42f, angleTrimDeg = 0f),
    SHOT_MED_EXACT("Medium Direct", "درمیانہ سیدھا شاٹ", "MED", powerFraction = 0.68f, angleTrimDeg = 0f),
    SHOT_POWER_EXACT("Power Drive", "تیز پاور شاٹ", "PWR", powerFraction = 0.90f, angleTrimDeg = 0f),
    SHOT_MED_TRIM_LEFT("Med + Left Cut", "درمیانہ بائیں کٹ", "MED-L", powerFraction = 0.66f, angleTrimDeg = -2.8f),
    SHOT_MED_TRIM_RIGHT("Med + Right Cut", "درمیانہ دائیں کٹ", "MED-R", powerFraction = 0.66f, angleTrimDeg = +2.8f);

    companion object {
        val LIVE_SCREEN_ACTIONS = listOf(MOVE_LEFT, TAP_CENTER, MOVE_RIGHT, SWIPE_UP, STAY)
        val POOL_SHOT_ACTIONS = listOf(
            SHOT_SOFT_EXACT,
            SHOT_MED_EXACT,
            SHOT_POWER_EXACT,
            SHOT_MED_TRIM_LEFT,
            SHOT_MED_TRIM_RIGHT
        )

        fun fromName(name: String): BotAction =
            entries.firstOrNull { it.name == name } ?: STAY
    }
}

data class ActionDecision(
    val action: BotAction,
    val isExploration: Boolean,
    val matchedNearestState: Boolean,
    val qEstimates: Map<BotAction, Float>
)

data class ReplayResult(
    val experiencesReplayed: Int,
    val qUpdatesApplied: Int,
    val averageTdError: Float
)
