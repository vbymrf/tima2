package io.tima.core.call

import android.content.Context
import android.content.Intent
import android.os.Build
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode

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

    fun on(title: String, text: String) {
        val context = app ?: run {
            Journal.trouble(LogCode.CALL, "службу звонка не поднять — приложение не представилось")
            return
        }
        val intent = Intent(context, CallService::class.java)
            .putExtra(CallService.TITLE, title)
            .putExtra(CallService.TEXT, text)
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
    fun off() {
        val context = app ?: return
        val stop = Intent(context, CallService::class.java).setAction(CallService.STOP)
        runCatching { context.startService(stop) }
            .onFailure { runCatching { context.stopService(Intent(context, CallService::class.java)) } }
    }
}

actual fun callOngoing(title: String, text: String) = AndroidCallNotice.on(title, text)

actual fun callOngoingOff() = AndroidCallNotice.off()
