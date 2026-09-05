package io.tima.core.contacts

/**
 * ПК: приглашать нечем.
 *
 * Ни приложения сообщений, ни звонилки, ни системного «поделиться» в том виде, в каком
 * они есть у телефона. Каждый способ честно отвечает «не вышло», и экран говорит это
 * словами — вместо кнопки, которая молча ничего не делает.
 */
private object NoInvite : Invite {
    override fun sms(phone: String, text: String): Boolean = false
    override fun call(phone: String): Boolean = false
    override fun share(text: String): Boolean = false
}

actual fun platformInvite(): Invite = NoInvite
