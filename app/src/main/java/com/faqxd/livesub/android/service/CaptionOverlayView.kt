package com.faqxd.livesub.android.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import com.faqxd.livesub.android.R
import com.faqxd.livesub.android.data.AppSettings
import kotlin.math.abs

class CaptionOverlayView(
    private val context: Context,
    private val settings: AppSettings,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        fun onToggleClicked()
        fun onRestartClicked()
        fun onLogClicked()
        fun onAudioSourceClicked()
        fun onCloseClicked()
        fun onSettingsClicked()
    }

    private var outCommitted = ""
    private var outDraft = ""
    private var inCommitted = ""
    private var inDraft = ""
    private var statusText = ""
    private var statusKind: StatusKind = StatusKind.IDLE
    private var outputRenderTarget = ""
    private var inputRenderTarget = ""
    private var outputAnimation: Runnable? = null
    private var inputAnimation: Runnable? = null

    private enum class StatusKind { IDLE, CONNECTING, CONNECTED, ERROR }

    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var rootView: View
    private lateinit var overlayRoot: View
    private lateinit var collapsedBall: TextView
    private lateinit var headerRow: View
    private lateinit var statusDot: View
    private lateinit var statusTextView: TextView
    private lateinit var langBadge: TextView
    private lateinit var lockBtn: Button
    private lateinit var minimizeBtn: Button
    private lateinit var outputView: TextView
    private lateinit var divider: View
    private lateinit var inputView: TextView
    private lateinit var controlsRow: View
    private lateinit var toolsRow: View
    private lateinit var toggleBtn: Button
    private lateinit var restartBtn: Button
    private lateinit var logBtn: Button
    private lateinit var closeBtn: Button
    private lateinit var settingsBtn: ImageButton
    private lateinit var fontMinusBtn: Button
    private lateinit var fontPlusBtn: Button
    private lateinit var sourceBtn: Button
    private lateinit var resizeHandle: TextView

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var layoutParams: WindowManager.LayoutParams = buildLayoutParams()
    private var attached = false
    private var initialized = false
    private var collapsed = false
    private var locked = settings.overlayLocked
    private var chromeVisible = true
    private var hideChromeRunnable: Runnable? = null

    val isAttached: Boolean get() = attached

    fun init() {
        if (initialized) return
        rootView = View.inflate(context, R.layout.overlay_caption, null)
        overlayRoot = rootView.findViewById(R.id.overlayRoot)
        collapsedBall = rootView.findViewById(R.id.overlayBall)
        headerRow = rootView.findViewById(R.id.overlayHeaderRow)
        statusDot = rootView.findViewById(R.id.overlayStatusDot)
        statusTextView = rootView.findViewById(R.id.overlayStatusText)
        langBadge = rootView.findViewById(R.id.overlayLangBadge)
        lockBtn = rootView.findViewById(R.id.overlayLockBtn)
        minimizeBtn = rootView.findViewById(R.id.overlayMinimizeBtn)
        outputView = rootView.findViewById(R.id.overlayOutput)
        divider = rootView.findViewById(R.id.overlayDivider)
        inputView = rootView.findViewById(R.id.overlayInput)
        controlsRow = rootView.findViewById(R.id.overlayControlsRow)
        toolsRow = rootView.findViewById(R.id.overlayToolsRow)
        toggleBtn = rootView.findViewById(R.id.overlayToggleBtn)
        restartBtn = rootView.findViewById(R.id.overlayRestartBtn)
        logBtn = rootView.findViewById(R.id.overlayLogBtn)
        closeBtn = rootView.findViewById(R.id.overlayCloseBtn)
        settingsBtn = rootView.findViewById(R.id.overlaySettingsBtn)
        fontMinusBtn = rootView.findViewById(R.id.overlayFontMinusBtn)
        fontPlusBtn = rootView.findViewById(R.id.overlayFontPlusBtn)
        sourceBtn = rootView.findViewById(R.id.overlaySourceBtn)
        resizeHandle = rootView.findViewById(R.id.overlayResizeHandle)

        toggleBtn.setOnClickListener {
            showChromeTemporarily()
            callbacks.onToggleClicked()
        }
        lockBtn.setOnClickListener {
            toggleLocked()
            showChromeTemporarily()
        }
        minimizeBtn.setOnClickListener {
            toggleCollapsed()
            showChromeTemporarily()
        }
        restartBtn.setOnClickListener {
            showChromeTemporarily()
            callbacks.onRestartClicked()
        }
        logBtn.setOnClickListener {
            showChromeTemporarily()
            callbacks.onLogClicked()
        }
        closeBtn.setOnClickListener { callbacks.onCloseClicked() }
        settingsBtn.setOnClickListener {
            showChromeTemporarily()
            callbacks.onSettingsClicked()
        }
        fontMinusBtn.setOnClickListener {
            adjustFontSize(-2)
            showChromeTemporarily()
        }
        fontPlusBtn.setOnClickListener {
            adjustFontSize(2)
            showChromeTemporarily()
        }
        sourceBtn.setOnClickListener {
            showChromeTemporarily()
            callbacks.onAudioSourceClicked()
        }

        installDragHandler()
        installResizeHandler()
        initialized = true
        applyStyle()
        applyLockState()
        showChromeTemporarily()
    }

    fun attach() {
        if (!initialized) init()
        if (attached) return
        try {
            windowManager.addView(rootView, layoutParams)
            attached = true
        } catch (e: Exception) {
            throw RuntimeException("Failed to add overlay window: ${e.message}", e)
        }
    }

    fun detach() {
        if (!attached) return
        try {
            windowManager.removeView(rootView)
        } catch (_: Exception) {
        }
        cancelTextAnimations()
        cancelChromeTimer()
        attached = false
    }

    fun setOutput(text: String?) {
        val t = text ?: ""
        if (outDraft.isNotEmpty()) {
            when {
                t.startsWith(outDraft) -> outDraft = t
                outDraft.startsWith(t) -> {
                    refreshOutput()
                    return
                }
                else -> {
                    outCommitted = (outCommitted + "\n" + outDraft).trimStart('\n')
                    if (outCommitted.length > 1500) outCommitted = outCommitted.takeLast(1500)
                    outDraft = t
                }
            }
        } else {
            outDraft = t
        }
        refreshOutput()
    }

    fun setInput(text: String?) {
        val t = text ?: ""
        if (inDraft.isNotEmpty()) {
            when {
                t.startsWith(inDraft) -> inDraft = t
                inDraft.startsWith(t) -> {
                    refreshInput()
                    return
                }
                else -> {
                    inCommitted = (inCommitted + "\n" + inDraft).trimStart('\n')
                    if (inCommitted.length > 800) inCommitted = inCommitted.takeLast(800)
                    inDraft = t
                }
            }
        } else {
            inDraft = t
        }
    }

    fun setStatus(status: String?) {
        statusText = status ?: ""
        val s = statusText.lowercase()
        statusKind = when {
            listOf("connected", "已连接", "ready", "live").any { it in s } -> StatusKind.CONNECTED
            listOf("connect", "正在连接", "starting", "loading", "init", "初始化", "等待").any { it in s } -> StatusKind.CONNECTING
            listOf("error", "fail", "disconnected", "错误", "失败", "断开").any { it in s } -> StatusKind.ERROR
            else -> StatusKind.IDLE
        }
        refreshStatus()
    }

    fun clear() {
        outCommitted = ""
        outDraft = ""
        inCommitted = ""
        inDraft = ""
        outputRenderTarget = ""
        inputRenderTarget = ""
        cancelTextAnimations()
        refreshOutput()
    }

    fun setRunningState(running: Boolean) {
        toggleBtn.text = if (running) context.getString(R.string.stop) else context.getString(R.string.start)
    }

    fun updateSettings(newSettings: AppSettings) {
        settings.replaceWith(newSettings)
        locked = settings.overlayLocked
        applyLockState()
        applyStyle()
    }

    fun applyStyle() {
        if (!initialized) return
        rootView.alpha = 1f
        outputView.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.fontSize.toFloat())
        outputView.maxLines = settings.subtitleMaxLines
        outputView.setTextColor(
            Color.argb((255 * settings.captionOpacity.coerceIn(0.2f, 1f)).toInt(), 255, 255, 255)
        )
        overlayRoot.background?.mutate()?.setAlpha(
            (255 * (0.22f + 0.78f * settings.bgOpacity.coerceIn(0f, 1f))).toInt()
        )
        langBadge.visibility = View.GONE
        divider.visibility = View.GONE
        inputView.visibility = View.GONE
        refreshSourceButton()
        applyCollapsedState()
        refreshStatus()
        refreshOutput()
    }

    fun setAudioSource(source: String) {
        settings.audioSource = AppSettings.normalizeAudioSource(source)
        refreshSourceButton()
    }

    private fun toggleCollapsed() {
        collapsed = !collapsed
        if (!collapsed) {
            showChromeTemporarily()
        }
        applyCollapsedState()
    }

    private fun toggleLocked() {
        locked = !locked
        settings.overlayLocked = locked
        settings.save(context)
        applyLockState()
    }

    private fun applyLockState() {
        if (!initialized) return
        lockBtn.text = context.getString(if (locked) R.string.unlock else R.string.lock)
    }

    private fun applyCollapsedState() {
        if (!initialized) return
        minimizeBtn.text = context.getString(if (collapsed) R.string.expand else R.string.minimize)
        if (collapsed) {
            cancelChromeTimer()
            overlayRoot.visibility = View.GONE
            collapsedBall.visibility = View.VISIBLE
            setWindowSize(dp(BALL_SIZE_DP), dp(BALL_SIZE_DP), save = false)
            return
        }
        collapsedBall.visibility = View.GONE
        overlayRoot.visibility = View.VISIBLE
        setWindowSize(normalWidthPx(), normalHeightPx(), save = false)
        applyChromeState()
        outputView.visibility = View.VISIBLE
        divider.visibility = View.GONE
        inputView.visibility = View.GONE
    }

    private fun toggleChrome() {
        if (chromeVisible) {
            hideChromeNow()
        } else {
            showChromeTemporarily()
        }
    }

    private fun showChromeTemporarily() {
        chromeVisible = true
        applyChromeState()
        scheduleChromeAutoHide()
    }

    private fun hideChromeNow() {
        if (collapsed) return
        chromeVisible = false
        cancelChromeTimer()
        applyChromeState()
    }

    private fun applyChromeState() {
        if (!initialized) return
        if (collapsed) return
        headerRow.visibility = if (chromeVisible || collapsed) View.VISIBLE else View.GONE
        controlsRow.visibility = if (chromeVisible && !collapsed) View.VISIBLE else View.GONE
        toolsRow.visibility = if (chromeVisible && !collapsed) View.VISIBLE else View.GONE
    }

    private fun scheduleChromeAutoHide() {
        cancelChromeTimer()
        if (collapsed) return
        hideChromeRunnable = Runnable { hideChromeNow() }
        mainHandler.postDelayed(hideChromeRunnable!!, CHROME_AUTO_HIDE_MS)
    }

    private fun cancelChromeTimer() {
        hideChromeRunnable?.let { mainHandler.removeCallbacks(it) }
        hideChromeRunnable = null
    }

    private fun adjustFontSize(delta: Int) {
        settings.fontSize = AppSettings.normalizeFontSize(settings.fontSize + delta)
        settings.save(context)
        applyStyle()
    }

    private fun refreshSourceButton() {
        if (!initialized) return
        sourceBtn.text = context.getString(
            if (settings.audioSource == "system") R.string.source_system_short else R.string.source_mic_short
        )
    }

    private fun refreshOutput() {
        var text = outCommitted
        if (outDraft.isNotEmpty()) {
            text = (text + "\n" + outDraft).trim('\n')
        }
        text = compactSubtitle(text)
        if (text.isEmpty()) text = context.getString(R.string.caption_placeholder)
        setTextAnimated(outputView, text, isOutput = true)
    }

    private fun refreshInput() {
        var text = inCommitted
        if (inDraft.isNotEmpty()) {
            text = (text + "\n" + inDraft).trim('\n')
        }
        text = latestLines(text, INPUT_VISIBLE_LINES)
        setTextAnimated(inputView, text, isOutput = false)
    }

    private fun setTextAnimated(view: TextView, target: String, isOutput: Boolean) {
        val currentTarget = if (isOutput) outputRenderTarget else inputRenderTarget
        if (currentTarget == target) return

        if (isOutput) {
            outputRenderTarget = target
            outputAnimation?.let { mainHandler.removeCallbacks(it) }
            outputAnimation = null
        } else {
            inputRenderTarget = target
            inputAnimation?.let { mainHandler.removeCallbacks(it) }
            inputAnimation = null
        }

        val current = view.text.toString()
        val placeholder = context.getString(R.string.caption_placeholder)
        val canAppend = current != placeholder &&
            current.isNotEmpty() &&
            target.startsWith(current) &&
            target.length - current.length in 1..96

        if (!canAppend) {
            view.text = target
            view.alpha = 0.82f
            view.animate().alpha(1f).setDuration(140L).start()
            return
        }

        var index = current.length
        val step = object : Runnable {
            override fun run() {
                val latest = if (isOutput) outputRenderTarget else inputRenderTarget
                if (!latest.startsWith(current)) {
                    view.text = latest
                    return
                }

                index = (index + if (isOutput) 3 else 4).coerceAtMost(latest.length)
                view.text = latest.substring(0, index)
                if (index < latest.length) {
                    mainHandler.postDelayed(this, 18L)
                }
            }
        }

        if (isOutput) outputAnimation = step else inputAnimation = step
        mainHandler.post(step)
    }

    private fun cancelTextAnimations() {
        outputAnimation?.let { mainHandler.removeCallbacks(it) }
        inputAnimation?.let { mainHandler.removeCallbacks(it) }
        outputAnimation = null
        inputAnimation = null
    }

    private fun latestLines(text: String, maxLines: Int): String =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
            .takeLast(maxLines)
            .joinToString("\n")

    private fun compactSubtitle(text: String): String {
        val maxLines = AppSettings.normalizeSubtitleMaxLines(settings.subtitleMaxLines)
        val segments = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
            .takeLast(maxLines * 4 + 2)
        if (segments.isEmpty()) return ""

        val joined = buildString {
            for (segment in segments) {
                if (isEmpty()) {
                    append(segment)
                } else {
                    val previous = last()
                    val next = segment.first()
                    if (needsSpace(previous, next)) append(' ')
                    append(segment)
                }
            }
        }.trim()

        val limit = (maxLines * 72).coerceAtLeast(80)
        return if (joined.length > limit) joined.takeLast(limit).trimStart() else joined
    }

    private fun needsSpace(previous: Char, next: Char): Boolean {
        if (next in setOf('.', ',', '!', '?', ';', ':', '，', '。', '！', '？', '、')) return false
        if (isCjk(previous) || isCjk(next)) return false
        return previous.isLetterOrDigit() && next.isLetterOrDigit()
    }

    private fun isCjk(char: Char): Boolean {
        val block = Character.UnicodeBlock.of(char)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
            block == Character.UnicodeBlock.HANGUL_JAMO ||
            block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
    }

    private fun refreshStatus() {
        if (statusTextView.text.toString() != statusText) {
            statusTextView.text = statusText.ifBlank { context.getString(R.string.status_idle) }
        }
        val colorRes = when (statusKind) {
            StatusKind.IDLE -> R.color.status_idle
            StatusKind.CONNECTING -> R.color.status_connecting
            StatusKind.CONNECTED -> R.color.status_connected
            StatusKind.ERROR -> R.color.status_error
        }
        statusDot.background?.setTint(context.getColor(colorRes))
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            normalWidthPx(),
            normalHeightPx(),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 90
        }
    }

    private fun normalWidthPx(): Int {
        val maxWidth = context.resources.displayMetrics.widthPixels - dp(24)
        return clampDimension(dp(AppSettings.normalizeOverlayWidth(settings.overlayWidthDp)), dp(260), maxWidth)
    }

    private fun normalHeightPx(): Int {
        val maxHeight = (context.resources.displayMetrics.heightPixels * 0.55f).toInt()
        return clampDimension(dp(AppSettings.normalizeOverlayHeight(settings.overlayHeightDp)), dp(96), maxHeight)
    }

    private fun setWindowSize(widthPx: Int, heightPx: Int, save: Boolean) {
        layoutParams.width = widthPx
        layoutParams.height = heightPx
        if (attached) {
            try {
                windowManager.updateViewLayout(rootView, layoutParams)
            } catch (_: Exception) {
            }
        }
        if (save && !collapsed) {
            settings.overlayWidthDp = AppSettings.normalizeOverlayWidth(pxToDp(widthPx))
            settings.overlayHeightDp = AppSettings.normalizeOverlayHeight(pxToDp(heightPx))
            settings.save(context)
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()

    private fun pxToDp(value: Int): Int =
        (value / context.resources.displayMetrics.density).toInt()

    private fun clampDimension(value: Int, min: Int, max: Int): Int =
        if (max < min) max.coerceAtLeast(1) else value.coerceIn(min, max)

    private fun installDragHandler() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var dragging = false

        rootView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (locked) return@setOnTouchListener true
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (!dragging && (abs(dx) > 8 || abs(dy) > 8)) {
                        dragging = true
                    }
                    if (dragging) {
                        layoutParams.x = initialX + dx.toInt()
                        layoutParams.y = (initialY - dy.toInt()).coerceAtLeast(0)
                        try {
                            windowManager.updateViewLayout(rootView, layoutParams)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val wasDragging = dragging
                    dragging = false
                    if (!wasDragging) {
                        if (collapsed) toggleCollapsed() else toggleChrome()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun installResizeHandler() {
        var initialWidth = 0
        var initialHeight = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        resizeHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (locked || collapsed) return@setOnTouchListener true
                    initialWidth = layoutParams.width
                    initialHeight = layoutParams.height
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    cancelChromeTimer()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (locked || collapsed) return@setOnTouchListener true
                    val width = initialWidth + (event.rawX - initialTouchX).toInt()
                    val height = initialHeight + (event.rawY - initialTouchY).toInt()
                    setWindowSize(
                        clampDimension(width, dp(260), context.resources.displayMetrics.widthPixels - dp(24)),
                        clampDimension(height, dp(96), (context.resources.displayMetrics.heightPixels * 0.55f).toInt()),
                        save = false
                    )
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!locked && !collapsed) {
                        setWindowSize(layoutParams.width, layoutParams.height, save = true)
                        showChromeTemporarily()
                    }
                    true
                }
                else -> false
            }
        }
    }

    companion object {
        private const val INPUT_VISIBLE_LINES = 6
        private const val BALL_SIZE_DP = 56
        private const val CHROME_AUTO_HIDE_MS = 4500L
    }
}
