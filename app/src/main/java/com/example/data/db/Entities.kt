package com.example.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "training_profiles")
data class TrainingProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val targetPackage: String,
    val description: String = "",
    val botMode: String = "POOL_AIM", // "POOL_AIM" or "GENERAL_TAP"
    val isLandscapeGame: Boolean = true,
    val learningRate: Float = 0.25f,
    val discountFactor: Float = 0.90f,
    val explorationRate: Float = 0.25f,
    val autoDecayEpsilon: Boolean = true,
    val actionIntervalMs: Long = 650L,
    // General Tap/Swipe zones (normalized 0..1)
    val tapLeftX: Float = 0.20f,
    val tapLeftY: Float = 0.78f,
    val tapCenterX: Float = 0.50f,
    val tapCenterY: Float = 0.75f,
    val tapRightX: Float = 0.80f,
    val tapRightY: Float = 0.78f,
    val hudTopY: Float = 0.03f,
    val hudBottomY: Float = 0.16f,
    // Pool / Aim-Shot calibration zones (normalized 0..1)
    val tableLeft: Float = 0.16f,
    val tableTop: Float = 0.18f,
    val tableRight: Float = 0.83f,
    val tableBottom: Float = 0.95f,
    val powerBarX: Float = 0.072f,
    val powerBarTopY: Float = 0.27f,
    val powerBarBottomY: Float = 0.84f,
    val turnBoxX: Float = 0.43f,
    val turnBoxY: Float = 0.08f,
    val pottedBarLeft: Float = 0.18f,
    val pottedBarRight: Float = 0.38f,
    val pottedBarY: Float = 0.125f,
    // Self-learned game physics model
    val learnedPowerScale: Float = 1.0f,
    val learnedFrictionCoef: Float = 0.52f,
    val learnedCutBiasDeg: Float = 0.0f,
    val learnedRestitution: Float = 0.93f,
    val learnedCushionBounce: Float = 0.80f,
    val avgPredictionError: Float = 0.0f,
    val totalBallsRecorded: Int = 0,
    val physicsSamplesCount: Int = 0,
    // Turn ownership & suit/imitation configuration
    val botSeatSide: String = "LEFT", // "LEFT", "RIGHT", "WATCH"
    val preferredSuit: String = "AUTO", // "AUTO", "SOLIDS", "STRIPES"
    val watchHumanLearn: Boolean = true,
    val humanShotsObserved: Int = 0,
    val totalMistakesLogged: Int = 0,
    val scratchesCount: Int = 0,
    // Training stats
    val totalEpisodes: Int = 0,
    val totalSteps: Int = 0,
    val bestScore: Int = 0,
    val lastScore: Int = 0,
    val totalReplayUpdates: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "q_table_entries",
    primaryKeys = ["profileId", "stateHash", "actionName"],
    indices = [Index(value = ["profileId"])]
)
data class QTableEntryEntity(
    val profileId: Long,
    val stateHash: String,
    val actionName: String,
    val qValue: Float,
    val visitCount: Int,
    val avgReward: Float,
    val stateSummary: String,
    val lastUpdatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "experiences",
    indices = [Index(value = ["profileId"])]
)
data class ExperienceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val profileId: Long,
    val episodeNumber: Int,
    val stepIndex: Int,
    val stateHash: String,
    val actionName: String,
    val reward: Float,
    val nextStateHash: String,
    val isTerminal: Boolean,
    val source: String,
    val screenNotes: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "training_sessions",
    indices = [Index(value = ["profileId"])]
)
data class TrainingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val profileId: Long,
    val episodeNumber: Int,
    val stepsCount: Int,
    val totalReward: Float,
    val score: Int,
    val explorationRateUsed: Float,
    val source: String,
    val durationMs: Long,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "shot_physics_logs",
    indices = [Index(value = ["profileId"])]
)
data class ShotPhysicsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val profileId: Long,
    val shotNumber: Int,
    val preCueX: Float,
    val preCueY: Float,
    val preTargetX: Float,
    val preTargetY: Float,
    val pocketId: String,
    val cutAngleDeg: Float,
    val plannedDistance: Float,
    val powerFraction: Float,
    val angleTrimDeg: Float,
    val postCueX: Float,
    val postCueY: Float,
    val cueTravelDist: Float,
    val estimatedFriction: Float,
    val angleDeflectionErrorDeg: Float,
    val pottedBall: Boolean,
    val keptTurn: Boolean,
    val reward: Float,
    val outcomeLabel: String = "SETTLED",
    val predCueX: Float = 0f,
    val predCueY: Float = 0f,
    val predTargetX: Float = 0f,
    val predTargetY: Float = 0f,
    val predTargetPotted: Boolean = false,
    val cuePredError: Float = 0f,
    val targetPredError: Float = 0f,
    val ballsTrackedCount: Int = 0,
    val collisionsCount: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "ball_telemetry_records",
    indices = [Index(value = ["profileId"]), Index(value = ["profileId", "shotNumber"])]
)
data class BallTelemetryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val profileId: Long,
    val shotNumber: Int,
    val shotSource: String, // "BOT" or "HUMAN"
    val ballRole: String,   // "CUE", "PRIMARY_TARGET", "COLLIDED_SECONDARY", "EIGHT_BALL", "STATIC"
    val ballSuit: String,   // "SOLID", "STRIPE", "EIGHT_BALL", "CUE"
    val ballColorTag: String,
    val preX: Float,
    val preY: Float,
    val predEndX: Float,
    val predEndY: Float,
    val predPotted: Boolean,
    val predPocketId: String,
    val postX: Float,
    val postY: Float,
    val actualMovedDist: Float,
    val actualPotted: Boolean,
    val actualPocketId: String,
    val predictionErrorDist: Float,
    val wasCollided: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
