package io.tima.core.contacts

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * Apple: `sms:` и `tel:` через `UIApplication`.
 *
 * **Системного «поделиться» здесь нет.** `UIActivityViewController` требует активного
 * контроллера, а брать его из общего кода значило бы протащить UIKit в место, где о нём
 * знать не должны. До появления своего экрана приглашения способ честно отвечает «не
 * вышло», и человек видит два оставшихся.
 */
private object AppleInvite : Invite {

    override fun sms(phone: String, text: String): Boolean =
        open("sms:$phone&body=${text.replace(" ", "%20")}")

    override fun call(phone: String): Boolean = open("tel:$phone")

    override fun share(text: String): Boolean = false

    private fun open(raw: String): Boolean {
        val url = NSURL.URLWithString(raw) ?: return false
        val app = UIApplication.sharedApplication
        if (!app.canOpenURL(url)) return false
        app.openURL(url)
        return true
    }
}

actual fun platformInvite(): Invite = AppleInvite
