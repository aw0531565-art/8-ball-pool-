package com.example.domain.vision

import android.graphics.Bitmap
import android.graphics.Color
import com.example.data.db.TrainingProfileEntity
import com.example.domain.physics.PoolPhysicsSimulator
import com.example.domain.physics.ShotSimulationPrediction
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

enum class DetectedBallKind { CUE, TARGET, EIGHT_BALL }

enum class BallSuit { UNKNOWN, SOLID, STRIPE, EIGHT_BALL }

enum class AssignedSuit { OPEN, SOLIDS, STRIPES, EIGHT_BALL }

enum class TurnOwner { LEFT_PLAYER, RIGHT_PLAYER, NONE }

data class DetectedBall(
    val normX: Float, // normalized 0..1 across full screen
    val normY: Float, // normalized 0..1 across full screen
    val kind: DetectedBallKind,
    val sampleColor: Int,
    val suit: BallSuit = BallSuit.UNKNOWN,
    val colorTag: String = "UNK",
    val outerWhiteFrac: Float = 0f,
    val outerColorFrac: Float = 0f
)

data class PoolPocket(
    val id: String,
    val normX: Float,
    val normY: Float
)

data class PlannedPoolShot(
    val cueBall: DetectedBall,
    val targetBall: DetectedBall,
    val pocket: PoolPocket,
    val aimPointX: Float,
    val aimPointY: Float,
    val cutAngleDeg: Float,
    val totalDistance: Float,
    val basePowerFraction: Float,
    val stateHash: String,
    val stateSummary: String,
    val isBankShot: Boolean = false
)

data class LockedTableGeometry(
    val captureW: Int,
    val captureH: Int,
    val aspectRatio: Float,
    val feltHsv: FloatArray,
    val bounds: List<Float>,
    val pockets: List<PoolPocket>
)

enum class AimFeedbackStatus {
    CONFIRMED_LEGAL_WHITE_GUIDE,
    FORBIDDEN_RED_MARKER,
    NO_CLEAR_AIM_FEEDBACK
}

data class AimFeedbackResult(
    val status: AimFeedbackStatus,
    val redForbiddenScore: Float,
    val whiteRingScore: Float,
    val whiteGuideScore: Float,
    val guideAngleErrorDeg: Float = 0f,
    val note: String = ""
)

data class PoolFrameAnalysis(
    val isMyTurn: Boolean,
    val turnOwner: TurnOwner,
    val leftGreenRatio: Float,
    val rightGreenRatio: Float,
    val powerBarVisible: Boolean,
    val greenTurnRatio: Float,
    val pottedOccupiedRatio: Float,
    val botRackCount: Int = 0,
    val oppRackCount: Int = 0,
    val detectedSuit: AssignedSuit,
    val effectiveSuit: AssignedSuit,
    val cueBall: DetectedBall?,
    val targetBalls: List<DetectedBall>,
    val eightBall: DetectedBall?,
    val pockets: List<PoolPocket>,
    val plannedShot: PlannedPoolShot?,
    val simulationPrediction: ShotSimulationPrediction? = null,
    val visionGrid: List<Float>,
    val summary: String,
    val detectedTableBounds: List<Float> = listOf(0.192f, 0.222f, 0.798f, 0.908f),
    val aspectRatio: Float = 2.0f,
    val tableLocked: Boolean = false,
    val detectedGeometry: LockedTableGeometry? = null
) {
    val allTableTargets: List<DetectedBall>
        get() = targetBalls + listOfNotNull(eightBall)
}

/**
 * Computer-vision analyzer for Pool / Billiards mobile game screenshots and
 * live MediaProjection frames.
 * - Supports one-time Table Geometry Locking (per capture resolution / orientation) so
 *   detected cushions and 6 pockets never drift mid-match, auto-unlocking on rotation.
 * - Dynamically detects assigned suit (Solids 1-7 vs Stripes 9-15 vs 8-Ball vs Open)
 *   from both bright and dimmed HUD racks.
 * - Strictly filters circular round balls (rejecting cue sticks & white aim lines) up to 16 balls.
 * - Inspects live game cue-aim feedback (Red ⊘ forbidden symbol vs White aiming ring & guideline).
 */
object PoolVisionAnalyzer {

    private const val BALL_RADIUS_ISO = PoolPhysicsSimulator.BALL_RADIUS_ISO

    private data class RackScanResult(
        val occupiedSlots: Int,
        val isStripesScore: Float,
        val occupiedRatio: Float,
        val avgLuminance: Float
    )

    private data class BallPatchStats(
        val repColor: Int,
        val totalWhiteFrac: Float,
        val outerWhiteFrac: Float,
        val outerColorFrac: Float,
        val darkFrac: Float,
        val discNonFeltFrac: Float,
        val feltHaloFrac: Float,
        val colorTag: String
    )

