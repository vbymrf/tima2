package io.tima.crypto

import org.kotlincrypto.hash.sha2.SHA256

/**
 * Идентификатор личной переписки — **выводится, а не назначается сервером**.
 *
 * **Почему это протокольный инвариант, а не деталь клиента.** Сервер `chat_id` не
 * придумывает и не проверяет: он берёт то, что лежит в `meta.chat_id` подписанного
 * конверта, и по нему же выдаёт ключ эпохи escrow. Значит обе стороны обязаны считать
 * **одно и то же число**, ни о чём не договариваясь: иначе собеседники окажутся в
 * разных переписках, и это будет выглядеть как «сообщения не приходят», хотя дошло всё.
 *
 * Раскладка перенесена из v1 (`TimaChatService.personalChatId`) **дословно**, и менять
 * её нельзя по той же причине, по которой нельзя менять вывод ключей устройства: смена
 * означает, что вся прежняя переписка разъезжается на две. Известный ответ закреплён
 * тестом — если он упадёт, неверен наш код, а не тест.
 *
 * ```
 * sha256("tima.personal.chat|" + меньший + "|" + больший)
 *   → первые 16 байт, биты версии/варианта UUID выставлены
 *   → запись UUID через дефисы
 * ```
 *
 * Порядок сторон не важен намеренно: сортировка делает вывод одинаковым у обоих.
 */
object PersonalChatId {

    /** Доменная метка: без неё тот же хэш годился бы для другой роли. */
    private const val LABEL = "tima.personal.chat|"

    fun of(userA: String, userB: String): String {
        require(userA.isNotBlank() && userB.isNotBlank()) { "идентификатор пользователя пустой" }

        val меньший = if (userA <= userB) userA else userB
        val больший = if (userA <= userB) userB else userA

        val h = SHA256().digest("$LABEL$меньший|$больший".encodeToByteArray())

        // Биты версии (4) и варианта (RFC 4122) — чтобы получился валидный UUID, а не
        // просто шестнадцатеричная строка: он ложится в столбцы типа uuid на сервере.
        h[6] = ((h[6].toInt() and 0x0f) or 0x40).toByte()
        h[8] = ((h[8].toInt() and 0x3f) or 0x80).toByte()

        val hex = buildString(32) {
            for (i in 0 until 16) {
                val b = h[i].toInt() and 0xFF
                append(HEX[b shr 4])
                append(HEX[b and 0x0F])
            }
        }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
