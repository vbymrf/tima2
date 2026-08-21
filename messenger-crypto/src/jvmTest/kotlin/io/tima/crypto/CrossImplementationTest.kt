package io.tima.crypto

import io.kodium.core.nacl
import io.kodium.ratchet.HKDF
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.XSalsa20Engine
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * Дифференциальная сверка NaCl-слоя Kodium со второй, независимой реализацией
 * (BouncyCastle) на множестве случайных входов.
 *
 * Зачем это отдельно от [VectorsTest]. Тот пиннит по ОДНОМУ входу на примитив и
 * ловит систематическую ошибку — так был пойман ML-KEM (ADR-0005 Поправка-1).
 * Здесь входов много, включая краевые длины, и ловится другое: ошибка,
 * зависящая от входа. Оба известных дефекта Kodium — сломанная внутренняя SHA-512,
 * из-за которой Ed25519 не проверялся внешними системами, и усечённый SHAKE в
 * ML-KEM — были ошибками СОВМЕСТИМОСТИ. Изнутри они невидимы: реализация
 * самосогласована, round-trip зелёный. Ловит только вторая реализация.
 *
 * Тест рандомизирован, поэтому **seed входит в текст каждого падения** — Gradle
 * не показывает stdout тестов, и печатать его в консоль бесполезно. Взяв seed из
 * сообщения об ошибке, прогон воспроизводят точно:
 *
 *     ./gradlew test -Dtima.crossimpl.seed=<seed из сообщения>
 */
class CrossImplementationTest {

    private val seed: Long =
        System.getProperty("tima.crossimpl.seed")?.toLongOrNull() ?: Random.nextLong()

    private val rnd = Random(seed)

    private fun bytes(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }

    /** Длины вокруг границ блоков — там живут ошибки смещения. */
    private val edgeLengths = intArrayOf(0, 1, 15, 16, 17, 31, 32, 33, 63, 64, 65, 127, 128, 129)

    private fun lengthFor(i: Int, bound: Int) =
        if (i < edgeLengths.size) edgeLengths[i] else rnd.nextInt(bound)

    // ── SecretBox ────────────────────────────────────────────────────────────

    /**
     * `crypto_secretbox` = XSalsa20 + Poly1305. У BouncyCastle нет готового
     * secretbox, но есть оба примитива, и конструкция определена однозначно.
     *
     * ВАЖНО: в NaCl первые 32 байта потока — одноразовый ключ Poly1305, а
     * шифрование начинается со смещения **32**. В IETF-конструкции
     * (ChaCha20-Poly1305) оно начинается с блока 1, то есть со смещения 64.
     * Перепутать легко: с 64 сходится только пустое сообщение.
     */
    private fun bcSecretBox(message: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        val engine = XSalsa20Engine().apply {
            init(true, ParametersWithIV(KeyParameter(key), nonce))
        }
        val macKey = ByteArray(32)
        engine.processBytes(ByteArray(32), 0, 32, macKey, 0)

        val ct = ByteArray(message.size)
        engine.processBytes(message, 0, message.size, ct, 0)

        val mac = ByteArray(16)
        Poly1305().apply {
            init(KeyParameter(macKey))
            update(ct, 0, ct.size)
            doFinal(mac, 0)
        }
        return mac + ct
    }

    @Test
    fun `secretbox - Kodium совпадает с XSalsa20-Poly1305 из BouncyCastle`() {
        repeat(ITERATIONS) { i ->
            val message = bytes(lengthFor(i, 2048))
            val nonce = bytes(nacl.SecretBox.NonceSize)
            val key = bytes(nacl.SecretBox.KeySize)

            assertContentEquals(
                bcSecretBox(message, nonce, key),
                nacl.SecretBox.seal(message, nonce, key),
                "secretbox разошёлся при длине ${message.size}; seed=$seed",
            )
        }
    }

    // ── X25519 ───────────────────────────────────────────────────────────────

    @Test
    fun `x25519 - публичный ключ из секретного совпадает с BouncyCastle`() {
        repeat(ITERATIONS) {
            val secret = bytes(32)

            // Порядок в Pair не задокументирован — различаем по тому, что не равно секрету.
            val pair = nacl.Box.keyPairFromSecretKey(secret)
            val kodiumPublic = if (!pair.first.contentEquals(secret)) pair.first else pair.second

            assertContentEquals(
                X25519PrivateKeyParameters(secret, 0).generatePublicKey().encoded,
                kodiumPublic,
                "X25519 разошёлся; seed=$seed",
            )
        }
    }

    // ── Ed25519 ──────────────────────────────────────────────────────────────

    /**
     * Проверяем именно межреализационное принятие: подписал Kodium — принял
     * BouncyCastle. Это ровно тот сценарий, который ломался у Kodium раньше
     * («Ed25519 detached signatures failed verification against external systems»),
     * и он же нужен нам: подписи конвертов проверяет сервер на Go.
     */
    @Test
    fun `ed25519 - подпись Kodium принимается BouncyCastle`() {
        repeat(ITERATIONS) { i ->
            val pair = nacl.Sign.keyPairFromSeed(bytes(32))
            val publicKey = if (pair.first.size == 32) pair.first else pair.second
            val secretKey = if (pair.first.size == 64) pair.first else pair.second

            val message = bytes(lengthFor(i, 512))
            val signature = nacl.Sign.signDetached(message, secretKey)

            val verifier = Ed25519Signer().apply {
                init(false, Ed25519PublicKeyParameters(publicKey, 0))
                update(message, 0, message.size)
            }
            assertTrue(
                verifier.verifySignature(signature),
                "BouncyCastle отверг подпись Kodium при длине ${message.size}; seed=$seed",
            )
        }
    }

