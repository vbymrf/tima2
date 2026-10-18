package io.tima.core.words

/**
 * Закреплённое сообщение и окно «Описание» (doc_mig/ПЛАН-(ЗО)-ЗАКРЕПЛЕНИЯ-И-ОПИСАНИЯ.md, ЗО3–ЗО5).
 */
interface ShowcaseWords {
    val menuPin: String
    val menuUnpin: String
    /** Полоса под шапкой: закреплённое не загружено — заглушка. */
    val pinnedStub: String
    val pinnedCaption: String
    /**
     * Служебная строка в ленте — словами читающего, без уведомления. [who] `null` — это я.
     * [on] — закрепили; иначе сняли.
     */
    fun pinLine(who: String?, on: Boolean): String
    /** Отказ сервера: `not_moderator`, `message_not_pinnable` и прочие; `offline` — нет сети. */
    fun pinRefused(code: String): String

    val title: String
    val loading: String
    val failed: String
    val empty: String
    val join: String
    val subscribe: String
    val makeSuper: String
    val changeSuper: String
    val superSet: String
    val superFailed: String
    /** Сообщение уровня 0 ушло из переписки в описание — в ленте его не будет. */
    val sentToShowcase: String
    /** Окно одного сообщения описания — «без ничего». */
    val single: String
}

object RussianShowcaseWords : ShowcaseWords {
    override val menuPin = "Закрепить"
    override val menuUnpin = "Открепить"
    override val pinnedStub = "Сообщение закреплено"
    override val pinnedCaption = "Закреплено"
    override fun pinLine(who: String?, on: Boolean) = when {
        who == null && on -> "Вы закрепили сообщение"
        who == null -> "Вы открепили сообщение"
        on -> "$who закрепил(а) сообщение"
        else -> "$who открепил(а) сообщение"
    }
    override fun pinRefused(code: String) = when (code) {
        "not_moderator" -> "Закрепляет модератор и выше"
        "message_not_pinnable" -> "Это сообщение закрепить нельзя: его нет или оно удалено"
        "offline" -> "Нет связи — закрепление не изменилось"
        else -> "Закрепление не изменилось: $code"
    }
    override val title = "Описание"
    override val loading = "Загружаю описание…"
    override val failed = "Описание не загрузилось — проверьте связь"
    override val empty = "Описания пока нет"
    override val join = "Вступить"
    override val subscribe = "Подписаться"
    override val makeSuper = "Создать суперсообщение"
    override val changeSuper = "Изменить суперсообщение"
    override val superSet = "Суперсообщение поставлено"
    override val superFailed = "Суперсообщение не поставлено — проверьте связь"
    override val sentToShowcase = "Ушло в описание: в переписке его нет, оно в окне «Описание»"
    override val single = "Сообщение описания"
}

object EnglishShowcaseWords : ShowcaseWords {
    override val menuPin = "Pin"
    override val menuUnpin = "Unpin"
    override val pinnedStub = "Message pinned"
    override val pinnedCaption = "Pinned"
    override fun pinLine(who: String?, on: Boolean) = when {
        who == null && on -> "You pinned a message"
        who == null -> "You unpinned a message"
        on -> "$who pinned a message"
        else -> "$who unpinned a message"
    }
    override fun pinRefused(code: String) = when (code) {
        "not_moderator" -> "Only moderators and above can pin"
        "message_not_pinnable" -> "This message can't be pinned: it's gone or deleted"
        "offline" -> "No connection — the pin didn't change"
        else -> "The pin didn't change: $code"
    }
    override val title = "About"
    override val loading = "Loading the description…"
    override val failed = "The description didn't load — check the connection"
    override val empty = "No description yet"
    override val join = "Join"
    override val subscribe = "Subscribe"
    override val makeSuper = "Create a super message"
    override val changeSuper = "Change the super message"
    override val superSet = "Super message set"
    override val superFailed = "Super message not set — check the connection"
    override val sentToShowcase = "Went to the description: it's not in the chat, it's in “About”"
    override val single = "Description message"
}

object SpanishShowcaseWords : ShowcaseWords {
    override val menuPin = "Fijar"
    override val menuUnpin = "Desfijar"
    override val pinnedStub = "Mensaje fijado"
    override val pinnedCaption = "Fijado"
    override fun pinLine(who: String?, on: Boolean) = when {
        who == null && on -> "Usted fijó un mensaje"
        who == null -> "Usted desfijó un mensaje"
        on -> "$who fijó un mensaje"
        else -> "$who desfijó un mensaje"
    }
    override fun pinRefused(code: String) = when (code) {
        "not_moderator" -> "Solo moderadores y superiores pueden fijar"
        "message_not_pinnable" -> "Este mensaje no se puede fijar: no existe o fue eliminado"
        "offline" -> "Sin conexión: el mensaje fijado no cambió"
        else -> "El mensaje fijado no cambió: $code"
    }
    override val title = "Descripción"
    override val loading = "Cargando la descripción…"
    override val failed = "La descripción no se cargó: revise la conexión"
    override val empty = "Aún no hay descripción"
    override val join = "Unirse"
    override val subscribe = "Suscribirse"
    override val makeSuper = "Crear un supermensaje"
    override val changeSuper = "Cambiar el supermensaje"
    override val superSet = "Supermensaje establecido"
    override val superFailed = "Supermensaje no establecido: revise la conexión"
    override val sentToShowcase = "Pasó a la descripción: no está en el chat, está en «Descripción»"
    override val single = "Mensaje de la descripción"
}
