package io.tima.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode

/**
 * Телефон включили — поднимаем канал, не дожидаясь, пока человек откроет приложение
 * (ПЛАН-УВЕДОМЛЕНИЙ.md, У3). **И приложение обновили** — тоже (заказчик 2026-09-30).
 *
 * Установка новой сборки останавливает приложение, и до ручного открытия оно не слышит ни
 * звонков, ни сообщений: 2026-09-30 после установки сборки 87 звонки на Redmi и Honor
 * кончались «вызов не забрало ни одно устройство собеседника». Поднимается всегда — и после
 * «Закрыть приложение» тоже: перезапуск и обновление возвращают фон (заказчик 2026-09-30).
 *
 * Без этого «уведомления работают» означало бы «работают у того, кто после перезагрузки
 * зашёл в приложение», то есть у того, кому они и не нужны.
 *
 * ── ПОЧЕМУ ЭТО ВООБЩЕ РАЗРЕШЕНО ─────────────────────────────────────────────
 *
 * С Android 12 службу переднего плана из фона поднимать нельзя, и `BOOT_COMPLETED` —
 * одно из немногих исключений. Но с Android 15 исключение сузили: из него **нельзя**
 * поднимать службы типов `dataSync`, `camera`, `mediaPlayback`, `phoneCall`,
 * `mediaProjection` и `microphone`. Нашего `specialUse` в этом перечне нет — и это
 * вторая причина, по которой тип выбран именно такой.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // `MY_PACKAGE_REPLACED` — в том же перечне исключений Android 12, что и загрузка:
        // службу переднего плана из него поднимать разрешено.
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> Journal.note(LogCode.APP_START, "телефон включён — поднимаю канал")
            Intent.ACTION_MY_PACKAGE_REPLACED -> Journal.note(LogCode.APP_START, "приложение обновлено — поднимаю канал")
            else -> return
        }
        ChannelService.start(context)
    }
}
