package io.tima.app.chat

import io.tima.app.session.Session

/**
 * Один ChatClient на весь процесс.
 *
 * Раньше клиент создавался в композиции и закрывался вместе с экраном: свернул
 * приложение — WS оборвался, входящий звонок приходить было некуда. Теперь соединение
 * переживает закрытие экрана, а живым его держит foreground-сервис.
 *
 * Трогать только с главного потока (экран и сервис — оба на нём).
 */
object ChatClientHolder {
    private var deviceId: String? = null
    private var current: ChatClient? = null

    /** Клиент текущей сессии; при смене устройства старый закрывается. */
    fun get(session: Session): ChatClient {
        val existing = current
        if (existing != null && deviceId == session.deviceId) return existing
        existing?.close()
        return createChatClient(session).also {
            current = it
            deviceId = session.deviceId
        }
    }

    /** Клиент, если он уже создан; сессию не читает и ничего не создаёт. */
    fun peek(): ChatClient? = current

    /** Выход из аккаунта: соединение больше не нужно. */
    fun close() {
        current?.close()
        current = null
        deviceId = null
    }
}