    fun analyze(
        bitmap: Bitmap,
        profile: TrainingProfileEntity,
        seatOverride: String? = null,
        suitOverride: String? = null,
        failedShotPenalties: Map<String, Float> = emptyMap(),
        lockedTable: LockedTableGeometry? = null,
        blockedTargetsIso: List<Pair<Float, Float>> = emptyList()
    ): PoolFrameAnalysis {
        val w = bitmap.width.coerceAtLeast(1)
        val h = bitmap.height.coerceAtLeast(1)
        val aspectRatio = (w.toFloat() / h.toFloat()).coerceIn(0.45f, 2.50f)

        val tLeft = profile.tableLeft.coerceIn(0.05f, 0.45f)
        val tTop = profile.tableTop.coerceIn(0.05f, 0.45f)
        val tRight = profile.tableRight.coerceIn(tLeft + 0.25f, 0.96f)
        val tBottom = profile.tableBottom.coerceIn(tTop + 0.25f, 0.98f)

        // 1. Strict Left vs Right Player Turn Ownership
        val leftGreenRatio = measureGreenBorderRatio(bitmap, profile.turnBoxX, profile.turnBoxY)
        val opponentTurnX = (1.0f - profile.turnBoxX).coerceIn(0.50f, 0.85f)
        val rightGreenRatio = measureGreenBorderRatio(bitmap, opponentTurnX, profile.turnBoxY)
        val powerBarVisible = measurePowerBarVisible(bitmap, profile.powerBarX)

        val turnOwner = when {
            leftGreenRatio >= 0.022f && leftGreenRatio >= rightGreenRatio * 1.28f -> TurnOwner.LEFT_PLAYER
            rightGreenRatio >= 0.022f && rightGreenRatio >= leftGreenRatio * 1.28f -> TurnOwner.RIGHT_PLAYER
            else -> TurnOwner.NONE
        }

        val effectiveSeat = (seatOverride ?: profile.botSeatSide).uppercase()
        val isMyTurn = when (effectiveSeat) {
            "LEFT" -> turnOwner == TurnOwner.LEFT_PLAYER
            "RIGHT" -> turnOwner == TurnOwner.RIGHT_PLAYER
            else -> false
        }

        // 2. Scan Left & Right Top HUD Racks dynamically to classify assigned suit for THIS match
        val hudY = detectBestHudRackY(
            bitmap = bitmap,
            leftX = profile.pottedBarLeft,
            rightX = profile.pottedBarRight,
            seedY = profile.pottedBarY
        )
        val leftRack = scanHudRack(
            bitmap = bitmap,
            leftX = profile.pottedBarLeft,
            rightX = profile.pottedBarRight,
            centerY = hudY
        )
        val oppLeftX = (1.0f - profile.pottedBarRight).coerceIn(0.55f, 0.72f)
        val oppRightX = (1.0f - profile.pottedBarLeft).coerceIn(0.70f, 0.90f)
        val rightRack = scanHudRack(
            bitmap = bitmap,
            leftX = oppLeftX,
            rightX = oppRightX,
            centerY = hudY
        )

        val botRack = if (effectiveSeat == "RIGHT") rightRack else leftRack
        val oppRack = if (effectiveSeat == "RIGHT") leftRack else rightRack
        val detectedSuit = inferAssignedSuit(botRack, oppRack)

        val suitChoice = (suitOverride ?: profile.preferredSuit).uppercase()
        val effectiveSuit = when (suitChoice) {
            "SOLIDS" -> if (detectedSuit == AssignedSuit.EIGHT_BALL) AssignedSuit.EIGHT_BALL else AssignedSuit.SOLIDS
            "STRIPES" -> if (detectedSuit == AssignedSuit.EIGHT_BALL) AssignedSuit.EIGHT_BALL else AssignedSuit.STRIPES
            else -> detectedSuit
        }

        val greenTurnRatio = if (effectiveSeat == "RIGHT") rightGreenRatio else leftGreenRatio
        val pottedRatio = botRack.occupiedRatio

        // 3. Use locked table geometry for the current screen orientation if valid; otherwise detect & validate
        val activeLock = lockedTable?.takeIf { lock ->
            lock.captureW == w &&
                lock.captureH == h &&
                abs(lock.aspectRatio - aspectRatio) < 0.08f &&
                lock.bounds.size == 4 &&
                lock.pockets.size == 6
        }
        val useLocked = activeLock != null

        val feltHsv: FloatArray
        val fLeft: Float
        val fTop: Float
        val fRight: Float
        val fBottom: Float
        val pockets: List<PoolPocket>
        var confirmedPocketsCount = 6
        var feltConsistency = 1.0f

        if (activeLock != null) {
            feltHsv = activeLock.feltHsv
            fLeft = activeLock.bounds[0]
            fTop = activeLock.bounds[1]
            fRight = activeLock.bounds[2]
            fBottom = activeLock.bounds[3]
            pockets = activeLock.pockets
        } else {
            val seedFLeft = (tLeft + 0.032f).coerceAtMost(tRight - 0.20f)
            val seedFRight = (tRight - 0.032f).coerceAtLeast(seedFLeft + 0.20f)
            val seedFTop = (tTop + 0.042f).coerceAtMost(tBottom - 0.20f)
            val seedFBottom = (tBottom - 0.042f).coerceAtLeast(seedFTop + 0.20f)

            val (estHsv, consistency) = estimateFeltColorAndConsistency(bitmap, seedFLeft, seedFTop, seedFRight, seedFBottom)
            feltHsv = estHsv
            feltConsistency = consistency

            val borders = detectInnerFeltBorders(
                bitmap = bitmap,
                feltHsv = feltHsv,
                seedLeft = seedFLeft,
                seedTop = seedFTop,
                seedRight = seedFRight,
                seedBottom = seedFBottom
            )
            fLeft = borders[0]
            fTop = borders[1]
            fRight = borders[2]
            fBottom = borders[3]

            val (detPockets, confirmed) = detectSixPocketsWithCount(bitmap, fLeft, fTop, fRight, fBottom)
            pockets = detPockets
            confirmedPocketsCount = confirmed
        }

        // 4. High-Resolution interior felt scan (adapts grid orientation for landscape vs rotated portrait)
        val isPortrait = aspectRatio < 1.0f
        val ballRadiusIso = if (isPortrait) BALL_RADIUS_ISO * aspectRatio else BALL_RADIUS_ISO
        val cols = if (isPortrait) 81 else 144
        val rows = if (isPortrait) 144 else 81
        val totalCells = cols * rows
        val cellSat = FloatArray(totalCells)
        val cellLum = FloatArray(totalCells)
        val nonFeltScore = FloatArray(totalCells)
        val isBallCell = BooleanArray(totalCells)

        val hsv = FloatArray(3)
        for (r in 0 until rows) {
            val ny = fTop + ((r + 0.5f) / rows) * (fBottom - fTop)
            val py = (ny * h).toInt().coerceIn(0, h - 1)
            for (c in 0 until cols) {
                val nx = fLeft + ((c + 0.5f) / cols) * (fRight - fLeft)
                val px = (nx * w).toInt().coerceIn(0, w - 1)
                val pixel = bitmap.getPixel(px, py)
                Color.colorToHSV(pixel, hsv)
                val idx = r * cols + c
                cellSat[idx] = hsv[1]
                cellLum[idx] = hsv[2]

                val nearPocket = pockets.any { isoDist(nx, ny, it.normX, it.normY, aspectRatio) < ballRadiusIso * 1.75f }
                if (nearPocket) continue

                val hueDiff = abs(hsv[0] - feltHsv[0]).let { minOf(it, 360f - it) }
                val valDiff = abs(hsv[2] - feltHsv[2])
                val satDiff = abs(hsv[1] - feltHsv[1])

                val score = (hueDiff / 32f) + (satDiff / 0.28f) + (valDiff / 0.28f)
                nonFeltScore[idx] = score
                val differsFromFelt = hueDiff > 22f || satDiff > 0.25f || valDiff > 0.24f
                isBallCell[idx] = differsFromFelt
            }
        }

        // Remove 1-cell thin lines (e.g., baulk line / aiming reticle lines) via 3x3 neighbor support
        val filteredBallCell = BooleanArray(totalCells)
        for (r in 1 until rows - 1) {
            for (c in 1 until cols - 1) {
                val idx = r * cols + c
                if (!isBallCell[idx]) continue
                var neighbors = 0
                for (dr in -1..1) {
                    for (dc in -1..1) {
                        if ((dr != 0 || dc != 0) && isBallCell[(r + dr) * cols + (c + dc)]) {
                            neighbors++
                        }
                    }
                }
                val isWhite = cellLum[idx] > 0.60f && cellSat[idx] < 0.28f
                val isVividOrDark = cellSat[idx] > 0.34f || cellLum[idx] < 0.22f || isWhite
                if (neighbors >= 4 && isVividOrDark) {
                    filteredBallCell[idx] = true
                }
            }
        }

        // 5. Extract candidate ball centers with cluster splitting for touching balls
        val candidateCenters = extractBallCentersWithClusterSplitting(
            cols = cols,
            rows = rows,
            filteredBallCell = filteredBallCell,
            nonFeltScore = nonFeltScore,
            fLeft = fLeft,
            fTop = fTop,
            fRight = fRight,
            fBottom = fBottom,
            aspectRatio = aspectRatio,
            ballRadiusIso = ballRadiusIso
        )

        // 6. Classify every candidate center from full-resolution bitmap disc patch,
        // filtering out thin cue-stick/aim-line artifacts so we only track real round balls (max 16).
        val patchCandidates = candidateCenters.mapNotNull { (nx, ny) ->
            val nearPocket = pockets.any { isoDist(nx, ny, it.normX, it.normY, aspectRatio) < ballRadiusIso * 1.32f }
            if (nearPocket) return@mapNotNull null
            if (nx < fLeft + 0.008f || nx > fRight - 0.008f || ny < fTop + 0.008f || ny > fBottom - 0.008f) {
                return@mapNotNull null
            }
            val stats = sampleFullResBallPatch(bitmap, nx, ny, aspectRatio, feltHsv, ballRadiusIso)
            if (stats.discNonFeltFrac < 0.48f) return@mapNotNull null
            if (stats.feltHaloFrac < 0.25f && stats.discNonFeltFrac < 0.68f) return@mapNotNull null
            Triple(nx, ny, stats)
        }.sortedByDescending { (_, _, st) ->
            st.discNonFeltFrac * 1.4f + st.feltHaloFrac * 0.6f
        }.take(16)

        // Identify the single best Cue Ball (highest pure-white score & lowest outer color band)
        var bestCueIdx = -1
        var bestCueScore = -1f
        patchCandidates.forEachIndexed { idx, (_, _, st) ->
            if (st.totalWhiteFrac >= 0.46f && st.outerColorFrac < 0.30f && st.darkFrac < 0.25f) {
                val cueScore = st.totalWhiteFrac * 2.4f - st.outerColorFrac * 2.2f - st.darkFrac * 1.5f
                if (cueScore > bestCueScore) {
                    bestCueScore = cueScore
                    bestCueIdx = idx
                }
            }
        }

        var cueBall: DetectedBall? = null
        val detectedTargets = mutableListOf<DetectedBall>()

        patchCandidates.forEachIndexed { idx, (nx, ny, st) ->
            if (idx == bestCueIdx) {
                cueBall = DetectedBall(
                    normX = nx,
                    normY = ny,
                    kind = DetectedBallKind.CUE,
                    sampleColor = st.repColor,
                    suit = BallSuit.UNKNOWN,
                    colorTag = "WHITE",
                    outerWhiteFrac = st.outerWhiteFrac,
                    outerColorFrac = st.outerColorFrac
                )
            } else {
                val (kind, suit, tag) = when {
                    st.darkFrac >= 0.38f && st.outerColorFrac < 0.26f ->
                        Triple(DetectedBallKind.EIGHT_BALL, BallSuit.EIGHT_BALL, "BLACK")
                    (st.outerWhiteFrac >= 0.19f && st.outerColorFrac >= 0.12f) ||
                        (st.totalWhiteFrac >= 0.35f && st.outerColorFrac >= 0.12f) ->
                        Triple(DetectedBallKind.TARGET, BallSuit.STRIPE, st.colorTag)
                    else ->
                        Triple(DetectedBallKind.TARGET, BallSuit.SOLID, st.colorTag)
                }
                detectedTargets.add(
                    DetectedBall(
                        normX = nx,
                        normY = ny,
                        kind = kind,
                        sampleColor = st.repColor,
                        suit = suit,
                        colorTag = tag,
                        outerWhiteFrac = st.outerWhiteFrac,
                        outerColorFrac = st.outerColorFrac
                    )
                )
            }
        }

        val eightBall = detectedTargets.firstOrNull { it.kind == DetectedBallKind.EIGHT_BALL }
        val allColoredTargets = detectedTargets.filter { it.kind == DetectedBallKind.TARGET }
        val rawLegalTargets = filterLegalTargets(
            effectiveSuit = effectiveSuit,
            targets = allColoredTargets,
            eightBall = eightBall
        )
        val legalTargets = if (blockedTargetsIso.isEmpty()) {
            rawLegalTargets
        } else {
            val unblocked = rawLegalTargets.filter { cand ->
                blockedTargetsIso.none { (bx, by) -> isoDist(cand.normX, cand.normY, bx, by, aspectRatio) < 0.032f }
            }
            unblocked.ifEmpty { rawLegalTargets }
        }
        val obstacleBalls = allColoredTargets + listOfNotNull(eightBall)
        val tableBounds = listOf(fLeft, fTop, fRight, fBottom)

        val planned = computeBestShot(
            cue = cueBall,
            targets = legalTargets,
            pockets = pockets,
            fLeft = fLeft,
            fTop = fTop,
            fRight = fRight,
            fBottom = fBottom,
            allObstacles = obstacleBalls,
            aspectRatio = aspectRatio,
            failedShotPenalties = failedShotPenalties,
            profile = profile,
            effectiveSuit = effectiveSuit
        )

        val simPrediction = if (cueBall != null && planned != null) {
            PoolPhysicsSimulator.simulateShot(
                cueBall = cueBall,
                allBalls = obstacleBalls,
                primaryTarget = planned.targetBall,
                aimPointX = planned.aimPointX,
                aimPointY = planned.aimPointY,
                powerFraction = planned.basePowerFraction,
                pockets = pockets,
                tableBounds = tableBounds,
                aspectRatio = aspectRatio,
                profile = profile
            )
        } else null

        val visionGrid = build6x6VisionGrid(bitmap, fLeft, fTop, fRight, fBottom, cueBall, allColoredTargets)

        val solidsCount = allColoredTargets.count { it.suit == BallSuit.SOLID }
        val stripesCount = allColoredTargets.count { it.suit == BallSuit.STRIPE }
        val turnTag = when {
            effectiveSeat == "WATCH" -> "WATCH(${turnOwner.name.take(1)})"
            isMyTurn -> "BOT TURN"
            turnOwner != TurnOwner.NONE -> "HUMAN TURN"
            else -> "WAIT"
        }

        val tableSpanOk = (fRight - fLeft) >= 0.45f && (fBottom - fTop) >= 0.48f
        val detectedGeometry = if (useLocked) {
            lockedTable
        } else if (tableSpanOk && confirmedPocketsCount >= 4 && feltConsistency >= 0.45f &&
            (cueBall != null || detectedTargets.size >= 2)
        ) {
            LockedTableGeometry(
                captureW = w,
                captureH = h,
                aspectRatio = aspectRatio,
                feltHsv = feltHsv,
                bounds = tableBounds,
                pockets = pockets
            )
        } else {
            null
        }

        val summary = buildString {
            append(turnTag)
            if (useLocked || detectedGeometry != null) append(" [LOCK]")
            append(" • ${effectiveSuit.name} (${botRack.occupiedSlots}/${oppRack.occupiedSlots})")
            append(" | Cue:${if (cueBall != null) "OK" else "N/A"}")
            append(" | S:$solidsCount St:$stripesCount")
            if (planned != null) {
                val bankTag = if (planned.isBankShot) " [BANK]" else ""
                append(" | ${planned.targetBall.suit.name.take(3)}(${planned.targetBall.colorTag.take(3)})->${planned.pocket.id} (${planned.cutAngleDeg.toInt()}°)$bankTag")
            }
        }

        return PoolFrameAnalysis(
            isMyTurn = isMyTurn,
            turnOwner = turnOwner,
            leftGreenRatio = leftGreenRatio,
            rightGreenRatio = rightGreenRatio,
            powerBarVisible = powerBarVisible,
            greenTurnRatio = greenTurnRatio,
            pottedOccupiedRatio = pottedRatio,
            botRackCount = botRack.occupiedSlots,
            oppRackCount = oppRack.occupiedSlots,
            detectedSuit = detectedSuit,
            effectiveSuit = effectiveSuit,
            cueBall = cueBall,
            targetBalls = allColoredTargets,
            eightBall = eightBall,
            pockets = pockets,
            plannedShot = planned,
            simulationPrediction = simPrediction,
            visionGrid = visionGrid,
            summary = summary,
            detectedTableBounds = tableBounds,
            aspectRatio = aspectRatio,
            tableLocked = useLocked || detectedGeometry != null,
            detectedGeometry = detectedGeometry
        )
    }

