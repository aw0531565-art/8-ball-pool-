package com.example.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.db.BallTelemetryEntity
import com.example.data.db.ExperienceEntity
import com.example.data.db.QTableEntryEntity
import com.example.data.db.ShotPhysicsEntity
import com.example.data.db.TrainingProfileEntity
import com.example.data.db.TrainingSessionEntity
import com.example.ui.components.LearningCurveChart
import com.example.ui.i18n.BotCopy
import com.example.ui.theme.AmberPulse
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.CyanElectric
import com.example.ui.theme.EmeraldNeon
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoryBrainScreen(
    copy: BotCopy,
    isUrdu: Boolean,
    profile: TrainingProfileEntity?,
    qEntries: List<QTableEntryEntity>,
    experiences: List<ExperienceEntity>,
    sessions: List<TrainingSessionEntity>,
    shotPhysicsLogs: List<ShotPhysicsEntity>,
    ballRecords: List<BallTelemetryEntity> = emptyList(),
    totalExperienceCount: Int,
    onTriggerReplay: () -> Unit,
    onResetMemory: () -> Unit,
    onExportJson: suspend () -> String,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Q-Table, 1 = Experiences, 2 = Shot Physics, 3 = Ball Records
    var searchQuery by remember { mutableStateOf("") }
    var showResetDialog by remember { mutableStateOf(false) }
    var exportedJson by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current

    val filteredQ = remember(qEntries, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) qEntries else qEntries.filter {
            it.stateHash.lowercase().contains(q) ||
                it.actionName.lowercase().contains(q) ||
                it.stateSummary.lowercase().contains(q)
        }
    }

    val filteredExp = remember(experiences, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) experiences else experiences.filter {
            it.stateHash.lowercase().contains(q) ||
                it.actionName.lowercase().contains(q) ||
                it.screenNotes.lowercase().contains(q)
        }
    }

    val filteredPhysics = remember(shotPhysicsLogs, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) shotPhysicsLogs else shotPhysicsLogs.filter {
            it.pocketId.lowercase().contains(q) ||
                "shot ${it.shotNumber}".contains(q)
        }
    }

    val filteredBalls = remember(ballRecords, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) ballRecords else ballRecords.filter {
            it.ballRole.lowercase().contains(q) ||
                it.ballSuit.lowercase().contains(q) ||
                it.ballColorTag.lowercase().contains(q) ||
                it.actualPocketId.lowercase().contains(q) ||
                "shot ${it.shotNumber}".contains(q)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = copy.memoryTitle,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = profile?.name ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = CyanElectric
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MemoryStatBox(
                    label = copy.statesLearnedLabel,
                    value = qEntries.map { it.stateHash }.distinct().size.toString(),
                    modifier = Modifier.weight(1f)
                )
                MemoryStatBox(
                    label = copy.memoriesLabel,
                    value = totalExperienceCount.toString(),
                    modifier = Modifier.weight(1f)
                )
                MemoryStatBox(
                    label = if (isUrdu) "اپڈیٹس" else "REPLAYS",
                    value = (profile?.totalReplayUpdates ?: 0).toString(),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Learned Game Physics Card
        item {
            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Speed, contentDescription = null, tint = EmeraldNeon)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isUrdu) {
                                "خود سیکھی گئی گیم فزکس (${profile?.physicsSamplesCount ?: 0} شاٹس)"
                            } else {
                                "Self-Calibrated Game Physics (${profile?.physicsSamplesCount ?: 0} shots)"
                            },
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PhysicsMetricPill(
                            label = if (isUrdu) "پاور اسکیل" else "Power Scale",
                            value = "${"%.2f".format(profile?.learnedPowerScale ?: 1.0f)}×",
                            modifier = Modifier.weight(1f)
                        )
                        PhysicsMetricPill(
                            label = if (isUrdu) "رگڑ (Friction)" else "Felt Friction",
                            value = "${"%.2f".format(profile?.learnedFrictionCoef ?: 0.52f)}",
                            modifier = Modifier.weight(1f)
                        )
                        PhysicsMetricPill(
                            label = if (isUrdu) "کٹ جھکاؤ" else "Cut Bias",
                            value = "${"%.1f".format(profile?.learnedCutBiasDeg ?: 0f)}°",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PhysicsMetricPill(
                            label = if (isUrdu) "ٹکراؤ باؤنس" else "Restitution",
                            value = "${"%.2f".format(profile?.learnedRestitution ?: 0.93f)}",
                            modifier = Modifier.weight(1f)
                        )
                        PhysicsMetricPill(
                            label = if (isUrdu) "کشن باؤنس" else "Rail Bounce",
                            value = "${"%.2f".format(profile?.learnedCushionBounce ?: 0.80f)}",
                            modifier = Modifier.weight(1f)
                        )
                        PhysicsMetricPill(
                            label = if (isUrdu) "اوسط فرق" else "Pred Error",
                            value = "${"%.3f".format(profile?.avgPredictionError ?: 0f)} (${profile?.totalBallsRecorded ?: 0})",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        item {
            LearningCurveChart(
                title = copy.learningCurveTitle,
                sessions = sessions,
                emptyHint = if (isUrdu) {
                    "ایکسٹرنل گیم پر بوٹ چلانے کے بعد یہاں ٹریننگ گراف نظر آئے گا۔"
                } else {
                    "Run Live Screen Bot on external games to plot shot & round improvement over time."
                }
            )
        }

        item {
            Button(
                onClick = onTriggerReplay,
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("btn_memory_replay")
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(copy.replayTrainBtn)
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val json = onExportJson()
                            clipboard.setText(AnnotatedString(json))
                            exportedJson = json
                        }
                    },
                    modifier = Modifier
                        .weight(1.35f)
                        .minimumInteractiveComponentSize()
                        .testTag("btn_export_brain")
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isUrdu) "فزکس اور ٹریننگ رپورٹ کاپی" else "Copy Physics & Training Report",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(
                    onClick = { showResetDialog = true },
                    modifier = Modifier
                        .weight(0.65f)
                        .minimumInteractiveComponentSize()
                        .testTag("btn_reset_memory")
                ) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = null, tint = CrimsonAlert)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isUrdu) "صاف" else "Reset", color = CrimsonAlert)
                }
            }
        }

        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    label = { Text(if (isUrdu) "Q-ٹیبل (${qEntries.size})" else "Q-Table (${qEntries.size})") },
                    modifier = Modifier.testTag("tab_q_table")
                )
                FilterChip(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    label = { Text(if (isUrdu) "تجربات (${experiences.size})" else "Experiences (${experiences.size})") },
                    modifier = Modifier.testTag("tab_experience_log")
                )
                FilterChip(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    label = { Text(if (isUrdu) "فزکس لاگ (${shotPhysicsLogs.size})" else "Shot Physics (${shotPhysicsLogs.size})") },
                    modifier = Modifier.testTag("tab_shot_physics")
                )
                FilterChip(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    label = { Text(if (isUrdu) "ہر بال ریکارڈ (${ballRecords.size})" else "Ball Records (${ballRecords.size})") },
                    modifier = Modifier.testTag("tab_ball_records")
                )
            }
        }

        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = {
                    Text(if (isUrdu) "اسٹیٹ، پاکٹ یا ایکشن تلاش کریں..." else "Filter by state hash, pocket, or action...")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_search_states")
            )
        }

        when (selectedTab) {
            0 -> {
                if (filteredQ.isEmpty()) {
                    item {
                        EmptyMemoryCard(
                            message = if (isUrdu) {
                                "ابھی تک کوئی Q-اسٹیٹ محفوظ نہیں ہوئی۔ گیم پر لائیو بوٹ چلائیں!"
                            } else {
                                "No learned Q-states yet. Run Live Bot on your game to populate on-device memory."
                            }
                        )
                    }
                } else {
                    items(filteredQ.take(80), key = { "${it.profileId}_${it.stateHash}_${it.actionName}" }) { entry ->
                        QEntryCard(entry = entry)
                    }
                }
            }
            1 -> {
                if (filteredExp.isEmpty()) {
                    item {
                        EmptyMemoryCard(
                            message = if (isUrdu) {
                                "ابھی تک کوئی تجربہ محفوظ نہیں ہوا۔"
                            } else {
                                "No gameplay experience transitions recorded yet."
                            }
                        )
                    }
                } else {
                    items(filteredExp, key = { it.id }) { exp ->
                        ExperienceCard(exp = exp)
                    }
                }
            }
            2 -> {
                if (filteredPhysics.isEmpty()) {
                    item {
                        EmptyMemoryCard(
                            message = if (isUrdu) {
                                "ابھی تک کوئی فزکس شاٹ ریکارڈ نہیں ہوا۔ 8-Ball Pool پر بوٹ چلائیں!"
                            } else {
                                "No shot physics telemetry recorded yet. Run Live Bot on your pool game to measure ball travel, friction, and cut deflection."
                            }
                        )
                    }
                } else {
                    items(filteredPhysics, key = { it.id }) { shot ->
                        ShotPhysicsCard(shot = shot)
                    }
                }
            }
            else -> {
                if (filteredBalls.isEmpty()) {
                    item {
                        EmptyMemoryCard(
                            message = if (isUrdu) {
                                "ابھی تک کسی بال کا ریکارڈ محفوظ نہیں ہوا۔ بوٹ یا ہیومن شاٹ کے بعد ہر بال کی پیشگوئی اور اصل پوزیشن یہاں نظر آئے گی۔"
                            } else {
                                "No per-ball prediction vs actual records yet. Every ball's pre-shot position, predicted stop/pocket, and actual outcome are logged here."
                            }
                        )
                    }
                } else {
                    items(filteredBalls, key = { it.id }) { ball ->
                        BallTelemetryCard(ball = ball)
                    }
                }
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(if (isUrdu) "محفوظ میموری صاف کریں؟" else "Reset Learned Memory?") },
            text = {
                Text(
                    if (isUrdu) {
                        "یہ اس پروفائل کی تمام سیکھی ہوئی Q-اسٹیٹس، فزکس اور تجربات حذف کر دے گا۔"
                    } else {
                        "This will erase all Q-table weights, learned physics, and saved experience transitions for this profile."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        onResetMemory()
                    },
                    modifier = Modifier.testTag("btn_confirm_reset_memory")
                ) {
                    Text(if (isUrdu) "حذف کریں" else "Reset", color = CrimsonAlert)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(if (isUrdu) "منسوخ" else "Cancel")
                }
            }
        )
    }

    if (exportedJson != null) {
        AlertDialog(
            onDismissRequest = { exportedJson = null },
            title = {
                Text(
                    if (isUrdu) {
                        "رپورٹ کلپ بورڈ میں کاپی ہو گئی! یہاں پیسٹ کریں"
                    } else {
                        "Report Copied to Clipboard — Ready to Paste"
                    }
                )
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = exportedJson.orEmpty(),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { exportedJson = null }) {
                    Text("OK")
                }
            }
        )
    }
}

