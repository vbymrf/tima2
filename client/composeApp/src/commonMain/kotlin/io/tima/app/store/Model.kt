package io.tima.app.store

/**
 * Состояние сообщения. Значения записаны в базу числами, поэтому менять их нельзя —
 * только добавлять новые.
 */
enum class MsgState(val code: Int) {
    /** Написано, ждёт отправки. Экран показывает часики. */
    QUEUED(0),

    /** Прямо сейчас шифруется и уходит. Отдельно от QUEUED, чтобы не отправить дважды. */
    SENDING(1),

    /** Сервер принял. */
    SENT(2),

    /** Собеседник прочитал (✓✓). */
    READ(3),

    /** Пришло от собеседника. */
    INCOMING(10),

    ;

    companion object {
        fun of(code: Int): MsgState = entries.firstOrNull { it.code == code } ?: INCOMING
    }
}

/**
 * Сообщение так, как оно лежит на устройстве.
 *
 * Метаданные — открыто, содержимое — под шифром (ADR-0016). [text] и [mediaJson]
 * здесь уже расшифрованы; шифрование живёт внутри [MessageStore] и наружу не течёт.
 */
data class StoredMessage(
    val localId: Long = 0,
    val chatId: String,
    /** Серверный идентификатор; 0 — сообщение ещё не на сервере. */
    val messageId: Long = 0,
    /** Свой идентификатор, рождается вместе с сообщением. По нему сервер отсекает повтор. */
    val clientMsgId: String,
    val senderId: String,
    val isGroup: Boolean = false,
    val createdAtMs: Long,
    val state: MsgState,
    val replyTo: Long = 0,
    val text: String = "",
    /**
     * Разметка (ADR-0011) — компактный JSON, тот же формат, что едет в конверте.
     * Пусто — сообщение без оформления, а это подавляющее большинство. Хранится
     * рядом с [text], а не вместо него: испорченная или нерасшифрованная разметка
     * не должна лишать сообщение текста (тот же принцип, что и у Markup.decode).
     */
    val markup: String = "",
    /**
     * Ссылка на УЖЕ выложенное вложение. Заполняется после успешной выгрузки —
     * повторная попытка отправки не выкладывает файл заново.
     */
    val mediaJson: String = "",
    /** Вид сообщения: 1 текст, 2 голосовое, 3 фото, 5 файл (crypto-protocol CK_*). */
    val kind: Int = 1,
    /**
     * Описание ждущего вложения. САМИ БАЙТЫ здесь не лежат: файл бывает на десятки
     * мегабайт, а список чата читается на каждое открытие экрана — держать их в
     * каждой строке значило бы поднимать всю переписку в память.
     * Байты берутся отдельно, [MessageStore.attachmentBytes], только на отправку.
     */
    val attachment: OutboxAttachment? = null,
) {
    /** Своё ли это сообщение (в отличие от пришедшего). */
    val mine: Boolean get() = state != MsgState.INCOMING

    /** Ждёт отправки — экран показывает часики, а не галочку. */
    val pending: Boolean get() = state == MsgState.QUEUED || state == MsgState.SENDING
}

/** Описание вложения, ждущего отправки. Без байтов — они хранятся отдельно. */
data class OutboxAttachment(
    val mime: String,
    val name: String = "",
    val durationMs: Int = 0,
    val sizeBytes: Long = 0,
)

/** Чат так, как он лежит на устройстве. [title] расшифрован. */
data class StoredChat(
    val chatId: String,
    val peerUserId: String = "",
    val isGroup: Boolean = false,
    val title: String = "",
    val lastText: String = "",
    val lastAtMs: Long = 0,
    val unread: Int = 0,
    val archivedAtMs: Long = 0,
)