    private fun extractBallCentersWithClusterSplitting(
        cols: Int,
        rows: Int,
        filteredBallCell: BooleanArray,
        nonFeltScore: FloatArray,
        fLeft: Float,
        fTop: Float,
        fRight: Float,
        fBottom: Float,
        aspectRatio: Float,
        ballRadiusIso: Float = BALL_RADIUS_ISO
    ): List<Pair<Float, Float>> {
        val visited = BooleanArray(cols * rows)
        val queue = IntArray(cols * rows)
        val centers = mutableListOf<Pair<Float, Float>>()

        for (startIdx in filteredBallCell.indices) {
            if (!filteredBallCell[startIdx] || visited[startIdx]) continue
            var head = 0
            var tail = 0
            queue[tail++] = startIdx
            visited[startIdx] = true

            val componentCells = mutableListOf<Int>()
            var minR = rows
            var maxR = 0
            var minC = cols
            var maxC = 0

            while (head < tail) {
                val curr = queue[head++]
                componentCells.add(curr)
                val cr = curr / cols
                val cc = curr % cols
                if (cr < minR) minR = cr
                if (cr > maxR) maxR = cr
                if (cc < minC) minC = cc
                if (cc > maxC) maxC = cc

                for (dr in -1..1) {
                    for (dc in -1..1) {
                        val nr = cr + dr
                        val nc = cc + dc
                        if (nr in 0 until rows && nc in 0 until cols) {
                            val nIdx = nr * cols + nc
                            if (filteredBallCell[nIdx] && !visited[nIdx]) {
                                visited[nIdx] = true
                                queue[tail++] = nIdx
                            }
                        }
                    }
                }
            }

            val spanR = maxR - minR + 1
            val spanC = maxC - minC + 1
            val count = componentCells.size
            if (count < 5) continue

            // Single isolated ball component
            if (count <= 38 && spanR <= 9 && spanC <= 9) {
                val aspectOk = maxOf(spanR, spanC).toFloat() / minOf(spanR, spanC).coerceAtLeast(1) < 2.6f
                if (aspectOk) {
                    var sumC = 0f
                    var sumR = 0f
                    componentCells.forEach { idx ->
                        sumR += idx / cols
                        sumC += idx % cols
                    }
                    val nx = fLeft + (((sumC / count) + 0.5f) / cols) * (fRight - fLeft)
                    val ny = fTop + (((sumR / count) + 0.5f) / rows) * (fBottom - fTop)
                    if (centers.none { isoDist(nx, ny, it.first, it.second, aspectRatio) < ballRadiusIso * 1.28f }) {
                        centers.add(nx to ny)
                    }
                }
            } else if (count <= 240 && spanR <= 24 && spanC <= 26) {
                // Multi-ball touching cluster: split into distinct ball peaks via greedy interior distance
                val cellSet = componentCells.toHashSet()
                val scoredCells = componentCells.map { idx ->
                    val cr = idx / cols
                    val cc = idx % cols
                    var interiorCount = 0
                    for (dr in -2..2) {
                        for (dc in -2..2) {
                            val nIdx = (cr + dr) * cols + (cc + dc)
                            if (cellSet.contains(nIdx)) interiorCount++
                        }
                    }
                    val nx = fLeft + ((cc + 0.5f) / cols) * (fRight - fLeft)
                    val ny = fTop + ((cr + 0.5f) / rows) * (fBottom - fTop)
                    Triple(nx, ny, interiorCount.toFloat() + nonFeltScore[idx] * 0.25f)
                }.sortedByDescending { it.third }

                val localCenters = mutableListOf<Pair<Float, Float>>()
                for ((nx, ny, _) in scoredCells) {
                    val farFromLocal = localCenters.none { isoDist(nx, ny, it.first, it.second, aspectRatio) < ballRadiusIso * 1.36f }
                    val farFromGlobal = centers.none { isoDist(nx, ny, it.first, it.second, aspectRatio) < ballRadiusIso * 1.28f }
                    if (farFromLocal && farFromGlobal) {
                        localCenters.add(nx to ny)
                        centers.add(nx to ny)
                        if (localCenters.size >= 6) break
                    }
                }
            }
        }
        return centers
    }