@Composable
private fun PhysicsMetricPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.55f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = EmeraldNeon
            )
        }
    }
}

@Composable
private fun ShotPhysicsCard(shot: ShotPhysicsEntity) {
    val statusColor = if (shot.pottedBall) EmeraldNeon else if (shot.keptTurn) AmberPulse else CrimsonAlert
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Shot #${shot.shotNumber} -> Pocket ${shot.pocketId}",
                    style = MaterialTheme.typography.labelMedium,
                    color = CyanElectric
                )
                Text(
                    text = when (shot.outcomeLabel) {
                        "CUE_SCRATCH" -> "SCRATCH (-4.5)"
                        "POTTED", "HUMAN_POTTED" -> shot.outcomeLabel
                        else -> if (shot.pottedBall) "POTTED" else if (shot.keptTurn) "KEPT TURN" else shot.outcomeLabel
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor
                )
            }
            Text(
                text = "Cut ${"%.1f".format(shot.cutAngleDeg)}° | Power ${(shot.powerFraction * 100).toInt()}% | Travel ${"%.3f".format(shot.cueTravelDist)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Friction=${"%.2f".format(shot.estimatedFriction)} | DeflectErr=${"%.1f".format(shot.angleDeflectionErrorDeg)}° | CueErr=${"%.3f".format(shot.cuePredError)} | Tracked=${shot.ballsTrackedCount} (${shot.collisionsCount} hits)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun BallTelemetryCard(ball: BallTelemetryEntity) {
    val badgeColor = when {
        ball.actualPotted -> EmeraldNeon
        ball.wasCollided -> CyanElectric
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Shot #${ball.shotNumber} [${ball.shotSource}] • ${ball.ballRole} (${ball.ballSuit}/${ball.ballColorTag})",
                    style = MaterialTheme.typography.labelMedium,
                    color = CyanElectric,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (ball.actualPotted) "POT:${ball.actualPocketId}" else "Err:${"%.3f".format(ball.predictionErrorDist)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeColor
                )
            }
            Text(
                text = "Pre(${"%.2f".format(ball.preX)},${"%.2f".format(ball.preY)}) -> Pred(${"%.2f".format(ball.predEndX)},${"%.2f".format(ball.predEndY)}${if (ball.predPotted) " [${ball.predPocketId}]" else ""}) | Act(${"%.2f".format(ball.postX)},${"%.2f".format(ball.postY)})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun MemoryStatBox(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = CyanElectric
            )
        }
    }
}

