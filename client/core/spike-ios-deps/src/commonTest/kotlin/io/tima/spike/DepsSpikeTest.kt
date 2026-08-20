package io.tima.spike

import asia.hombre.kyber.KyberCipherText
import asia.hombre.kyber.KyberDecapsulationKey
import asia.hombre.kyber.KyberEncapsulationKey
import asia.hombre.kyber.api.MLKEM_768
import com.squareup.wire.ProtoAdapter
import io.kodium.Kodium
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Спайк К1.4: зависимости крипто-ядра работают на каждом таргете, включая iOS.
 *
 * **Символы вызываются, а не только компилируются.** Компиляция доказывает, что имена
 * нашлись, и не более: в v1 реализация ML-KEM в Kodium компилировалась, работала,
 * проходила тесты — и заполняла матрицу энтропией на 3 % (ADR-0005 Поправка-1).
 * Поэтому здесь считаются настоящие значения и сверяются размеры.
 *
 * API взяты из `messenger-crypto` (`Mlkem768.kt`, `EnvelopeCipher.kt`), а не угаданы.
 *
 * Модуль удаляется в К2, когда ответ получен и крипто переехало по-настоящему.
 */
class DepsSpikeTest {

    @Test
    fun kodium_симметричное_шифрование_туда_обратно() {
        val key = Kodium.generateHighEntropyKey()
        val plaintext = "проверка на всех таргетах".encodeToByteArray()

        val sealed = Kodium.encryptSymmetric(key, plaintext).getOrThrow()
        assertTrue(sealed.size > plaintext.size, "шифртекст должен быть длиннее: nonce ‖ box")

        val opened = Kodium.decryptSymmetric(key, sealed).getOrThrow()
        assertContentEquals(plaintext, opened, "Kodium: расшифрованное не совпало")
    }

    @Test
    fun mlkem768_общий_секрет_совпадает_и_размеры_канонические() {
        // Размеры FIPS 203 — те же константы, что в Mlkem768.kt. Если провайдер на
        // iOS отдаст другие, это не «другая реализация», это несовместимость.
        val pair = MLKEM_768().generate()
        val publicKey = pair.encapsulationKey.fullBytes
        val secretKey = pair.decapsulationKey.fullBytes
        assertEquals(1184, publicKey.size, "ML-KEM-768 public key")
        assertEquals(2400, secretKey.size, "ML-KEM-768 secret key")

        val encapsulated = KyberEncapsulationKey.fromBytes(publicKey).encapsulate()
        assertEquals(1088, encapsulated.cipherText.fullBytes.size, "ML-KEM-768 ciphertext")
        assertEquals(32, encapsulated.sharedSecretKey.size, "общий секрет")

        val decapsulated = KyberCipherText.fromBytes(encapsulated.cipherText.fullBytes)
            .decapsulate(KyberDecapsulationKey.fromBytes(secretKey))

        assertContentEquals(
            encapsulated.sharedSecretKey,
            decapsulated,
            "ML-KEM-768: общий секрет не совпал — провайдер несовместим",
        )
    }

    @Test
    fun wire_рантайм_кодирует_и_разбирает() {
        val bytes = ProtoAdapter.STRING.encode("узлы вместо плоского текста")
        assertEquals("узлы вместо плоского текста", ProtoAdapter.STRING.decode(bytes))
    }
}