    private fun sampleFullResBallPatch(
        bitmap: Bitmap,
        normX: Float,
        normY: Float,
        aspectRatio: Float,
        feltHsv: FloatArray,
        ballRadiusIso: Float = BALL_RADIUS_ISO
    ): BallPatchStats {
        val w = bitmap.width
        val h = bitmap.height
        val hsv = FloatArray(3)

        var totalSamples = 0
        var whiteSamples = 0
        var darkSamples = 0
        var nonFeltSamples = 0
        var outerSamples = 0
        var outerWhiteSamples = 0
        var outerColorSamples = 0

        var bestSatScore = -1f
        var repColor = Color.GRAY
        val hueBins = IntArray(7) // 0=Yellow, 1=Blue, 2=Red, 3=Purple, 4=Orange, 5=Green, 6=Maroon

        val rIso = ballRadiusIso * 0.94f
        val gridSteps = -4..4
        for (ryIdx in gridSteps) {
            val dyNorm = ryIdx / 4f
            val sy = (normY + dyNorm * rIso).coerceIn(0.01f, 0.99f)
            val py = (sy * h).toInt().coerceIn(0, h - 1)
            for (rxIdx in gridSteps) {
                val dxNorm = rxIdx / 4f
                val distNorm = hypot(dxNorm, dyNorm)
                if (distNorm > 1.02f) continue

                val sx = (normX + (dxNorm * rIso) / aspectRatio).coerceIn(0.01f, 0.99f)
                val px = (sx * w).toInt().coerceIn(0, w - 1)
                val pixel = bitmap.getPixel(px, py)
                Color.colorToHSV(pixel, hsv)

                val hue = hsv[0]
                val sat = hsv[1]
                val lum = hsv[2]

                totalSamples++
                val isWhite = lum > 0.62f && sat < 0.27f
                val isDark = lum < 0.23f && sat < 0.45f
                val isVivid = sat > 0.34f && lum > 0.17f

                val hueDiff = abs(hue - feltHsv[0]).let { minOf(it, 360f - it) }
                val differsFromFelt = hueDiff > 22f || abs(sat - feltHsv[1]) > 0.24f || abs(lum - feltHsv[2]) > 0.22f
                if (differsFromFelt) nonFeltSamples++

                if (isWhite) whiteSamples++
                if (isDark) darkSamples++

                if (distNorm >= 0.46f) {
                    outerSamples++
                    if (isWhite) outerWhiteSamples++
                    if (isVivid) outerColorSamples++
                }

                if (isVivid) {
                    val satScore = sat * lum
                    if (satScore > bestSatScore) {
                        bestSatScore = satScore
                        repColor = pixel
                    }
                    val bin = classifyColorBin(hue, lum)
                    if (bin in hueBins.indices) hueBins[bin]++
                }
            }
        }

        // 8-point felt halo ring at r = 1.48 * ballRadiusIso to distinguish round balls from straight cue sticks/lines
        var haloFeltCount = 0
        val haloRadius = ballRadiusIso * 1.48f
        for (k in 0 until 8) {
            val angle = k * (PI / 4.0)
            val hx = (normX + (cos(angle).toFloat() * haloRadius) / aspectRatio).coerceIn(0.01f, 0.99f)
            val hy = (normY + sin(angle).toFloat() * haloRadius).coerceIn(0.01f, 0.99f)
            Color.colorToHSV(
                bitmap.getPixel((hx * w).toInt().coerceIn(0, w - 1), (hy * h).toInt().coerceIn(0, h - 1)),
                hsv
            )
            val hueDiff = abs(hsv[0] - feltHsv[0]).let { minOf(it, 360f - it) }
            if (hueDiff < 28f && abs(hsv[1] - feltHsv[1]) < 0.32f && abs(hsv[2] - feltHsv[2]) < 0.32f) {
                haloFeltCount++
            }
        }

        val totalDenom = totalSamples.coerceAtLeast(1).toFloat()
        val outerDenom = outerSamples.coerceAtLeast(1).toFloat()
        val topBinIdx = hueBins.indices.maxByOrNull { hueBins[it] } ?: -1
        val colorTag = when {
            darkSamples.toFloat() / totalDenom >= 0.38f -> "BLACK"
            whiteSamples.toFloat() / totalDenom >= 0.62f && outerColorSamples.toFloat() / outerDenom < 0.20f -> "WHITE"
            topBinIdx == 0 && hueBins[0] > 0 -> "YELLOW"
            topBinIdx == 1 && hueBins[1] > 0 -> "BLUE"
            topBinIdx == 2 && hueBins[2] > 0 -> "RED"
            topBinIdx == 3 && hueBins[3] > 0 -> "PURPLE"
            topBinIdx == 4 && hueBins[4] > 0 -> "ORANGE"
            topBinIdx == 5 && hueBins[5] > 0 -> "GREEN"
            topBinIdx == 6 && hueBins[6] > 0 -> "MAROON"
            else -> "COLOR"
        }

        return BallPatchStats(
            repColor = repColor,
            totalWhiteFrac = whiteSamples / totalDenom,
            outerWhiteFrac = outerWhiteSamples / outerDenom,
            outerColorFrac = outerColorSamples / outerDenom,
            darkFrac = darkSamples / totalDenom,
            discNonFeltFrac = nonFeltSamples / totalDenom,
            feltHaloFrac = haloFeltCount / 8f,
            colorTag = colorTag
        )
    }

    private fun classifyColorBin(hue: Float, lum: Float): Int {
        return when {
            hue in 39f..66f -> 0 // Yellow (1/9)
            hue in 188f..250f -> 1 // Blue (2/10)
            hue in 252f..318f -> 3 // Purple (4/12)
            hue in 16f..39f -> 4 // Orange (5/13)
            hue in 85f..168f -> 5 // Green (6/14)
            (hue < 16f || hue >= 332f) && lum < 0.48f -> 6 // Maroon (7/15)
            hue < 16f || hue >= 318f -> 2 // Red (3/11)
            else -> 4
        }
    }

    private fun detectBestHudRackY(
        bitmap: Bitmap,
        leftX: Float,
        rightX: Float,
        seedY: Float
    ): Float {
        val w = bitmap.width
        val h = bitmap.height
        val hsv = FloatArray(3)
        var bestY = seedY
        var bestVivid = -1

        val slotStart = (leftX + 0.012f).coerceIn(0.05f, 0.90f)
        val slotEnd = (rightX - 0.012f).coerceIn(slotStart + 0.05f, 0.95f)

        for (dyStep in -3..3) {
            val candY = (seedY + dyStep * 0.005f).coerceIn(0.06f, 0.20f)
            val py = (candY * h).toInt().coerceIn(0, h - 1)
            var vivid = 0
            for (slot in 0 until 7) {
                val sx = slotStart + (slot / 6f) * (slotEnd - slotStart)
                for (dx in listOf(-0.004f, 0f, 0.004f)) {
                    val px = ((sx + dx) * w).toInt().coerceIn(0, w - 1)
                    Color.colorToHSV(bitmap.getPixel(px, py), hsv)
                    if (hsv[1] > 0.35f && hsv[2] > 0.22f) vivid++
                }
            }
            if (vivid > bestVivid) {
                bestVivid = vivid
                bestY = candY
            }
        }
        return if (bestVivid >= 3) bestY else seedY
    }

    private fun scanHudRack(
        bitmap: Bitmap,
        leftX: Float,
        rightX: Float,
        centerY: Float
    ): RackScanResult {
        val w = bitmap.width
        val h = bitmap.height
        val hsv = FloatArray(3)
        var occupied = 0
        var stripeVotes = 0f
        var lumSum = 0f
        var lumCount = 0

        val slotStart = (leftX + 0.011f).coerceIn(0.05f, 0.90f)
        val slotEnd = (rightX - 0.011f).coerceIn(slotStart + 0.05f, 0.95f)

        // Full ball icon radius in HUD: rx ~ 0.010f, ry ~ 0.021f (accounts for landscape 2:1 aspect)
        val rx = 0.010f
        val ry = 0.021f

        for (slot in 0 until 7) {
            val sx = slotStart + (slot / 6f) * (slotEnd - slotStart)
            var vividPixels = 0
            var brightInnerPixels = 0
            var capWhitePixels = 0
            var outerColorPixels = 0
            var slotLumSum = 0f
            var slotLumCount = 0

            for (dyIdx in -4..4) {
                val dyNorm = dyIdx / 4f
                val py = ((centerY + dyNorm * ry) * h).toInt().coerceIn(0, h - 1)
                for (dxIdx in -3..3) {
                    val dxNorm = dxIdx / 3f
                    val rNorm = hypot(dxNorm, dyNorm)
                    if (rNorm > 0.95f) continue

                    val px = ((sx + dxNorm * rx) * w).toInt().coerceIn(0, w - 1)
                    val pixel = bitmap.getPixel(px, py)
                    Color.colorToHSV(pixel, hsv)
                    val s = hsv[1]
                    val v = hsv[2]
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    val chroma = maxOf(r, g, b) - minOf(r, g, b)
                    slotLumSum += v
                    slotLumCount++

                    // Supports both active bright rack and waiting dimmed rack, while rejecting dark charcoal empty slots
                    val isColor = s > 0.30f && v > 0.16f && chroma >= 26
                    val isWhite = s < 0.28f && v > 0.30f

                    if (isColor) vividPixels++
                    if (rNorm < 0.45f && v > 0.24f) brightInnerPixels++

                    if (rNorm >= 0.46f) {
                        if (isColor) outerColorPixels++
                        if (abs(dyNorm) >= 0.48f && isWhite) capWhitePixels++
                    }
                }
            }

            if (vividPixels >= 4 || (vividPixels >= 3 && brightInnerPixels >= 3)) {
                occupied++
                if (slotLumCount > 0) {
                    lumSum += slotLumSum / slotLumCount
                    lumCount++
                }
                when {
                    capWhitePixels >= 3 && capWhitePixels >= outerColorPixels * 0.42f -> stripeVotes += 1f
                    outerColorPixels >= capWhitePixels * 2 && outerColorPixels >= 5 -> stripeVotes -= 1f
                    capWhitePixels >= 2 -> stripeVotes += 0.5f
                    else -> stripeVotes -= 0.5f
                }
            }
        }

        val normScore = if (occupied > 0) stripeVotes / occupied else 0f
        val avgLum = if (lumCount > 0) lumSum / lumCount else 0f
        return RackScanResult(
            occupiedSlots = occupied,
            isStripesScore = normScore,
            occupiedRatio = occupied / 7f,
            avgLuminance = avgLum
        )
    }

