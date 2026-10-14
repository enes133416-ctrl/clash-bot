package com.clashbot.auto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Ekran görüntüsünü 720x450 boyutuna küçülterek (bellek ayırmadan yeniden kullanarak)
 * hızlı sabit koordinatlı piksel renk örneklemesi ile sahne doğrulaması yapar.
 */
class ScreenRecognizer {

    companion object {
        const val SMALL_WIDTH = 720
        const val SMALL_HEIGHT = 450
        private const val MIN_POINT_DISTANCE_REF = 100.0
    }

    private val smallBitmap: Bitmap =
        Bitmap.createBitmap(SMALL_WIDTH, SMALL_HEIGHT, Bitmap.Config.ARGB_8888)
    private val smallCanvas = Canvas(smallBitmap)
    private val pixelBuffer = IntArray(SMALL_WIDTH * SMALL_HEIGHT)
    private val srcRect = Rect()
    private val dstRect = Rect(0, 0, SMALL_WIDTH, SMALL_HEIGHT)
    private val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /**
     * Tam çözünürlüklü ekran görüntüsünü küçük (720x450) tampon görüntüye çizer
     * ve mevcut sahneyi döndürür.
     */
    @Synchronized
    fun recognizeScene(fullBitmap: Bitmap): Scene {
        srcRect.set(0, 0, fullBitmap.width, fullBitmap.height)
        smallCanvas.drawBitmap(fullBitmap, srcRect, dstRect, scalePaint)
        smallBitmap.getPixels(pixelBuffer, 0, SMALL_WIDTH, 0, 0, SMALL_WIDTH, SMALL_HEIGHT)

        // Öncelik sırasıyla 5 sahnenin DOĞRULAMA kurallarını kontrol et
        if (verifyScreen5BattleEnd()) return Scene.SCREEN_5_BATTLE_END
        if (verifyScreen3ArmyConfirm()) return Scene.SCREEN_3_ARMY_CONFIRM
        if (verifyScreen4OpponentVillage()) return Scene.SCREEN_4_OPPONENT_VILLAGE
        if (verifyScreen2AttackMenu()) return Scene.SCREEN_2_ATTACK_MENU
        if (verifyScreen1Village()) return Scene.SCREEN_1_VILLAGE

        return Scene.UNKNOWN
    }

    /**
     * EKRAN 1 DOĞRULAMA:
     * 1. Sol üstte "Lord Stark" oyuncu adı / seviye alanı, sağ üstte altın/iksir/kara iksir sayaçları,
     *    sağ altta "İnşa Et" butonu görünmeli.
     * 2. Sol altta sarı "Savaş" butonu (x 30-264, y 1258-1490, merkez 147,1375) görünmeli.
     * 3. Başka menü veya açık pencere olmamalı.
     */
    private fun verifyScreen1Village(): Boolean {
        // Sol altta sarı/altın "Savaş" butonu kontrolü (x 30-264, y 1258-1490)
        val savasYellowRatio = countColorRatio(55, 1280, 240, 1465, stepX = 18, stepY = 18) { r, g, b ->
            isYellowOrGoldButton(r, g, b) || isWarmButtonContent(r, g, b)
        }
        val savasTextBright = countColorRatio(70, 1415, 225, 1480, stepX = 12, stepY = 10) { r, g, b ->
            isBrightWhiteText(r, g, b)
        }
        if (savasYellowRatio < 0.28f || savasTextBright < 0.05f) return false

        // Sağ altta "İnşa Et" butonu kontrolü (x≈2270, y≈1375)
        val insaEtRatio = countColorRatio(2175, 1280, 2365, 1470, stepX = 20, stepY = 20) { r, g, b ->
            isYellowOrGoldButton(r, g, b) || isWarmButtonContent(r, g, b)
        }
        val insaEtTextBright = countColorRatio(2180, 1415, 2360, 1480, stepX = 15, stepY = 10) { r, g, b ->
            isBrightWhiteText(r, g, b)
        }
        if (insaEtRatio < 0.22f || insaEtTextBright < 0.04f) return false

        // Sol üstte oyuncu adı ("Lord Stark") beyaz yazı / mavi XP rozeti kontrolü
        val topLeftPlayerNameBright = countColorRatio(115, 42, 370, 105, stepX = 15, stepY = 10) { r, g, b ->
            isBrightWhiteText(r, g, b)
        }
        val topLeftXpBadge = countColorRatio(35, 38, 110, 110, stepX = 12, stepY = 12) { r, g, b ->
            isBlueBadge(r, g, b) || isBrightWhiteText(r, g, b)
        }
        if (topLeftPlayerNameBright < 0.03f && topLeftXpBadge < 0.12f) return false

        // Sağ üstte altın ve iksir sayaçları kontrolü
        val topRightGold = countColorRatio(2260, 40, 2400, 115, stepX = 14, stepY = 12) { r, g, b ->
            isYellowOrGoldButton(r, g, b) || isBrightWhiteText(r, g, b)
        }
        val topRightElixir = countColorRatio(2260, 125, 2400, 210, stepX = 14, stepY = 12) { r, g, b ->
            isElixirPink(r, g, b) || isBrightWhiteText(r, g, b)
        }
        if (topRightGold < 0.08f && topRightElixir < 0.08f) return false

        // Ortada açık bir menü/pencere (Ekran 2/3/5) olmadığından emin ol
        val centerModalOrange = countColorRatio(180, 1000, 580, 1120, stepX = 25, stepY = 20) { r, g, b ->
            isOrangeButton(r, g, b)
        }
        if (centerModalOrange > 0.55f) return false

        return true
    }

