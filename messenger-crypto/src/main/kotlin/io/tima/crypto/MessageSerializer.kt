package io.tima.crypto

import com.github.luben.zstd.Zstd
import io.tima.crypto.proto.ContentKind
import io.tima.crypto.proto.Envelope
import io.tima.crypto.proto.EscrowBlob as ProtoEscrowBlob
import io.tima.crypto.proto.MessageBody
import io.tima.crypto.proto.Metadata
import io.tima.crypto.proto.WrappedKey
import okio.ByteString.Companion.toByteString

/**
 * Сериализация wire-форматов (crypto-protocol.md §9, ADR-0009).
 *
 * Body: `MessageBody --protobuf(Wire)--> bytes --zstd--> plaintext конверта` (сжатие СТРОГО
 * до шифрования). Protobuf-байты MessageBody заморожены вектором `message_body` —
 * Wire-сериализация нормативна для Go-реализации; zstd-вывод по байтам НЕ нормативен
 * (нормативна распаковка).
 *
 * Envelope: чистый protobuf без сжатия (внутри уже ciphertext) — тело POST /messages.
 */
object MessageSerializer {

    /** Уровень zstd; на вывод не влияет ни один контракт — только распаковка нормативна. */
    private const val ZSTD_LEVEL = 3

    /** Потолок распакованного body — защита от zstd-бомбы в конверте. */
    const val MAX_BODY_BYTES: Long = 16L * 1024 * 1024

    // ── MessageBody: protobuf + zstd ──

    /** `zstd(protobuf(body))` — вход для [EnvelopeCipher.seal]. */
    fun encodeBody(body: MessageBody): ByteArray =
        Zstd.compress(MessageBody.ADAPTER.encode(body), ZSTD_LEVEL)

    /** Обратный ход после [EnvelopeCipher.open]: `parse(unzstd(payload))`. */
    fun decodeBody(payload: ByteArray): Result<MessageBody> = runCatching {
        val size = Zstd.getFrameContentSize(payload)
        check(size in 0..MAX_BODY_BYTES) { "Недопустимый размер body: $size байт (max $MAX_BODY_BYTES)" }
        MessageBody.ADAPTER.decode(Zstd.decompress(payload, size.toInt()))
    }

    // ── Envelope: protobuf (без сжатия) ──

    fun encodeEnvelope(message: SealedPersonalMessage): ByteArray =
        Envelope.ADAPTER.encode(message.toProto())

    fun decodeEnvelope(bytes: ByteArray): Result<SealedPersonalMessage> = runCatching {
        Envelope.ADAPTER.decode(bytes).toSealed()
    }

    private fun SealedPersonalMessage.toProto(): Envelope = Envelope(
        format_version = formatVersion,
        meta = Metadata(
            message_id = meta.messageId.toLong(),
            chat_id = meta.chatId,
            sender_id = meta.senderId,
            sender_device = meta.senderDevice,
            kind = requireNotNull(ContentKind.fromValue(meta.kind)) { "Неизвестный ContentKind: ${meta.kind}" },
            created_at_unix_ms = meta.createdAtUnixMs,
            reply_to = meta.replyTo.toLong(),
        ),
        encrypted_payload = encryptedPayload.toByteString(),
        escrow = ProtoEscrowBlob(
            mlkem_ct = escrow.mlkemCt.toByteString(),
            wrapped_message_key = escrow.wrappedMessageKey.toByteString(),
            escrow_key_version = escrow.escrowKeyVersion,
        ),
        sender_ephemeral_pub = senderEphemeralPub.toByteString(),
        ratchet_envelope = ratchetEnvelope.toByteString(),
        signature = signature.toByteString(),
        wrapped_keys = wrappedKeys.map { (recipient, wrapped) ->
            WrappedKey(recipient = recipient, wrapped = wrapped.toByteString())
        },
    )

    private fun Envelope.toSealed(): SealedPersonalMessage {
        val meta = requireNotNull(meta) { "Envelope без meta" }
        val escrow = requireNotNull(escrow) { "Envelope без escrow (ADR-0004: escrow обязателен)" }
        return SealedPersonalMessage(
            formatVersion = format_version,
            meta = EnvelopeMeta(
                messageId = meta.message_id.toULong(),
                chatId = meta.chat_id,
                senderId = meta.sender_id,
                senderDevice = meta.sender_device,
                kind = meta.kind.value,
                createdAtUnixMs = meta.created_at_unix_ms,
                replyTo = meta.reply_to.toULong(),
            ),
            encryptedPayload = encrypted_payload.toByteArray(),
            escrow = EscrowBlob(
                mlkemCt = escrow.mlkem_ct.toByteArray(),
                wrappedMessageKey = escrow.wrapped_message_key.toByteArray(),
                escrowKeyVersion = escrow.escrow_key_version,
            ),
            senderEphemeralPub = sender_ephemeral_pub.toByteArray(),
            ratchetEnvelope = ratchet_envelope.toByteArray(),
            signature = signature.toByteArray(),
            wrappedKeys = wrapped_keys.associate { it.recipient to it.wrapped.toByteArray() },
        )
    }
}
