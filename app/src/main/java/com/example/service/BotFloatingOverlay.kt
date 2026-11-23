package com.example.service

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * Compact expandable floating bot controller attached via TYPE_ACCESSIBILITY_OVERLAY
 * (requires zero SYSTEM_ALERT_WINDOW permission). Starts as a small draggable 52dp orb
 * showing Turn/Force status and expands on tap into a control card with Turn/Seat Lock,
 * Suit Lock (Auto/Solids/Stripes), Shot Force meter + presets, and ON/Shoot/OFF buttons.
 */
class BotFloatingOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var rootContainer: FrameLayout? = null
    private var miniOrbView: LinearLayout? = null
    private var expandedCardView: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isAttached = false
    private var isExpanded = false

    // Mini orb views
    private var miniForceText: TextView? = null
    private var miniStatusDot: TextView? = null
    private var miniBgDrawable: GradientDrawable? = null

    // Expanded panel views
    private var headerStatusText: TextView? = null
    private var turnSuitStatusText: TextView? = null
    private var visionStatusText: TextView? = null
    private var forcePercentText: TextView? = null
    private var forceProgressBar: ProgressBar? = null
    private var lastForceDetailText: TextView? = null
    private var onOffButton: TextView? = null

    private var seatLeftBtn: TextView? = null
    private var seatRightBtn: TextView? = null
    private var seatWatchBtn: TextView? = null

    private var suitAutoBtn: TextView? = null
    private var suitSolidsBtn: TextView? = null
    private var suitStripesBtn: TextView? = null

    private var presetAutoBtn: TextView? = null
    private var presetLowBtn: TextView? = null
    private var presetMedBtn: TextView? = null
    private var presetHighBtn: TextView? = null

    private var currentTelemetry: LiveBotTelemetry = BotServiceBridge.telemetry.value

    fun showOrUpdate(telemetry: LiveBotTelemetry) {
        currentTelemetry = telemetry
        mainHandler.post {
            if (!telemetry.isScreenCaptureActive) {
                hideInternal()
                return@post
            }
            if (!isAttached) {
                attachInternal()
            }
            bindTelemetry(telemetry)
        }
    }

    fun hide() {
        mainHandler.post {
            hideInternal()
        }
    }

    private fun attachInternal() {
        if (isAttached) return
        val container = FrameLayout(context)

        val orb = buildMiniOrb()
        val card = buildExpandedCard()
        card.visibility = if (isExpanded) View.VISIBLE else View.GONE
        orb.visibility = if (isExpanded) View.GONE else View.VISIBLE

        container.addView(orb)
        container.addView(card)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(76)
        }

        try {
            windowManager.addView(container, params)
            rootContainer = container
            miniOrbView = orb
            expandedCardView = card
            layoutParams = params
            isAttached = true
        } catch (_: Exception) {
        }
    }

    private fun hideInternal() {
        if (!isAttached) return
        try {
            rootContainer?.let { windowManager.removeViewImmediate(it) }
        } catch (_: Exception) {
        }
        rootContainer = null
        miniOrbView = null
        expandedCardView = null
        layoutParams = null
        isAttached = false
        isExpanded = false
    }

    private fun toggleExpanded() {
        isExpanded = !isExpanded
        miniOrbView?.visibility = if (isExpanded) View.GONE else View.VISIBLE
        expandedCardView?.visibility = if (isExpanded) View.VISIBLE else View.GONE
        clampParamsInsideScreen()
    }

    private fun buildMiniOrb(): LinearLayout {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#E60B1220"))
            setStroke(dp(2), Color.parseColor("#10B981"))
        }
        miniBgDrawable = bg

        val orb = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = bg
            elevation = dp(6).toFloat()
            val sizePx = dp(52)
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        val dot = TextView(context).apply {
            text = "BOT"
            setTextColor(Color.parseColor("#10B981"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        miniStatusDot = dot

        val forceTxt = TextView(context).apply {
            text = "AUTO"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        miniForceText = forceTxt

        orb.addView(dot)
        orb.addView(forceTxt)
        attachDragAndTapListener(orb, onTap = { toggleExpanded() })
        return orb
    }

    private fun buildExpandedCard(): LinearLayout {
        val cardBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(Color.parseColor("#EE0B1220"))
            setStroke(dp(1) + 1, Color.parseColor("#38BDF8"))
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg
            elevation = dp(8).toFloat()
            layoutParams = FrameLayout.LayoutParams(dp(264), FrameLayout.LayoutParams.WRAP_CONTENT)
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }

        // Top Header (Draggable + Minimize)
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }

        val titleTxt = TextView(context).apply {
            text = "NeuroBot • ON"
            setTextColor(Color.parseColor("#10B981"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerStatusText = titleTxt

        val minimizeBtn = makePillButton(
            label = "—",
            bgColor = "#1E293B",
            textColor = "#E2E8F0",
            padH = 10,
            padV = 4
        ).apply {
            setOnClickListener { toggleExpanded() }
        }

        headerRow.addView(titleTxt)
        headerRow.addView(minimizeBtn)
        attachDragAndTapListener(headerRow, onTap = { toggleExpanded() })
        card.addView(headerRow)

        // Turn & Suit Indicator Badge
        val turnSuitTxt = TextView(context).apply {
            text = "WAIT • Suit: OPEN | Human: 0"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(3))
        }
        turnSuitStatusText = turnSuitTxt
        card.addView(turnSuitTxt)

        // Vision summary line
        val visionTxt = TextView(context).apply {
            text = "Cue: Scanning... | Pkt: -"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(0, 0, 0, dp(6))
        }
        visionStatusText = visionTxt
        card.addView(visionTxt)

        // Seat Selection Row: P1 Left | P2 Right | Watch Only
        val seatRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(5))
        }
        val sLeft = makeSeatChip("Bot P1(L)", "LEFT").also { seatLeftBtn = it }
        val sRight = makeSeatChip("Bot P2(R)", "RIGHT").also { seatRightBtn = it }
        val sWatch = makeSeatChip("Watch", "WATCH").also { seatWatchBtn = it }
        seatRow.addView(sLeft)
        seatRow.addView(spaceX(4))
        seatRow.addView(sRight)
        seatRow.addView(spaceX(4))
        seatRow.addView(sWatch)
        card.addView(seatRow)

        // Suit Selection Row: Auto | Solid | Stripe
        val suitRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(7))
        }
        val suAuto = makeSuitChip("Suit:Auto", null).also { suitAutoBtn = it }
        val suSolid = makeSuitChip("Solids", "SOLIDS").also { suitSolidsBtn = it }
        val suStripe = makeSuitChip("Stripes", "STRIPES").also { suitStripesBtn = it }
        suitRow.addView(suAuto)
        suitRow.addView(spaceX(4))
        suitRow.addView(suSolid)
        suitRow.addView(spaceX(4))
        suitRow.addView(suStripe)
        card.addView(suitRow)

        // Force / Power Meter Section
        val forceHeaderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val forceTitle = TextView(context).apply {
            text = "Shot Force (فورس)"
            setTextColor(Color.parseColor("#F59E0B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val forceVal = TextView(context).apply {
            text = "0%"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
        }
        forcePercentText = forceVal
        forceHeaderRow.addView(forceTitle)
        forceHeaderRow.addView(forceVal)
        card.addView(forceHeaderRow)

        val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 60
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(7)
            ).apply {
                topMargin = dp(3)
                bottomMargin = dp(3)
            }
        }
        forceProgressBar = bar
        card.addView(bar)

        val lastForceTxt = TextView(context).apply {
            text = "Last: --% • Cut: --° • Fric: 0.52"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(0, 0, 0, dp(7))
        }
        lastForceDetailText = lastForceTxt
        card.addView(lastForceTxt)

        // Force Presets Row
        val presetRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        val pAuto = makePresetChip("Auto", null).also { presetAutoBtn = it }
        val pLow = makePresetChip("35%", 0.35f).also { presetLowBtn = it }
        val pMed = makePresetChip("65%", 0.65f).also { presetMedBtn = it }
        val pHigh = makePresetChip("90%", 0.90f).also { presetHighBtn = it }
        presetRow.addView(pAuto)
        presetRow.addView(spaceX(4))
        presetRow.addView(pLow)
        presetRow.addView(spaceX(4))
        presetRow.addView(pMed)
        presetRow.addView(spaceX(4))
        presetRow.addView(pHigh)
        card.addView(presetRow)

        // ON/OFF, Shoot Now, Stop Controls Row
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val toggleBtn = makePillButton(
            label = "Pause",
            bgColor = "#F59E0B",
            textColor = "#070B14",
            padH = 10,
            padV = 7
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
            setOnClickListener {
                sendServiceAction(ScreenCaptureService.ACTION_TOGGLE_PAUSE)
            }
        }
        onOffButton = toggleBtn

        val shootBtn = makePillButton(
            label = "Shoot",
            bgColor = "#06B6D4",
            textColor = "#070B14",
            padH = 8,
            padV = 7
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                sendServiceAction(ScreenCaptureService.ACTION_SHOOT_NOW)
            }
        }

        val stopBtn = makePillButton(
            label = "OFF",
            bgColor = "#EF4444",
            textColor = "#FFFFFF",
            padH = 8,
            padV = 7
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.85f)
            setOnClickListener {
                sendServiceAction(ScreenCaptureService.ACTION_STOP)
            }
        }

        btnRow.addView(toggleBtn)
        btnRow.addView(spaceX(6))
        btnRow.addView(shootBtn)
        btnRow.addView(spaceX(6))
        btnRow.addView(stopBtn)
        card.addView(btnRow)

        return card
    }

    private fun makeSeatChip(label: String, seat: String): TextView {
        return makePillButton(
            label = label,
            bgColor = "#1E293B",
            textColor = "#CBD5E1",
            padH = 5,
            padV = 4
        ).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                BotServiceBridge.setManualSeatSideOverride(seat)
            }
        }
    }

    private fun makeSuitChip(label: String, suit: String?): TextView {
        return makePillButton(
            label = label,
            bgColor = "#1E293B",
            textColor = "#CBD5E1",
            padH = 5,
            padV = 4
        ).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                BotServiceBridge.setManualSuitOverride(suit)
            }
        }
    }

    private fun makePresetChip(label: String, forceValue: Float?): TextView {
        return makePillButton(
            label = label,
            bgColor = "#1E293B",
            textColor = "#CBD5E1",
            padH = 6,
            padV = 5
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                BotServiceBridge.setManualPowerOverride(forceValue)
            }
        }
    }

    private fun makePillButton(
        label: String,
        bgColor: String,
        textColor: String,
        padH: Int,
        padV: Int
    ): TextView {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(Color.parseColor(bgColor))
        }
        return TextView(context).apply {
            text = label
            setTextColor(Color.parseColor(textColor))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = bg
            setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
        }
    }

    private fun bindTelemetry(t: LiveBotTelemetry) {
        val isPaused = t.isLiveBotPaused
        val seat = t.manualSeatSideOverride ?: "LEFT"
        val isWatchingHuman = t.turnOwnerLabel.contains("HUMAN", ignoreCase = true) || seat == "WATCH"

        val accentColor = when {
            isPaused -> Color.parseColor("#F59E0B")
            t.isMyTurnDetected -> Color.parseColor("#10B981")
            isWatchingHuman -> Color.parseColor("#38BDF8")
            else -> Color.parseColor("#10B981")
        }

        miniBgDrawable?.setStroke(dp(2), accentColor)
        miniStatusDot?.text = when {
            isPaused -> "OFF"
            t.isMyTurnDetected -> "BOT"
            isWatchingHuman -> "OBS"
            else -> "ON"
        }
        miniStatusDot?.setTextColor(accentColor)

        val displayForce = t.manualPowerOverride ?: t.plannedPowerFraction.takeIf { it > 0.01f } ?: t.lastShotPowerFraction
        val forcePct = (displayForce * 100f).toInt().coerceIn(0, 100)
        miniForceText?.text = if (forcePct > 0) "${forcePct}%" else "AUTO"

        headerStatusText?.text = if (isPaused) "NeuroBot • PAUSED" else "NeuroBot • LIVE"
        headerStatusText?.setTextColor(accentColor)

        val lockTag = if (t.isTableLocked) "LOCK✓" else "SCAN"
        turnSuitStatusText?.text = "${t.turnOwnerLabel} • $lockTag • Suit:${t.effectiveSuitLabel}"
        turnSuitStatusText?.setTextColor(
            when {
                t.isMyTurnDetected -> Color.parseColor("#10B981")
                isWatchingHuman -> Color.parseColor("#38BDF8")
                else -> Color.parseColor("#F59E0B")
            }
        )

        val cueMark = if (t.isCueBallDetected) "Cue✓" else "Cue✗"
        val pocketStr = t.plannedPocketId ?: "-"
        val aimTag = if (t.aimFeedbackLabel != "Idle") " • ${t.aimFeedbackLabel}" else ""
        visionStatusText?.text = "${t.captureSizeLabel} • $cueMark | Tgt:${t.detectedTargetBallsCount} | Pkt:$pocketStr$aimTag\n${t.simulationSummary}"

        val modeSuffix = if (t.manualPowerOverride != null) "Manual" else "AI Auto"
        forcePercentText?.text = "${forcePct}% ($modeSuffix)"
        forceProgressBar?.progress = forcePct

        val lastPct = (t.lastShotPowerFraction * 100f).toInt()
        val outcomeBadge = when {
            t.lastOutcomeLabel.contains("SCRATCH") -> "⚠ SCRATCH"
            t.lastOutcomeLabel.contains("MISSED") -> "✗ MISSED"
            t.lastOutcomeLabel.contains("POTTED") -> "✓ POTTED"
            else -> t.lastOutcomeLabel
        }
        lastForceDetailText?.text =
            "Last:${if (lastPct > 0) "$lastPct%" else "--"} • $outcomeBadge • Balls:${t.ballsTrackedCount} Err:${"%.3f".format(t.avgPredictionError)}"

        onOffButton?.text = if (isPaused) "Turn ON" else "Pause"
        val btnBg = onOffButton?.background as? GradientDrawable
        btnBg?.setColor(Color.parseColor(if (isPaused) "#10B981" else "#F59E0B"))

        highlightChip(seatLeftBtn, seat == "LEFT", "#10B981")
        highlightChip(seatRightBtn, seat == "RIGHT", "#10B981")
        highlightChip(seatWatchBtn, seat == "WATCH", "#38BDF8")

        val suitOverride = t.manualSuitOverride
        highlightChip(suitAutoBtn, suitOverride == null || suitOverride == "AUTO", "#0EA5E9")
        highlightChip(suitSolidsBtn, suitOverride == "SOLIDS", "#0EA5E9")
        highlightChip(suitStripesBtn, suitOverride == "STRIPES", "#0EA5E9")

        highlightChip(presetAutoBtn, t.manualPowerOverride == null, "#0EA5E9")
        highlightChip(presetLowBtn, t.manualPowerOverride != null && abs(t.manualPowerOverride - 0.35f) < 0.05f, "#0EA5E9")
        highlightChip(presetMedBtn, t.manualPowerOverride != null && abs(t.manualPowerOverride - 0.65f) < 0.05f, "#0EA5E9")
        highlightChip(presetHighBtn, t.manualPowerOverride != null && abs(t.manualPowerOverride - 0.90f) < 0.05f, "#0EA5E9")
    }

    private fun highlightChip(btn: TextView?, selected: Boolean, selectedColor: String) {
        val bg = btn?.background as? GradientDrawable ?: return
        bg.setColor(Color.parseColor(if (selected) selectedColor else "#1E293B"))
        btn.setTextColor(Color.parseColor(if (selected) "#070B14" else "#CBD5E1"))
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(context, ScreenCaptureService::class.java).setAction(action)
        ContextCompat.startForegroundService(context, intent)
    }

    private fun attachDragAndTapListener(view: View, onTap: () -> Unit) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        view.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) {
                        moved = true
                    }
                    params.x = initialX + dx
                    params.y = initialY + dy
                    clampParamsInsideScreen()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        onTap()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun clampParamsInsideScreen() {
        val params = layoutParams ?: return
        val container = rootContainer ?: return
        val dm = context.resources.displayMetrics
        val maxW = (dm.widthPixels - dp(56)).coerceAtLeast(100)
        val maxH = (dm.heightPixels - dp(56)).coerceAtLeast(100)
        params.x = params.x.coerceIn(0, maxW)
        params.y = params.y.coerceIn(0, maxH)
        try {
            windowManager.updateViewLayout(container, params)
        } catch (_: Exception) {
        }
    }

    private fun spaceX(widthDp: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(widthDp), 1)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
}
