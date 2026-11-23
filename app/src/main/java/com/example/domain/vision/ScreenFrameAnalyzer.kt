package com.example.domain.vision

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

data class ScreenPerception(
    val stateHash: String,
    val gridCells: List<Float>, // 36 normalized luminance cells (6x6)
    val hudDelta: Float,
    val fullDelta: Float,
    val summary: String
)

data class RewardAssessment(
    val reward: Float,
    val isTerminal: Boolean,
    val reason: String
)

object ScreenFrameAnalyzer {

    private val terminalKeywords = listOf(
        "game over", "try again", "play again", "restart", "you died",
        "defeat", "failed", "continue?", "watch ad", "respawn"
    )

    private val positiveKeywords = listOf(
        "victory", "level up", "new best", "high score", "combo",
        "bonus", "clear", "win", "stage"
    )

    fun analyzeBitmap(
        bitmap: Bitmap,
        previous: ScreenPerception?,
        hudTopY: Float,
        hudBottomY: Float
    ): ScreenPerception {
        val w = bitmap.width.coerceAtLeast(1)
        val h = bitmap.height.coerceAtLeast(1)
        val cols = 6
        val rows = 6

        val cells = ArrayList<Float>(cols * rows)
        val hashChars = StringBuilder(cols * rows)
        var hudSum = 0f
        var hudCount = 0

        for (r in 0 until rows) {
            val yStart = ((r.toFloat() / rows) * h).toInt().coerceIn(0, h - 1)
            val yEnd = (((r + 1).toFloat() / rows) * h).toInt().coerceIn(yStart + 1, h)
            val sampleY = (yStart + yEnd) / 2
            val normY = sampleY.toFloat() / h

            for (c in 0 until cols) {
                val xStart = ((c.toFloat() / cols) * w).toInt().coerceIn(0, w - 1)
                val xEnd = (((c + 1).toFloat() / cols) * w).toInt().coerceIn(xStart + 1, w)
                val sampleX = (xStart + xEnd) / 2

                // Sample 2x2 sub-points inside each grid cell for stable perceptual reading
                val p1 = bitmap.getPixel(sampleX, sampleY)
                val p2 = bitmap.getPixel((xStart + sampleX) / 2, (yStart + sampleY) / 2)
                val lum = (pixelLuminance(p1) + pixelLuminance(p2)) * 0.5f
                cells.add(lum)

                val bucket = (lum * 9.99f).toInt().coerceIn(0, 9)
                hashChars.append(bucket)

                if (normY in hudTopY..hudBottomY) {
                    hudSum += lum
                    hudCount++
                }
            }
        }

        val fullDelta = if (previous != null && previous.gridCells.size == cells.size) {
            var diff = 0f
            for (i in cells.indices) {
                diff += abs(cells[i] - previous.gridCells[i])
            }
            diff / cells.size
        } else {
            0f
        }

        val hudDelta = if (previous != null && previous.gridCells.size == cells.size) {
            // Compare top row cells representing HUD band
            var diff = 0f
            val topBandCells = 6
            for (i in 0 until topBandCells) {
                diff += abs(cells[i] - previous.gridCells[i])
            }
            diff / topBandCells
        } else {
            0f
        }

        val avgBright = if (cells.isNotEmpty()) cells.average().toFloat() else 0f
        val summary = "Lum ${(avgBright * 100).toInt()}% | Δ=${"%.2f".format(fullDelta)} | HUD=${"%.2f".format(hudDelta)}"

        return ScreenPerception(
            stateHash = "SCR_${hashChars}",
            gridCells = cells,
            hudDelta = hudDelta,
            fullDelta = fullDelta,
            summary = summary
        )
    }

    fun evaluateScreenTransition(
        previous: ScreenPerception?,
        current: ScreenPerception,
        uiTexts: List<String>,
        staleFramesCount: Int
    ): RewardAssessment {
        val joined = uiTexts.joinToString(" ").lowercase()

        if (terminalKeywords.any { joined.contains(it) }) {
            return RewardAssessment(
                reward = -3.0f,
                isTerminal = true,
                reason = "Terminal UI text detected"
            )
        }

        if (positiveKeywords.any { joined.contains(it) }) {
            return RewardAssessment(
                reward = +1.5f,
                isTerminal = false,
                reason = "Progress milestone detected"
            )
        }

        if (previous == null) {
            return RewardAssessment(0.05f, false, "Initial frame observed")
        }

        // Sudden huge screen flash/transition often indicates menu reset or round end
        if (current.fullDelta > 0.45f) {
            return RewardAssessment(
                reward = -1.2f,
                isTerminal = true,
                reason = "Major scene reset (Δ=${"%.2f".format(current.fullDelta)})"
            )
        }

        // Completely frozen screen for multiple consecutive steps suggests inactive/stuck state
        if (staleFramesCount >= 5 && current.fullDelta < 0.01f) {
            return RewardAssessment(
                reward = -0.35f,
                isTerminal = false,
                reason = "Static screen penalty"
            )
        }

        // Healthy playfield motion + subtle HUD update indicates active progress
        val hudBonus = if (current.hudDelta in 0.02f..0.28f) 0.45f else 0f
        val motionBonus = if (current.fullDelta in 0.02f..0.30f) 0.20f else -0.05f

        return RewardAssessment(
            reward = motionBonus + hudBonus,
            isTerminal = false,
            reason = if (hudBonus > 0f) "HUD & playfield active" else "Active playfield frame"
        )
    }

    private fun pixelLuminance(pixel: Int): Float {
        val r = Color.red(pixel) / 255f
        val g = Color.green(pixel) / 255f
        val b = Color.blue(pixel) / 255f
        return (0.299f * r + 0.587f * g + 0.114f * b).coerceIn(0f, 1f)
    }
}
