package io.tima.spike

import com.squareup.wire.ProtoAdapter
import io.kodium.Kodium
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Спайк К1.4, общая часть: зависимости, у которых артефакты Apple ЕСТЬ.
 *
 * **Символы вызываются, а не только компилируются.** Компиляция доказывает, что
 * имена нашлись, и не более: в v1 реализация ML-KEM в Kodium компилировалась,
 * работала, проходила тесты — и заполняла матрицу энтропией на 3 % (ADR-0005
 * Поправка-1). Поэтому здесь считаются настоящие значения.
 *
 * API взяты из `messenger-crypto`, где они используются, а не угаданы.
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
    fun wire_рантайм_кодирует_и_разбирает() {
        val bytes = ProtoAdapter.STRING.encode("узлы вместо плоского текста")
        assertEquals("узлы вместо плоского текста", ProtoAdapter.STRING.decode(bytes))
    }
}
