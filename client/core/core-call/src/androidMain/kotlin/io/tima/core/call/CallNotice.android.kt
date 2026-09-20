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

    fun off() {
        val context = app ?: return
        runCatching { context.stopService(Intent(context, CallService::class.java)) }
    }
}

actual fun callOngoing(title: String, text: String) = AndroidCallNotice.on(title, text)

actual fun callOngoingOff() = AndroidCallNotice.off()
