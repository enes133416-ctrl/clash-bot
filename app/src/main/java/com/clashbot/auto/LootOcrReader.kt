package com.clashbot.auto

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class LootInfo(
    val gold: Long,
    val elixir: Long
) {
    val total: Long get() = gold + elixir
}

/**
 * Google ML Kit Text Recognition (cihaz içi Latin model) kullanarak
 * sadece Ekran 4'teki "Mevcut Ganimet" bölgesini (x 110-340, y 150-280)
 * orijinal çözünürlükten kırparak okur. Kara iksir (y≈312) kırpma alanı dışındadır.
 */
class LootOcrReader {

    companion object {
        // Sahne promptunda belirtilen ganimet okuma bölgesi (2419 x 1512 referansına göre)
        private const val LOOT_LEFT_REF = 110
        private const val LOOT_TOP_REF = 150
        private const val LOOT_RIGHT_REF = 340
        private const val LOOT_BOTTOM_REF = 280
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun readLoot(fullBitmap: Bitmap): LootInfo? {
        val cropRect = CoordinateMapper.toRealRect(
            LOOT_LEFT_REF,
            LOOT_TOP_REF,
            LOOT_RIGHT_REF,
            LOOT_BOTTOM_REF,
            fullBitmap.width,
            fullBitmap.height
        )

        val width = (cropRect.width()).coerceAtLeast(1)
        val height = (cropRect.height()).coerceAtLeast(1)
        if (cropRect.left + width > fullBitmap.width || cropRect.top + height > fullBitmap.height) {
            return null
        }

        val cropped = Bitmap.createBitmap(fullBitmap, cropRect.left, cropRect.top, width, height)
        try {
            // 1. Doğrudan orijinal kırpılmış görüntü üzerinde OCR çalıştır
            val rawResult = runOcr(cropped)
            val parsedRaw = parseLootFromText(rawResult, height)
            if (parsedRaw != null) {
                return parsedRaw
            }

            // 2. Gerekirse yüksek kontrastlı (beyaz yazı filtreli) küçük kopya ile dene
            val highContrast = createHighContrastCrop(cropped)
            try {
                val contrastResult = runOcr(highContrast)
                return parseLootFromText(contrastResult, height)
            } finally {
                if (!highContrast.isRecycled) {
                    highContrast.recycle()
                }
            }
        } finally {
            if (!cropped.isRecycled) {
                cropped.recycle()
            }
        }
    }

    private suspend fun runOcr(bitmap: Bitmap): Text? =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    if (cont.isActive) cont.resume(result)
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(null)
                }
        }

    private fun parseLootFromText(visionText: Text?, cropHeight: Int): LootInfo? {
        if (visionText == null) return null

        val allLines = mutableListOf<Pair<Int, Long>>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val centerY = line.boundingBox?.centerY() ?: 0
                val value = parseNumericLine(line.text)
                if (value != null && value > 0L) {
                    allLines.add(centerY to value)
                }
            }
        }

        if (allLines.isEmpty()) return null
        allLines.sortBy { it.first }

        // Eğer 2 veya daha fazla satır okunduysa:
        // İlk satır altın (y≈178), ikinci satır iksir (y≈245). Kara iksir (y≈312) zaten y<=280 kırpması dışındadır.
        if (allLines.size >= 2) {
            val gold = allLines[0].second
            val elixir = allLines[1].second
            return LootInfo(gold = gold, elixir = elixir)
        }

        // Tek satır içinde newline ile birleşmiş iki sayı olma ihtimaline karşı kontrol
        val splitValues = visionText.text
            .lines()
            .mapNotNull { parseNumericLine(it) }
            .filter { it > 0L }

        if (splitValues.size >= 2) {
            return LootInfo(gold = splitValues[0], elixir = splitValues[1])
        }

        // Üst yarı ve alt yarı olarak ayrı değerlendirilemiyorsa geçersiz say (tekrar okunacak)
        val midY = cropHeight / 2
        val topLine = allLines.firstOrNull { it.first <= midY }?.second
        val bottomLine = allLines.firstOrNull { it.first > midY }?.second
        if (topLine != null && bottomLine != null) {
            return LootInfo(gold = topLine, elixir = bottomLine)
        }

        return null
    }

    /**
     * "1 761 067" gibi boşluklu sayıları 1761067 olarak çözümler.
     */
    private fun parseNumericLine(raw: String): Long? {
        if (raw.isBlank()) return null
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when (ch) {
                in '0'..'9' -> sb.append(ch)
                'O', 'o' -> sb.append('0')
                'I', 'l', '|' -> sb.append('1')
                'S', 's' -> sb.append('5')
                'B' -> sb.append('8')
                ' ', '.', ',' -> {
                    // Binlik ayırıcı boşluk/nokta/virgülleri atla
                }
            }
        }
        val digits = sb.toString()
        if (digits.isEmpty() || digits.length > 9) return null
        return digits.toLongOrNull()
    }

    /**
     * Küçük ganimet kırpmasındaki parlak beyaz rakamları siyah-beyaz yüksek kontrasta çevirir.
     */
    private fun createHighContrastCrop(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            val isBrightGlyph = r >= 185 && g >= 180 && b >= 165
            pixels[i] = if (isBrightGlyph) Color.BLACK else Color.WHITE
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    fun close() {
        try {
            recognizer.close()
        } catch (_: Throwable) {
        }
    }
}
