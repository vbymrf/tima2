package io.tima.core.call

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.Build
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Android: служба переднего плана на время звонка — [CallService].
 *
 * **Контекст приложения, а не окна.** Служба переживает окно: человек сворачивает
 * приложение, окно умирает, а разговор продолжается — ради этого всё и заводилось.
 */
object AndroidCallNotice {

    @Volatile
    private var app: Context? = null

    /** Зовётся из `Application.onCreate`. */
    fun attach(context: Context) {
        app = context.applicationContext
    }

    fun on(title: String, text: String, hangUpLabel: String = "", connectedAt: Long = 0L, camera: Boolean = false) {
        val context = app ?: run {
            Journal.trouble(LogCode.CALL, "службу звонка не поднять — приложение не представилось")
            return
        }
        val intent = Intent(context, CallService::class.java)
            .putExtra(CallService.TITLE, title)
            .putExtra(CallService.TEXT, text)
            .putExtra(CallService.HANG_UP_LABEL, hangUpLabel)
            .putExtra(CallService.CONNECTED_AT, connectedAt)
            .putExtra(CallService.CAMERA, camera)
        // ── ЗАПУСК МОЖЕТ БЫТЬ ЗАПРЕЩЁН, И ЭТО НЕ ПОЛОМКА ────────────────────
        //
        // С Android 12 службу переднего плана **нельзя поднять из фона**. Сегодня мы
        // зовём это только там, где человек только что нажал кнопку, то есть приложение
        // на экране, — но входящий звонок в фоне, когда он появится, придёт именно так, и
        // законное окно для запуска даст только высокоприоритетный push.
        //
        // Поэтому не падаем, а записываем: звонок сам по себе от этого не ломается, он
        // просто потеряет микрофон при сворачивании.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }.onFailure {
            Journal.trouble(
                LogCode.CALL,
                "служба звонка не запустилась",
                "причина" to (it.message ?: it::class.simpleName ?: "неизвестно"),
            )
        }
    }

    /**
     * Погасить службу — **командой ей самой, а не `stopService` снаружи** (Redmi
     * 2026-09-27, отчёт 5KXE).
     *
     * `stopService` сразу после `startForegroundService` уничтожает службу раньше, чем та
     * успела подняться в передний план, и Android закрывает приложение целиком:
     * `ForegroundServiceDidNotStartInTimeException`. Так бывает, когда звонок кончился
     * в ту же секунду, что начался: ответили — и собеседник тут же положил трубку.
     *
     * Команда встаёт в очередь службы **после** команды запуска: служба сначала
     * поднимается, потом гаснет, и правило соблюдено. Из фона Android может запретить и
     * эту команду — тогда служба давно в переднем плане, и `stopService` безопасен.
     */
    /** Блокировка «гасить экран по датчику»; `null` — не взята. */
    private var nearEar: PowerManager.WakeLock? = null
    private var toldNoSensor = false

    /**
     * Гасить экран по датчику приближения (ПЛАН-(В)-ВИДЕО.md В10). Механизм системный — тот же,
     * что у звонилки телефона: пока блокировка взята, телефон у уха гасит экран и не
     * принимает касаний, от уха — зажигает.
     *
     * Отпускается с флагом «дождаться, пока отведут»: иначе экран загорелся бы прямо у уха
     * в ту секунду, когда звонок стал видео или громким.
     */
    @Synchronized
    fun proximity(on: Boolean) {
        val context = app ?: return
        if (on == (nearEar != null)) return
        val power = context.getSystemService(PowerManager::class.java) ?: return
        if (!on) {
            runCatching { nearEar?.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY) }
            nearEar = null
            Journal.note(LogCode.CALL, "датчик приближения", "гасит экран" to false)
            return
        }
        if (!power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            if (!toldNoSensor) {
                toldNoSensor = true
                Journal.note(LogCode.CALL, "датчика приближения нет — экран у уха не гаснет")
            }
            return
        }
        nearEar = runCatching {
            power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "tima:call-near-ear").apply {
                setReferenceCounted(false)
                // Срок — страховка от забытой блокировки: разговор дольше часа — редкость,
                // а экран, который не гаснет у уха, хуже, чем не гаснущий никогда.
                acquire(NEAR_EAR_LIMIT_MS)
            }
        }.onFailure {
            Journal.trouble(LogCode.CALL, "датчик приближения не взят", "причина" to (it.message ?: it::class.simpleName))
        }.getOrNull()
        if (nearEar != null) Journal.note(LogCode.CALL, "датчик приближения", "гасит экран" to true)
    }

    private const val NEAR_EAR_LIMIT_MS = 3_600_000L

    /**
     * Держать ли экран включённым — видеозвонок (решение заказчика 2026-09-30).
     *
     * Флаг ставит главное окно (`FLAG_KEEP_SCREEN_ON`), а не блокировка питания: он живёт,
     * пока окно на экране, и сам снимается, когда человек ушёл в другое приложение, —
     * забыть его отпустить нельзя. Отсюда окна не видно, поэтому здесь только желание.
     */
    val screenOn = MutableStateFlow(false)

    fun keepScreen(on: Boolean) {
        if (screenOn.value == on) return
        screenOn.value = on
        Journal.note(LogCode.CALL, "экран во время видеозвонка", "не гаснет" to on)
    }

    fun off() {
        val context = app ?: return
        val stop = Intent(context, CallService::class.java).setAction(CallService.STOP)
        runCatching { context.startService(stop) }
            .onFailure { runCatching { context.stopService(Intent(context, CallService::class.java)) } }
    }
}

actual fun callOngoing(title: String, text: String, hangUpLabel: String, connectedAt: Long, camera: Boolean) =
    AndroidCallNotice.on(title, text, hangUpLabel, connectedAt, camera)

actual fun callOngoingOff() = AndroidCallNotice.off()

actual fun callProximity(on: Boolean) = AndroidCallNotice.proximity(on)

actual fun callKeepScreen(on: Boolean) = AndroidCallNotice.keepScreen(on)