    /**
     * EKRAN 2 DOĞRULAMA:
     * 1. Sol üstte "Çok Oyunculu", "Tek Oyuncu" ve "Alıştırma" sekmeleri görünmeli.
     * 2. Solda "Savaş" kartının altında turuncu "Eşleşme Bul 1500" butonu (x 130-638, y 980-1140) görünmeli.
     */
    private fun verifyScreen2AttackMenu(): Boolean {
        // Soldaki turuncu "Eşleşme Bul 1500" butonu (x 130-638, y 980-1140, merkez 383, 1060)
        val orangeBtnRatio = countColorRatio(155, 995, 610, 1125, stepX = 20, stepY = 16) { r, g, b ->
            isOrangeButton(r, g, b) || isYellowOrGoldButton(r, g, b)
        }
        val orangeBtnText = countColorRatio(190, 1005, 575, 1120, stepX = 16, stepY = 14) { r, g, b ->
            isBrightWhiteText(r, g, b)
        }
        if (orangeBtnRatio < 0.35f || orangeBtnText < 0.04f) return false

        // Sol üstte 3 sekme: "Çok Oyunculu" (x≈260,y≈100), "Tek Oyuncu" (x≈690,y≈100), "Alıştırma" (x≈1185,y≈100)
        val tab1Text = countColorRatio(120, 65, 430, 135, stepX = 18, stepY = 12) { r, g, b ->
            isBrightWhiteText(r, g, b) || isTabHeaderTone(r, g, b)
        }
        val tab2Text = countColorRatio(520, 65, 850, 135, stepX = 18, stepY = 12) { r, g, b ->
            isBrightWhiteText(r, g, b) || isTabHeaderTone(r, g, b)
        }
        val tab3Text = countColorRatio(1010, 65, 1340, 135, stepX = 18, stepY = 12) { r, g, b ->
            isBrightWhiteText(r, g, b) || isTabHeaderTone(r, g, b)
        }
        if (tab1Text < 0.10f || tab2Text < 0.10f || tab3Text < 0.10f) return false

        // Sol alttaki yeşil "Orduyu Düzenle" (x≈265, y≈1450) veya ortadaki kart varlığı
        val bottomLeftEditArmyGreen = countColorRatio(140, 1410, 390, 1490, stepX = 20, stepY = 15) { r, g, b ->
            isGreenButton(r, g, b)
        }
        val middleCardButton = countColorRatio(820, 1010, 1170, 1125, stepX = 25, stepY = 20) { r, g, b ->
            isOrangeButton(r, g, b) || isYellowOrGoldButton(r, g, b) || isBrightWhiteText(r, g, b)
        }

        return bottomLeftEditArmyGreen >= 0.15f || middleCardButton >= 0.15f
    }

