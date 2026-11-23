package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.TrainingProfileEntity
import com.example.domain.vision.AssignedSuit
import com.example.domain.vision.BallSuit
import com.example.domain.vision.PoolVisionAnalyzer
import org.junit.Assert.assertEquals
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

    private fun renderSyntheticPoolFrame(
        botIsStripes: Boolean,
        botRemaining: Int,
        oppRemaining: Int
    ): Bitmap {
        val w = 1280
        val h = 640
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
        drawHudRack(pixels, w, h, hudY, 0.18f, 0.38f, botRemaining, botIsStripes)
        drawHudRack(pixels, w, h, hudY, 0.62f, 0.82f, oppRemaining, !botIsStripes)

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
        isStripes: Boolean
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
                drawTableBall(pixels, w, h, sx, hudY, colors[slot % colors.size], isStripe = isStripes, radius = 13f)
            } else {
                fillCircle(pixels, w, h, sx, hudY, 12f, Color.rgb(24, 28, 38))
            }
        }
    }

    private fun drawTableBall(
        pixels: IntArray,
        w: Int,
        h: Int,
        cx: Float,
        cy: Float,
        color: Int,
        isStripe: Boolean,
        radius: Float = 12f
    ) {
        if (isStripe) {
            fillCircle(pixels, w, h, cx, cy, radius, Color.WHITE)
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
            fillCircle(pixels, w, h, cx, cy, radius * 0.30f, Color.WHITE)
        } else {
            fillCircle(pixels, w, h, cx, cy, radius, color)
            fillCircle(pixels, w, h, cx, cy, radius * 0.28f, Color.WHITE)
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
