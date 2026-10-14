package com.clashbot.auto

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Oyun üzerinde küçük, sürüklenebilir Başlat/Durdur butonu ve tek satır durum logu gösterir.
 * WRAP_CONTENT ve FLAG_NOT_FOCUSABLE sayesinde yalnızca küçük buton/panel alanı dokunulabilirdir;
 * oyunun geri kalan dokunuşları kesinlikle engellenmez.
 */
class OverlayController(
    private val context: Context,
    private val onToggleClicked: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var rootView: View? = null
    private var btnToggle: TextView? = null
    private var tvStatus: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    fun show() {
        uiScope.launch {
            if (rootView != null) return@launch
            try {
                val inflater = LayoutInflater.from(context)
                val view = inflater.inflate(R.layout.overlay_control, null)
                val toggle = view.findViewById<TextView>(R.id.btnOverlayToggle)
                val status = view.findViewById<TextView>(R.id.tvOverlayStatus)

                val overlayType = if (Settings.canDrawOverlays(context)) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
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
                    // Oyun butonlarını ve ganimet alanını kapatmayacak üst-orta başlangıç konumu
                    x = 460
                    y = 12
                }

                setupDragAndClick(view, toggle, params)

                windowManager.addView(view, params)
                rootView = view
                btnToggle = toggle
                tvStatus = status
                layoutParams = params
            } catch (_: Throwable) {
            }
        }
    }

    private fun setupDragAndClick(
        container: View,
        toggleButton: TextView,
        params: WindowManager.LayoutParams
    ) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        val touchListener = View.OnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isDragging = true
                        params.x = initialX + dx
                        params.y = initialY + dy
                        try {
                            windowManager.updateViewLayout(container, params)
                        } catch (_: Throwable) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging && v.id == toggleButton.id) {
                        onToggleClicked()
                    }
                    true
                }
                else -> false
            }
        }

        container.setOnTouchListener(touchListener)
        toggleButton.setOnTouchListener(touchListener)
    }

    fun updateState(isRunning: Boolean, logMessage: String) {
        uiScope.launch {
            try {
                tvStatus?.text = logMessage
                btnToggle?.let { btn ->
                    btn.text = if (isRunning) {
                        context.getString(R.string.btn_stop)
                    } else {
                        context.getString(R.string.btn_start)
                    }
                    val bg = GradientDrawable().apply {
                        cornerRadius = 28f
                        setColor(if (isRunning) Color.parseColor("#EF4444") else Color.parseColor("#10B981"))
                    }
                    btn.background = bg
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun hide() {
        uiScope.launch {
            val view = rootView ?: return@launch
            try {
                windowManager.removeView(view)
            } catch (_: Throwable) {
            } finally {
                rootView = null
                btnToggle = null
                tvStatus = null
                layoutParams = null
            }
        }
    }

    fun destroy() {
        hide()
        uiScope.cancel()
    }
}