    /**
     * EKRAN 3 DOĞRULAMA:
     * 1. Üstte "Ordum", "Kaydedilen Setler" ve "Ordu Tarifleri" sekmeleri görünmeli.
     * 2. Sağ altta yeşil "Saldır!" butonu (x 1842-2254, y 1197-1282, merkez 2050, 1238) görünmeli.
     */
    private fun verifyScreen3ArmyConfirm(): Boolean {
        // Sağ altta yeşil "Saldır!" butonu (x 1842-2254, y 1197-1282)
        val greenAttackRatio = countColorRatio(1860, 1205, 2235, 1275, stepX = 18, stepY = 12) { r, g, b ->
            isGreenButton(r, g, b)
        }
        val attackTextBright = countColorRatio(1920, 1210, 2190, 1270, stepX = 15, stepY = 10) { r, g, b ->
            isBrightWhiteText(r, g, b)
        }
        if (greenAttackRatio < 0.35f || attackTextBright < 0.04f) return false

        // Sağ üstte kırmızı "X" kapatma butonu (x≈2228, y≈255)
        val redCloseRatio = countColorRatio(2185, 220, 2270, 290, stepX = 10, stepY = 10) { r, g, b ->
            isRedButton(r, g, b)
        }
        if (redCloseRatio < 0.20f) return false

        // Üstte "Ordum", "Kaydedilen Setler", "Ordu Tarifleri" sekmeleri (y≈215-290)
        val armyTabsRatio = countColorRatio(260, 220, 1550, 290, stepX = 30, stepY = 14) { r, g, b ->
            isBrightWhiteText(r, g, b) || isTabHeaderTone(r, g, b)
        }
        return armyTabsRatio >= 0.15f
    }

    /**
     * EKRAN 4 DOĞRULAMA:
     * 1. Sol üstte "Mevcut Ganimet" ve üstte "Savaşın başlamasına kalan" yazısı görünmeli.
     * 2. Sağ altta turuncu "İleri 1500" butonu (x 2040-2398, y 1068-1230), altta kart sırası görünmeli.
     */
    private fun verifyScreen4OpponentVillage(): Boolean {
        // Sağ altta turuncu "İleri 1500" butonu (x 2040-2398, y 1068-1230, merkez 2220, 1148)
        val nextOrangeRatio = countColorRatio(2060, 1080, 2380, 1215, stepX = 18, stepY = 16) { r, g, b ->
            isOrangeButton(r, g, b) || isYellowOrGoldButton(r, g, b)
        }
        val nextTextBright = countColorRatio(2080, 1090, 2350, 1205, stepX = 16, stepY = 14) { r, g, b ->
            isBrightWhiteText(r, g, b)
        }
        if (nextOrangeRatio < 0.32f || nextTextBright < 0.04f) return false

        // Sol altta kırmızı "Savaşı Bitir" butonu (x≈160, y≈1190)
        val endBattleRedRatio = countColorRatio(65, 1140, 255, 1235, stepX = 16, stepY = 14) { r, g, b ->
            isRedButton(r, g, b)
        }
        if (endBattleRedRatio < 0.20f) return false

        // Sol üstte "Mevcut Ganimet" ve ganimet satırları (x 110-340, y 115-280)
        val lootAreaBrightOrResource = countColorRatio(70, 115, 340, 280, stepX = 16, stepY = 14) { r, g, b ->
            isBrightWhiteText(r, g, b) || isYellowOrGoldButton(r, g, b) || isElixirPink(r, g, b)
        }
        if (lootAreaBrightOrResource < 0.04f) return false

        // Üst ortada "Savaşın başlamasına kalan" yazısı (x 900-1520, y 40-140)
        val countdownBright = countColorRatio(920, 40, 1500, 140, stepX = 20, stepY = 14) { r, g, b ->
            isBrightWhiteText(r, g, b)
        }
        if (countdownBright < 0.02f) return false

        // Altta birlik kart sırası (x=326, y=1395, sınırlar x 248-403, y 1295-1495)
        val cardSlotActive = countColorRatio(255, 1305, 395, 1485, stepX = 16, stepY = 18) { r, g, b ->
            r + g + b > 140
        }
        return cardSlotActive >= 0.25f
    }

