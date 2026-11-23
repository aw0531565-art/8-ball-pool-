package com.example.domain.physics

import com.example.data.db.TrainingProfileEntity
import com.example.domain.vision.BallSuit
import com.example.domain.vision.DetectedBall
import com.example.domain.vision.DetectedBallKind
import com.example.domain.vision.PoolPocket
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class SimulatedBallPath(
    val ballIndex: Int,
    val role: String, // "CUE", "PRIMARY_TARGET", "COLLIDED_SECONDARY", "STATIC"
    val kind: DetectedBallKind,
    val suit: BallSuit,
    val colorTag: String,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val travelDist: Float,
    val wasCollided: Boolean,
    val potted: Boolean,
    val pottedPocketId: String?,
    val waypoints: List<Pair<Float, Float>>
)

data class BallCollisionEvent(
    val stepIndex: Int,
    val ballAIndex: Int,
    val ballBIndex: Int,
    val impactX: Float,
    val impactY: Float,
    val impactSpeed: Float
)

data class ShotSimulationPrediction(
    val cuePath: SimulatedBallPath,
    val targetPath: SimulatedBallPath?,
    val allBallPaths: List<SimulatedBallPath>,
    val collisions: List<BallCollisionEvent>,
    val cueScratchPredicted: Boolean,
    val targetPocketedId: String?,
    val eightBallPocketedId: String?,
    val secondaryPocketedIds: List<String>,
    val summaryText: String
)

/**
 * Continuous 2D multi-ball pool physics simulator operating in aspect-corrected isotropic
 * table coordinates. Predicts trajectories, ball-to-ball collisions, rail bounces, resting
 * locations, and pocket drops for the cue ball, primary target ball, and every secondary ball.
 */
object PoolPhysicsSimulator {

    const val BALL_RADIUS_ISO = 0.0175f
    private const val POCKET_CAPTURE_CORNER_ISO = 0.036f
    private const val POCKET_CAPTURE_SIDE_ISO = 0.032f

    private class SimBody(
        val index: Int,
        var role: String,
        val kind: DetectedBallKind,
        val suit: BallSuit,
        val colorTag: String,
        val startX: Float,
        val startY: Float,
        var xIso: Float,
        var yIso: Float,
        var vx: Float = 0f,
        var vy: Float = 0f,
        var travelDist: Float = 0f,
        var collided: Boolean = false,
        var potted: Boolean = false,
        var pocketId: String? = null,
        val waypoints: MutableList<Pair<Float, Float>> = mutableListOf()
    )

    fun simulateShot(
        cueBall: DetectedBall,
        allBalls: List<DetectedBall>,
        primaryTarget: DetectedBall?,
        aimPointX: Float,
        aimPointY: Float,
        powerFraction: Float,
        pockets: List<PoolPocket>,
        tableBounds: List<Float>,
        aspectRatio: Float,
        profile: TrainingProfileEntity? = null
    ): ShotSimulationPrediction {
        val powerScale = (profile?.learnedPowerScale ?: 1.0f).coerceIn(0.75f, 1.25f)
        val frictionCoef = (profile?.learnedFrictionCoef ?: 0.52f).coerceIn(0.25f, 0.85f)
        val restitution = (profile?.learnedRestitution ?: 0.93f).coerceIn(0.75f, 0.99f)
        val cushionBounce = (profile?.learnedCushionBounce ?: 0.80f).coerceIn(0.55f, 0.96f)

        return simulateShotWithParams(
            cueBall = cueBall,
            allBalls = allBalls,
            primaryTarget = primaryTarget,
            aimPointX = aimPointX,
            aimPointY = aimPointY,
            powerFraction = powerFraction,
            pockets = pockets,
            tableBounds = tableBounds,
            aspectRatio = aspectRatio,
            powerScale = powerScale,
            frictionCoef = frictionCoef,
            restitution = restitution,
            cushionBounce = cushionBounce
        )
    }

