package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NeuroBotDao {

    @Query("SELECT * FROM training_profiles ORDER BY id ASC")
    fun observeProfiles(): Flow<List<TrainingProfileEntity>>

    @Query("SELECT * FROM training_profiles ORDER BY id ASC")
    suspend fun getProfilesOnce(): List<TrainingProfileEntity>

    @Query("SELECT * FROM training_profiles WHERE id = :profileId LIMIT 1")
    suspend fun getProfileById(profileId: Long): TrainingProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: TrainingProfileEntity): Long

    @Update
    suspend fun updateProfile(profile: TrainingProfileEntity)

    @Query("DELETE FROM training_profiles WHERE id = :profileId")
    suspend fun deleteProfile(profileId: Long)

    @Query("SELECT * FROM q_table_entries WHERE profileId = :profileId ORDER BY lastUpdatedAt DESC")
    fun observeQEntries(profileId: Long): Flow<List<QTableEntryEntity>>

    @Query("SELECT * FROM q_table_entries WHERE profileId = :profileId")
    suspend fun getQEntriesForProfile(profileId: Long): List<QTableEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertQEntries(entries: List<QTableEntryEntity>)

    @Query("DELETE FROM q_table_entries WHERE profileId = :profileId")
    suspend fun clearQEntries(profileId: Long)

    @Query("SELECT * FROM experiences WHERE profileId = :profileId ORDER BY id DESC LIMIT :limit")
    fun observeRecentExperiences(profileId: Long, limit: Int = 60): Flow<List<ExperienceEntity>>

    @Query("SELECT * FROM experiences WHERE profileId = :profileId ORDER BY id DESC LIMIT :limit")
    suspend fun getRecentExperiencesOnce(profileId: Long, limit: Int = 256): List<ExperienceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExperiences(items: List<ExperienceEntity>)

    @Query("SELECT COUNT(*) FROM experiences WHERE profileId = :profileId")
    fun observeExperienceCount(profileId: Long): Flow<Int>

    @Query("DELETE FROM experiences WHERE profileId = :profileId")
    suspend fun clearExperiences(profileId: Long)

    @Query("SELECT * FROM training_sessions WHERE profileId = :profileId ORDER BY id ASC")
    fun observeSessions(profileId: Long): Flow<List<TrainingSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: TrainingSessionEntity): Long

    @Query("DELETE FROM training_sessions WHERE profileId = :profileId")
    suspend fun clearSessions(profileId: Long)

    @Query("SELECT * FROM shot_physics_logs WHERE profileId = :profileId ORDER BY id DESC LIMIT :limit")
    fun observeShotPhysics(profileId: Long, limit: Int = 40): Flow<List<ShotPhysicsEntity>>

    @Query("SELECT * FROM shot_physics_logs WHERE profileId = :profileId ORDER BY id DESC LIMIT :limit")
    suspend fun getShotPhysicsOnce(profileId: Long, limit: Int = 50): List<ShotPhysicsEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShotPhysics(item: ShotPhysicsEntity): Long

    @Query("DELETE FROM shot_physics_logs WHERE profileId = :profileId")
    suspend fun clearShotPhysics(profileId: Long)

    @Query("SELECT * FROM ball_telemetry_records WHERE profileId = :profileId ORDER BY id DESC LIMIT :limit")
    fun observeBallRecords(profileId: Long, limit: Int = 120): Flow<List<BallTelemetryEntity>>

    @Query("SELECT * FROM ball_telemetry_records WHERE profileId = :profileId ORDER BY id DESC LIMIT :limit")
    suspend fun getBallRecordsOnce(profileId: Long, limit: Int = 150): List<BallTelemetryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBallRecords(items: List<BallTelemetryEntity>)

    @Query("DELETE FROM ball_telemetry_records WHERE profileId = :profileId")
    suspend fun clearBallRecords(profileId: Long)
}
