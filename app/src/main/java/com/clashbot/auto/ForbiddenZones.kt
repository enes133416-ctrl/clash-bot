package com.clashbot.auto

import android.graphics.Rect

/**
 * Sahne promptlarındaki "BASILMAYACAK YERLER" ve hedef buton sınırlarını
 * referans (2419 x 1512) koordinat sisteminde kesin olarak denetler.
 */
object ForbiddenZones {

    // EKRAN 1 yasaklı bölgeler
    private val screen1Forbidden = listOf(
        Rect(2120, 1240, 2419, 1512), // Sağ alttaki "İnşa Et" (x≈2270, y≈1375)
        Rect(290, 1340, 455, 1512),   // "Savaş" butonunun sağındaki mavi kalkan ikonu (x≈373, y≈1425)
        Rect(0, 0, 460, 1050)         // Sol üst profil/seviye ikonu ve sol klan/mesaj butonları
    )
    private val screen1AllowedButton = Rect(30, 1258, 264, 1490)

    // EKRAN 2 yasaklı bölgeler
    private val screen2Forbidden = listOf(
        Rect(720, 960, 1270, 1160),   // Ortadaki "Kupalı Savaş" kartı "Eşleşme Bul 0/10" (x≈997, y≈1075)
        Rect(60, 1370, 470, 1512),    // Sol alttaki yeşil "Orduyu Düzenle" butonu (x≈265, y≈1450)
        Rect(480, 20, 1420, 180),     // Sol üstteki "Tek Oyuncu" (x≈690,y≈100) ve "Alıştırma" (x≈1185,y≈100)
        Rect(2120, 1240, 2419, 1512), // Sağ alttaki "İnşa Et" butonu (x≈2270, y≈1375)
        Rect(1300, 180, 2380, 1220)   // "Hazine Avı" kartı ve bilgi (i) ikonu
    )
    private val screen2AllowedButton = Rect(130, 980, 638, 1140)

    // EKRAN 3 yasaklı bölgeler
    private val screen3Forbidden = listOf(
        Rect(2150, 180, 2310, 330),   // Sağ üstteki kırmızı "X" kapatma butonu (x≈2228, y≈255)
        Rect(1830, 1050, 2270, 1190), // Yeşil "27" butonu (x≈1936,y≈1125) ve kale butonu (x≈2157,y≈1125)
        Rect(2160, 380, 2290, 490),   // Çöp kutusu 1 (x≈2226, y≈430)
        Rect(2160, 675, 2290, 790),   // Çöp kutusu 2 (x≈2226, y≈733)
        Rect(1720, 675, 1840, 790),   // Çöp kutusu 3 (x≈1788, y≈733)
        Rect(1720, 995, 1840, 1115),  // Çöp kutusu 4 (x≈1787, y≈1057)
        Rect(140, 310, 1835, 1320),   // Sol taraftaki kahramanlar, ordu, iksir ve kuşatma makinesi kartları
        Rect(1580, 310, 2160, 445),   // Üstteki iksir süre kutuları (x≈1728,y≈380 ve x≈2030,y≈380)
        Rect(0, 1240, 1800, 1512),    // Arka plan sol alt "Savaş"
        Rect(2260, 1240, 2419, 1512)  // Arka plan sağ alt "İnşa Et"
    )
    private val screen3AllowedButton = Rect(1842, 1197, 2254, 1282)

    // EKRAN 4 yasaklı bölgeler
    private val screen4Forbidden = listOf(
        Rect(20, 1100, 300, 1280),    // Sol alttaki kırmızı "Savaşı Bitir" (x≈160, y≈1190)
        Rect(360, 1110, 1100, 1270),  // "Ordu Güçlendirildi" ve "Kahramanlar Güçlendirildi" (x≈530, 910, y≈1190)
        Rect(410, 1285, 1850, 1512),  // Alttaki diğer kartlar (x 450-1815, y 1295-1495)
        Rect(0, 1285, 245, 1512),     // 11 kartının solundaki alan
        Rect(1880, 0, 2419, 360),     // Sağ üstteki altın/iksir/kara iksir sayaçları
        Rect(0, 0, 450, 360),         // Sol üstteki rakip adı, klan ve ganimet alanı
        Rect(860, 395, 1690, 1050)    // Köyün içi ve kırmızı sınır çizgisinin içi
    )
    private val screen4NextButton = Rect(2040, 1068, 2398, 1230)
    private val screen4TroopCard11 = Rect(248, 1295, 403, 1495)

    // EKRAN 5 yasaklı bölgeler
    private val screen5Forbidden = listOf(
        Rect(600, 140, 1820, 400),    // Altın şerit
        Rect(700, 400, 1720, 940),    // "Kazancın" değerleri
        Rect(180, 480, 750, 1160),    // "Ekşi İksir" kutusu
        Rect(750, 945, 1680, 1222),   // "Birlik sarfiyatı" kartı (x≈1213, y≈1090)
        Rect(1850, 0, 2419, 360)      // Sağ üstteki sayaçlar
    )
    private val screen5AllowedButton = Rect(1037, 1228, 1388, 1378)

    /**
     * Verilen sahnede (xRef, yRef) noktasına dokunulup dokunulamayacağını kontrol eder.
     * Hem yasaklı bölgelerde olmamalı hem de sahnenin izin verilen hedef alanında olmalıdır.
     */
    fun isTapAllowed(scene: Scene, xRef: Int, yRef: Int): Boolean {
        if (xRef !in 0..2419 || yRef !in 0..1512) return false

        return when (scene) {
            Scene.SCREEN_1_VILLAGE -> {
                if (screen1Forbidden.any { it.contains(xRef, yRef) }) return false
                screen1AllowedButton.contains(xRef, yRef)
            }
            Scene.SCREEN_2_ATTACK_MENU -> {
                if (screen2Forbidden.any { it.contains(xRef, yRef) }) return false
                screen2AllowedButton.contains(xRef, yRef)
            }
            Scene.SCREEN_3_ARMY_CONFIRM -> {
                if (screen3Forbidden.any { it.contains(xRef, yRef) }) return false
                screen3AllowedButton.contains(xRef, yRef)
            }
            Scene.SCREEN_4_OPPONENT_VILLAGE -> {
                if (screen4Forbidden.any { it.contains(xRef, yRef) }) return false
                if (screen4NextButton.contains(xRef, yRef)) return true
                if (screen4TroopCard11.contains(xRef, yRef)) return true
                isOuterDeploymentCoordinateAllowed(xRef, yRef)
            }
            Scene.SCREEN_5_BATTLE_END -> {
                if (screen5Forbidden.any { it.contains(xRef, yRef) }) return false
                screen5AllowedButton.contains(xRef, yRef)
            }
            Scene.UNKNOWN -> false
        }
    }

    /**
     * Ekran 4'te asker bırakılacak dış yeşil alan koordinatının köy içi veya arayüz
     * üzerinde olmadığını doğrular.
     */
    fun isOuterDeploymentCoordinateAllowed(xRef: Int, yRef: Int): Boolean {
        if (xRef !in 420..2400 || yRef !in 120..1240) return false
        if (screen4Forbidden.any { it.contains(xRef, yRef) }) return false
        if (screen4NextButton.contains(xRef, yRef)) return false
        return true
    }
}
