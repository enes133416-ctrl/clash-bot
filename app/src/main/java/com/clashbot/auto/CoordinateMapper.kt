package com.clashbot.auto

import android.graphics.PointF
import android.graphics.Rect
import kotlin.math.roundToInt

object CoordinateMapper {
    const val REF_WIDTH = 2419f
    const val REF_HEIGHT = 1512f

    /**
     * Tüm referans (2419 x 1512) koordinatlarını gerçek ekran boyutuna çeviren tek yardımcı fonksiyon.
     * x_gerçek = x_ref * (gerçek_genişlik / 2419)
     * y_gerçek = y_ref * (gerçek_yükseklik / 1512)
     */
    fun toRealCoordinate(xRef: Float, yRef: Float, realWidth: Int, realHeight: Int): PointF {
        val xReal = xRef * (realWidth.toFloat() / REF_WIDTH)
        val yReal = yRef * (realHeight.toFloat() / REF_HEIGHT)
        return PointF(xReal, yReal)
    }

    fun toRealPoint(xRef: Int, yRef: Int, realWidth: Int, realHeight: Int): PointF {
        return toRealCoordinate(xRef.toFloat(), yRef.toFloat(), realWidth, realHeight)
    }

    fun toRealRect(
        leftRef: Int,
        topRef: Int,
        rightRef: Int,
        bottomRef: Int,
        realWidth: Int,
        realHeight: Int
    ): Rect {
        val topLeft = toRealCoordinate(leftRef.toFloat(), topRef.toFloat(), realWidth, realHeight)
        val bottomRight = toRealCoordinate(rightRef.toFloat(), bottomRef.toFloat(), realWidth, realHeight)
        val left = topLeft.x.roundToInt().coerceIn(0, (realWidth - 1).coerceAtLeast(0))
        val top = topLeft.y.roundToInt().coerceIn(0, (realHeight - 1).coerceAtLeast(0))
        val right = bottomRight.x.roundToInt().coerceIn((left + 1).coerceAtMost(realWidth), realWidth)
        val bottom = bottomRight.y.roundToInt().coerceIn((top + 1).coerceAtMost(realHeight), realHeight)
        return Rect(left, top, right, bottom)
    }
}
