package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.data.db.QTableEntryEntity
import com.example.data.db.TrainingProfileEntity
import com.example.service.LiveBotTelemetry
import com.example.service.ScreenCaptureService
import com.example.ui.components.BotHeaderHeroCard
import com.example.ui.components.VisionSensorMatrix
import com.example.ui.i18n.BotCopy
import com.example.ui.theme.AmberPulse
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.CyanElectric
import com.example.ui.theme.EmeraldNeon

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BotControlScreen(
    copy: BotCopy,
    isUrdu: Boolean,
    profile: TrainingProfileEntity?,
    qEntries: List<QTableEntryEntity>,
    experienceCount: Int,
    telemetry: LiveBotTelemetry,
    statusBanner: String?,
    onSelectBotMode: (String) -> Unit,
    onSelectSeatSide: (String) -> Unit,
    onSelectPreferredSuit: (String) -> Unit,
    onToggleWatchHuman: (Boolean) -> Unit,
    onFeedbackReward: (Float) -> Unit,
    onTriggerReplay: () -> Unit,
    onDismissBanner: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isPoolMode = (profile?.botMode ?: "POOL_AIM") == "POOL_AIM"

    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val startIntent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(ScreenCaptureService.EXTRA_PROFILE_ID, profile?.id ?: 1L)
            }
            ContextCompat.startForegroundService(context, startIntent)
        }
    }

    val launchScreenCapturePrompt = {
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        launchScreenCapturePrompt()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            BotHeaderHeroCard(
                profileName = profile?.name ?: copy.appTitle,
                subtitle = copy.heroSubtitle,
                isBotActive = telemetry.isScreenCaptureActive && !telemetry.isLiveBotPaused,
                qStateCount = qEntries.map { it.stateHash }.distinct().size,
                experienceCount = experienceCount,
                bestScore = profile?.bestScore ?: 0,
                isUrdu = isUrdu
            )
        }

        item {
            AnimatedVisibility(visible = statusBanner != null) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = EmeraldNeon.copy(alpha = 0.16f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, EmeraldNeon.copy(alpha = 0.45f), MaterialTheme.shapes.medium)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = statusBanner.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = EmeraldNeon,
                            modifier = Modifier.weight(1f)
                        )
                        FilledTonalButton(onClick = onDismissBanner) {
                            Text("OK")
                        }
                    }
                }
            }
        }

        // Bot Strategy Mode selector
        item {
            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (isUrdu) "گیم آٹومیشن موڈ منتخب کریں" else "Target Game Engine Mode",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilterChip(
                            selected = isPoolMode,
                            onClick = { onSelectBotMode("POOL_AIM") },
                            leadingIcon = {
                                Icon(Icons.Filled.SportsEsports, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            label = {
                                Text(if (isUrdu) "8-Ball / Aim & Shoot" else "8-Ball / Aim & Shoot")
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chip_mode_pool")
                        )
                        FilterChip(
                            selected = !isPoolMode,
                            onClick = { onSelectBotMode("GENERAL_TAP") },
                            leadingIcon = {
                                Icon(Icons.Filled.TouchApp, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            label = {
                                Text(if (isUrdu) "جنرل Tap / Swipe" else "Tap & Swipe")
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chip_mode_general")
                        )
                    }
                    Text(
                        text = if (isPoolMode) {
                            if (isUrdu) {
                                "ٹیبل، سفید کیو بال، ٹارگٹ بالز، سبز ٹرن بارڈر اور بائیں پاور سلائیڈر کو پڑھ کر خودکار شاٹ کھیلتا ہے اور نتیجے سے سیکھتا ہے۔"
                            } else {
                                "Detects table felt, cue ball, target balls, green turn border, aims the cue stick, pulls the left power bar, and learns from shot outcomes."
                            }
                        } else {
                            if (isUrdu) {
                                "کسی بھی موبائل گیم کی اسکرین کو 6×6 ویژن گرڈ اور UI ٹیکسٹ سے پڑھ کر خودکار Tap اور Swipe کرتا ہے۔"
                            } else {
                                "Reads any mobile game screen via 6×6 visual grid + UI nodes and executes learned taps/swipes."
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Turn Ownership, Suit Recognition & Watch-Human Learning Card (POOL_AIM)
        if (isPoolMode) {
            item {
                val activeSeat = (telemetry.manualSeatSideOverride ?: profile?.botSeatSide ?: "LEFT").uppercase()
                val activeSuit = (telemetry.manualSuitOverride ?: profile?.preferredSuit ?: "AUTO").uppercase()
                val watchHuman = profile?.watchHumanLearn ?: true
                val humanShots = maxOf(profile?.humanShotsObserved ?: 0, telemetry.humanShotsObserved)

                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isUrdu) "اپنی باری، بالز (Solids/Stripes) اور ہیومن لرننگ" else "Turn Control, Ball Suit & Human Learning",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = if (isUrdu) "سیکھے شاٹس: $humanShots" else "Observed: $humanShots",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyanElectric
                            )
                        }

                        Text(
                            text = if (isUrdu) {
                                "بوٹ صرف اپنی منتخب باری پر کھیلے گا۔ مخالف/ہیومن کی باری پر ہاتھ نہیں لگائے گا بلکہ دیکھ کر سیکھے گا:"
                            } else {
                                "Bot plays ONLY its selected turn and stays hands-off on Human turns while passively learning physics & shots:"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                "LEFT" to (if (isUrdu) "Bot بایاں (P1)" else "Bot P1 (L)"),
                                "RIGHT" to (if (isUrdu) "Bot دایاں (P2)" else "Bot P2 (R)"),
                                "WATCH" to (if (isUrdu) "صرف دیکھے" else "Watch Only")
                            ).forEach { (seatCode, label) ->
                                FilterChip(
                                    selected = activeSeat == seatCode,
                                    onClick = { onSelectSeatSide(seatCode) },
                                    label = { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("chip_seat_${seatCode.lowercase()}")
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                "AUTO" to (if (isUrdu) "Auto (HUD)" else "Auto (HUD)"),
                                "SOLIDS" to (if (isUrdu) "Solids (1-7)" else "Solids (1-7)"),
                                "STRIPES" to (if (isUrdu) "Stripes (9-15)" else "Stripes (9-15)")
                            ).forEach { (suitCode, label) ->
                                FilterChip(
                                    selected = activeSuit == suitCode,
                                    onClick = { onSelectPreferredSuit(suitCode) },
                                    label = { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("chip_suit_${suitCode.lowercase()}")
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isUrdu) {
                                    "ہیومن شاٹس دیکھ کر خودکار ٹریننگ کریں"
                                } else {
                                    "Learn in real-time when Human plays"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = watchHuman,
                                onCheckedChange = onToggleWatchHuman,
                                modifier = Modifier.testTag("switch_watch_human")
                            )
                        }
                    }
                }
            }
        }

        // Step 1: Accessibility Gesture Controller
        item {
            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.AccessibilityNew, contentDescription = null, tint = CyanElectric)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = copy.accessibilityCardTitle, style = MaterialTheme.typography.titleSmall)
                        }
                        StatusBadge(
                            isActive = telemetry.isAccessibilityConnected,
                            activeText = copy.activeLabel,
                            inactiveText = copy.inactiveLabel
                        )
                    }
                    Text(
                        text = if (isUrdu) {
                            "دوسری گیم پر خودکار نشانہ لگانے (Swipe)، پاور بار کھینچنے اور چھوٹا فلوٹنگ آئیکون دکھانے کے لیے Android Accessibility میں NeuroBot کو آن کریں۔"
                        } else {
                            "Required so NeuroBot can show the mini floating icon, aim the cue, and pull the power slider inside external games."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!telemetry.isAccessibilityConnected) {
                        Text(
                            text = if (isUrdu) {
                                "نوٹ (Android 13+/Vivo): اگر Accessibility بٹن بند (grey) ہو تو پہلے 'App Info' کھول کر اوپر ⋮ سے 'Allow restricted settings' آن کریں۔"
                            } else {
                                "Tip (Android 13+/Vivo): If NeuroBot is greyed out in Accessibility, open App Info -> ⋮ -> Allow restricted settings first."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = AmberPulse
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("btn_open_accessibility_settings")
                        ) {
                            Text(copy.openSettingsBtn, maxLines = 1)
                        }
                        if (!telemetry.isAccessibilityConnected) {
                            FilledTonalButton(
                                onClick = {
                                    val intent = Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        android.net.Uri.fromParts("package", context.packageName, null)
                                    ).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("btn_open_app_info")
                            ) {
                                Text(if (isUrdu) "App Info" else "App Info", maxLines = 1)
                            }
                        }
                    }
                }
            }
        }

        // Step 2: MediaProjection Live Screen Reader
        item {
            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Cast, contentDescription = null, tint = EmeraldNeon)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = copy.screenReaderCardTitle, style = MaterialTheme.typography.titleSmall)
                        }
                        StatusBadge(
                            isActive = telemetry.isScreenCaptureActive,
                            activeText = copy.activeLabel,
                            inactiveText = copy.inactiveLabel
                        )
                    }

                    Text(
                        text = if (isUrdu) {
                            "یہ بٹن دبانے کے بعد اپنی گیم (مثلاً 8 Ball Pool) کھول لیں۔ بوٹ اسکرین پڑھ کر خودکار کھیلے گا اور تجربات محفوظ کرے گا۔"
                        } else {
                            "Tap below, grant screen capture, then switch to your external game (e.g., 8 Ball Pool). Use the notification shade anytime to Pause, Train Replay, or Stop."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                if (telemetry.isScreenCaptureActive) {
                                    context.startService(
                                        Intent(context, ScreenCaptureService::class.java)
                                            .setAction(ScreenCaptureService.ACTION_STOP)
                                    )
                                } else {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                        ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.POST_NOTIFICATIONS
                                        ) != PackageManager.PERMISSION_GRANTED
                                    ) {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        launchScreenCapturePrompt()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (telemetry.isScreenCaptureActive) CrimsonAlert else EmeraldNeon,
                                contentColor = Color(0xFF070B14)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("btn_toggle_screen_capture")
                        ) {
                            Icon(
                                imageVector = if (telemetry.isScreenCaptureActive) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (telemetry.isScreenCaptureActive) copy.stopScreenReaderBtn else copy.startScreenReaderBtn,
                                maxLines = 1
                            )
                        }

                        if (telemetry.isScreenCaptureActive) {
                            FilledTonalButton(
                                onClick = {
                                    context.startService(
                                        Intent(context, ScreenCaptureService::class.java)
                                            .setAction(ScreenCaptureService.ACTION_TOGGLE_PAUSE)
                                    )
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("btn_pause_live_bot")
                            ) {
                                Icon(
                                    imageVector = if (telemetry.isLiveBotPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                    contentDescription = "Pause or resume live bot"
                                )
                            }
                        }
                    }
                }
            }
        }

        // Live Force Meter + Floating Overlay Info
        item {
            val activeForce = telemetry.manualPowerOverride
                ?: telemetry.plannedPowerFraction.takeIf { it > 0.01f }
                ?: telemetry.lastShotPowerFraction
            val forcePct = (activeForce * 100f).toInt().coerceIn(0, 100)
            val lastForcePct = (telemetry.lastShotPowerFraction * 100f).toInt()

            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isUrdu) "شاٹ فورس اور منی فلوٹنگ آئیکون" else "Live Shot Force & Mini Floating Icon",
                            style = MaterialTheme.typography.titleSmall
                        )
                        StatusBadge(
                            isActive = telemetry.isScreenCaptureActive && telemetry.isAccessibilityConnected,
                            activeText = if (isUrdu) "آئیکون آن" else "HUD ON",
                            inactiveText = if (isUrdu) "آف" else "HUD OFF"
                        )
                    }
                    Text(
                        text = if (isUrdu) {
                            "بوٹ آن کرنے پر گیم کے اوپر ایک چھوٹا آئیکون نظر آئے گا؛ اس پر ٹیپ کرنے سے وہ بڑا ہو کر ON/OFF اور لائیو فورس دکھاتا ہے۔"
                        } else {
                            "When active over a game, a small draggable NeuroBot orb floats on screen. Tap it to expand ON/OFF, live Shot Force, and quick power presets."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (telemetry.manualPowerOverride != null) {
                                "Force: ${forcePct}% (Manual)"
                            } else {
                                "Force: ${forcePct}% (AI Auto)"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = AmberPulse
                        )
                        Text(
                            text = "Last: ${if (lastForcePct > 0) "$lastForcePct%" else "--"} • Fric ${"%.2f".format(telemetry.estimatedFriction)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanElectric
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf<Pair<String, Float?>>(
                            "Auto" to null,
                            "35%" to 0.35f,
                            "65%" to 0.65f,
                            "90%" to 0.90f
                        ).forEach { (label, frac) ->
                            val selected = if (frac == null) {
                                telemetry.manualPowerOverride == null
                            } else {
                                telemetry.manualPowerOverride != null &&
                                    kotlin.math.abs(telemetry.manualPowerOverride - frac) < 0.05f
                            }
                            FilterChip(
                                selected = selected,
                                onClick = { com.example.service.BotServiceBridge.setManualPowerOverride(frac) },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("chip_force_${label.lowercase()}")
                            )
                        }
                    }
                }
            }
        }

        // Live Screen Radar + External Telemetry
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                VisionSensorMatrix(
                    title = copy.visionSensorTitle,
                    gridCells = telemetry.latestVisionGrid,
                    stateHash = telemetry.latestStateHash,
                    modifier = Modifier.weight(0.46f)
                )
                Card(
                    modifier = Modifier.weight(0.54f),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = if (isUrdu) "ایکسٹرنل اسکرین اسٹیٹس" else "External Screen Status",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "App: ${telemetry.activeForegroundPackage.substringAfterLast('.')}",
                            style = MaterialTheme.typography.labelMedium,
                            color = CyanElectric
                        )
                        if (isPoolMode) {
                            Text(
                                text = "Turn: ${telemetry.turnOwnerLabel}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (telemetry.isMyTurnDetected) EmeraldNeon else AmberPulse
                            )
                            Text(
                                text = "Suit: ${telemetry.effectiveSuitLabel} (HUD: ${telemetry.detectedSuitLabel}) • Learned Human: ${telemetry.humanShotsObserved}",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyanElectric
                            )
                            Text(
                                text = "Cue: ${if (telemetry.isCueBallDetected) "OK" else "Scan"} • Targets: ${telemetry.detectedTargetBallsCount} • Pkt: ${telemetry.plannedPocketId ?: "-"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Pred: ${telemetry.simulationSummary}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AmberPulse
                            )
                            Text(
                                text = "Balls Recorded: ${telemetry.ballsTrackedCount} • AvgErr: ${"%.3f".format(telemetry.avgPredictionError)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                text = "UI Nodes: ${telemetry.clickableNodeCount}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = "Last: ${telemetry.latestAction?.shortCode ?: "NONE"} (r=${"%.1f".format(telemetry.latestReward)})",
                            style = MaterialTheme.typography.labelSmall,
                            color = EmeraldNeon
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilledTonalButton(
                                onClick = { onFeedbackReward(5.0f) },
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("btn_feedback_good"),
                                contentPadding = PaddingValues(horizontal = 6.dp)
                            ) {
                                Icon(Icons.Filled.ThumbUp, contentDescription = copy.rewardFeedbackGood, modifier = Modifier.size(16.dp))
                            }
                            FilledTonalButton(
                                onClick = { onFeedbackReward(-2.0f) },
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("btn_feedback_bad"),
                                contentPadding = PaddingValues(horizontal = 6.dp)
                            ) {
                                Icon(Icons.Filled.ThumbDown, contentDescription = copy.rewardFeedbackBad, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }

        if (telemetry.detectedUiTexts.isNotEmpty()) {
            item {
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = if (isUrdu) "اسکرین سے پڑھا گیا ٹیکسٹ" else "Detected Screen UI Text",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            telemetry.detectedUiTexts.take(10).forEach { token ->
                                AssistChip(
                                    onClick = {},
                                    label = { Text(token, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            FilledTonalButton(
                onClick = onTriggerReplay,
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("btn_trigger_replay")
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${copy.replayTrainBtn} (${profile?.totalReplayUpdates ?: 0})",
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (telemetry.liveLogLines.isNotEmpty()) {
            item {
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = if (isUrdu) "لائیو ایکشن لاگ" else "Live Bot Action Log",
                            style = MaterialTheme.typography.titleSmall
                        )
                        telemetry.liveLogLines.take(10).forEach { line ->
                            Text(
                                text = "• $line",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(
    isActive: Boolean,
    activeText: String,
    inactiveText: String
) {
    Surface(
        shape = CircleShape,
        color = if (isActive) EmeraldNeon.copy(alpha = 0.18f) else CrimsonAlert.copy(alpha = 0.15f)
    ) {
        Text(
            text = if (isActive) activeText else inactiveText,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = if (isActive) EmeraldNeon else CrimsonAlert,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
