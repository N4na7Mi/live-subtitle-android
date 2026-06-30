package com.faqxd.livesub.android.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
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
import com.faqxd.livesub.android.data.Languages
import kotlin.math.abs

class CaptionOverlayView(
    private val context: Context,
    private val settings: AppSettings,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        fun onToggleClicked()
        fun onClearClicked()
        fun onCloseClicked()
        fun onSettingsClicked()
    }

    private var outCommitted = ""
    private var outDraft = ""
    private var inCommitted = ""
    private var inDraft = ""
    private var statusText = ""
    private var statusKind: StatusKind = StatusKind.IDLE

    private enum class StatusKind { IDLE, CONNECTING, CONNECTED, ERROR }

    private lateinit var rootView: View
    private lateinit var statusDot: View
    private lateinit var statusTextView: TextView
    private lateinit var langBadge: TextView
    private lateinit var minimizeBtn: Button
    private lateinit var outputView: TextView
    private lateinit var divider: View
    private lateinit var inputView: TextView
    private lateinit var controlsRow: View
    private lateinit var toggleBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var closeBtn: Button
    private lateinit var settingsBtn: ImageButton

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var layoutParams: WindowManager.LayoutParams = buildLayoutParams()
    private var attached = false
    private var initialized = false
    private var collapsed = false

    val isAttached: Boolean get() = attached

    fun init() {
        if (initialized) return
        rootView = View.inflate(context, R.layout.overlay_caption, null)
        statusDot = rootView.findViewById(R.id.overlayStatusDot)
        statusTextView = rootView.findViewById(R.id.overlayStatusText)
        langBadge = rootView.findViewById(R.id.overlayLangBadge)
        minimizeBtn = rootView.findViewById(R.id.overlayMinimizeBtn)
        outputView = rootView.findViewById(R.id.overlayOutput)
        divider = rootView.findViewById(R.id.overlayDivider)
        inputView = rootView.findViewById(R.id.overlayInput)
        controlsRow = rootView.findViewById(R.id.overlayControlsRow)
        toggleBtn = rootView.findViewById(R.id.overlayToggleBtn)
        clearBtn = rootView.findViewById(R.id.overlayClearBtn)
        closeBtn = rootView.findViewById(R.id.overlayCloseBtn)
        settingsBtn = rootView.findViewById(R.id.overlaySettingsBtn)

        toggleBtn.setOnClickListener { callbacks.onToggleClicked() }
        minimizeBtn.setOnClickListener { toggleCollapsed() }
        clearBtn.setOnClickListener { callbacks.onClearClicked() }
        closeBtn.setOnClickListener { callbacks.onCloseClicked() }
        settingsBtn.setOnClickListener { callbacks.onSettingsClicked() }

        installDragHandler()
        initialized = true
        applyStyle()
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
        attached = false
    }

    fun setOutput(text: String?) {
        val t = text ?: ""
        if (outDraft.isNotEmpty() && t.startsWith(outDraft)) {
            outDraft = t
        } else {
            if (outDraft.isNotEmpty()) {
                outCommitted = (outCommitted + "\n" + outDraft).trimStart('\n')
                if (outCommitted.length > 1500) outCommitted = outCommitted.takeLast(1500)
            }
            outDraft = t
        }
        refreshOutput()
    }

    fun setInput(text: String?) {
        val t = text ?: ""
        if (inDraft.isNotEmpty() && t.startsWith(inDraft)) {
            inDraft = t
        } else {
            if (inDraft.isNotEmpty()) {
                inCommitted = (inCommitted + "\n" + inDraft).trimStart('\n')
                if (inCommitted.length > 800) inCommitted = inCommitted.takeLast(800)
            }
            inDraft = t
        }
        refreshInput()
    }

    fun setStatus(status: String?) {
        statusText = status ?: ""
        val s = statusText.lowercase()
        statusKind = when {
            listOf("connected", "已连接", "ready", "live").any { it in s } -> StatusKind.CONNECTED
            listOf("connect", "正在连接", "starting", "loading", "init").any { it in s } -> StatusKind.CONNECTING
            listOf("error", "fail", "disconnected", "stop", "错误", "失败", "断开", "停止").any { it in s } -> StatusKind.ERROR
            else -> StatusKind.IDLE
        }
        refreshStatus()
    }

    fun clear() {
        outCommitted = ""
        outDraft = ""
        inCommitted = ""
        inDraft = ""
        refreshOutput()
        refreshInput()
    }

    fun setRunningState(running: Boolean) {
        toggleBtn.text = if (running) context.getString(R.string.pause) else context.getString(R.string.start)
    }

    fun applyStyle() {
        if (!initialized) return
        outputView.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.fontSize.toFloat())
        inputView.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.fontSize * 0.6f)
        langBadge.text = "${context.getString(R.string.lang_badge_prefix)} ${Languages.nameFor(settings.targetLanguage)}"
        val showOrig = settings.showOriginal
        divider.visibility = if (showOrig) View.VISIBLE else View.GONE
        inputView.visibility = if (showOrig) View.VISIBLE else View.GONE
        rootView.alpha = 0.4f + 0.6f * settings.bgOpacity.coerceIn(0f, 1f)
        applyCollapsedState()
        refreshStatus()
    }

    private fun toggleCollapsed() {
        collapsed = !collapsed
        applyCollapsedState()
    }

    private fun applyCollapsedState() {
        if (!initialized) return
        minimizeBtn.text = context.getString(if (collapsed) R.string.expand else R.string.minimize)
        outputView.visibility = if (collapsed) View.GONE else View.VISIBLE
        controlsRow.visibility = if (collapsed) View.GONE else View.VISIBLE
        val showInput = !collapsed && settings.showOriginal
        divider.visibility = if (showInput) View.VISIBLE else View.GONE
        inputView.visibility = if (showInput) View.VISIBLE else View.GONE
    }

    private fun refreshOutput() {
        var text = outCommitted
        if (outDraft.isNotEmpty()) {
            text = (text + "\n" + outDraft).trim('\n')
        }
        if (text.isEmpty()) text = context.getString(R.string.caption_placeholder)
        if (outputView.text.toString() != text) {
            outputView.text = text
        }
    }

    private fun refreshInput() {
        var text = inCommitted
        if (inDraft.isNotEmpty()) {
            text = (text + "\n" + inDraft).trim('\n')
        }
        if (inputView.text.toString() != text) {
            inputView.text = text
        }
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
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 200
        }
    }

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
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (!dragging && (abs(dx) > 8 || abs(dy) > 8)) {
                        dragging = true
                    }
                    if (dragging) {
                        layoutParams.x = initialX + dx.toInt()
                        layoutParams.y = initialY + dy.toInt()
                        try {
                            windowManager.updateViewLayout(rootView, layoutParams)
                        } catch (_: Exception) {
                        }
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val wasDragging = dragging
                    dragging = false
                    wasDragging
                }
                else -> false
            }
        }
    }
}