    private fun inferAssignedSuit(botRack: RackScanResult, oppRack: RackScanResult): AssignedSuit {
        if (botRack.occupiedSlots == 0 && oppRack.occupiedSlots == 0) {
            return AssignedSuit.OPEN
        }
        if (botRack.occupiedSlots == 0 && oppRack.occupiedSlots >= 1) {
            return AssignedSuit.EIGHT_BALL
        }
        // Weight bright/active rack slightly more than dimmed waiting rack when combining differential scores
        val combinedScore = if (oppRack.occupiedSlots >= 1) {
            val botW = if (botRack.avgLuminance >= oppRack.avgLuminance) 0.62f else 0.45f
            val oppW = 1.0f - botW
            botRack.isStripesScore * botW - oppRack.isStripesScore * oppW
        } else {
            botRack.isStripesScore
        }
        return when {
            combinedScore >= 0.10f -> AssignedSuit.STRIPES
            combinedScore <= -0.10f -> AssignedSuit.SOLIDS
            botRack.isStripesScore >= 0f -> AssignedSuit.STRIPES
            else -> AssignedSuit.SOLIDS
        }
    }

    /**
     * Scans only near the expected cushion border band [seed +/- 0.05] outward from felt into wood rail,
     * requiring a persistent non-felt run so interior balls or cue sticks can never shrink the table!
     */
    private fun detectInnerFeltBorders(
        bitmap: Bitmap,
        feltHsv: FloatArray,
        seedLeft: Float,
        seedTop: Float,
        seedRight: Float,
        seedBottom: Float
    ): List<Float> {
        val w = bitmap.width
        val h = bitmap.height
        val hsv = FloatArray(3)

        fun isFeltPixel(nx: Float, ny: Float): Boolean {
            val px = (nx * w).toInt().coerceIn(0, w - 1)
            val py = (ny * h).toInt().coerceIn(0, h - 1)
            Color.colorToHSV(bitmap.getPixel(px, py), hsv)
            val hueDiff = abs(hsv[0] - feltHsv[0]).let { minOf(it, 360f - it) }
            val satDiff = abs(hsv[1] - feltHsv[1])
            val valDiff = abs(hsv[2] - feltHsv[2])
            return hueDiff < 26f && satDiff < 0.30f && valDiff < 0.30f
        }

        fun scanEdgeX(startX: Float, endX: Float, step: Float): Float? {
            val ys = listOf(0.32f, 0.44f, 0.56f, 0.68f, 0.78f)
            val hits = mutableListOf<Float>()
            for (y in ys) {
                var x = startX
                var nonFeltRun = 0
                while (if (step > 0) x <= endX else x >= endX) {
                    if (!isFeltPixel(x, y)) {
                        nonFeltRun++
                        // Confirm outward cushion stays non-felt beyond a ball diameter
                        if (nonFeltRun >= 3 && !isFeltPixel((x + step * 4f).coerceIn(0.02f, 0.98f), y)) {
                            hits.add(x - step * 2f)
                            break
                        }
                    } else {
                        nonFeltRun = 0
                    }
                    x += step
                }
            }
            if (hits.size < 3) return null
            hits.sort()
            return hits[hits.size / 2]
        }

        fun scanEdgeY(startY: Float, endY: Float, step: Float): Float? {
            val xs = listOf(0.28f, 0.39f, 0.50f, 0.61f, 0.72f)
            val hits = mutableListOf<Float>()
            for (x in xs) {
                var y = startY
                var nonFeltRun = 0
                while (if (step > 0) y <= endY else y >= endY) {
                    if (!isFeltPixel(x, y)) {
                        nonFeltRun++
                        if (nonFeltRun >= 3 && !isFeltPixel(x, (y + step * 4f).coerceIn(0.05f, 0.99f))) {
                            hits.add(y - step * 2f)
                            break
                        }
                    } else {
                        nonFeltRun = 0
                    }
                    y += step
                }
            }
            if (hits.size < 3) return null
            hits.sort()
            return hits[hits.size / 2]
        }

        val detLeft = scanEdgeX((seedLeft + 0.055f).coerceAtMost(0.45f), (seedLeft - 0.045f).coerceAtLeast(0.08f), -0.004f)
            ?.coerceIn(seedLeft - 0.035f, seedLeft + 0.035f) ?: seedLeft
        val detRight = scanEdgeX((seedRight - 0.055f).coerceAtLeast(0.55f), (seedRight + 0.045f).coerceAtMost(0.92f), +0.004f)
            ?.coerceIn(seedRight - 0.035f, seedRight + 0.035f) ?: seedRight
        val detTop = scanEdgeY((seedTop + 0.060f).coerceAtMost(0.45f), (seedTop - 0.045f).coerceAtLeast(0.12f), -0.005f)
            ?.coerceIn(seedTop - 0.035f, seedTop + 0.035f) ?: seedTop
        val detBottom = scanEdgeY((seedBottom - 0.060f).coerceAtLeast(0.55f), (seedBottom + 0.045f).coerceAtMost(0.97f), +0.005f)
            ?.coerceIn(seedBottom - 0.035f, seedBottom + 0.035f) ?: seedBottom

        return listOf(
            detLeft.coerceAtMost(detRight - 0.25f),
            detTop.coerceAtMost(detBottom - 0.25f),
            detRight.coerceAtLeast(detLeft + 0.25f),
            detBottom.coerceAtLeast(detTop + 0.25f)
        )
    }

    private fun detectSixPocketsWithCount(
        bitmap: Bitmap,
        fLeft: Float,
        fTop: Float,
        fRight: Float,
        fBottom: Float
    ): Pair<List<PoolPocket>, Int> {
        val w = bitmap.width
        val h = bitmap.height
        val hsv = FloatArray(3)
        val seeds = listOf(
            PoolPocket("TL", fLeft + 0.006f, fTop + 0.010f),
            PoolPocket("TM", (fLeft + fRight) * 0.5f, fTop - 0.004f),
            PoolPocket("TR", fRight - 0.006f, fTop + 0.010f),
            PoolPocket("BL", fLeft + 0.006f, fBottom - 0.010f),
            PoolPocket("BM", (fLeft + fRight) * 0.5f, fBottom + 0.004f),
            PoolPocket("BR", fRight - 0.006f, fBottom - 0.010f)
        )

        var confirmed = 0
        val resolved = seeds.map { seed ->
            var sumX = 0f
            var sumY = 0f
            var darkCount = 0
            for (dyIdx in -4..4) {
                val ny = (seed.normY + dyIdx * 0.005f).coerceIn(0.05f, 0.98f)
                val py = (ny * h).toInt().coerceIn(0, h - 1)
                for (dxIdx in -4..4) {
                    val nx = (seed.normX + dxIdx * 0.003f).coerceIn(0.05f, 0.96f)
                    val px = (nx * w).toInt().coerceIn(0, w - 1)
                    Color.colorToHSV(bitmap.getPixel(px, py), hsv)
                    if (hsv[2] < 0.16f && hsv[1] < 0.55f) {
                        sumX += nx
                        sumY += ny
                        darkCount++
                    }
                }
            }
            if (darkCount >= 5) {
                confirmed++
                PoolPocket(seed.id, sumX / darkCount, sumY / darkCount)
            } else {
                seed
            }
        }
        return resolved to confirmed
    }

    fun filterLegalTargets(
        effectiveSuit: AssignedSuit,
        targets: List<DetectedBall>,
        eightBall: DetectedBall?
    ): List<DetectedBall> {
        val solids = targets.filter { it.suit == BallSuit.SOLID }
        val stripes = targets.filter { it.suit == BallSuit.STRIPE }
        return when (effectiveSuit) {
            AssignedSuit.SOLIDS -> {
                solids.ifEmpty {
                    if (stripes.isEmpty()) listOfNotNull(eightBall) else emptyList()
                }
            }
            AssignedSuit.STRIPES -> {
                stripes.ifEmpty {
                    if (solids.isEmpty()) listOfNotNull(eightBall) else emptyList()
                }
            }
            AssignedSuit.EIGHT_BALL -> listOfNotNull(eightBall)
            AssignedSuit.OPEN -> targets.ifEmpty { listOfNotNull(eightBall) }
        }
    }

