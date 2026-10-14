package com.clashbot.auto

import android.graphics.Bitmap
import android.graphics.Point
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Durum makinesi (State Machine):
 * Döngü: Ekran 1 -> Ekran 2 -> Ekran 3 -> Ekran 4 -> (Savaş) -> Ekran 5 -> Ekran 1
 */
class BotStateMachine(
    private val screenshotProvider: suspend () -> Bitmap?,
    private val gestureController: GestureController,
    private val onStatusUpdate: (isRunning: Boolean, message: String) -> Unit
) {

    companion object {
        private const val LOOP_INTERVAL_MS = 160L
        private const val POLL_INTERVAL_MS = 120L
        private const val MAX_WAIT_MS = 2000L
        private const val MULTI_TAP_INTERVAL_MS = 70L
        private const val UNKNOWN_SCREEN_WARN_MS = 30_000L
        private const val MAX_CONSECUTIVE_RETRIES = 3
        private const val TARGET_LOOT_THRESHOLD = 3_000_000L

        // EKRAN 1 hedef: "Savaş" butonu (x = 147, y = 1375)
        private const val SCREEN1_TARGET_X = 147
        private const val SCREEN1_TARGET_Y = 1375

        // EKRAN 2 hedef: Soldaki "Eşleşme Bul 1500" butonu (x = 383, y = 1060)
        private const val SCREEN2_TARGET_X = 383
        private const val SCREEN2_TARGET_Y = 1060

        // EKRAN 3 hedef: Sağ alttaki yeşil "Saldır!" butonu (x = 2050, y = 1238)
        private const val SCREEN3_TARGET_X = 2050
        private const val SCREEN3_TARGET_Y = 1238

        // EKRAN 4 hedefleri:
        // Adım 2A: Sağ alttaki turuncu "İleri 1500" butonu (x = 2220, y = 1148)
        private const val SCREEN4_NEXT_X = 2220
        private const val SCREEN4_NEXT_Y = 1148

        // Adım 2B-1: Sol alttaki "x40" / "11" birlik kartı (x = 326, y = 1395)
        private const val SCREEN4_TROOP_CARD_X = 326
        private const val SCREEN4_TROOP_CARD_Y = 1395

        // Adım 2B-2: Köy dışındaki yeşil alanlarda 20 farklı nokta (birbirinden >= 100 px uzak)
        private val DEPLOYMENT_POINTS_REF: List<Point> = listOf(
            // Sol/üst bölge (10 nokta)
            Point(560, 190),
            Point(700, 200),
            Point(800, 160),
            Point(850, 270),
            Point(700, 330),
            Point(600, 330),
            Point(800, 380),
            Point(480, 450),
            Point(600, 470),
            Point(480, 580),
            // Sağ bölge (10 nokta)
            Point(2200, 800),
            Point(2300, 780),
            Point(2380, 700),
            Point(2280, 900),
            Point(2170, 920),
            Point(2060, 860),
            Point(2060, 1000),
            Point(1960, 1090),
            Point(1850, 1170),
            Point(1700, 1200)
        )

        // EKRAN 5 hedef: Alt ortadaki yeşil "Eve Dön" butonu (x = 1210, y = 1295)
        private const val SCREEN5_TARGET_X = 1210
        private const val SCREEN5_TARGET_Y = 1295
    }

    private val running = AtomicBoolean(false)
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logChannel = Channel<Pair<Boolean, String>>(Channel.CONFLATED)

    private val screenRecognizer = ScreenRecognizer()
    private val lootOcrReader = LootOcrReader()

    private var loopJob: Job? = null

    init {
        // Log ve UI güncellemelerini ana döngü dışında ayrı bir coroutine'de işle
        logScope.launch {
            for ((state, msg) in logChannel) {
                onStatusUpdate(state, msg)
            }
        }
    }

    fun isRunning(): Boolean = running.get()

    fun toggle() {
        if (running.get()) {
            stop("Durduruldu")
        } else {
            start()
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        emitLog(true, "Başlatıldı: Ekran okunuyor...")
        loopJob = workerScope.launch {
            runLoop()
        }
    }

    fun stop(reason: String = "Durduruldu") {
        running.set(false)
        loopJob?.cancel()
        loopJob = null
        emitLog(false, reason)
    }

    private fun emitLog(isRunningState: Boolean, message: String) {
        logChannel.trySend(isRunningState to message)
    }

    private suspend fun runLoop() {
        var unknownSinceMs = 0L
        var lastActionScene: Scene? = null
        var consecutiveRetries = 0
        var lastScreen4LootTotal = -1L
        var waitingForBattleEnd = false

        while (workerScope.isActive && running.get()) {
            val cycleStartMs = SystemClock.elapsedRealtime()
            try {
                val frame = screenshotProvider()
                if (frame == null) {
                    waitUpTo(POLL_INTERVAL_MS)
                    continue
                }

                val realWidth = frame.width
                val realHeight = frame.height
                val currentScene = try {
                    screenRecognizer.recognizeScene(frame)
                } finally {
                    // Ekran 4 değilse tam çözünürlüklü Bitmap'e artık ihtiyaç yok
                    if (!frame.isRecycled && !waitingForBattleEnd) {
                        // Ekran 4'te OCR için frame kullanılacağından aşağıda recycle edilir
                    }
                }

                if (!running.get()) {
                    recycleSafely(frame)
                    break
                }

                // 20 asker bırakıldıktan sonra savaşın bitip Ekran 5'in açılmasını bekleme durumu
                if (waitingForBattleEnd) {
                    if (currentScene == Scene.SCREEN_5_BATTLE_END || currentScene == Scene.SCREEN_1_VILLAGE) {
                        waitingForBattleEnd = false
                        unknownSinceMs = 0L
                    } else {
                        recycleSafely(frame)
                        emitLog(true, "Savaş devam ediyor, Ekran 5 bekleniyor...")
                        waitElapsedFrame(cycleStartMs)
                        continue
                    }
                }

                when (currentScene) {
                    Scene.UNKNOWN -> {
                        recycleSafely(frame)
                        if (unknownSinceMs == 0L) {
                            unknownSinceMs = SystemClock.elapsedRealtime()
                        }
                        val unknownDuration = SystemClock.elapsedRealtime() - unknownSinceMs
                        if (unknownDuration >= UNKNOWN_SCREEN_WARN_MS) {
                            emitLog(true, "Bilinmeyen ekran (${unknownDuration / 1000} sn) - Dokunulmuyor")
                        } else {
                            emitLog(true, "Ekran bekleniyor...")
                        }
                    }

                    Scene.SCREEN_1_VILLAGE -> {
                        recycleSafely(frame)
                        unknownSinceMs = 0L
                        lastScreen4LootTotal = -1L

                        if (!checkAndTrackRetries(
                                scene = Scene.SCREEN_1_VILLAGE,
                                lastScene = lastActionScene,
                                currentRetries = consecutiveRetries,
                                updateRetries = { consecutiveRetries = it }
                            )
                        ) {
                            break
                        }
                        lastActionScene = Scene.SCREEN_1_VILLAGE

                        emitLog(true, "Ekran 1: 'Savaş' butonuna dokunuluyor")
                        val tapped = gestureController.tapReferencePoint(
                            Scene.SCREEN_1_VILLAGE,
                            SCREEN1_TARGET_X,
                            SCREEN1_TARGET_Y,
                            realWidth,
                            realHeight
                        )
                        if (tapped) {
                            val changed = waitForSceneChange(
                                fromScene = Scene.SCREEN_1_VILLAGE,
                                targetScene = Scene.SCREEN_2_ATTACK_MENU
                            )
                            if (changed) consecutiveRetries = 0
                        }
                    }

                    Scene.SCREEN_2_ATTACK_MENU -> {
                        recycleSafely(frame)
                        unknownSinceMs = 0L

                        if (!checkAndTrackRetries(
                                scene = Scene.SCREEN_2_ATTACK_MENU,
                                lastScene = lastActionScene,
                                currentRetries = consecutiveRetries,
                                updateRetries = { consecutiveRetries = it }
                            )
                        ) {
                            break
                        }
                        lastActionScene = Scene.SCREEN_2_ATTACK_MENU

                        emitLog(true, "Ekran 2: 'Eşleşme Bul 1500' dokunuluyor")
                        val tapped = gestureController.tapReferencePoint(
                            Scene.SCREEN_2_ATTACK_MENU,
                            SCREEN2_TARGET_X,
                            SCREEN2_TARGET_Y,
                            realWidth,
                            realHeight
                        )
                        if (tapped) {
                            val changed = waitForSceneChange(
                                fromScene = Scene.SCREEN_2_ATTACK_MENU,
                                targetScene = Scene.SCREEN_3_ARMY_CONFIRM
                            )
                            if (changed) consecutiveRetries = 0
                        }
                    }

                    Scene.SCREEN_3_ARMY_CONFIRM -> {
                        recycleSafely(frame)
                        unknownSinceMs = 0L

                        if (!checkAndTrackRetries(
                                scene = Scene.SCREEN_3_ARMY_CONFIRM,
                                lastScene = lastActionScene,
                                currentRetries = consecutiveRetries,
                                updateRetries = { consecutiveRetries = it }
                            )
                        ) {
                            break
                        }
                        lastActionScene = Scene.SCREEN_3_ARMY_CONFIRM

                        emitLog(true, "Ekran 3: 'Saldır!' butonuna dokunuluyor")
                        val tapped = gestureController.tapReferencePoint(
                            Scene.SCREEN_3_ARMY_CONFIRM,
                            SCREEN3_TARGET_X,
                            SCREEN3_TARGET_Y,
                            realWidth,
                            realHeight
                        )
                        if (tapped) {
                            val changed = waitForSceneChange(
                                fromScene = Scene.SCREEN_3_ARMY_CONFIRM,
                                targetScene = Scene.SCREEN_4_OPPONENT_VILLAGE
                            )
                            if (changed) consecutiveRetries = 0
                        }
                    }

                    Scene.SCREEN_4_OPPONENT_VILLAGE -> {
                        unknownSinceMs = 0L
                        // ADIM 1: Ganimeti oku (x 110-340, y 150-280)
                        val loot = try {
                            lootOcrReader.readLoot(frame)
                        } finally {
                            recycleSafely(frame)
                        }

                        if (!running.get()) break

                        if (loot == null) {
                            // Ganimet okunamazsa hiçbir yere basma, en fazla 1-2 sn içinde tekrar oku
                            emitLog(true, "Ekran 4: Ganimet okunuyor...")
                            waitUpTo(350L)
                            continue
                        }

                        val totalLoot = loot.total
                        if (totalLoot != lastScreen4LootTotal) {
                            consecutiveRetries = 0
                            lastScreen4LootTotal = totalLoot
                        }

                        if (totalLoot <= TARGET_LOOT_THRESHOLD) {
                            // ADIM 2A: TOPLAM <= 3.000.000 -> "İleri 1500" (x = 2220, y = 1148)
                            if (!checkAndTrackRetries(
                                    scene = Scene.SCREEN_4_OPPONENT_VILLAGE,
                                    lastScene = lastActionScene,
                                    currentRetries = consecutiveRetries,
                                    updateRetries = { consecutiveRetries = it }
                                )
                            ) {
                                break
                            }
                            lastActionScene = Scene.SCREEN_4_OPPONENT_VILLAGE

                            emitLog(true, "Ganimet: ${formatNumber(totalLoot)} <= 3M -> İleri")
                            val tapped = gestureController.tapReferencePoint(
                                Scene.SCREEN_4_OPPONENT_VILLAGE,
                                SCREEN4_NEXT_X,
                                SCREEN4_NEXT_Y,
                                realWidth,
                                realHeight
                            )
                            if (tapped) {
                                waitForNextVillageTransition()
                            }
                        } else {
                            // ADIM 2B: TOPLAM > 3.000.000 -> Asker seç (326, 1395) ve 20 dış noktaya bırak
                            consecutiveRetries = 0
                            lastActionScene = Scene.SCREEN_4_OPPONENT_VILLAGE
                            emitLog(true, "Ganimet: ${formatNumber(totalLoot)} > 3M -> Saldırı!")

                            val cardSelected = gestureController.tapReferencePoint(
                                Scene.SCREEN_4_OPPONENT_VILLAGE,
                                SCREEN4_TROOP_CARD_X,
                                SCREEN4_TROOP_CARD_Y,
                                realWidth,
                                realHeight
                            )

                            if (cardSelected && running.get()) {
                                waitUpTo(90L)
                                deployTwentyTroops(realWidth, realHeight)
                                waitingForBattleEnd = true
                                emitLog(true, "20 birlik bırakıldı, savaş bitişi bekleniyor")
                            }
                        }
                    }

                    Scene.SCREEN_5_BATTLE_END -> {
                        recycleSafely(frame)
                        unknownSinceMs = 0L
                        waitingForBattleEnd = false

                        if (!checkAndTrackRetries(
                                scene = Scene.SCREEN_5_BATTLE_END,
                                lastScene = lastActionScene,
                                currentRetries = consecutiveRetries,
                                updateRetries = { consecutiveRetries = it }
                            )
                        ) {
                            break
                        }
                        lastActionScene = Scene.SCREEN_5_BATTLE_END

                        emitLog(true, "Ekran 5: 'Eve Dön' butonuna dokunuluyor")
                        val tapped = gestureController.tapReferencePoint(
                            Scene.SCREEN_5_BATTLE_END,
                            SCREEN5_TARGET_X,
                            SCREEN5_TARGET_Y,
                            realWidth,
                            realHeight
                        )
                        if (tapped) {
                            val changed = waitForSceneChange(
                                fromScene = Scene.SCREEN_5_BATTLE_END,
                                targetScene = Scene.SCREEN_1_VILLAGE
                            )
                            if (changed) consecutiveRetries = 0
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // Uygulama hata alırsa çökmesin: hatayı yakala, logla, döngüye devam et
                emitLog(running.get(), "Hata yakalandı: ${t.javaClass.simpleName}")
            }

            waitElapsedFrame(cycleStartMs)
        }
    }

    /**
     * Aynı sahnede üst üste 3'ten fazla başarısız deneme olursa döngüyü durdurur ve uyarı gösterir.
     */
    private fun checkAndTrackRetries(
        scene: Scene,
        lastScene: Scene?,
        currentRetries: Int,
        updateRetries: (Int) -> Unit
    ): Boolean {
        if (lastScene == scene) {
            val nextRetry = currentRetries + 1
            updateRetries(nextRetry)
            if (nextRetry > MAX_CONSECUTIVE_RETRIES) {
                stop("Uyarı: ${scene.displayName} 3 denemede geçilemedi!")
                return false
            }
        } else {
            updateRetries(1)
        }
        return true
    }

    /**
     * Ekran 4 Adım 2B: Birbirinden en az 100 piksel uzak 20 farklı dış yeşil noktaya
     * sırayla birer kez dokunur. Aynı noktaya iki kez dokunmaz, 20 dokunuş bitince durur.
     */
    private suspend fun deployTwentyTroops(realWidth: Int, realHeight: Int) {
        val usedPoints = ArrayList<Point>(20)
        for (target in DEPLOYMENT_POINTS_REF) {
            if (!running.get()) return
            if (usedPoints.size >= 20) break

            val safePoint = screenRecognizer.resolveSafeOuterGrassPoint(
                preferredXRef = target.x,
                preferredYRef = target.y,
                usedPoints = usedPoints
            ) ?: continue

            // Aynı noktaya iki kez dokunulmasını kesin olarak engelle
            if (usedPoints.any { it.x == safePoint.x && it.y == safePoint.y }) {
                continue
            }

            val dispatched = gestureController.tapReferencePoint(
                scene = Scene.SCREEN_4_OPPONENT_VILLAGE,
                xRef = safePoint.x,
                yRef = safePoint.y,
                realWidth = realWidth,
                realHeight = realHeight
            )

            if (dispatched) {
                usedPoints.add(safePoint)
                emitLog(true, "Asker bırakılıyor: ${usedPoints.size}/20")
                waitUpTo(MULTI_TAP_INTERVAL_MS)
            }
        }
    }

    /**
     * Bir dokunuştan sonra sabit uyku yerine her 100-150 ms'de yeni ekran görüntüsü alarak
     * ekran değişimini kontrol eder. Hedef ekran görününce hemen devam eder.
     * Üst sınır: 2 saniye (2000 ms).
     */
    private suspend fun waitForSceneChange(fromScene: Scene, targetScene: Scene?): Boolean {
        val startMs = SystemClock.elapsedRealtime()
        while (running.get() && (SystemClock.elapsedRealtime() - startMs) < MAX_WAIT_MS) {
            waitUpTo(POLL_INTERVAL_MS)
            if (!running.get()) return false

            val bitmap = screenshotProvider() ?: continue
            val detected = try {
                screenRecognizer.recognizeScene(bitmap)
            } finally {
                recycleSafely(bitmap)
            }

            if (targetScene != null && detected == targetScene) {
                return true
            }
            if (detected != fromScene && detected != Scene.UNKNOWN) {
                return true
            }
        }
        return false
    }

    /**
     * Ekran 4'te "İleri 1500" butonuna basıldıktan sonra bulut geçişini ve yeni köyün
     * yüklenmesini en fazla 2 saniye boyunca 120 ms aralıkla izler.
     */
    private suspend fun waitForNextVillageTransition() {
        val startMs = SystemClock.elapsedRealtime()
        var sawCloudOrChange = false
        while (running.get() && (SystemClock.elapsedRealtime() - startMs) < MAX_WAIT_MS) {
            waitUpTo(POLL_INTERVAL_MS)
            if (!running.get()) return

            val bitmap = screenshotProvider() ?: continue
            val scene = try {
                screenRecognizer.recognizeScene(bitmap)
            } finally {
                recycleSafely(bitmap)
            }

            if (scene != Scene.SCREEN_4_OPPONENT_VILLAGE) {
                sawCloudOrChange = true
            } else if (sawCloudOrChange) {
                // Bulut ekranından çıkıp yeni rakip köyüne ulaştı, hemen devam et
                return
            }
        }
    }

    private suspend fun waitElapsedFrame(cycleStartMs: Long) {
        val elapsed = SystemClock.elapsedRealtime() - cycleStartMs
        val remaining = LOOP_INTERVAL_MS - elapsed
        if (remaining > 15L && running.get()) {
            waitUpTo(remaining.coerceAtMost(LOOP_INTERVAL_MS))
        }
    }

    private suspend fun waitUpTo(ms: Long) {
        if (ms <= 0L || !running.get()) return
        delay(ms.coerceAtMost(MAX_WAIT_MS))
    }

    private fun recycleSafely(bitmap: Bitmap?) {
        try {
            if (bitmap != null && !bitmap.isRecycled) {
                bitmap.recycle()
            }
        } catch (_: Throwable) {
        }
    }

    private fun formatNumber(value: Long): String {
        return value.toString().reversed().chunked(3).joinToString(" ").reversed()
    }

    fun destroy() {
        stop("Servis kapatıldı")
        lootOcrReader.close()
        logChannel.close()
    }
}
