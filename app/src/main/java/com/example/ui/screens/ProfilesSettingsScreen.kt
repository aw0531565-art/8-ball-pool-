package com.example.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.db.TrainingProfileEntity
import com.example.ui.i18n.BotCopy
import com.example.ui.theme.CyanElectric
import com.example.ui.theme.EmeraldNeon
import kotlin.math.roundToLong

@Composable
fun ProfilesSettingsScreen(
    copy: BotCopy,
    isUrdu: Boolean,
    isDarkTheme: Boolean,
    profiles: List<TrainingProfileEntity>,
    activeProfile: TrainingProfileEntity?,
    onSelectProfile: (Long) -> Unit,
    onCreateProfile: (String, String, String) -> Unit,
    onUpdateHyperparams: (Float, Float, Float, Boolean, Long) -> Unit,
    onToggleLanguage: () -> Unit,
    onToggleTheme: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showCreateDialog by remember { mutableStateOf(false) }

    var alpha by remember(activeProfile?.id) { mutableFloatStateOf(activeProfile?.learningRate ?: 0.25f) }
    var gamma by remember(activeProfile?.id) { mutableFloatStateOf(activeProfile?.discountFactor ?: 0.90f) }
    var epsilon by remember(activeProfile?.id) { mutableFloatStateOf(activeProfile?.explorationRate ?: 0.35f) }
    var autoDecay by remember(activeProfile?.id) { mutableStateOf(activeProfile?.autoDecayEpsilon ?: true) }
    var intervalMs by remember(activeProfile?.id) {
        mutableFloatStateOf((activeProfile?.actionIntervalMs ?: 350L).toFloat())
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = copy.profilesTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f)
                )
                FilledTonalButton(
                    onClick = { showCreateDialog = true },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("btn_new_profile")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isUrdu) "نیا" else "New")
                }
            }
        }

        items(profiles, key = { it.id }) { item ->
            val isSelected = item.id == activeProfile?.id
            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isSelected) {
                            Modifier.border(1.5.dp, CyanElectric, MaterialTheme.shapes.medium)
                        } else {
                            Modifier
                        }
                    )
                    .clickable { onSelectProfile(item.id) }
                    .testTag("profile_card_${item.id}")
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = if (isSelected) CyanElectric else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${item.targetPackage} • Ep ${item.totalEpisodes} • Best ${item.bestScore}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = "Selected Profile",
                            tint = EmeraldNeon
                        )
                    }
                }
            }
        }

        if (activeProfile != null) {
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
                            Icon(Icons.Filled.Tune, contentDescription = null, tint = CyanElectric)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isUrdu) "سیکھنے کی رفتار اور پالیسی" else "Self-Training Hyperparameters",
                                style = MaterialTheme.typography.titleSmall
                            )
                        }

                        Text("Learning Rate α: ${"%.2f".format(alpha)}", style = MaterialTheme.typography.labelMedium)
                        Slider(value = alpha, onValueChange = { alpha = it }, valueRange = 0.05f..0.80f)

                        Text("Discount Factor γ: ${"%.2f".format(gamma)}", style = MaterialTheme.typography.labelMedium)
                        Slider(value = gamma, onValueChange = { gamma = it }, valueRange = 0.40f..0.99f)

                        Text("Exploration ε: ${(epsilon * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                        Slider(value = epsilon, onValueChange = { epsilon = it }, valueRange = 0.02f..0.85f)

                        Text("Action Interval: ${intervalMs.roundToLong()} ms", style = MaterialTheme.typography.labelMedium)
                        Slider(value = intervalMs, onValueChange = { intervalMs = it }, valueRange = 180f..2000f)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isUrdu) "خودکار ε کمی (Auto-Decay)" else "Auto-Decay Exploration ε",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Switch(
                                checked = autoDecay,
                                onCheckedChange = { autoDecay = it },
                                modifier = Modifier.testTag("switch_auto_decay")
                            )
                        }

                        Button(
                            onClick = {
                                onUpdateHyperparams(alpha, gamma, epsilon, autoDecay, intervalMs.roundToLong())
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .testTag("btn_save_hyperparams")
                        ) {
                            Text(if (isUrdu) "تبدیلیاں محفوظ کریں" else "Apply Hyperparameters")
                        }
                    }
                }
            }
        }

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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Translate, contentDescription = null, tint = CyanElectric)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isUrdu) "زبان: اردو" else "Language: English / اردو",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Switch(
                            checked = isUrdu,
                            onCheckedChange = { onToggleLanguage() },
                            modifier = Modifier.testTag("switch_language")
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.DarkMode, contentDescription = null, tint = EmeraldNeon)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isUrdu) "ڈارک موڈ" else "Cyber Dark Theme",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Switch(
                            checked = isDarkTheme,
                            onCheckedChange = { onToggleTheme() },
                            modifier = Modifier.testTag("switch_theme")
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        var pkg by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(copy.newProfileBtn) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(if (isUrdu) "پروفائل کا نام" else "Profile Name") },
                        singleLine = true,
                        modifier = Modifier.testTag("input_profile_name")
                    )
                    OutlinedTextField(
                        value = pkg,
                        onValueChange = { pkg = it },
                        label = { Text(if (isUrdu) "گیم پیکج (اختیاری)" else "Target Game Package (optional)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = desc,
                        onValueChange = { desc = it },
                        label = { Text(if (isUrdu) "تفصیل" else "Notes") },
                        maxLines = 2
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (name.isNotBlank()) {
                            onCreateProfile(name, pkg, desc)
                            showCreateDialog = false
                        }
                    },
                    modifier = Modifier.testTag("btn_confirm_create_profile")
                ) {
                    Text(if (isUrdu) "بنائیں" else "Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text(if (isUrdu) "منسوخ" else "Cancel")
                }
            }
        )
    }
}
