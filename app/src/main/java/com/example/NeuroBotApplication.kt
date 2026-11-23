package com.example

import android.app.Application
import com.example.data.db.NeuroBotDatabase
import com.example.data.preferences.BotPreferencesStore
import com.example.data.repository.BotMemoryRepository
import com.example.domain.rl.SelfTrainingEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NeuroBotApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: NeuroBotDatabase by lazy { NeuroBotDatabase.getInstance(this) }
    val repository: BotMemoryRepository by lazy { BotMemoryRepository(database.neuroBotDao()) }
    val trainingEngine: SelfTrainingEngine by lazy { SelfTrainingEngine(repository) }
    val preferencesStore: BotPreferencesStore by lazy { BotPreferencesStore(this) }

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            repository.ensureDefaultProfiles()
        }
    }
}
