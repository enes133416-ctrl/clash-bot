package com.clashbot.auto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * AccessibilityService.dispatchGesture ile kısa (40 ms) dokunuşlar gönderir.
 * Her dokunuştan önce:
 * 1) Botun hâlâ çalışır durumda olduğunu,
 * 2) Koordinatın o sahnenin "BASILMAYACAK YERLER" listesinde olmadığını doğrular,
 * 3) Tek yardımcı fonksiyon (CoordinateMapper.toRealPoint) ile gerçek ekran koordinatına çevirir.
 */
class GestureController(
    private val service: AccessibilityService,
    private val isRunningProvider: () -> Boolean
) {

    companion object {
        // Dokunuşlar için kısa (≈30–50 ms) tap gesture süresi
        private const val TAP_DURATION_MS = 40L
    }

    suspend fun tapReferencePoint(
        scene: Scene,
        xRef: Int,
        yRef: Int,
        realWidth: Int,
        realHeight: Int
    ): Boolean {
        if (!isRunningProvider()) return false

        // Kesin yasaklı bölge ("BASILMAYACAK YERLER") kontrolü
        if (!ForbiddenZones.isTapAllowed(scene, xRef, yRef)) {
            return false
        }

        val realPoint = CoordinateMapper.toRealPoint(xRef, yRef, realWidth, realHeight)
        val x = realPoint.x.coerceIn(1f, (realWidth - 2).coerceAtLeast(1).toFloat())
        val y = realPoint.y.coerceIn(1f, (realHeight - 2).coerceAtLeast(1).toFloat())

        if (!isRunningProvider()) return false

        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        return suspendCancellableCoroutine { cont ->
            if (!isRunningProvider()) {
                if (cont.isActive) cont.resume(false)
                return@suspendCancellableCoroutine
            }

            val dispatched = service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )

            if (!dispatched && cont.isActive) {
                cont.resume(false)
            }
        }
    }
}
