package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.i18n.BotStrings
import com.example.ui.screens.BotControlScreen
import com.example.ui.screens.CalibratorScreen
import com.example.ui.screens.MemoryBrainScreen
import com.example.ui.screens.ProfilesSettingsScreen
import com.example.ui.theme.NeuroBotTheme
import com.example.ui.viewmodel.BotViewModel

const val ROUTE_BOT_CONTROL = "bot_control"
const val ROUTE_CALIBRATOR = "calibrator"
const val ROUTE_MEMORY = "memory"
const val ROUTE_PROFILES = "profiles"

private data class NavTab(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: BotViewModel = viewModel()
            val prefs by viewModel.userPreferences.collectAsStateWithLifecycle()

            NeuroBotTheme(darkTheme = prefs.isDarkTheme) {
                NeuroBotAppShell(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        com.example.service.BotServiceBridge.setOwnAppForeground(true)
    }

    override fun onPause() {
        com.example.service.BotServiceBridge.setOwnAppForeground(false)
        super.onPause()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeuroBotAppShell(viewModel: BotViewModel) {
    val prefs by viewModel.userPreferences.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val qEntries by viewModel.qEntries.collectAsStateWithLifecycle()
    val recentExperiences by viewModel.recentExperiences.collectAsStateWithLifecycle()
    val experienceCount by viewModel.experienceCount.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val shotPhysicsLogs by viewModel.shotPhysicsLogs.collectAsStateWithLifecycle()
    val ballRecords by viewModel.ballRecords.collectAsStateWithLifecycle()
    val liveTelemetry by viewModel.liveTelemetry.collectAsStateWithLifecycle()
    val screenshotState by viewModel.screenshotTestState.collectAsStateWithLifecycle()

    val isUrdu = prefs.isUrduLanguage
    val copy = if (isUrdu) BotStrings.Urdu else BotStrings.English

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: ROUTE_BOT_CONTROL

    val tabs = listOf(
        NavTab(ROUTE_BOT_CONTROL, copy.tabBotControl, Icons.Filled.SmartToy, Icons.Outlined.SmartToy, "nav_bot_control"),
        NavTab(ROUTE_CALIBRATOR, copy.tabCalibrator, Icons.Filled.CenterFocusStrong, Icons.Outlined.CenterFocusStrong, "nav_calibrator"),
        NavTab(ROUTE_MEMORY, copy.tabMemory, Icons.Filled.Memory, Icons.Outlined.Memory, "nav_memory"),
        NavTab(ROUTE_PROFILES, copy.tabProfiles, Icons.Filled.Tune, Icons.Outlined.Tune, "nav_profiles")
    )

    val navigateToTab: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = copy.appTitle,
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    actions = {
                        TextButton(
                            onClick = { viewModel.toggleLanguage() },
                            modifier = Modifier.testTag("btn_toggle_language")
                        ) {
                            Text(copy.languageLabel)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
            bottomBar = {
                if (!useRail) {
                    NavigationBar {
                        tabs.forEach { tab ->
                            val selected = currentRoute == tab.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateToTab(tab.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label, maxLines = 1) },
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail {
                        tabs.forEach { tab ->
                            val selected = currentRoute == tab.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateToTab(tab.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label, maxLines = 1) },
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = ROUTE_BOT_CONTROL,
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 720.dp)
                    ) {
                        composable(ROUTE_BOT_CONTROL) {
                            BotControlScreen(
                                copy = copy,
                                isUrdu = isUrdu,
                                profile = activeProfile,
                                qEntries = qEntries,
                                experienceCount = experienceCount,
                                telemetry = liveTelemetry,
                                statusBanner = screenshotState.statusBanner,
                                onSelectBotMode = { viewModel.setBotMode(it) },
                                onSelectSeatSide = { viewModel.setBotSeatSide(it) },
                                onSelectPreferredSuit = { viewModel.setPreferredSuit(it) },
                                onToggleWatchHuman = { viewModel.setWatchHumanLearn(it) },
                                onFeedbackReward = { viewModel.giveManualLiveFeedback(it) },
                                onTriggerReplay = { viewModel.triggerExperienceReplay() },
                                onDismissBanner = { viewModel.clearStatusBanner() }
                            )
                        }

                        composable(ROUTE_CALIBRATOR) {
                            CalibratorScreen(
                                copy = copy,
                                isUrdu = isUrdu,
                                profile = activeProfile,
                                screenshotState = screenshotState,
                                onPickScreenshot = { viewModel.testVisionOnScreenshotUri(it) },
                                onSaveCalibration = { tl, tt, tr, tb, px, tx, ty, lx, cy, rx ->
                                    viewModel.updatePoolAndTapCalibration(tl, tt, tr, tb, px, tx, ty, lx, cy, rx)
                                }
                            )
                        }

                        composable(ROUTE_MEMORY) {
                            MemoryBrainScreen(
                                copy = copy,
                                isUrdu = isUrdu,
                                profile = activeProfile,
                                qEntries = qEntries,
                                experiences = recentExperiences,
                                sessions = sessions,
                                shotPhysicsLogs = shotPhysicsLogs,
                                ballRecords = ballRecords,
                                totalExperienceCount = experienceCount,
                                onTriggerReplay = { viewModel.triggerExperienceReplay() },
                                onResetMemory = { viewModel.resetActiveProfileMemory() },
                                onExportJson = { viewModel.exportBrainJson() }
                            )
                        }

                        composable(ROUTE_PROFILES) {
                            ProfilesSettingsScreen(
                                copy = copy,
                                isUrdu = isUrdu,
                                isDarkTheme = prefs.isDarkTheme,
                                profiles = profiles,
                                activeProfile = activeProfile,
                                onSelectProfile = { viewModel.selectProfile(it) },
                                onCreateProfile = { n, p, d -> viewModel.createProfile(n, p, d) },
                                onUpdateHyperparams = { a, g, e, decay, interval ->
                                    viewModel.updateHyperparameters(a, g, e, decay, interval)
                                },
                                onToggleLanguage = { viewModel.toggleLanguage() },
                                onToggleTheme = { viewModel.toggleTheme() }
                            )
                        }
                    }
                }
            }
        }
    }
}
