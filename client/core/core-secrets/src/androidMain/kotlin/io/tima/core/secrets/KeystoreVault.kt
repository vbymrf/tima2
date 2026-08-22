package io.tima.core.secrets

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Хранилище Android: секрет лежит файлом, зашифрованным ключом из **AndroidKeyStore**.
 *
 * **Почему не «просто файл в приватном каталоге приложения».** Приватный каталог
 * закрыт от других приложений, но не от того, кто снял образ памяти устройства или
 * получил root. Ключ AndroidKeyStore из процесса **не извлекается вообще** — на
 * устройствах с аппаратным хранилищем он живёт в отдельном чипе, и приложение может
 * только попросить им зашифровать. Скопированный файл без этого ключа бесполезен.
 *
 * **Чего здесь сознательно нет.**
 *
 * `setUnlockedDeviceRequired(true)` не ставится. Соблазн есть — но это ровно тот же
 * выбор, что и `AfterFirstUnlock` на Apple: мессенджер обязан достать сообщение, пока
 * телефон лежит в кармане заблокированным. Потребуй разблокировки — и доставка в фоне
 * не сможет расшифровать локальную базу: уведомление придёт, а сообщения не будет.
 *
 * StrongBox (`setIsStrongBoxBacked`) не запрашивается: на устройствах без него вызов
 * бросает исключение, и обвязка «попробовать, поймать, повторить без него» стоит
 * дороже выигрыша. Вернуть — когда появится измеренная нужда.
 */
internal class KeystoreVault(
    context: Context,
    private val scope: String,
) : SecretVault {

    private val directory = File(File(context.filesDir, "tima-secrets"), scope)

    init {
        directory.mkdirs()
    }

    override fun put(alias: SecretAlias, secret: ByteArray) {
        require(secret.isNotEmpty()) { "секрет пустой" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, keyForScope())
        }
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(secret)

        // Пишем во временный файл и переименовываем: прерванная на середине запись не
        // должна оставить обрезанный шифртекст вместо рабочего секрета — это была бы
        // потеря локальной базы без всякого злоумышленника.
        val target = fileOf(alias)
        val temp = File(target.path + ".new")
        temp.writeBytes(byteArrayOf(iv.size.toByte()) + iv + ciphertext)
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) {
                temp.delete()
                throw SecretVaultFailure("не удалось заменить секрет ${alias.value}")
            }
        }
    }

    override fun get(alias: SecretAlias): ByteArray? {
        val file = fileOf(alias)
        if (!file.isFile) return null
        val bytes = file.readBytes()
        if (bytes.size < 2) throw SecretVaultFailure("запись ${alias.value} обрезана")

        val ivSize = bytes[0].toInt()
        if (ivSize <= 0 || bytes.size <= 1 + ivSize) {
            throw SecretVaultFailure("запись ${alias.value} испорчена: длина вектора $ivSize")
        }
        val iv = bytes.copyOfRange(1, 1 + ivSize)
        val ciphertext = bytes.copyOfRange(1 + ivSize, bytes.size)

        return try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, keyForScope(), GCMParameterSpec(TAG_BITS, iv))
                doFinal(ciphertext)
            }
        } catch (e: Throwable) {
            // Не `null`: «нет секрета» и «секрет есть, но не читается» — разные беды.
            // Первое означает первый запуск и рождение нового ключа; принять за первое
            // второе значило бы молча выбросить локальную базу.
            throw SecretVaultFailure(
                "секрет ${alias.value} есть, но не расшифровывается: ключ хранилища " +
                    "заменён (переустановка, сброс блокировки экрана) или файл испорчен",
                e,
            )
        }
    }

    override fun remove(alias: SecretAlias): Boolean = fileOf(alias).delete()

    private fun fileOf(alias: SecretAlias) = File(directory, "${alias.value}.bin")

    /**
     * Ключ AES-256 в AndroidKeyStore, свой на каждый [scope]. Создаётся при первом
     * обращении и переживает перезапуски; из процесса не извлекается.
     */
    private fun keyForScope(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val alias = "tima-vault-$scope"
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    // Каждый вызов шифрования обязан идти со своим вектором: GCM с
                    // повторённым вектором на том же ключе раскрывает открытый текст.
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}

/**
 * Контекст приложения для хранилища секретов.
 *
 * **Почему так, а не параметром.** Общая подпись `platformVault(scope)` контекста не
 * знает — и не должна: на ПК и на Apple его нет. Передавать его через все слои ради
 * одной платформы значило бы протащить Android в общий код. Поэтому приложение
 * отдаёт контекст один раз при запуске, из `Application.onCreate`.
 */
object AndroidSecrets {

    @Volatile
    private var appContext: Context? = null

    /** Вызывается из `Application.onCreate` — до любого обращения к хранилищу. */
    fun install(context: Context) {
        appContext = context.applicationContext
    }

    internal fun context(): Context = appContext ?: throw SecretVaultFailure(
        "AndroidSecrets.install(context) не вызван: хранилищу секретов нужен контекст " +
            "приложения, и взять его самому оно не может",
    )
}

/** Android: файл, зашифрованный ключом из AndroidKeyStore. */
actual fun platformVault(scope: String): SecretVault =
    KeystoreVault(AndroidSecrets.context(), scope)