    /**
     * Rotates the cue->aimPoint ray in true isotropic screen space by [angleTrimDeg] and
     * projects the aim tap out to a safe minimum distance along the ray so micro-pixel touch rounding
     * never causes huge angle error or clicks the cue-ball move handle.
     */
    fun computeAimPointWithTrim(
        shot: PlannedPoolShot,
        angleTrimDeg: Float,
        aspectRatio: Float = 2.0f,
        fLeft: Float = 0.18f,
        fTop: Float = 0.20f,
        fRight: Float = 0.82f,
        fBottom: Float = 0.92f
    ): Pair<Float, Float> {
        val isoDx = (shot.aimPointX - shot.cueBall.normX) * aspectRatio
        val isoDy = shot.aimPointY - shot.cueBall.normY
        val len = hypot(isoDx, isoDy).coerceAtLeast(0.01f)
        val rad = angleTrimDeg * (PI / 180.0)
        val rxIso = (isoDx * cos(rad) - isoDy * sin(rad)).toFloat()
        val ryIso = (isoDx * sin(rad) + isoDy * cos(rad)).toFloat()

        val targetRayDist = maxOf(len, 0.12f)
        val scale = targetRayDist / len
        val tapX = (shot.cueBall.normX + (rxIso * scale) / aspectRatio).coerceIn(fLeft + 0.01f, fRight - 0.01f)
        val tapY = (shot.cueBall.normY + (ryIso * scale)).coerceIn(fTop + 0.01f, fBottom - 0.01f)
        return tapX to tapY
    }

    fun computeBestShot(
        cue: DetectedBall?,
        targets: List<DetectedBall>,
        pockets: List<PoolPocket>,
        fLeft: Float,
        fTop: Float,
        fRight: Float,
        fBottom: Float,
        allObstacles: List<DetectedBall> = targets,
        aspectRatio: Float = 2.0f,
        failedShotPenalties: Map<String, Float> = emptyMap(),
        profile: TrainingProfileEntity? = null,
        effectiveSuit: AssignedSuit = AssignedSuit.OPEN
    ): PlannedPoolShot? {
        if (cue == null || targets.isEmpty()) return null

        val candidates = mutableListOf<Pair<Float, PlannedPoolShot>>()
        val marginX = 0.008f / aspectRatio
        val marginY = 0.008f

        for (target in targets) {
            val ctIsoDx = (target.normX - cue.normX) * aspectRatio
            val ctIsoDy = target.normY - cue.normY
            val ctDist = hypot(ctIsoDx, ctIsoDy)
            if (ctDist < 0.028f) continue

            for (pocket in pockets) {
                val tpIsoDx = (pocket.normX - target.normX) * aspectRatio
                val tpIsoDy = pocket.normY - target.normY
                val tpDist = hypot(tpIsoDx, tpIsoDy)
                if (tpDist < 0.028f) continue

                val dot = ((ctIsoDx / ctDist) * (tpIsoDx / tpDist) + (ctIsoDy / ctDist) * (tpIsoDy / tpDist)).coerceIn(-1f, 1f)
                val cutAngleDeg = (acos(dot) * 180.0 / PI).toFloat()
                if (cutAngleDeg > 66f) continue

                val (jawX, jawY) = pocketEntryVector(pocket.id)
                val jawDot = ((tpIsoDx / tpDist) * jawX + (tpIsoDy / tpDist) * jawY).coerceIn(-1f, 1f)
                val jawAngleDeg = (acos(jawDot) * 180.0 / PI).toFloat()
                val maxJawDeg = if (pocket.id == "TM" || pocket.id == "BM") 50f else 58f
                if (jawAngleDeg > maxJawDeg) continue

                val ghostIsoX = (target.normX * aspectRatio) - (tpIsoDx / tpDist) * (BALL_RADIUS_ISO * 2.0f)
                val ghostIsoY = target.normY - (tpIsoDy / tpDist) * (BALL_RADIUS_ISO * 2.0f)
                val ghostX = ghostIsoX / aspectRatio
                val ghostY = ghostIsoY

                if (ghostX < fLeft + marginX || ghostX > fRight - marginX ||
                    ghostY < fTop + marginY || ghostY > fBottom - marginY
                ) {
                    continue
                }

                var blockerPenalty = 0f
                for (obs in allObstacles) {
                    if (isoDist(obs.normX, obs.normY, target.normX, target.normY, aspectRatio) < 0.022f) continue
                    val distCuePath = pointToSegmentDistanceIso(
                        obs.normX, obs.normY,
                        cue.normX, cue.normY,
                        ghostX, ghostY,
                        aspectRatio
                    )
                    val distTargetPath = pointToSegmentDistanceIso(
                        obs.normX, obs.normY,
                        target.normX, target.normY,
                        pocket.normX, pocket.normY,
                        aspectRatio
                    )
                    if (distCuePath < BALL_RADIUS_ISO * 1.95f || distTargetPath < BALL_RADIUS_ISO * 1.95f) {
                        blockerPenalty += if (obs.kind == DetectedBallKind.EIGHT_BALL) 90f else 62f
                    }
                }

                val scratchRiskPenalty = if (cutAngleDeg < 7f && abs(jawAngleDeg) < 12f && tpDist < 0.28f) 8f else 0f

                val angleBucket = (cutAngleDeg / 15f).toInt().coerceIn(0, 4)
                val distBucket = (ctDist + tpDist).let { (it * 3.5f).toInt().coerceIn(0, 4) }
                val stateHash = "POOL_A${angleBucket}_D${distBucket}_${pocket.id}"
                val priorFailPenalty = failedShotPenalties[stateHash] ?: 0f

                val totalDist = ctDist + tpDist
                val geomScore = (90f - cutAngleDeg) * 1.45f -
                    totalDist * 30f -
                    jawAngleDeg * 0.35f -
                    blockerPenalty -
                    scratchRiskPenalty -
                    priorFailPenalty

                val basePower = (0.38f + totalDist * 0.36f + (cutAngleDeg / 180f) * 0.14f).coerceIn(0.32f, 0.88f)
                candidates.add(
                    geomScore to PlannedPoolShot(
                        cueBall = cue,
                        targetBall = target,
                        pocket = pocket,
                        aimPointX = ghostX.coerceIn(fLeft, fRight),
                        aimPointY = ghostY.coerceIn(fTop, fBottom),
                        cutAngleDeg = cutAngleDeg,
                        totalDistance = totalDist,
                        basePowerFraction = basePower,
                        stateHash = stateHash,
                        stateSummary = "Cut ${cutAngleDeg.toInt()}° | ${target.suit.name} | Dist ${"%.2f".format(totalDist)} -> ${pocket.id}",
                        isBankShot = false
                    )
                )
            }
        }

        // Evaluate full 2D multi-ball physics simulation on top candidates to avoid scratches & 8-ball fouls
        val topCandidates = candidates.sortedByDescending { it.first }.take(6)
        var bestScore = Float.NEGATIVE_INFINITY
        var bestShot: PlannedPoolShot? = null
        val tableBounds = listOf(fLeft, fTop, fRight, fBottom)

        for ((geomScore, shot) in topCandidates) {
            val sim = PoolPhysicsSimulator.simulateShot(
                cueBall = cue,
                allBalls = allObstacles,
                primaryTarget = shot.targetBall,
                aimPointX = shot.aimPointX,
                aimPointY = shot.aimPointY,
                powerFraction = shot.basePowerFraction,
                pockets = pockets,
                tableBounds = tableBounds,
                aspectRatio = aspectRatio,
                profile = profile
            )
            var simBonus = 0f
            if (sim.cueScratchPredicted) simBonus -= 48f
            if (sim.eightBallPocketedId != null && effectiveSuit != AssignedSuit.EIGHT_BALL) simBonus -= 110f
            if (sim.targetPocketedId == shot.pocket.id) simBonus += 12f
            if (sim.secondaryPocketedIds.isNotEmpty()) simBonus += 5f

            val totalScore = geomScore + simBonus
            if (totalScore > bestScore) {
                bestScore = totalScore
                bestShot = shot
            }
        }

        if (bestShot == null || bestScore < -15f) {
            val bank = computeOneCushionBankShot(
                cue = cue,
                targets = targets,
                pockets = pockets,
                fLeft = fLeft,
                fTop = fTop,
                fRight = fRight,
                fBottom = fBottom,
                allObstacles = allObstacles,
                aspectRatio = aspectRatio,
                failedShotPenalties = failedShotPenalties
            )
            if (bank != null && (bestShot == null || bestScore < -15f)) {
                return bank
            }
        }
        return bestShot
    }