@Composable
private fun QEntryCard(entry: QTableEntryEntity) {
    val qColor = if (entry.qValue >= 0f) EmeraldNeon else CrimsonAlert
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.stateHash,
                    style = MaterialTheme.typography.labelMedium,
                    color = CyanElectric,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${entry.stateSummary} • Visits: ${entry.visitCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = entry.actionName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "Q=${if (entry.qValue >= 0) "+" else ""}${"%.2f".format(entry.qValue)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = qColor
                )
            }
        }
    }
}

@Composable
private fun ExperienceCard(exp: ExperienceEntity) {
    val rColor = when {
        exp.reward > 0.5f -> EmeraldNeon
        exp.reward < 0f -> CrimsonAlert
        else -> AmberPulse
    }
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Ep ${exp.episodeNumber} #${exp.stepIndex} [${exp.source}]",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanElectric
                )
                Text(
                    text = "r=${if (exp.reward >= 0) "+" else ""}${"%.2f".format(exp.reward)}${if (exp.isTerminal) " • END" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = rColor
                )
            }
            Text(
                text = "${exp.stateHash} -> ${exp.actionName}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (exp.screenNotes.isNotBlank()) {
                Text(
                    text = exp.screenNotes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun EmptyMemoryCard(message: String) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Psychology,
                contentDescription = null,
                tint = CyanElectric
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
