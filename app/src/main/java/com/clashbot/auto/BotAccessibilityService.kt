package com.clashbot.auto

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Android Erişilebilirlik Servisi (API 30+):
 * - takeScreenshot ile MediaProjection izni olmadan hızlı ekran görüntüsü alır.
 * - dispatchGesture ile root gerektirmeden dokunuş gönderir.
 * - Yüzen (overlay) kontrol butonunu ve durum makinesini yönetir.
 */
class BotAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: BotAccessibilityService? = null
            private set

        @Volatile
        var lastStatusText: String = "Hazır"
            private set

        var statusListener: ((isRunning: Boolean, status: String) -> Unit)? = null
    }

    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private var overlayController: OverlayController? = null
    private var stateMachine: BotStateMachine? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        val overlay = OverlayController(this) {
            toggleBot()
        }
        overlay.show()
        overlayController = overlay

        val gestureCtrl = GestureController(this) {
            stateMachine?.isRunning() == true
        }

        val machine = BotStateMachine(
            screenshotProvider = { captureScreenBitmap() },
            gestureController = gestureCtrl,
            onStatusUpdate = { running, message ->
                lastStatusText = message
                overlayController?.updateState(running, message)
                statusListener?.invoke(running, message)
            }
        )
        stateMachine = machine

        lastStatusText = "Hazır"
        overlay.updateState(false, lastStatusText)
        statusListener?.invoke(false, lastStatusText)
    }

    fun isBotRunning(): Boolean = stateMachine?.isRunning() == true

    fun toggleBot() {
        stateMachine?.toggle()
    }

    fun startBot() {
        overlayController?.show()
        stateMachine?.start()
    }

    fun stopBot(reason: String = "Durduruldu") {
        stateMachine?.stop(reason)
    }

    /**
     * AccessibilityService.takeScreenshot (API 30+) kullanarak ekran görüntüsü alır
     * ve piksel okuma için ARGB_8888 yazılım Bitmap'ine dönüştürür.
     */
    private suspend fun captureScreenBitmap(): Bitmap? =
        suspendCancellableCoroutine { cont ->
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    screenshotExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val hwBuffer = screenshot.hardwareBuffer
                            val colorSpace = screenshot.colorSpace
                            var hwBitmap: Bitmap? = null
                            var softBitmap: Bitmap? = null
                            try {
                                hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                                softBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            } catch (_: Throwable) {
                            } finally {
                                try {
                                    hwBitmap?.recycle()
                                } catch (_: Throwable) {
                                }
                                try {
                                    hwBuffer.close()
                                } catch (_: Throwable) {
                                }
                            }
                            if (cont.isActive) {
                                cont.resume(softBitmap)
                            } else {
                                softBitmap?.recycle()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) {
                                cont.resume(null)
                            }
                        }
                    }
                )
            } catch (_: Throwable) {
                if (cont.isActive) {
                    cont.resume(null)
                }
            }
        }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Olay tabanlı değil, hızlı ekran görüntüsü döngüsü ile çalışır.
    }

    override fun onInterrupt() {
        stateMachine?.stop("Erişilebilirlik kesildi")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        screenshotExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun cleanup() {
        instance = null
        stateMachine?.destroy()
        stateMachine = null
        overlayController?.destroy()
        overlayController = null
        statusListener?.invoke(false, "Servis devre dışı")
    }
}
