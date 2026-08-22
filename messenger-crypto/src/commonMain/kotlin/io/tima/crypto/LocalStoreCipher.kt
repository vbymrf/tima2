package io.tima.crypto

import io.kodium.Kodium
import io.kodium.ratchet.HKDF

/**
 * Шифрование покоя локальной базы — Plan.md §3.4.2 и §3.4.3, вариант A.
 *
 * `SecretBox(поле, ключ_покоя)`, где `ключ_покоя = HKDF(deviceSecret, "tima/local-store/v1")`.
 * Формат тот же, что у конверта: `nonce(24) ‖ box`.
 *
 * **Почему это лежит здесь, а не в клиенте.** Правило одно: слой NaCl — только Kodium и
 * только за обёрткой в этом модуле. Шифрование покоя — такая же обёртка, как
 * [EnvelopeCipher] и [MediaCipher]; вынеси её в клиент, и вызов SecretBox появится вторым
 * местом, а «сменить провайдера — правка одного файла» перестанет быть правдой.
 *
 * **Почему AEAD на поле, а не на файле базы (решение 2026-08-20, §3.4.3).** Шифрование
 * файла целиком дало бы и закрытые метаданные, и поиск по зашифрованному индексу — ценой
 * нативной зависимости на трёх платформах, включая сборку iOS. Для клиента, которого ещё
 * не существовало, это плата вперёд.
 *
 * **Что при этом утекает, если устройство и базу прочитали:** кто с кем, когда, сколько
 * сообщений, их состояния и размеры вложений. **Содержимое — нет.** Это честная граница
 * варианта A, и знать её надо заранее, а не из отчёта по безопасности.
 *
 * Метка HKDF **не меняется**: она печёная в уже записанные базы, и правка метки не
 * миграция, а потеря всей местной переписки. Список таких меток —
 * `.cursor/rules/crypto-invariants.mdc`.
 */
object LocalStoreCipher {

    /**
     * Метка вывода ключа покоя. Та же, что в v1: базы v1 читаются тем же ключом.
     *
     * Менять нельзя — см. `crypto-invariants.mdc`.
     */
    const val HKDF_LABEL: String = "tima/local-store/v1"

    /** Длина ключа SecretBox. */
    const val KEY_SIZE: Int = 32

    /**
     * Ключ покоя из секрета устройства.
     *
     * Секрет — те же 32 байта, из которых выводятся ключи устройства и личности; живёт он
     * в хранилище платформы (`core-secrets`), а не рядом с файлом базы. В v1 на ПК он
     * лежал открытым файлом рядом с базой, и шифрование покоя было **декоративным**: тот,
     * кто дошёл до файла базы, доходил и до ключа.
     */
    fun keyFromDeviceSecret(deviceSecret: ByteArray): ByteArray {
        require(deviceSecret.size == KEY_SIZE) {
            "секрет устройства должен быть $KEY_SIZE байта, а не ${deviceSecret.size}"
        }
        return HKDF.deriveSecrets(
            salt = null,
            ikm = deviceSecret,
            info = HKDF_LABEL.encodeToByteArray(),
            length = KEY_SIZE,
        )
    }

    /** Закрыть поле. Nonce берётся CSPRNG, поэтому два вызова дают разные байты. */
    fun seal(key: ByteArray, plaintext: ByteArray): Result<ByteArray> {
        require(key.size == KEY_SIZE) { "ключ покоя должен быть $KEY_SIZE байта" }
        return Kodium.encryptSymmetric(key, plaintext)
    }

    /**
     * Открыть поле.
     *
     * Провал — это **не поломка кода**: чужой ключ, испорченный файл или запись, сделанная
     * другим устройством, дают ровно это. Поэтому `Result`, а не исключение: вызывающий
     * показывает такую строку как нечитаемую, а не падает.
     */
    fun open(key: ByteArray, sealed: ByteArray): Result<ByteArray> {
        require(key.size == KEY_SIZE) { "ключ покоя должен быть $KEY_SIZE байта" }
        return Kodium.decryptSymmetric(key, sealed)
    }
}