    fun simulateShotWithParams(
        cueBall: DetectedBall,
        allBalls: List<DetectedBall>,
        primaryTarget: DetectedBall?,
        aimPointX: Float,
        aimPointY: Float,
        powerFraction: Float,
        pockets: List<PoolPocket>,
        tableBounds: List<Float>,
        aspectRatio: Float,
        powerScale: Float = 1.0f,
        frictionCoef: Float = 0.52f,
        restitution: Float = 0.93f,
        cushionBounce: Float = 0.80f
    ): ShotSimulationPrediction {
        val safeAspect = aspectRatio.coerceIn(0.45f, 2.50f)
        val fLeft = tableBounds.getOrElse(0) { 0.18f }
        val fTop = tableBounds.getOrElse(1) { 0.21f }
        val fRight = tableBounds.getOrElse(2) { 0.80f }
        val fBottom = tableBounds.getOrElse(3) { 0.91f }

        val minXIso = fLeft * safeAspect + BALL_RADIUS_ISO * 0.45f
        val maxXIso = fRight * safeAspect - BALL_RADIUS_ISO * 0.45f
        val minYIso = fTop + BALL_RADIUS_ISO * 0.45f
        val maxYIso = fBottom - BALL_RADIUS_ISO * 0.45f

        val bodies = mutableListOf<SimBody>()

        val aimDxIso = (aimPointX - cueBall.normX) * safeAspect
        val aimDyIso = aimPointY - cueBall.normY
        val aimLen = hypot(aimDxIso, aimDyIso).coerceAtLeast(1e-4f)
        val dirX = aimDxIso / aimLen
        val dirY = aimDyIso / aimLen

        // Calibrated initial velocity from powerFraction * powerScale (matches real travel ~ 0.15..1.65 iso units)
        val clampedPower = powerFraction.coerceIn(0.15f, 0.98f)
        val launchSpeed = (0.35f + clampedPower * 1.45f) * powerScale

        val cueBody = SimBody(
            index = 0,
            role = "CUE",
            kind = DetectedBallKind.CUE,
            suit = BallSuit.UNKNOWN,
            colorTag = cueBall.colorTag,
            startX = cueBall.normX,
            startY = cueBall.normY,
            xIso = cueBall.normX * safeAspect,
            yIso = cueBall.normY,
            vx = dirX * launchSpeed,
            vy = dirY * launchSpeed,
            collided = true
        ).apply {
            waypoints.add(cueBall.normX to cueBall.normY)
        }
        bodies.add(cueBody)

        var targetBodyIndex = -1
        allBalls.forEach { ball ->
            // Skip duplicate cue ball if present
            if (hypot((ball.normX - cueBall.normX) * safeAspect, ball.normY - cueBall.normY) < BALL_RADIUS_ISO * 0.9f) {
                return@forEach
            }
            val isPrimary = primaryTarget != null &&
                hypot((ball.normX - primaryTarget.normX) * safeAspect, ball.normY - primaryTarget.normY) < BALL_RADIUS_ISO * 1.1f
            val idx = bodies.size
            if (isPrimary && targetBodyIndex < 0) {
                targetBodyIndex = idx
            }
            val role = when {
                isPrimary -> "PRIMARY_TARGET"
                ball.kind == DetectedBallKind.EIGHT_BALL -> "EIGHT_BALL"
                else -> "STATIC"
            }
            bodies.add(
                SimBody(
                    index = idx,
                    role = role,
                    kind = ball.kind,
                    suit = ball.suit,
                    colorTag = ball.colorTag,
                    startX = ball.normX,
                    startY = ball.normY,
                    xIso = ball.normX * safeAspect,
                    yIso = ball.normY
                ).apply {
                    waypoints.add(ball.normX to ball.normY)
                }
            )
        }

        val pocketIso = pockets.map { p ->
            Triple(p.id, p.normX * safeAspect, p.normY)
        }

        val collisions = mutableListOf<BallCollisionEvent>()
        val dt = 0.008f
        val maxSteps = 420
        // Friction deceleration mapped from learnedFrictionCoef (0.28..0.78 -> decel 0.55..1.75 iso/s^2)
        val frictionDecel = 0.25f + frictionCoef * 1.90f
        val minSpeed = 0.015f
        val minDist = BALL_RADIUS_ISO * 2.0f

        for (step in 0 until maxSteps) {
            var anyMoving = false

            // 1. Advance positions & check pocket captures + cushion bounces
            for (b in bodies) {
                if (b.potted) continue
                val speed = hypot(b.vx, b.vy)
                if (speed < minSpeed) {
                    b.vx = 0f
                    b.vy = 0f
                    continue
                }
                anyMoving = true

                val dx = b.vx * dt
                val dy = b.vy * dt
                b.xIso += dx
                b.yIso += dy
                b.travelDist += hypot(dx, dy)

                // Check pocket capture
                for ((pid, pxIso, pyIso) in pocketIso) {
                    val capR = if (pid == "TM" || pid == "BM") POCKET_CAPTURE_SIDE_ISO else POCKET_CAPTURE_CORNER_ISO
                    if (hypot(b.xIso - pxIso, b.yIso - pyIso) <= capR) {
                        b.potted = true
                        b.pocketId = pid
                        b.xIso = pxIso
                        b.yIso = pyIso
                        b.vx = 0f
                        b.vy = 0f
                        b.waypoints.add((pxIso / safeAspect) to pyIso)
                        break
                    }
                }
                if (b.potted) continue

                // Cushion bounces (skip bounce if near pocket mouth so balls can drop cleanly)
                val nearAnyPocket = pocketIso.any { (_, px, py) ->
                    hypot(b.xIso - px, b.yIso - py) < POCKET_CAPTURE_CORNER_ISO * 1.45f
                }
                if (!nearAnyPocket) {
                    var bounced = false
                    if (b.xIso < minXIso) {
                        b.xIso = minXIso
                        b.vx = abs(b.vx) * cushionBounce
                        b.vy *= 0.94f
                        bounced = true
                    } else if (b.xIso > maxXIso) {
                        b.xIso = maxXIso
                        b.vx = -abs(b.vx) * cushionBounce
                        b.vy *= 0.94f
                        bounced = true
                    }
                    if (b.yIso < minYIso) {
                        b.yIso = minYIso
                        b.vy = abs(b.vy) * cushionBounce
                        b.vx *= 0.94f
                        bounced = true
                    } else if (b.yIso > maxYIso) {
                        b.yIso = maxYIso
                        b.vy = -abs(b.vy) * cushionBounce
                        b.vx *= 0.94f
                        bounced = true
                    }
                    if (bounced) {
                        b.waypoints.add((b.xIso / safeAspect) to b.yIso)
                    }
                }

                // Apply felt friction deceleration
                val newSpeed = (speed - frictionDecel * dt).coerceAtLeast(0f)
                if (newSpeed <= minSpeed) {
                    b.vx = 0f
                    b.vy = 0f
                } else {
                    val ratio = newSpeed / speed
                    b.vx *= ratio
                    b.vy *= ratio
                }
            }

            if (!anyMoving) break

            // 2. Pairwise elastic/inelastic ball-to-ball collision resolution
            for (i in 0 until bodies.size) {
                val a = bodies[i]
                if (a.potted) continue
                for (j in i + 1 until bodies.size) {
                    val b = bodies[j]
                    if (b.potted) continue

                    val dx = b.xIso - a.xIso
                    val dy = b.yIso - a.yIso
                    val dist = hypot(dx, dy)
                    if (dist < minDist && dist > 1e-6f) {
                        val nx = dx / dist
                        val ny = dy / dist
                        val overlap = minDist - dist
                        a.xIso -= nx * overlap * 0.5f
                        a.yIso -= ny * overlap * 0.5f
                        b.xIso += nx * overlap * 0.5f
                        b.yIso += ny * overlap * 0.5f

                        val rvx = b.vx - a.vx
                        val rvy = b.vy - a.vy
                        val velAlongNormal = rvx * nx + rvy * ny
                        if (velAlongNormal < 0f) {
                            val impulse = -(1f + restitution) * velAlongNormal * 0.5f
                            a.vx -= impulse * nx
                            a.vy -= impulse * ny
                            b.vx += impulse * nx
                            b.vy += impulse * ny

                            a.collided = true
                            b.collided = true
                            if (b.role == "STATIC") b.role = "COLLIDED_SECONDARY"
                            if (a.role == "STATIC") a.role = "COLLIDED_SECONDARY"

                            a.waypoints.add((a.xIso / safeAspect) to a.yIso)
                            b.waypoints.add((b.xIso / safeAspect) to b.yIso)

                            collisions.add(
                                BallCollisionEvent(
                                    stepIndex = step,
                                    ballAIndex = a.index,
                                    ballBIndex = b.index,
                                    impactX = ((a.xIso + b.xIso) * 0.5f) / safeAspect,
                                    impactY = (a.yIso + b.yIso) * 0.5f,
                                    impactSpeed = abs(velAlongNormal)
                                )
                            )
                        }
                    }
                }
            }

            // Record periodic waypoints for smooth curve visualization
            if (step % 10 == 0) {
                for (b in bodies) {
                    if (!b.potted && hypot(b.vx, b.vy) >= minSpeed) {
                        b.waypoints.add((b.xIso / safeAspect) to b.yIso)
                    }
                }
            }
        }

        // Finalize endpoints
        val allPaths = bodies.map { b ->
            val endX = (b.xIso / safeAspect).coerceIn(0f, 1f)
            val endY = b.yIso.coerceIn(0f, 1f)
            if (b.waypoints.lastOrNull()?.let { hypot(it.first - endX, it.second - endY) > 0.002f } != false) {
                b.waypoints.add(endX to endY)
            }
            SimulatedBallPath(
                ballIndex = b.index,
                role = b.role,
                kind = b.kind,
                suit = b.suit,
                colorTag = b.colorTag,
                startX = b.startX,
                startY = b.startY,
                endX = endX,
                endY = endY,
                travelDist = b.travelDist,
                wasCollided = b.collided,
                potted = b.potted,
                pottedPocketId = b.pocketId,
                waypoints = b.waypoints.toList()
            )
        }

        val cuePath = allPaths.first()
        val targetPath = if (targetBodyIndex >= 0) allPaths.getOrNull(targetBodyIndex) else null
        val eightPath = allPaths.firstOrNull { it.kind == DetectedBallKind.EIGHT_BALL && it.ballIndex != targetBodyIndex }
        val secondaryPocketed = allPaths.filter {
            it.ballIndex != 0 && it.ballIndex != targetBodyIndex && it.potted && it.pottedPocketId != null
        }.mapNotNull { it.pottedPocketId }

        val summary = buildString {
            append("Cue->${cuePath.pottedPocketId ?: "(${"%.2f".format(cuePath.endX)},${"%.2f".format(cuePath.endY)})"}")
            if (targetPath != null) {
                append(" | Tgt->${targetPath.pottedPocketId ?: "(${"%.2f".format(targetPath.endX)},${"%.2f".format(targetPath.endY)})"}")
            }
            val secMoved = allPaths.count { it.ballIndex != 0 && it.ballIndex != targetBodyIndex && it.wasCollided }
            if (secMoved > 0) {
                append(" | Collided:$secMoved")
                if (secondaryPocketed.isNotEmpty()) {
                    append(" (Pot:${secondaryPocketed.joinToString(",")})")
                }
            }
        }

        return ShotSimulationPrediction(
            cuePath = cuePath,
            targetPath = targetPath,
            allBallPaths = allPaths,
            collisions = collisions,
            cueScratchPredicted = cuePath.potted,
            targetPocketedId = targetPath?.pottedPocketId,
            eightBallPocketedId = eightPath?.pottedPocketId,
            secondaryPocketedIds = secondaryPocketed,
            summaryText = summary
        )
    }
}
