package io.tima.core.contacts

import android.content.Intent
import android.net.Uri

/**
 * Android: три системных намерения.
 *
 * **`FLAG_ACTIVITY_NEW_TASK` обязателен**: намерение запускается из контекста
 * приложения, а не из активности — своей активности у этого модуля нет и не должно быть.
 *
 * Каждый вызов возвращает, получилось ли: на устройстве может не быть ни звонилки, ни
 * приложения сообщений (планшет без сим-карты), и тогда экран обязан сказать об этом, а
 * не молчать.
 */
private object AndroidInvite : Invite {

    override fun sms(phone: String, text: String): Boolean =
        start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone")).putExtra("sms_body", text))

    override fun call(phone: String): Boolean =
        // ACTION_DIAL, а не ACTION_CALL: набор открывается, звонок делает человек.
        // ACTION_CALL потребовал бы разрешения CALL_PHONE — права звонить за него.
        start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))

    override fun share(text: String): Boolean = start(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
            null,
        ),
    )

    private fun start(intent: Intent): Boolean {
        val context = AndroidContacts.contextOrNull() ?: return false
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }
}

actual fun platformInvite(): Invite = AndroidInvite
