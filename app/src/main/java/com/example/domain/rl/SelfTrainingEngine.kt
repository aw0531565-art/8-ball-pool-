package com.example.domain.rl

import com.example.data.db.ExperienceEntity
import com.example.data.db.QTableEntryEntity
import com.example.data.repository.BotMemoryRepository
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.random.Random

/**
 * Genuine on-device Tabular + Perceptual-Similarity Q-Learning engine with
 * Experience Replay persisted into Room SQLite.
 */
class SelfTrainingEngine(private val repository: BotMemoryRepository) {

    private data class CachedQCell(
        var qValue: Float,
        var visitCount: Int,
        var avgReward: Float,
        var stateSummary: String,
        var lastUpdatedAt: Long,
        var dirty: Boolean = false
    )

    // profileId -> stateHash -> actionName -> CachedQCell
    private val cache = ConcurrentHashMap<Long, ConcurrentHashMap<String, ConcurrentHashMap<String, CachedQCell>>>()
    private val loadedProfiles = ConcurrentHashMap.newKeySet<Long>()
    private val pendingExperiences = mutableListOf<ExperienceEntity>()
    private val lock = Any()

    suspend fun ensureLoaded(profileId: Long) {
        if (loadedProfiles.contains(profileId)) return
        val rows = repository.getQEntriesOnce(profileId)
        val profileMap = cache.getOrPut(profileId) { ConcurrentHashMap() }
        rows.forEach { row ->
            val actionMap = profileMap.getOrPut(row.stateHash) { ConcurrentHashMap() }
            actionMap[row.actionName] = CachedQCell(
                qValue = row.qValue,
                visitCount = row.visitCount,
                avgReward = row.avgReward,
                stateSummary = row.stateSummary,
                lastUpdatedAt = row.lastUpdatedAt,
                dirty = false
            )
        }
        loadedProfiles.add(profileId)
    }

    fun clearCacheForProfile(profileId: Long) {
        cache.remove(profileId)
        loadedProfiles.remove(profileId)
        synchronized(lock) {
            pendingExperiences.removeAll { it.profileId == profileId }
        }
    }

    fun selectAction(
        profileId: Long,
        stateHash: String,
        availableActions: List<BotAction>,
        epsilon: Float
    ): ActionDecision {
        val profileMap = cache[profileId]
        val exactMap = profileMap?.get(stateHash)
        var usedNearest = false
        val lookupMap = if (exactMap != null && exactMap.isNotEmpty()) {
            exactMap
        } else {
            val nearestHash = findNearestStateHash(profileMap, stateHash)
            if (nearestHash != null) {
                usedNearest = true
                profileMap?.get(nearestHash)
            } else {
                null
            }
        }

        val estimates = availableActions.associateWith { action ->
            lookupMap?.get(action.name)?.qValue ?: 0f
        }

        val explore = Random.nextFloat() < epsilon.coerceIn(0f, 0.95f)
        val chosen = if (explore) {
            // Prefer least-visited actions slightly during exploration
            val unvisited = availableActions.filter { (lookupMap?.get(it.name)?.visitCount ?: 0) == 0 }
            if (unvisited.isNotEmpty()) unvisited.random() else availableActions.random()
        } else {
            val maxQ = estimates.values.maxOrNull() ?: 0f
            val bestActions = availableActions.filter { abs((estimates[it] ?: 0f) - maxQ) < 1e-4f }
            bestActions.random()
        }

        return ActionDecision(
            action = chosen,
            isExploration = explore,
            matchedNearestState = usedNearest,
            qEstimates = estimates
        )
    }

    fun recordStep(
        profileId: Long,
        episodeNumber: Int,
        stepIndex: Int,
        stateHash: String,
        stateSummary: String,
        action: BotAction,
        reward: Float,
        nextStateHash: String,
        isTerminal: Boolean,
        availableActions: List<BotAction>,
        learningRate: Float,
        discountFactor: Float,
        source: String,
        screenNotes: String = ""
    ): Float {
        val profileMap = cache.getOrPut(profileId) { ConcurrentHashMap() }
        val stateActions = profileMap.getOrPut(stateHash) { ConcurrentHashMap() }
        val cell = stateActions.getOrPut(action.name) {
            CachedQCell(
                qValue = 0f,
                visitCount = 0,
                avgReward = 0f,
                stateSummary = stateSummary,
                lastUpdatedAt = System.currentTimeMillis(),
                dirty = true
            )
        }

        val maxNextQ = if (isTerminal) {
            0f
        } else {
            val nextMap = profileMap[nextStateHash]
            availableActions.maxOfOrNull { nextMap?.get(it.name)?.qValue ?: 0f } ?: 0f
        }

        val target = reward + discountFactor * maxNextQ
        val tdError = target - cell.qValue
        val newQ = cell.qValue + learningRate * tdError
        val newVisits = cell.visitCount + 1
        val newAvgReward = ((cell.avgReward * cell.visitCount) + reward) / newVisits

        cell.qValue = newQ
        cell.visitCount = newVisits
        cell.avgReward = newAvgReward
        cell.stateSummary = stateSummary
        cell.lastUpdatedAt = System.currentTimeMillis()
        cell.dirty = true

        synchronized(lock) {
            pendingExperiences.add(
                ExperienceEntity(
                    profileId = profileId,
                    episodeNumber = episodeNumber,
                    stepIndex = stepIndex,
                    stateHash = stateHash,
                    actionName = action.name,
                    reward = reward,
                    nextStateHash = nextStateHash,
                    isTerminal = isTerminal,
                    source = source,
                    screenNotes = screenNotes
                )
            )
        }
        return newQ
    }