    private fun computeOneCushionBankShot(
        cue: DetectedBall,
        targets: List<DetectedBall>,
        pockets: List<PoolPocket>,
        fLeft: Float,
        fTop: Float,
        fRight: Float,
        fBottom: Float,
        allObstacles: List<DetectedBall>,
        aspectRatio: Float,
        failedShotPenalties: Map<String, Float>
    ): PlannedPoolShot? {
        var bestScore = Float.NEGATIVE_INFINITY
        var bestBank: PlannedPoolShot? = null

        for (target in targets) {
            val ctIsoDx = (target.normX - cue.normX) * aspectRatio
            val ctIsoDy = target.normY - cue.normY
            val ctDist = hypot(ctIsoDx, ctIsoDy)
            if (ctDist < 0.03f) continue

            val mirrorRails = listOf("TOP" to fTop, "BOT" to fBottom)
            for ((_, railY) in mirrorRails) {
                for (pocket in pockets) {
                    val mirroredY = 2f * railY - pocket.normY
                    val tpIsoDx = (pocket.normX - target.normX) * aspectRatio
                    val tpIsoDy = mirroredY - target.normY
                    val tpDist = hypot(tpIsoDx, tpIsoDy)
                    if (tpDist < 0.04f) continue

                    val dot = ((ctIsoDx / ctDist) * (tpIsoDx / tpDist) + (ctIsoDy / ctDist) * (tpIsoDy / tpDist)).coerceIn(-1f, 1f)
                    val cutAngleDeg = (acos(dot) * 180.0 / PI).toFloat()
                    if (cutAngleDeg > 38f) continue

                    val ghostX = ((target.normX * aspectRatio) - (tpIsoDx / tpDist) * (BALL_RADIUS_ISO * 2.0f)) / aspectRatio
                    val ghostY = target.normY - (tpIsoDy / tpDist) * (BALL_RADIUS_ISO * 2.0f)
                    if (ghostX !in fLeft..fRight || ghostY !in fTop..fBottom) continue

                    var blocked = false
                    for (obs in allObstacles) {
                        if (isoDist(obs.normX, obs.normY, target.normX, target.normY, aspectRatio) < 0.022f) continue
                        if (pointToSegmentDistanceIso(obs.normX, obs.normY, cue.normX, cue.normY, ghostX, ghostY, aspectRatio) < BALL_RADIUS_ISO * 1.9f) {
                            blocked = true
                            break
                        }
                    }
                    if (blocked) continue

                    val totalDist = ctDist + tpDist
                    val angleBucket = (cutAngleDeg / 15f).toInt().coerceIn(0, 4)
                    val distBucket = (totalDist * 3.5f).toInt().coerceIn(0, 4)
                    val stateHash = "POOL_A${angleBucket}_D${distBucket}_${pocket.id}"
                    val penalty = failedShotPenalties[stateHash] ?: 0f
                    val score = (80f - cutAngleDeg * 1.8f) - totalDist * 28f - penalty

                    if (score > bestScore) {
                        bestScore = score
                        bestBank = PlannedPoolShot(
                            cueBall = cue,
                            targetBall = target,
                            pocket = pocket,
                            aimPointX = ghostX,
                            aimPointY = ghostY,
                            cutAngleDeg = cutAngleDeg,
                            totalDistance = totalDist,
                            basePowerFraction = (0.50f + totalDist * 0.28f).coerceIn(0.45f, 0.90f),
                            stateHash = stateHash,
                            stateSummary = "Bank ${cutAngleDeg.toInt()}° | ${target.suit.name} -> ${pocket.id}",
                            isBankShot = true
                        )
                    }
                }
            }
        }
        return bestBank
    }

    private fun pocketEntryVector(pocketId: String): Pair<Float, Float> {
        val invSqrt2 = 0.70710677f
        return when (pocketId) {
            "TL" -> -invSqrt2 to -invSqrt2
            "TR" -> +invSqrt2 to -invSqrt2
            "BL" -> -invSqrt2 to +invSqrt2
            "BR" -> +invSqrt2 to +invSqrt2
            "TM" -> 0f to -1f
            "BM" -> 0f to +1f
            else -> 0f to 1f
        }
    }

    private fun isoDist(ax: Float, ay: Float, bx: Float, by: Float, aspectRatio: Float): Float {
        return hypot((ax - bx) * aspectRatio, ay - by)
    }

    private fun pointToSegmentDistanceIso(
        px: Float,
        py: Float,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
        aspectRatio: Float
    ): Float {
        val pxI = px * aspectRatio
        val axI = ax * aspectRatio
        val bxI = bx * aspectRatio
        val abx = bxI - axI
        val aby = by - ay
        val lenSq = abx * abx + aby * aby
        if (lenSq < 1e-6f) return hypot(pxI - axI, py - ay)
        val t = (((pxI - axI) * abx + (py - ay) * aby) / lenSq).coerceIn(0.05f, 0.95f)
        val projX = axI + t * abx
        val projY = ay + t * aby
        return hypot(pxI - projX, py - projY)
    }

    /**
     * Inspects the live aiming indicator around [targetBall] right after orienting the cue stick:
     * - Detects the Red `⊘` prohibition icon above/on opponent or illegal balls -> FORBIDDEN_RED_MARKER
     * - Detects the bright White aiming ring & forward target-ball guideline -> CONFIRMED_LEGAL_WHITE_GUIDE
     *   and measures guide-line angular error relative to [intendedPocket].
     */
    fun inspectAimFeedback(
        bitmap: Bitmap,
        cueBall: DetectedBall,
        targetBall: DetectedBall,
        intendedPocket: PoolPocket,
        aspectRatio: Float = 2.0f
    ): AimFeedbackResult {
        val w = bitmap.width.coerceAtLeast(1)
        val h = bitmap.height.coerceAtLeast(1)
        val safeAspect = aspectRatio.coerceIn(0.45f, 2.50f)
        val hsv = FloatArray(3)

        val radIso = if (safeAspect < 1.0f) BALL_RADIUS_ISO * safeAspect else BALL_RADIUS_ISO

        // 1. Scan upper badge halo strictly above top rim of target ball for the bright Red `⊘` symbol
        var redBadgeHits = 0
        var redBadgeTotal = 0
        var blackOutlineHits = 0
        val tx = targetBall.normX
        val ty = targetBall.normY

        for (dyStep in -5..-1) {
            // dyStep in [-5..-1] covers [-2.56*R .. -0.80*R] above ball center (strictly above target ball body)
            val dyIso = (dyStep * 0.44f - 0.36f) * radIso
            val sy = (ty + dyIso).coerceIn(0.01f, 0.99f)
            val py = (sy * h).toInt().coerceIn(0, h - 1)
            for (dxStep in -4..4) {
                val dxIso = (dxStep * 0.30f) * radIso
                // Never count pixels overlapping the target ball itself (crucial for Solid #3 / Maroon #7)
                val distFromCenter = hypot(dxIso, dyIso)
                if (distFromCenter < radIso * 1.15f) continue
                val sx = (tx + dxIso / safeAspect).coerceIn(0.01f, 0.99f)
                val px = (sx * w).toInt().coerceIn(0, w - 1)
                val p = bitmap.getPixel(px, py)
                Color.colorToHSV(p, hsv)
                redBadgeTotal++
                val isCrimsonIcon = (hsv[0] <= 14f || hsv[0] >= 344f) &&
                    hsv[1] >= 0.62f && hsv[2] >= 0.58f &&
                    Color.red(p) >= 175 && Color.green(p) <= 75 && Color.blue(p) <= 75
                if (isCrimsonIcon) redBadgeHits++
                if (hsv[2] < 0.16f && hsv[1] < 0.55f) blackOutlineHits++
            }
        }
        val redForbiddenScore = if (redBadgeTotal > 0) redBadgeHits.toFloat() / redBadgeTotal else 0f

        // 2. Scan White Aiming Circle around ghost impact point & White forward target guideline
        val ctDx = (tx - cueBall.normX) * safeAspect
        val ctDy = ty - cueBall.normY
        val ctLen = hypot(ctDx, ctDy).coerceAtLeast(0.01f)
        val fwdX = ctDx / ctLen
        val fwdY = ctDy / ctLen

        val ghostX = (tx * safeAspect - fwdX * radIso * 1.95f) / safeAspect
        val ghostY = ty - fwdY * radIso * 1.95f
        var ringWhiteHits = 0
        val ringSamples = 16
        for (k in 0 until ringSamples) {
            val ang = k * (2.0 * PI / ringSamples)
            val rx = (ghostX + (cos(ang).toFloat() * radIso * 0.96f) / safeAspect).coerceIn(0.01f, 0.99f)
            val ry = (ghostY + sin(ang).toFloat() * radIso * 0.96f).coerceIn(0.01f, 0.99f)
            Color.colorToHSV(
                bitmap.getPixel((rx * w).toInt().coerceIn(0, w - 1), (ry * h).toInt().coerceIn(0, h - 1)),
                hsv
            )
            if (hsv[2] >= 0.78f && hsv[1] <= 0.25f) ringWhiteHits++
        }
        val whiteRingScore = ringWhiteHits.toFloat() / ringSamples

        // Scan forward white guideline rays around the cue->target axis (-52° .. +52° in 4° steps)
        val baseAngle = atan2(fwdY.toDouble(), fwdX.toDouble())
        val pocketAngle = atan2(
            (intendedPocket.normY - ty).toDouble(),
            ((intendedPocket.normX - tx) * safeAspect).toDouble()
        )
        var bestRayHits = 0
        var bestRayAngleRad = baseAngle
        for (degOffset in -52..52 step 4) {
            val ang = baseAngle + degOffset * (PI / 180.0)
            val cosA = cos(ang).toFloat()
            val sinA = sin(ang).toFloat()
            var hits = 0
            for (step in 1..10) {
                val r = radIso * (1.35f + step * 0.42f)
                val sx = (tx + (cosA * r) / safeAspect).coerceIn(0.01f, 0.99f)
                val sy = (ty + sinA * r).coerceIn(0.01f, 0.99f)
                Color.colorToHSV(
                    bitmap.getPixel((sx * w).toInt().coerceIn(0, w - 1), (sy * h).toInt().coerceIn(0, h - 1)),
                    hsv
                )
                if (hsv[2] >= 0.78f && hsv[1] <= 0.25f) hits++
            }
            if (hits > bestRayHits) {
                bestRayHits = hits
                bestRayAngleRad = ang
            }
        }
        val whiteGuideScore = bestRayHits / 10f
        val angleDiffDeg = (((bestRayAngleRad - pocketAngle) * 180.0 / PI).toFloat()).let { d ->
            var n = d
            while (n > 180f) n -= 360f
            while (n < -180f) n += 360f
            n
        }

        val strongRedForbiddenBadge = (redBadgeHits >= 5 && blackOutlineHits >= 2 && whiteGuideScore < 0.45f) ||
            (redBadgeHits >= 6 && redForbiddenScore >= 0.16f && whiteGuideScore < 0.45f)
        val status = when {
            strongRedForbiddenBadge ->
                AimFeedbackStatus.FORBIDDEN_RED_MARKER
            bestRayHits >= 4 || (whiteRingScore >= 0.28f && bestRayHits >= 3) ->
                AimFeedbackStatus.CONFIRMED_LEGAL_WHITE_GUIDE
            else ->
                AimFeedbackStatus.NO_CLEAR_AIM_FEEDBACK
        }

        val note = when (status) {
            AimFeedbackStatus.FORBIDDEN_RED_MARKER ->
                "Red ⊘ icon detected (${redBadgeHits} hits) -> NOT my ball"
            AimFeedbackStatus.CONFIRMED_LEGAL_WHITE_GUIDE ->
                "White aim guide confirmed (ray=${bestRayHits}/10, err=${"%.1f".format(angleDiffDeg)}°)"
            AimFeedbackStatus.NO_CLEAR_AIM_FEEDBACK ->
                "No white guide yet (ring=${(whiteRingScore * 100).toInt()}%, ray=${bestRayHits})"
        }

        return AimFeedbackResult(
            status = status,
            redForbiddenScore = redForbiddenScore,
            whiteRingScore = whiteRingScore,
            whiteGuideScore = whiteGuideScore,
            guideAngleErrorDeg = angleDiffDeg,
            note = note
        )
    }

