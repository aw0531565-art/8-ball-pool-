package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.TrainingProfileEntity
import com.example.domain.vision.AimFeedbackStatus
import com.example.domain.vision.AssignedSuit
import com.example.domain.vision.BallSuit
import com.example.domain.vision.DetectedBall
import com.example.domain.vision.DetectedBallKind
import com.example.domain.vision.PoolPocket
import com.example.domain.vision.PoolVisionAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("NeuroBot", appName)
    }

    @Test
    fun `poolVisionAnalyzer detects both SOLIDS and STRIPES match assignments and classifies table balls`() {
        val profile = TrainingProfileEntity(
            id = 1L,
            name = "8-Ball Pool Auto-Bot",
            targetPackage = "com.miniclip.eightballpool",
            botMode = "POOL_AIM",
            botSeatSide = "LEFT",
            preferredSuit = "AUTO"
        )

        // Match A: Bot (LEFT) has SOLIDS (5 remaining), Opponent (RIGHT) has STRIPES (4 remaining)
        val solidsMatchBmp = renderSyntheticPoolFrame(
            botIsStripes = false,
            botRemaining = 5,
            oppRemaining = 4
        )
        val analysisSolids = PoolVisionAnalyzer.analyze(solidsMatchBmp, profile)
        assertEquals(AssignedSuit.SOLIDS, analysisSolids.detectedSuit)
        assertEquals(5, analysisSolids.botRackCount)
        assertEquals(4, analysisSolids.oppRackCount)
        assertNotNull(analysisSolids.cueBall)
        assertNotNull(analysisSolids.eightBall)
        assertTrue(analysisSolids.targetBalls.any { it.suit == BallSuit.SOLID })
        assertTrue(analysisSolids.targetBalls.any { it.suit == BallSuit.STRIPE })

        // Match B: Bot (LEFT) has STRIPES (6 remaining), Opponent (RIGHT) has SOLIDS (5 remaining)
        val stripesMatchBmp = renderSyntheticPoolFrame(
            botIsStripes = true,
            botRemaining = 6,
            oppRemaining = 5
        )
        val analysisStripes = PoolVisionAnalyzer.analyze(stripesMatchBmp, profile)
        assertEquals(AssignedSuit.STRIPES, analysisStripes.detectedSuit)
        assertEquals(6, analysisStripes.botRackCount)
        assertEquals(5, analysisStripes.oppRackCount)
        assertNotNull(analysisStripes.cueBall)
        assertNotNull(analysisStripes.eightBall)
        assertTrue(analysisStripes.targetBalls.any { it.suit == BallSuit.SOLID })
        assertTrue(analysisStripes.targetBalls.any { it.suit == BallSuit.STRIPE })
    }

    @Test
    fun `poolVisionAnalyzer locks table geometry once and auto-unlocks when screen rotates`() {
        val profile = TrainingProfileEntity(
            id = 2L,
            name = "8-Ball Pool Auto-Bot",
            targetPackage = "com.miniclip.eightballpool",
            botMode = "POOL_AIM",
            botSeatSide = "LEFT",
            preferredSuit = "AUTO"
        )

        // 1. First valid frame locks table geometry
        val landscapeBmp1 = renderSyntheticPoolFrame(
            botIsStripes = false,
            botRemaining = 5,
            oppRemaining = 5
        )
        val firstPass = PoolVisionAnalyzer.analyze(landscapeBmp1, profile)
        val locked = firstPass.detectedGeometry
        assertNotNull("Valid pool table frame should produce LockedTableGeometry", locked)
        assertEquals(1280, locked!!.captureW)
        assertEquals(640, locked.captureH)

        // 2. Second landscape frame with cue stick / edge glare keeps EXACT locked table bounds
        val landscapeBmp2 = renderSyntheticPoolFrame(
            botIsStripes = false,
            botRemaining = 5,
            oppRemaining = 5,
            drawCueStickAndGuideline = true
        )
        val lockedPass = PoolVisionAnalyzer.analyze(landscapeBmp2, profile, lockedTable = locked)
        assertTrue("Table must remain locked for matching orientation", lockedPass.tableLocked)
        assertEquals(locked.bounds, lockedPass.detectedTableBounds)
        assertEquals(locked.pockets, lockedPass.pockets)

        // 3. Rotated capture dimensions (640x1280) must discard old 1280x640 lock and re-detect new portrait lock
        val portraitBmp = renderSyntheticPoolFrame(
            w = 640,
            h = 1280,
            botIsStripes = false,
            botRemaining = 5,
            oppRemaining = 5
        )
        val rotatedPass = PoolVisionAnalyzer.analyze(portraitBmp, profile, lockedTable = locked)
        assertNotNull("New portrait frame should produce new portrait geometry lock", rotatedPass.detectedGeometry)
        assertEquals(640, rotatedPass.detectedGeometry?.captureW)
        assertEquals(1280, rotatedPass.detectedGeometry?.captureH)
        assertTrue(rotatedPass.detectedGeometry !== locked)

        // 4. Non-table frame on rotated resolution must not lock or reuse old landscape lock
        val blankPortrait = Bitmap.createBitmap(640, 1280, Bitmap.Config.ARGB_8888)
        val blankPass = PoolVisionAnalyzer.analyze(blankPortrait, profile, lockedTable = locked)
        assertFalse(blankPass.tableLocked)
    }

    @Test
    fun `poolVisionAnalyzer detects dimmed opponent rack and caps ghost balls under 16`() {
        val profile = TrainingProfileEntity(
            id = 3L,
            name = "8-Ball Pool Auto-Bot",
            targetPackage = "com.miniclip.eightballpool",
            botMode = "POOL_AIM",
            botSeatSide = "LEFT",
            preferredSuit = "AUTO"
        )

        val bmp = renderSyntheticPoolFrame(
            botIsStripes = false,
            botRemaining = 6,
            oppRemaining = 5,
            dimOpponentRack = true,
            drawCueStickAndGuideline = true
        )
        val analysis = PoolVisionAnalyzer.analyze(bmp, profile)
        assertEquals(AssignedSuit.SOLIDS, analysis.detectedSuit)
        assertTrue("Total balls + cue must never exceed 16", analysis.allTableTargets.size + 1 <= 16)
    }

    @Test
    fun `inspectAimFeedback distinguishes Red forbidden symbol from White guideline and solid Red ball`() {
        val w = 1280
        val h = 640
        val aspect = w.toFloat() / h.toFloat()
        val cueBall = DetectedBall(0.72f, 0.30f, DetectedBallKind.CUE, Color.WHITE)
        val yellowStripe = DetectedBall(0.71f, 0.72f, DetectedBallKind.TARGET, Color.YELLOW, BallSuit.STRIPE, "YELLOW")
        val redSolid = DetectedBall(0.55f, 0.34f, DetectedBallKind.TARGET, Color.RED, BallSuit.SOLID, "RED")
        val brPocket = PoolPocket("BR", 0.795f, 0.902f)
        val tmPocket = PoolPocket("TM", 0.495f, 0.222f)

        // Case 1: Red prohibition `⊘` icon rendered right above target ball -> FORBIDDEN_RED_MARKER
        val redIconPixels = IntArray(w * h) { Color.rgb(18, 108, 178) }
        drawTableBall(redIconPixels, w, h, cueBall.normX * w, cueBall.normY * h, Color.WHITE, isStripe = false)
        drawTableBall(redIconPixels, w, h, yellowStripe.normX * w, yellowStripe.normY * h, Color.rgb(240, 200, 10), isStripe = true)
        // Draw crisp crimson `⊘` emblem centered 1.4*R above yellowStripe
        val badgeCx = yellowStripe.normX * w
        val badgeCy = (yellowStripe.normY - 0.022f * 1.35f) * h
        fillCircle(redIconPixels, w, h, badgeCx, badgeCy, 14f, Color.rgb(230, 20, 20))
        fillCircle(redIconPixels, w, h, badgeCx, badgeCy, 8f, Color.rgb(18, 22, 32))
        // Diagonal red slash across the inner dark circle
        for (s in -8..8) {
            fillCircle(redIconPixels, w, h, badgeCx + s, badgeCy + s, 2.2f, Color.rgb(230, 20, 20))
        }
        val redIconBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(redIconPixels, 0, w, 0, 0, w, h)
        }
        val redRes = PoolVisionAnalyzer.inspectAimFeedback(redIconBmp, cueBall, yellowStripe, brPocket, aspect)
        assertEquals(AimFeedbackStatus.FORBIDDEN_RED_MARKER, redRes.status)

        // Case 2: Solid Red #3 ball with White Ghost Ring + White Guideline -> CONFIRMED_LEGAL_WHITE_GUIDE (NOT Red forbidden!)
        val whiteGuidePixels = IntArray(w * h) { Color.rgb(18, 108, 178) }
        drawTableBall(whiteGuidePixels, w, h, cueBall.normX * w, cueBall.normY * h, Color.WHITE, isStripe = false)
        drawTableBall(whiteGuidePixels, w, h, redSolid.normX * w, redSolid.normY * h, Color.rgb(225, 25, 25), isStripe = false)
        // Draw forward white guideline from redSolid toward tmPocket
        val tpDx = (tmPocket.normX - redSolid.normX) * aspect
        val tpDy = tmPocket.normY - redSolid.normY
        val tpLen = kotlin.math.hypot(tpDx, tpDy)
        val uX = tpDx / tpLen
        val uY = tpDy / tpLen
        for (i in 1..28) {
            val dist = 0.022f * (1.25f + i * 0.25f)
            val gx = (redSolid.normX + (uX * dist) / aspect) * w
            val gy = (redSolid.normY + uY * dist) * h
            fillCircle(whiteGuidePixels, w, h, gx, gy, 3.0f, Color.WHITE)
        }
        val whiteGuideBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(whiteGuidePixels, 0, w, 0, 0, w, h)
        }
        val whiteRes = PoolVisionAnalyzer.inspectAimFeedback(whiteGuideBmp, cueBall, redSolid, tmPocket, aspect)
        assertEquals(AimFeedbackStatus.CONFIRMED_LEGAL_WHITE_GUIDE, whiteRes.status)
    }

    private fun renderSyntheticPoolFrame(
        botIsStripes: Boolean,
        botRemaining: Int,
        oppRemaining: Int,
        w: Int = 1280,
        h: Int = 640,
        dimOpponentRack: Boolean = false,
        drawCueStickAndGuideline: Boolean = false
    ): Bitmap {
        val pixels = IntArray(w * h) { Color.rgb(18, 22, 32) }

        // Wood rails
        fillRect(pixels, w, h, 0.16f * w, 0.18f * h, 0.83f * w, 0.95f * h, Color.rgb(115, 58, 22))

        // Blue/Teal Pool Felt inside cushions
        fillRect(pixels, w, h, 0.192f * w, 0.222f * h, 0.798f * w, 0.908f * h, Color.rgb(18, 108, 178))

        // 6 Black corner & side pockets
        val pocketCoords = listOf(
            0.195f to 0.228f, 0.495f to 0.222f, 0.795f to 0.228f,
            0.195f to 0.902f, 0.495f to 0.908f, 0.795f to 0.902f
        )
        pocketCoords.forEach { (px, py) ->
            fillCircle(pixels, w, h, px * w, py * h, 18f, Color.rgb(10, 10, 12))
        }

        // Left & Right HUD racks at y = 0.125
        val hudY = 0.125f * h
        drawHudRack(pixels, w, h, hudY, 0.18f, 0.38f, botRemaining, botIsStripes, dimmed = false)
        drawHudRack(pixels, w, h, hudY, 0.62f, 0.82f, oppRemaining, !botIsStripes, dimmed = dimOpponentRack)

        // Optional cue stick + long white aiming line (tests that ghost balls <= 16!)
        if (drawCueStickAndGuideline) {
            for (step in 0..80) {
                val t = step / 80f
                val gx = (0.34f + t * 0.28f) * w
                val gy = (0.56f + t * 0.12f) * h
                fillCircle(pixels, w, h, gx, gy, 2.5f, Color.WHITE)
            }
            for (step in 0..50) {
                val t = step / 50f
                val sx = (0.20f + t * 0.11f) * w
                val sy = (0.50f + t * 0.05f) * h
                fillCircle(pixels, w, h, sx, sy, 3.5f, Color.rgb(215, 165, 105))
            }
        }

        // Cue ball (pure white)
        fillCircle(pixels, w, h, 0.34f * w, 0.56f * h, 12f, Color.WHITE)

        // 8-ball (pure black with tiny white center dot)
        fillCircle(pixels, w, h, 0.55f * w, 0.45f * h, 12f, Color.rgb(14, 14, 16))
        fillCircle(pixels, w, h, 0.55f * w, 0.45f * h, 3.2f, Color.WHITE)

        // Solid Red ball (full red + small white number dot)
        drawTableBall(pixels, w, h, 0.48f * w, 0.64f * h, Color.rgb(225, 28, 28), isStripe = false)

        // Striped Yellow ball (white ball with vivid horizontal colored band)
        drawTableBall(pixels, w, h, 0.64f * w, 0.62f * h, Color.rgb(240, 200, 10), isStripe = true)

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun drawHudRack(
        pixels: IntArray,
        w: Int,
        h: Int,
        hudY: Float,
        leftX: Float,
        rightX: Float,
        remainingCount: Int,
        isStripes: Boolean,
        dimmed: Boolean = false
    ) {
        val slotStart = leftX + 0.011f
        val slotEnd = rightX - 0.011f
        val colors = listOf(
            Color.rgb(245, 205, 15),
            Color.rgb(25, 95, 235),
            Color.rgb(225, 32, 32),
            Color.rgb(145, 45, 205),
            Color.rgb(245, 125, 20),
            Color.rgb(24, 175, 65),
            Color.rgb(155, 25, 35)
        )
        for (slot in 0 until 7) {
            val sx = (slotStart + (slot / 6f) * (slotEnd - slotStart)) * w
            if (slot < remainingCount) {
                val baseColor = colors[slot % colors.size]
                val drawColor = if (dimmed) dimColor(baseColor, 0.55f) else baseColor
                val capWhite = if (dimmed) dimColor(Color.WHITE, 0.55f) else Color.WHITE
                drawTableBall(
                    pixels, w, h, sx, hudY, drawColor,
                    isStripe = isStripes, radius = 13f, whiteColor = capWhite
                )
            } else {
                fillCircle(pixels, w, h, sx, hudY, 12f, Color.rgb(24, 28, 38))
            }
        }
    }

    private fun dimColor(color: Int, factor: Float): Int {
        return Color.rgb(
            (Color.red(color) * factor).toInt().coerceIn(0, 255),
            (Color.green(color) * factor).toInt().coerceIn(0, 255),
            (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        )
    }

    private fun drawTableBall(
        pixels: IntArray,
        w: Int,
        h: Int,
        cx: Float,
        cy: Float,
        color: Int,
        isStripe: Boolean,
        radius: Float = 12f,
        whiteColor: Int = Color.WHITE
    ) {
        if (isStripe) {
            fillCircle(pixels, w, h, cx, cy, radius, whiteColor)
            // Horizontal stripe band clipped inside the circle
            val x0 = (cx - radius).toInt().coerceIn(0, w - 1)
            val x1 = (cx + radius).toInt().coerceIn(0, w - 1)
            val y0 = (cy - radius * 0.36f).toInt().coerceIn(0, h - 1)
            val y1 = (cy + radius * 0.36f).toInt().coerceIn(0, h - 1)
            val rSq = radius * radius
            for (y in y0..y1) {
                val dy = y - cy
                for (x in x0..x1) {
                    val dx = x - cx
                    if (dx * dx + dy * dy <= rSq) {
                        pixels[y * w + x] = color
                    }
                }
            }
            fillCircle(pixels, w, h, cx, cy, radius * 0.30f, whiteColor)
        } else {
            fillCircle(pixels, w, h, cx, cy, radius, color)
            fillCircle(pixels, w, h, cx, cy, radius * 0.28f, whiteColor)
        }
    }

    private fun fillRect(
        pixels: IntArray,
        w: Int,
        h: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        color: Int
    ) {
        val x0 = left.toInt().coerceIn(0, w - 1)
        val x1 = right.toInt().coerceIn(0, w - 1)
        val y0 = top.toInt().coerceIn(0, h - 1)
        val y1 = bottom.toInt().coerceIn(0, h - 1)
        for (y in y0..y1) {
            val rowOff = y * w
            for (x in x0..x1) {
                pixels[rowOff + x] = color
            }
        }
    }

    private fun fillCircle(
        pixels: IntArray,
        w: Int,
        h: Int,
        cx: Float,
        cy: Float,
        radius: Float,
        color: Int
    ) {
        val x0 = (cx - radius).toInt().coerceIn(0, w - 1)
        val x1 = (cx + radius).toInt().coerceIn(0, w - 1)
        val y0 = (cy - radius).toInt().coerceIn(0, h - 1)
        val y1 = (cy + radius).toInt().coerceIn(0, h - 1)
        val rSq = radius * radius
        for (y in y0..y1) {
            val dy = y - cy
            val rowOff = y * w
            for (x in x0..x1) {
                val dx = x - cx
                if (dx * dx + dy * dy <= rSq) {
                    pixels[rowOff + x] = color
                }
            }
        }
    }
}
