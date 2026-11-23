package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.db.TrainingProfileEntity
import com.example.domain.vision.BallSuit
import com.example.ui.i18n.BotCopy
import com.example.ui.theme.AmberPulse
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.CyanElectric
import com.example.ui.theme.EmeraldNeon
import com.example.ui.viewmodel.ScreenshotTestState

@Composable
fun CalibratorScreen(
    copy: BotCopy,
    isUrdu: Boolean,
    profile: TrainingProfileEntity?,
    screenshotState: ScreenshotTestState,
    onPickScreenshot: (android.net.Uri) -> Unit,
    onSaveCalibration: (Float, Float, Float, Float, Float, Float, Float, Float, Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onPickScreenshot(uri)
        }
    }

    var tableLeft by remember(profile?.id) { mutableFloatStateOf(profile?.tableLeft ?: 0.16f) }
    var tableTop by remember(profile?.id) { mutableFloatStateOf(profile?.tableTop ?: 0.18f) }
    var tableRight by remember(profile?.id) { mutableFloatStateOf(profile?.tableRight ?: 0.83f) }
    var tableBottom by remember(profile?.id) { mutableFloatStateOf(profile?.tableBottom ?: 0.95f) }
    var powerBarX by remember(profile?.id) { mutableFloatStateOf(profile?.powerBarX ?: 0.072f) }
    var turnBoxX by remember(profile?.id) { mutableFloatStateOf(profile?.turnBoxX ?: 0.43f) }
    var turnBoxY by remember(profile?.id) { mutableFloatStateOf(profile?.turnBoxY ?: 0.08f) }
    var tapLeftX by remember(profile?.id) { mutableFloatStateOf(profile?.tapLeftX ?: 0.20f) }
    var tapCenterY by remember(profile?.id) { mutableFloatStateOf(profile?.tapCenterY ?: 0.75f) }
    var tapRightX by remember(profile?.id) { mutableFloatStateOf(profile?.tapRightX ?: 0.80f) }

    val isPoolMode = (profile?.botMode ?: "POOL_AIM") == "POOL_AIM"

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = copy.calibratorTitle,
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = if (isUrdu) {
                    "اپنی اصل گیم کا اسکرین شاٹ منتخب کر کے چیک کریں کہ بوٹ بالز، پاکٹس، ٹرن بارڈر اور نشانہ صحیح پڑھ رہا ہے یا نہیں۔"
                } else {
                    "Pick any real game screenshot from your gallery to verify ball/table vision detection and tune screen zones."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            FilledTonalButton(
                onClick = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("btn_pick_screenshot")
            ) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (isUrdu) "گیم اسکرین شاٹ پر ویژن ٹیسٹ کریں" else "Test Vision on Game Screenshot"
                )
            }
        }

        // Visual Calibration & Detection Overlay Preview
        item {
            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val bmp = screenshotState.previewBitmap
                    val aspect = if (bmp != null && bmp.height > 0) {
                        (bmp.width.toFloat() / bmp.height).coerceIn(0.45f, 2.25f)
                    } else if (isPoolMode) {
                        2.1f
                    } else {
                        0.65f
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspect)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF060A12))
                            .border(1.dp, CyanElectric.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    ) {
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Analyzed game screenshot",
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Canvas(modifier = Modifier.fillMaxSize()) {
                            if (isPoolMode) {
                                // Table bounds
                                drawRect(
                                    color = CyanElectric,
                                    topLeft = Offset(tableLeft * size.width, tableTop * size.height),
                                    size = Size(
                                        (tableRight - tableLeft) * size.width,
                                        (tableBottom - tableTop) * size.height
                                    ),
                                    style = Stroke(width = 2.dp.toPx())
                                )
                                // Power bar zone
                                drawLine(
                                    color = AmberPulse,
                                    start = Offset(powerBarX * size.width, (profile?.powerBarTopY ?: 0.27f) * size.height),
                                    end = Offset(powerBarX * size.width, (profile?.powerBarBottomY ?: 0.84f) * size.height),
                                    strokeWidth = 4.dp.toPx()
                                )
                                // Turn indicator box
                                drawCircle(
                                    color = EmeraldNeon,
                                    radius = 12.dp.toPx(),
                                    center = Offset(turnBoxX * size.width, turnBoxY * size.height),
                                    style = Stroke(width = 2.dp.toPx())
                                )

                                // Detected balls, auto-detected inner cushion border, & planned shot vector
                                val analysis = screenshotState.poolAnalysis
                                if (analysis != null) {
                                    val db = analysis.detectedTableBounds
                                    if (db.size == 4) {
                                        drawRect(
                                            color = AmberPulse.copy(alpha = 0.85f),
                                            topLeft = Offset(db[0] * size.width, db[1] * size.height),
                                            size = Size(
                                                (db[2] - db[0]) * size.width,
                                                (db[3] - db[1]) * size.height
                                            ),
                                            style = Stroke(width = 1.8.dp.toPx())
                                        )
                                    }
                                    analysis.pockets.forEach { pocket ->
                                        drawCircle(
                                            color = CyanElectric.copy(alpha = 0.85f),
                                            radius = 7.dp.toPx(),
                                            center = Offset(pocket.normX * size.width, pocket.normY * size.height),
                                            style = Stroke(width = 2.dp.toPx())
                                        )
                                    }
                                    analysis.targetBalls.forEach { ball ->
                                        val ballCenter = Offset(ball.normX * size.width, ball.normY * size.height)
                                        when (ball.suit) {
                                            BallSuit.STRIPE -> {
                                                drawCircle(
                                                    color = CyanElectric,
                                                    radius = 9.dp.toPx(),
                                                    center = ballCenter,
                                                    style = Stroke(width = 2.dp.toPx())
                                                )
                                                drawCircle(
                                                    color = Color.White,
                                                    radius = 5.5.dp.toPx(),
                                                    center = ballCenter,
                                                    style = Stroke(width = 1.5.dp.toPx())
                                                )
                                            }
                                            else -> {
                                                drawCircle(
                                                    color = EmeraldNeon,
                                                    radius = 8.dp.toPx(),
                                                    center = ballCenter,
                                                    style = Stroke(width = 2.2.dp.toPx())
                                                )
                                            }
                                        }
                                    }
                                    analysis.eightBall?.let { eight ->
                                        drawCircle(
                                            color = CrimsonAlert,
                                            radius = 9.dp.toPx(),
                                            center = Offset(eight.normX * size.width, eight.normY * size.height),
                                            style = Stroke(width = 2.5.dp.toPx())
                                        )
                                    }
                                    analysis.cueBall?.let { cue ->
                                        drawCircle(
                                            color = Color.White,
                                            radius = 10.dp.toPx(),
                                            center = Offset(cue.normX * size.width, cue.normY * size.height),
                                            style = Stroke(width = 2.5.dp.toPx())
                                        )
                                    }
                                    analysis.plannedShot?.let { shot ->
                                        val cuePt = Offset(shot.cueBall.normX * size.width, shot.cueBall.normY * size.height)
                                        val aimPt = Offset(shot.aimPointX * size.width, shot.aimPointY * size.height)
                                        val targetPt = Offset(shot.targetBall.normX * size.width, shot.targetBall.normY * size.height)
                                        val pocketPt = Offset(shot.pocket.normX * size.width, shot.pocket.normY * size.height)
                                        drawLine(
                                            color = Color.White.copy(alpha = 0.55f),
                                            start = cuePt,
                                            end = aimPt,
                                            strokeWidth = 1.8.dp.toPx()
                                        )
                                        drawLine(
                                            color = AmberPulse.copy(alpha = 0.45f),
                                            start = targetPt,
                                            end = pocketPt,
                                            strokeWidth = 1.6.dp.toPx()
                                        )
                                    }

                                    // Full multi-ball physics simulation trajectories (Cue + Target + Secondary Collisions)
                                    analysis.simulationPrediction?.let { sim ->
                                        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
                                        sim.allBallPaths.forEach { bPath ->
                                            if (bPath.waypoints.size < 2 || (!bPath.wasCollided && bPath.ballIndex != 0)) {
                                                return@forEach
                                            }
                                            val pathColor = when (bPath.role) {
                                                "CUE" -> if (sim.cueScratchPredicted) CrimsonAlert else Color.White
                                                "PRIMARY_TARGET" -> AmberPulse
                                                else -> Color(0xFFE879F9) // Neon magenta for secondary collided balls
                                            }
                                            val composePath = Path()
                                            bPath.waypoints.forEachIndexed { idx, (wx, wy) ->
                                                val px = wx * size.width
                                                val py = wy * size.height
                                                if (idx == 0) composePath.moveTo(px, py) else composePath.lineTo(px, py)
                                            }
                                            drawPath(
                                                path = composePath,
                                                color = pathColor.copy(alpha = 0.92f),
                                                style = Stroke(
                                                    width = if (bPath.role == "CUE" || bPath.role == "PRIMARY_TARGET") 2.6.dp.toPx() else 2.2.dp.toPx(),
                                                    cap = StrokeCap.Round,
                                                    pathEffect = if (bPath.role == "COLLIDED_SECONDARY" || bPath.role == "EIGHT_BALL") dashEffect else null
                                                )
                                            )
                                            val endOffset = Offset(bPath.endX * size.width, bPath.endY * size.height)
                                            if (bPath.potted) {
                                                drawCircle(
                                                    color = pathColor,
                                                    radius = 6.dp.toPx(),
                                                    center = endOffset,
                                                    style = Stroke(width = 2.dp.toPx())
                                                )
                                            } else {
                                                drawCircle(
                                                    color = pathColor,
                                                    radius = 4.dp.toPx(),
                                                    center = endOffset
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                // General tap markers
                                drawCircle(CyanElectric, 10.dp.toPx(), Offset(tapLeftX * size.width, (profile?.tapLeftY ?: 0.78f) * size.height))
                                drawCircle(EmeraldNeon, 10.dp.toPx(), Offset((profile?.tapCenterX ?: 0.50f) * size.width, tapCenterY * size.height))
                                drawCircle(AmberPulse, 10.dp.toPx(), Offset(tapRightX * size.width, (profile?.tapRightY ?: 0.78f) * size.height))
                            }
                        }
                    }

                    val analysis = screenshotState.poolAnalysis
                    if (analysis != null) {
                        Text(
                            text = analysis.summary,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (analysis.isMyTurn) EmeraldNeon else AmberPulse
                        )
                        Text(
                            text = "TurnOwner: ${analysis.turnOwner.name} | Table:${if (analysis.tableLocked || analysis.detectedGeometry != null) "LOCK-READY" else "SCAN"} | HUD Suit: ${analysis.detectedSuit.name} (Bot:${analysis.botRackCount}, Opp:${analysis.oppRackCount}) | Green=Solid, Cyan=Stripe, Red=8, Magenta=2ndary",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        analysis.plannedShot?.let { shot ->
                            Text(
                                text = "Best Shot: ${shot.stateSummary} • Power ${(shot.basePowerFraction * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyanElectric
                            )
                        }
                        analysis.simulationPrediction?.let { sim ->
                            Text(
                                text = "Predicted Physics: ${sim.summaryText}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (sim.cueScratchPredicted) CrimsonAlert else EmeraldNeon
                            )
                        }
                    }
                }
            }
        }

        // Calibration sliders
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
                        Icon(Icons.Filled.CenterFocusStrong, contentDescription = null, tint = CyanElectric)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isPoolMode) {
                                if (isUrdu) "پول ٹیبل اور پاور بار کیلیبریشن" else "Table, Power Bar & Turn Calibration"
                            } else {
                                if (isUrdu) "ٹیپ زون کیلیبریشن" else "Tap & Swipe Zone Calibration"
                            },
                            style = MaterialTheme.typography.titleSmall
                        )
                    }

                    if (isPoolMode) {
                        Text("Table Left: ${(tableLeft * 100).toInt()}%  |  Table Right: ${(tableRight * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                        Slider(value = tableLeft, onValueChange = { tableLeft = it.coerceAtMost(tableRight - 0.30f) }, valueRange = 0.08f..0.40f)
                        Slider(value = tableRight, onValueChange = { tableRight = it.coerceAtLeast(tableLeft + 0.30f) }, valueRange = 0.60f..0.94f)

                        Text("Table Top: ${(tableTop * 100).toInt()}%  |  Table Bottom: ${(tableBottom * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                        Slider(value = tableTop, onValueChange = { tableTop = it.coerceAtMost(tableBottom - 0.30f) }, valueRange = 0.08f..0.42f)
                        Slider(value = tableBottom, onValueChange = { tableBottom = it.coerceAtLeast(tableTop + 0.30f) }, valueRange = 0.65f..0.98f)

                        Text("Power Slider X: ${(powerBarX * 100).toInt()}%  |  Turn Indicator: (${(turnBoxX * 100).toInt()}%, ${(turnBoxY * 100).toInt()}%)", style = MaterialTheme.typography.labelSmall)
                        Slider(value = powerBarX, onValueChange = { powerBarX = it }, valueRange = 0.03f..0.16f)
                        Slider(value = turnBoxX, onValueChange = { turnBoxX = it }, valueRange = 0.25f..0.75f)
                    } else {
                        Text("Left Tap X: ${(tapLeftX * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                        Slider(value = tapLeftX, onValueChange = { tapLeftX = it }, valueRange = 0.08f..0.40f)
                        Text("Center Tap Y: ${(tapCenterY * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                        Slider(value = tapCenterY, onValueChange = { tapCenterY = it }, valueRange = 0.40f..0.90f)
                        Text("Right Tap X: ${(tapRightX * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                        Slider(value = tapRightX, onValueChange = { tapRightX = it }, valueRange = 0.60f..0.92f)
                    }

                    Button(
                        onClick = {
                            onSaveCalibration(
                                tableLeft, tableTop, tableRight, tableBottom,
                                powerBarX, turnBoxX, turnBoxY,
                                tapLeftX, tapCenterY, tapRightX
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("btn_save_zones")
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isUrdu) "کیلیبریشن محفوظ کریں" else "Save Calibration")
                    }
                }
            }
        }
    }
}