    /**
     * EKRAN 5 DOĞRULAMA:
     * 1. Ortada altın şerit ve üzerinde sonuç başlığı ("Yenilgi" vb.) görünmeli.
     * 2. Altta yeşil "Eve Dön" butonu (x 1037-1388, y 1228-1378, merkez 1210, 1295) görünmeli.
     */
    private fun verifyScreen5BattleEnd(): Boolean {
        // Alt ortada yeşil "Eve Dön" butonu (x 1037-1388, y 1228-1378)
        val returnHomeGreenRatio = countColorRatio(1055, 1240, 1370, 1365, stepX = 18, stepY = 14) { r, g, b ->
            isGreenButton(r, g, b)
        }
        val returnHomeTextBright = countColorRatio(1080, 1255, 1345, 1350, stepX = 16, stepY = 12) { r, g, b ->
            isBrightWhiteText(r, g, b)
        }
        if (returnHomeGreenRatio < 0.35f || returnHomeTextBright < 0.04f) return false

        // Ortada üstte altın şerit ve başlık alanı (x 820-1600, y 170-360)
        val goldRibbonRatio = countColorRatio(840, 175, 1580, 355, stepX = 24, stepY = 16) { r, g, b ->
            isYellowOrGoldButton(r, g, b) || isOrangeButton(r, g, b) || isBrightWhiteText(r, g, b)
        }
        return goldRibbonRatio >= 0.12f
    }

    /**
     * Ekran 4 Adım 2B için: Verilen referans noktanın kırmızı sınır çizgisi içinde veya
     * su/arayüz üzerinde olup olmadığını kontrol eder. Gerekirse dış yeşil alanda ve
     * önceki tüm noktalardan en az 100 piksel uzakta güvenli bir nokta seçer.
     */
    @Synchronized
    fun resolveSafeOuterGrassPoint(
        preferredXRef: Int,
        preferredYRef: Int,
        usedPoints: List<Point>
    ): Point? {
        if (isValidDeploymentPoint(preferredXRef, preferredYRef, usedPoints)) {
            return Point(preferredXRef, preferredYRef)
        }

        // Eğer tercih edilen nokta kırmızı çizgi / su / arayüz üzerindeyse dışa doğru alternatif adayları tara
        val isLeftTopGroup = preferredXRef < 1200
        val offsets = if (isLeftTopGroup) {
            listOf(
                Point(-40, -30), Point(-60, 0), Point(0, -45), Point(45, -35),
                Point(-50, 45), Point(-80, -20), Point(60, -50), Point(-70, 70),
                Point(0, 60), Point(-100, 20), Point(80, -30), Point(-40, 90)
            )
        } else {
            listOf(
                Point(40, 30), Point(55, -20), Point(30, 55), Point(-45, 45),
                Point(65, 40), Point(-55, -35), Point(40, -55), Point(-70, 30),
                Point(25, 80), Point(-40, 75), Point(75, 0), Point(-80, -20)
            )
        }

        for (offset in offsets) {
            val candidateX = (preferredXRef + offset.x).coerceIn(455, 2390)
            val candidateY = (preferredYRef + offset.y).coerceIn(145, 1220)
            if (isValidDeploymentPoint(candidateX, candidateY, usedPoints)) {
                return Point(candidateX, candidateY)
            }
        }

        // Son çare: mesafe ve yasaklı bölge kuralını koruyarak orijinal noktayı döndür
        if (ForbiddenZones.isOuterDeploymentCoordinateAllowed(preferredXRef, preferredYRef) &&
            isFarEnoughFromUsed(preferredXRef, preferredYRef, usedPoints)
        ) {
            return Point(preferredXRef, preferredYRef)
        }
        return null
    }

    private fun isValidDeploymentPoint(xRef: Int, yRef: Int, usedPoints: List<Point>): Boolean {
        if (!ForbiddenZones.isOuterDeploymentCoordinateAllowed(xRef, yRef)) return false
        if (!isFarEnoughFromUsed(xRef, yRef, usedPoints)) return false

        // Nokta çevresinde kırmızı sınır çizgisi veya su/arayüz var mı kontrol et
        val redOrWaterRatio = countColorRatio(
            (xRef - 18).coerceAtLeast(0),
            (yRef - 18).coerceAtLeast(0),
            (xRef + 18).coerceAtMost(2418),
            (yRef + 18).coerceAtMost(1511),
            stepX = 9,
            stepY = 9
        ) { r, g, b ->
            isRedBorderLine(r, g, b) || isWaterTile(r, g, b)
        }
        if (redOrWaterRatio > 0.30f) return false

        // Yeşil/doğal zemin kontrolü
        val grassOrTerrainRatio = countColorRatio(
            (xRef - 18).coerceAtLeast(0),
            (yRef - 18).coerceAtLeast(0),
            (xRef + 18).coerceAtMost(2418),
            (yRef + 18).coerceAtMost(1511),
            stepX = 9,
            stepY = 9
        ) { r, g, b ->
            isGrassOrOuterTerrain(r, g, b)
        }
        return grassOrTerrainRatio >= 0.40f
    }

