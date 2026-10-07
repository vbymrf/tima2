package io.tima.core.words

/**
 * Настройки → «Аккаунты» (было «Виртуальные аккаунты», заказчик 2026-10-07): все аккаунты
 * устройства, разделитель основных и виртуальных, пин-код у текущего.
 */
interface AccountListWords {
    val main: String
    val virtuals: String
    val current: String
    val virtualMark: String
    /** Подпись под значком замка — «пин текстом снизу, сверху иконка». */
    val pin: String
}

object RussianAccountListWords : AccountListWords {
    override val main = "Основной"
    override val virtuals = "Виртуальные"
    override val current = "сейчас"
    override val virtualMark = "виртуальный"
    override val pin = "Пин"
}

object EnglishAccountListWords : AccountListWords {
    override val main = "Main"
    override val virtuals = "Virtual"
    override val current = "current"
    override val virtualMark = "virtual"
    override val pin = "PIN"
}

object SpanishAccountListWords : AccountListWords {
    override val main = "Principal"
    override val virtuals = "Virtuales"
    override val current = "ahora"
    override val virtualMark = "virtual"
    override val pin = "PIN"
}