    // ── HKDF-SHA256 ──────────────────────────────────────────────────────────

    private fun bcHkdf(salt: ByteArray?, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest()).apply {
            init(HKDFParameters(ikm, salt, info))
        }
        return ByteArray(length).also { generator.generateBytes(it, 0, length) }
    }

    @Test
    fun `hkdf sha256 - Kodium совпадает с BouncyCastle`() {
        repeat(ITERATIONS) {
            val salt = bytes(rnd.nextInt(33))
            val ikm = bytes(1 + rnd.nextInt(64))
            val info = bytes(rnd.nextInt(32))
            val length = 1 + rnd.nextInt(128)

            assertContentEquals(
                bcHkdf(salt, ikm, info, length),
                HKDF.deriveSecrets(salt = salt, ikm = ikm, info = info, length = length),
                "HKDF разошёлся при length=$length; seed=$seed",
            )
        }
    }

    /**
     * Отдельно: `salt = null`. Это НАШ боевой путь — так вызывают
     * [AccountMnemonic], [MediaCipher] и [EscrowModule]. По RFC 5869 отсутствие
     * соли означает нули длиной с выход хеша, и обе реализации обязаны понимать
     * это одинаково.
     */
    @Test
    fun `hkdf sha256 - без соли обе реализации трактуют RFC 5869 одинаково`() {
        repeat(ITERATIONS) {
            val ikm = bytes(1 + rnd.nextInt(64))
            val info = bytes(rnd.nextInt(32))
            val length = 1 + rnd.nextInt(128)

            assertContentEquals(
                bcHkdf(null, ikm, info, length),
                HKDF.deriveSecrets(salt = null, ikm = ikm, info = info, length = length),
                "HKDF без соли разошёлся при length=$length; seed=$seed",
            )
        }
    }

    // ── ML-KEM-768 ───────────────────────────────────────────────────────────

    /**
     * Провайдер ML-KEM у нас KyberKotlin (ADR-0005 Поправка-2), и его обязан
     * понимать будущий HSM. Сверяем в обе стороны: escrow работает только если
     * анклав декапсулирует наш шифртекст и наоборот.
     */
    @Test
    fun `mlkem768 - KyberKotlin и BouncyCastle совместимы в обе стороны`() {
        val parameters = MLKEMParameters.ml_kem_768
        repeat(KEM_ITERATIONS) {
            val (ourPublic, ourSecret) = Mlkem768.keyPair()

            // Их инкапсуляция — наша декапсуляция.
            val encapsulated = MLKEMGenerator(SecureRandom())
                .generateEncapsulated(MLKEMPublicKeyParameters(parameters, ourPublic))
            assertContentEquals(
                encapsulated.secret,
                Mlkem768.decapsulate(encapsulated.encapsulation, ourSecret),
                "BouncyCastle -> KyberKotlin разошлись; seed=$seed",
            )

            // Наша инкапсуляция — их декапсуляция.
            val generator = MLKEMKeyPairGenerator().apply {
                init(MLKEMKeyGenerationParameters(SecureRandom(), parameters))
            }
            val theirPair = generator.generateKeyPair()
            val theirPublic = (theirPair.public as MLKEMPublicKeyParameters).encoded
            val theirSecret = (theirPair.private as MLKEMPrivateKeyParameters).encoded

            val (ourShared, ourCiphertext) = Mlkem768.encapsulate(theirPublic)
            assertContentEquals(
                ourShared,
                MLKEMExtractor(MLKEMPrivateKeyParameters(parameters, theirSecret)).extractSecret(ourCiphertext),
                "KyberKotlin -> BouncyCastle разошлись; seed=$seed",
            )
        }
    }

    /**
     * Implicit rejection по FIPS 203: повреждённый шифртекст даёт ДРУГОЙ общий
     * секрет, а не ошибку и не null. На этом стоит наш тест «повреждённый
     * escrow_blob — unwrap падает, а не возвращает мусор»: провал ловится MAC-ом
     * SecretBox, а не декапсуляцией.
     */
    @Test
    fun `mlkem768 - повреждённый шифртекст даёт другой секрет, а не отказ`() {
        repeat(KEM_ITERATIONS) {
            val (publicKey, secretKey) = Mlkem768.keyPair()
            val (shared, ciphertext) = Mlkem768.encapsulate(publicKey)

            val damaged = ciphertext.copyOf()
            val at = rnd.nextInt(damaged.size)
            damaged[at] = (damaged[at].toInt() xor 1).toByte()

            val rejected = Mlkem768.decapsulate(damaged, secretKey)
            assertTrue(rejected != null, "декапсуляция вернула null вместо implicit rejection; seed=$seed")
            assertTrue(
                !rejected.contentEquals(shared),
                "повреждённый ct дал ТОТ ЖЕ секрет — implicit rejection не работает; seed=$seed",
            )
        }
    }

    private companion object {
        const val ITERATIONS = 500

        /** ML-KEM на порядок дороже симметрики — меньше прогонов, тот же смысл. */
        const val KEM_ITERATIONS = 50
    }
}