    private fun estimateFeltColorAndConsistency(
        bitmap: Bitmap,
        tLeft: Float,
        tTop: Float,
        tRight: Float,
        tBottom: Float
    ): Pair<FloatArray, Float> {
        val w = bitmap.width
        val h = bitmap.height
        val hues = mutableListOf<Float>()
        val sats = mutableListOf<Float>()
        val vals = mutableListOf<Float>()
        val samples = mutableListOf<FloatArray>()
        val hsv = FloatArray(3)
        for (ry in listOf(0.22f, 0.40f, 0.60f, 0.78f)) {
            for (rx in listOf(0.20f, 0.38f, 0.56f, 0.74f)) {
                val px = ((tLeft + rx * (tRight - tLeft)) * w).toInt().coerceIn(0, w - 1)
                val py = ((tTop + ry * (tBottom - tTop)) * h).toInt().coerceIn(0, h - 1)
                Color.colorToHSV(bitmap.getPixel(px, py), hsv)
                val copy = floatArrayOf(hsv[0], hsv[1], hsv[2])
                samples.add(copy)
                // Prefer colored felt pixels over white/black ball patches when taking median
                if (hsv[1] >= 0.18f && hsv[2] in 0.16f..0.92f) {
                    hues.add(hsv[0])
                    sats.add(hsv[1])
                    vals.add(hsv[2])
                }
            }
        }
        if (hues.isEmpty()) {
            samples.forEach {
                hues.add(it[0])
                sats.add(it[1])
                vals.add(it[2])
            }
        }
        hues.sort()
        sats.sort()
        vals.sort()
        val medH = hues[hues.size / 2]
        val medS = sats[sats.size / 2]
        val medV = vals[vals.size / 2]
        val felt = floatArrayOf(medH, medS, medV)
        val consistentCount = samples.count { s ->
            val hDiff = abs(s[0] - medH).let { minOf(it, 360f - it) }
            hDiff < 26f && abs(s[1] - medS) < 0.30f && abs(s[2] - medV) < 0.30f
        }
        val consistency = consistentCount.toFloat() / samples.size.coerceAtLeast(1)
        return felt to consistency
    }

    private fun measureGreenBorderRatio(bitmap: Bitmap, centerX: Float, centerY: Float): Float {
        val w = bitmap.width
        val h = bitmap.height
        val cx = (centerX * w).toInt()
        val cy = (centerY * h).toInt()
        val rx = (0.030f * w).toInt().coerceAtLeast(8)
        val ry = (0.052f * h).toInt().coerceAtLeast(8)
        var greenCount = 0
        var total = 0
        val stepX = (rx / 6).coerceAtLeast(1)
        val stepY = (ry / 6).coerceAtLeast(1)
        for (y in (cy - ry)..(cy + ry) step stepY) {
            if (y !in 0 until h) continue
            for (x in (cx - rx)..(cx + rx) step stepX) {
                if (x !in 0 until w) continue
                val p = bitmap.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                if (g > 125 && g > r + 35 && g > b + 35) {
                    greenCount++
                }
                total++
            }
        }
        return if (total > 0) greenCount.toFloat() / total else 0f
    }

    private fun measurePowerBarVisible(bitmap: Bitmap, powerBarX: Float): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        var brightWoodPixels = 0
        val samples = 10
        val hsv = FloatArray(3)
        for (dxNorm in listOf(-0.008f, 0f, 0.008f)) {
            val px = ((powerBarX + dxNorm) * w).toInt().coerceIn(0, w - 1)
            for (i in 0 until samples) {
                val ny = 0.34f + (i.toFloat() / (samples - 1)) * 0.44f
                val py = (ny * h).toInt().coerceIn(0, h - 1)
                Color.colorToHSV(bitmap.getPixel(px, py), hsv)
                if (hsv[2] > 0.34f && hsv[0] in 12f..68f) {
                    brightWoodPixels++
                }
            }
        }
        return brightWoodPixels >= 6
    }

    private fun build6x6VisionGrid(
        bitmap: Bitmap,
        tLeft: Float,
        tTop: Float,
        tRight: Float,
        tBottom: Float,
        cueBall: DetectedBall?,
        targets: List<DetectedBall>
    ): List<Float> {
        val grid = MutableList(36) { 0.10f }
        val w = bitmap.width
        val h = bitmap.height
        for (r in 0 until 6) {
            for (c in 0 until 6) {
                val nx = tLeft + ((c + 0.5f) / 6f) * (tRight - tLeft)
                val ny = tTop + ((r + 0.5f) / 6f) * (tBottom - tTop)
                val p = bitmap.getPixel((nx * w).toInt().coerceIn(0, w - 1), (ny * h).toInt().coerceIn(0, h - 1))
                val lum = (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) / 255f
                grid[r * 6 + c] = (lum * 0.45f).coerceIn(0.05f, 0.45f)
            }
        }
        for (ball in targets) {
            val gc = (((ball.normX - tLeft) / (tRight - tLeft)) * 6f).toInt().coerceIn(0, 5)
            val gr = (((ball.normY - tTop) / (tBottom - tTop)) * 6f).toInt().coerceIn(0, 5)
            grid[gr * 6 + gc] = 0.72f
        }
        if (cueBall != null) {
            val gc = (((cueBall.normX - tLeft) / (tRight - tLeft)) * 6f).toInt().coerceIn(0, 5)
            val gr = (((cueBall.normY - tTop) / (tBottom - tTop)) * 6f).toInt().coerceIn(0, 5)
            grid[gr * 6 + gc] = 1.0f
        }
        return grid
    }
}