    suspend fun flushToDatabase(profileId: Long) {
        val profileMap = cache[profileId]
        val dirtyEntries = mutableListOf<QTableEntryEntity>()
        profileMap?.forEach { (stateHash, actionMap) ->
            actionMap.forEach { (actionName, cell) ->
                if (cell.dirty) {
                    dirtyEntries.add(
                        QTableEntryEntity(
                            profileId = profileId,
                            stateHash = stateHash,
                            actionName = actionName,
                            qValue = cell.qValue,
                            visitCount = cell.visitCount,
                            avgReward = cell.avgReward,
                            stateSummary = cell.stateSummary,
                            lastUpdatedAt = cell.lastUpdatedAt
                        )
                    )
                    cell.dirty = false
                }
            }
        }
        val toInsertExp: List<ExperienceEntity>
        synchronized(lock) {
            toInsertExp = pendingExperiences.filter { it.profileId == profileId }
            pendingExperiences.removeAll { it.profileId == profileId }
        }
        if (dirtyEntries.isNotEmpty()) {
            repository.upsertQEntries(dirtyEntries)
        }
        if (toInsertExp.isNotEmpty()) {
            repository.insertExperiences(toInsertExp)
        }
    }

    suspend fun runExperienceReplay(
        profileId: Long,
        learningRate: Float,
        discountFactor: Float,
        availableActions: List<BotAction>,
        batchSize: Int = 64,
        epochs: Int = 3
    ): ReplayResult {
        ensureLoaded(profileId)
        flushToDatabase(profileId)

        val stored = repository.getRecentExperiencesOnce(profileId, limit = 256)
        if (stored.isEmpty()) {
            return ReplayResult(0, 0, 0f)
        }

        // Prioritize informative transitions (terminal or large absolute reward) mixed with random samples
        val sortedBySalience = stored.sortedByDescending { abs(it.reward) + if (it.isTerminal) 2f else 0f }
        val topHalf = sortedBySalience.take((batchSize / 2).coerceAtLeast(1))
        val randomHalf = stored.shuffled().take((batchSize - topHalf.size).coerceAtLeast(1))
        val batch = (topHalf + randomHalf).distinctBy { it.id }

        val profileMap = cache.getOrPut(profileId) { ConcurrentHashMap() }
        var updates = 0
        var totalTdError = 0f

        repeat(epochs) {
            batch.shuffled().forEach { exp ->
                val stateActions = profileMap.getOrPut(exp.stateHash) { ConcurrentHashMap() }
                val cell = stateActions.getOrPut(exp.actionName) {
                    CachedQCell(
                        qValue = 0f,
                        visitCount = 1,
                        avgReward = exp.reward,
                        stateSummary = exp.screenNotes.ifEmpty { "Replay state" },
                        lastUpdatedAt = System.currentTimeMillis(),
                        dirty = true
                    )
                }
                val maxNextQ = if (exp.isTerminal) {
                    0f
                } else {
                    val nextMap = profileMap[exp.nextStateHash]
                    availableActions.maxOfOrNull { nextMap?.get(it.name)?.qValue ?: 0f } ?: 0f
                }
                val target = exp.reward + discountFactor * maxNextQ
                val tdError = target - cell.qValue
                cell.qValue = cell.qValue + (learningRate * 0.65f) * tdError
                cell.lastUpdatedAt = System.currentTimeMillis()
                cell.dirty = true
                totalTdError += abs(tdError)
                updates++
            }
        }

        flushToDatabase(profileId)
        val avgTd = if (updates > 0) totalTdError / updates else 0f
        return ReplayResult(
            experiencesReplayed = batch.size,
            qUpdatesApplied = updates,
            averageTdError = avgTd
        )
    }

    private fun findNearestStateHash(
        profileMap: ConcurrentHashMap<String, ConcurrentHashMap<String, CachedQCell>>?,
        targetHash: String
    ): String? {
        if (profileMap == null || profileMap.isEmpty()) return null
        var bestKey: String? = null
        var bestDist = Int.MAX_VALUE
        // Compare prefix/grid tokens when hashes share format
        for (candidate in profileMap.keys) {
            if (candidate.length != targetHash.length) continue
            var dist = 0
            for (i in targetHash.indices) {
                if (targetHash[i] != candidate[i]) dist++
            }
            if (dist < bestDist) {
                bestDist = dist
                bestKey = candidate
            }
        }
        return if (bestDist <= (targetHash.length / 4).coerceAtLeast(2)) bestKey else null
    }
}
