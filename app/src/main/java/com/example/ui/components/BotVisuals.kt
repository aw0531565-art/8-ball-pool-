package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.db.TrainingSessionEntity
import com.example.domain.rl.ActionDecision
import com.example.domain.rl.BotAction
import com.example.ui.theme.AmberPulse
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.CyanElectric
import com.example.ui.theme.EmeraldNeon
import kotlin.math.abs

@Composable
fun BotHeaderHeroCard(
    profileName: String,
    subtitle: String,
    isBotActive: Boolean,
    qStateCount: Int,
    experienceCount: Int,
    bestScore: Int,
    isUrdu: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(148.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_bot_hero),
                contentDescription = "NeuroBot neural telemetry banner",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xCC070B14),
                                Color(0xE60B1222)
                            )
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(CyanElectric.copy(alpha = 0.16f))
                                .border(1.dp, CyanElectric.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Psychology,
                                contentDescription = null,
                                tint = CyanElectric,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = profileName,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFB7C7E8),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Surface(
                        shape = CircleShape,
                        color = if (isBotActive) EmeraldNeon.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.12f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (isBotActive) EmeraldNeon else AmberPulse)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isBotActive) {
                                    if (isUrdu) "لائیو" else "ACTIVE"
                                } else {
                                    if (isUrdu) "تیار" else "READY"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isBotActive) EmeraldNeon else Color.White
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HeroStatChip(
                        label = if (isUrdu) "اسٹیٹس" else "Q-STATES",
                        value = qStateCount.toString(),
                        accent = CyanElectric,
                        modifier = Modifier.weight(1f)
                    )
                    HeroStatChip(
                        label = if (isUrdu) "تجربات" else "MEMORIES",
                        value = experienceCount.toString(),
                        accent = EmeraldNeon,
                        modifier = Modifier.weight(1f)
                    )
                    HeroStatChip(
                        label = if (isUrdu) "بہترین" else "BEST",
                        value = bestScore.toString(),
                        accent = AmberPulse,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroStatChip(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = Color(0x990F172A)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF9FB0D1),
                maxLines = 1
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = accent,
                maxLines = 1
            )
        }
    }
}

@Composable
fun VisionSensorMatrix(
    title: String,
    gridCells: List<Float>,
    stateHash: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF060A12))
            ) {
                val cols = 6
                val rows = 6
                val gap = 3.dp.toPx()
                val cellW = (size.width - gap * (cols + 1)) / cols
                val cellH = (size.height - gap * (rows + 1)) / rows

                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        val idx = r * cols + c
                        val v = gridCells.getOrElse(idx) { 0f }.coerceIn(0f, 1f)
                        val color = when {
                            v >= 0.90f -> CrimsonAlert.copy(alpha = 0.92f)
                            v >= 0.60f -> EmeraldNeon.copy(alpha = 0.88f)
                            v >= 0.25f -> CyanElectric.copy(alpha = 0.35f + v * 0.5f)
                            else -> Color(0xFF141E33).copy(alpha = 0.7f + v)
                        }
                        val x = gap + c * (cellW + gap)
                        val y = gap + r * (cellH + gap)
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(x, y),
                            size = Size(cellW, cellH),
                            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stateHash,
                style = MaterialTheme.typography.labelSmall,
                color = CyanElectric,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun QEstimatePanel(
    title: String,
    decision: ActionDecision?,
    isUrdu: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (decision != null) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (decision.isExploration) {
                            AmberPulse.copy(alpha = 0.2f)
                        } else {
                            EmeraldNeon.copy(alpha = 0.2f)
                        }
                    ) {
                        Text(
                            text = if (decision.isExploration) {
                                if (isUrdu) "تجربہ" else "EXPLORE"
                            } else {
                                if (isUrdu) "علم" else "EXPLOIT"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (decision.isExploration) AmberPulse else EmeraldNeon,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            val entries = decision?.qEstimates?.entries?.toList()
                ?: BotAction.POOL_SHOT_ACTIONS.associateWith { 0f }.entries.toList()
            val maxAbs = entries.maxOfOrNull { abs(it.value) }?.coerceAtLeast(1f) ?: 1f

            entries.forEach { (action, qVal) ->
                val isSelected = decision?.action == action
                val fraction = (abs(qVal) / maxAbs).coerceIn(0.05f, 1f)
                val barColor = when {
                    isSelected -> CyanElectric
                    qVal >= 0f -> EmeraldNeon.copy(alpha = 0.7f)
                    else -> CrimsonAlert.copy(alpha = 0.75f)
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Filled.Bolt,
                                    contentDescription = null,
                                    tint = CyanElectric,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                            }
                            Text(
                                text = if (isUrdu) action.labelUr else action.shortCode,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isSelected) CyanElectric else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "${if (qVal >= 0) "+" else ""}${"%.2f".format(qVal)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = barColor
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .height(6.dp)
                                .clip(CircleShape)
                                .background(barColor)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LearningCurveChart(
    title: String,
    sessions: List<TrainingSessionEntity>,
    emptyHint: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (sessions.isNotEmpty()) {
                    val recentAvg = sessions.takeLast(8).map { it.score }.average()
                    Text(
                        text = "Avg(last 8): ${"%.1f".format(recentAvg)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = EmeraldNeon
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))

            if (sessions.size < 2) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(108.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = emptyHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            } else {
                val points = sessions.takeLast(40)
                val maxScore = points.maxOf { it.score }.coerceAtLeast(10).toFloat()
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(118.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f))
                        .padding(horizontal = 10.dp, vertical = 12.dp)
                ) {
                    val w = size.width
                    val h = size.height
                    val stepX = if (points.size > 1) w / (points.size - 1) else w

                    // Subtle horizontal grid lines
                    for (i in 0..2) {
                        val gy = h * (i / 2f)
                        drawLine(
                            color = Color.White.copy(alpha = 0.08f),
                            start = Offset(0f, gy),
                            end = Offset(w, gy),
                            strokeWidth = 1.dp.toPx()
                        )
                    }

                    val linePath = Path()
                    val fillPath = Path()

                    points.forEachIndexed { index, session ->
                        val x = index * stepX
                        val normY = (session.score / maxScore).coerceIn(0f, 1f)
                        val y = h - (normY * h)
                        if (index == 0) {
                            linePath.moveTo(x, y)
                            fillPath.moveTo(x, h)
                            fillPath.lineTo(x, y)
                        } else {
                            linePath.lineTo(x, y)
                            fillPath.lineTo(x, y)
                        }
                        if (index == points.lastIndex) {
                            fillPath.lineTo(x, h)
                            fillPath.close()
                        }
                    }

                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                CyanElectric.copy(alpha = 0.35f),
                                Color.Transparent
                            )
                        )
                    )
                    drawPath(
                        path = linePath,
                        color = CyanElectric,
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                    )

                    val last = points.last()
                    val lastY = h - ((last.score / maxScore).coerceIn(0f, 1f) * h)
                    drawCircle(
                        color = EmeraldNeon,
                        radius = 4.5.dp.toPx(),
                        center = Offset((points.lastIndex) * stepX, lastY)
                    )
                }
            }
        }
    }
}
