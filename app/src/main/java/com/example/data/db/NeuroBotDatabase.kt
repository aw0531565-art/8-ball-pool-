package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TrainingProfileEntity::class,
        QTableEntryEntity::class,
        ExperienceEntity::class,
        TrainingSessionEntity::class,
        ShotPhysicsEntity::class,
        BallTelemetryEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class NeuroBotDatabase : RoomDatabase() {
    abstract fun neuroBotDao(): NeuroBotDao

    companion object {
        @Volatile
        private var INSTANCE: NeuroBotDatabase? = null

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE training_profiles ADD COLUMN botSeatSide TEXT NOT NULL DEFAULT 'LEFT'")
                db.execSQL("ALTER TABLE training_profiles ADD COLUMN preferredSuit TEXT NOT NULL DEFAULT 'AUTO'")
                db.execSQL("ALTER TABLE training_profiles ADD COLUMN watchHumanLearn INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE training_profiles ADD COLUMN humanShotsObserved INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE training_profiles ADD COLUMN totalMistakesLogged INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE training_profiles ADD COLUMN scratchesCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE shot_physics_logs ADD COLUMN outcomeLabel TEXT NOT NULL DEFAULT 'SETTLED'")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE training_profiles ADD COLUMN learnedRestitution REAL NOT NULL DEFAULT 0.93")
                db.execSQL("ALTER TABLE training_profiles ADD COLUMN learnedCushionBounce REAL NOT NULL DEFAULT 0.80")
                db.execSQL("ALTER TABLE training_profiles ADD COLUMN avgPredictionError REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE training_profiles ADD COLUMN totalBallsRecorded INTEGER NOT NULL DEFAULT 0")

                db.execSQL("ALTER TABLE shot_physics_logs ADD COLUMN predCueX REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE shot_physics_logs ADD COLUMN predCueY REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE shot_physics_logs ADD COLUMN predTargetX REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE shot_physics_logs ADD COLUMN predTargetY REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE shot_physics_logs ADD COLUMN predTargetPotted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE shot_physics_logs ADD COLUMN cuePredError REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE shot_physics_logs ADD COLUMN targetPredError REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE shot_physics_logs ADD COLUMN ballsTrackedCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE shot_physics_logs ADD COLUMN collisionsCount INTEGER NOT NULL DEFAULT 0")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ball_telemetry_records (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        profileId INTEGER NOT NULL,
                        shotNumber INTEGER NOT NULL,
                        shotSource TEXT NOT NULL,
                        ballRole TEXT NOT NULL,
                        ballSuit TEXT NOT NULL,
                        ballColorTag TEXT NOT NULL,
                        preX REAL NOT NULL,
                        preY REAL NOT NULL,
                        predEndX REAL NOT NULL,
                        predEndY REAL NOT NULL,
                        predPotted INTEGER NOT NULL,
                        predPocketId TEXT NOT NULL,
                        postX REAL NOT NULL,
                        postY REAL NOT NULL,
                        actualMovedDist REAL NOT NULL,
                        actualPotted INTEGER NOT NULL,
                        actualPocketId TEXT NOT NULL,
                        predictionErrorDist REAL NOT NULL,
                        wasCollided INTEGER NOT NULL,
                        timestamp INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ball_telemetry_records_profileId ON ball_telemetry_records(profileId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ball_telemetry_records_profileId_shotNumber ON ball_telemetry_records(profileId, shotNumber)")
            }
        }

        fun getInstance(context: Context): NeuroBotDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NeuroBotDatabase::class.java,
                    "neurobot_memory.db"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
