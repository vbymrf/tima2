package io.tima.crypto

import io.kodium.ratchet.HKDF

/**
 * Шифрование медиа (crypto-protocol.md §5, media-storage.md).
 *
 * Малые файлы (< [CHUNK_THRESHOLD_BYTES]) — целиком одним SecretBox с `media_key`.
 * Большие — чанками: `chunk_key[i] = HKDF-SHA256(ikm = media_key, salt пустой,
 * info = "chunk:" + i десятичный, 32 байта)`, каждый чанк — отдельный SecretBox.
 * Деривация заморожена KAT-вектором `media_chunk_keys`.
 *
 * `media_key` передаётся получателю внутри `encrypted_payload` сообщения-указателя
 * (поле `MediaRef.media_key` в MessageBody) — под шифрованием конверта.
 * Сервер и MinIO видят только ciphertext.
 */
object MediaCipher {

    /** Порог чанкования (10 MB, §10.2 обзора); сам размер чанка выбирает отправитель. */
    const val CHUNK_THRESHOLD_BYTES: Int = 10 * 1024 * 1024

    /** Размер чанка по умолчанию — 4 MiB plaintext. */
    const val DEFAULT_CHUNK_BYTES: Int = 4 * 1024 * 1024

    // ── Целиком (фото, голосовые, документы) ──

    fun seal(mediaKey: ByteArray, plaintext: ByteArray): Result<ByteArray> =
        EnvelopeCipher.seal(mediaKey, plaintext)

    fun open(mediaKey: ByteArray, sealed: ByteArray): Result<ByteArray> =
        EnvelopeCipher.open(mediaKey, sealed)

    // ── Чанками (видео, большие файлы) ──

    /** Шифрует один чанк своим производным ключом. Индексация с 0. */
    fun sealChunk(mediaKey: ByteArray, index: Int, chunkPlaintext: ByteArray): Result<ByteArray> =
        EnvelopeCipher.seal(chunkKey(mediaKey, index), chunkPlaintext)

    /** Расшифровывает чанк; MAC ловит и повреждение, и подмену чанков местами (ключ у каждого свой). */
    fun openChunk(mediaKey: ByteArray, index: Int, sealedChunk: ByteArray): Result<ByteArray> =
        EnvelopeCipher.open(chunkKey(mediaKey, index), sealedChunk)

    /** Нормативная деривация — KAT `media_chunk_keys`. */
    internal fun chunkKey(mediaKey: ByteArray, index: Int): ByteArray {
        require(index >= 0) { "Индекс чанка не может быть отрицательным" }
        return HKDF.deriveSecrets(salt = null, ikm = mediaKey, info = "chunk:$index".encodeToByteArray(), length = 32)
    }
}