    private fun isFarEnoughFromUsed(xRef: Int, yRef: Int, usedPoints: List<Point>): Boolean {
        for (pt in usedPoints) {
            val dist = hypot((xRef - pt.x).toDouble(), (yRef - pt.y).toDouble())
            if (dist < MIN_POINT_DISTANCE_REF) return false
        }
        return true
    }

    private inline fun countColorRatio(
        leftRef: Int,
        topRef: Int,
        rightRef: Int,
        bottomRef: Int,
        stepX: Int,
        stepY: Int,
        predicate: (r: Int, g: Int, b: Int) -> Boolean
    ): Float {
        var matched = 0
        var total = 0
        var yRef = topRef
        while (yRef <= bottomRef) {
            var xRef = leftRef
            while (xRef <= rightRef) {
                val color = getPixelAtRef(xRef, yRef)
                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)
                if (predicate(r, g, b)) {
                    matched++
                }
                total++
                xRef += stepX
            }
            yRef += stepY
        }
        return if (total == 0) 0f else matched.toFloat() / total.toFloat()
    }

    private fun getPixelAtRef(xRef: Int, yRef: Int): Int {
        val mapped = CoordinateMapper.toRealPoint(xRef, yRef, SMALL_WIDTH, SMALL_HEIGHT)
        val x = mapped.x.roundToInt().coerceIn(0, SMALL_WIDTH - 1)
        val y = mapped.y.roundToInt().coerceIn(0, SMALL_HEIGHT - 1)
        return pixelBuffer[y * SMALL_WIDTH + x]
    }

    // --- Renk Sınıflandırıcı Yardımcılar ---

    private fun isYellowOrGoldButton(r: Int, g: Int, b: Int): Boolean {
        return r >= 175 && g >= 115 && b <= 105 && r >= g && (r - b) >= 80
    }

    private fun isWarmButtonContent(r: Int, g: Int, b: Int): Boolean {
        return r >= 150 && g >= 85 && b <= 110 && r > b + 50
    }

    private fun isOrangeButton(r: Int, g: Int, b: Int): Boolean {
        return r >= 185 && g in 75..210 && b <= 95 && (r - b) >= 95 && r >= g + 15
    }

    private fun isGreenButton(r: Int, g: Int, b: Int): Boolean {
        return g >= 135 && g > b + 35 && g >= r - 10 && r in 35..195 && b <= 125
    }

    private fun isRedButton(r: Int, g: Int, b: Int): Boolean {
        return r >= 155 && r > g + 45 && r > b + 45 && g <= 115 && b <= 115
    }

    private fun isBrightWhiteText(r: Int, g: Int, b: Int): Boolean {
        return r >= 210 && g >= 210 && b >= 200
    }

    private fun isBlueBadge(r: Int, g: Int, b: Int): Boolean {
        return b >= 145 && g >= 80 && b > r + 35
    }

    private fun isElixirPink(r: Int, g: Int, b: Int): Boolean {
        return r >= 165 && b >= 140 && g <= 130 && r > g + 40
    }

    private fun isTabHeaderTone(r: Int, g: Int, b: Int): Boolean {
        return r in 70..200 && g in 65..195 && b in 55..175
    }

    private fun isRedBorderLine(r: Int, g: Int, b: Int): Boolean {
        return r >= 165 && g <= 95 && b <= 95 && (r - g) >= 75
    }

    private fun isWaterTile(r: Int, g: Int, b: Int): Boolean {
        return b >= 130 && b > r + 40 && b > g + 15
    }

    private fun isGrassOrOuterTerrain(r: Int, g: Int, b: Int): Boolean {
        // Köy dışı çimen ve doğal kenar zemin tonları
        val isGreenish = g >= 70 && g >= b + 12 && r <= g + 35 && b <= 140
        val isWarmDirtOrSand = r in 90..215 && g in 80..195 && b in 40..145 && r >= b + 20
        return isGreenish || isWarmDirtOrSand
    }
}
