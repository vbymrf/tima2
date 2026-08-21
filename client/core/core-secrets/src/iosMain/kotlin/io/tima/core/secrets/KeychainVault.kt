@file:OptIn(ExperimentalForeignApi::class)

package io.tima.core.secrets

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy

/**
 * Хранилище Apple: Keychain, класс `kSecClassGenericPassword`.
 *
 * **Доступность выбрана осознанно и стоит объяснения**:
 * `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`.
 *
 * - `AfterFirstUnlock` — потому что мессенджер обязан достать сообщение, пока телефон
 *   лежит в кармане заблокированным. `WhenUnlocked` означал бы, что доставка в фоне не
 *   может расшифровать локальную базу, то есть уведомление приходит, а сообщения нет.
 * - `ThisDeviceOnly` — потому что иначе секрет попадёт в iCloud Keychain и в
 *   резервную копию. Тогда ключ локальной базы достаётся вместе с учётной записью
 *   Apple, и «шифрование покоя на устройстве» перестаёт значить сказанное.
 */
internal class KeychainVault(private val service: String) : SecretVault {

    override fun put(alias: SecretAlias, secret: ByteArray) {
        require(secret.isNotEmpty()) { "секрет пустой" }
        // SecItemAdd не перезаписывает: без удаления второй put вернул бы
        // errSecDuplicateItem, и смена секрета устройства молча не срабатывала бы.
        remove(alias)

        val data = secret.toCFData() ?: throw SecretVaultFailure("не удалось собрать CFData")
        val query = запрос(alias, 4)
        try {
            CFDictionarySetValue(query, kSecValueData, data)
            CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
            val status = SecItemAdd(query, null)
            if (status != errSecSuccess) {
                throw SecretVaultFailure("Keychain отказал при записи ${alias.value}: OSStatus $status")
            }
        } finally {
            CFRelease(data)
            CFRelease(query)
        }
    }

    override fun get(alias: SecretAlias): ByteArray? {
        val query = запрос(alias, 5)
        try {
            CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
            memScoped {
                val holder = alloc<CFTypeRefVar>()
                val status = SecItemCopyMatching(query, holder.ptr)
                if (status == errSecItemNotFound) return null
                if (status != errSecSuccess) {
                    // Не `null`: «нет секрета» означает первый запуск и рождение нового
                    // ключа. Принять за первый запуск отказ Keychain значило бы молча
                    // выбросить локальную переписку.
                    throw SecretVaultFailure("Keychain отказал при чтении ${alias.value}: OSStatus $status")
                }
                val data: CFDataRef = holder.value?.reinterpret()
                    ?: throw SecretVaultFailure("Keychain вернул успех без данных")
                try {
                    return data.toByteArray()
                } finally {
                    CFRelease(data)
                }
            }
        } finally {
            CFRelease(query)
        }
    }

    override fun remove(alias: SecretAlias): Boolean {
        val query = запрос(alias, 3)
        try {
            val status = SecItemDelete(query)
            if (status == errSecSuccess) return true
            if (status == errSecItemNotFound) return false
            throw SecretVaultFailure("Keychain отказал при удалении ${alias.value}: OSStatus $status")
        } finally {
            CFRelease(query)
        }
    }

    /** Общая часть запроса: класс, служба, имя. Освобождать обязан вызывающий. */
    private fun запрос(alias: SecretAlias, capacity: Int): CFMutableDictionaryRef {
        val query = CFDictionaryCreateMutable(
            kCFAllocatorDefault,
            capacity.convert(),
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        ) ?: throw SecretVaultFailure("не удалось собрать запрос к Keychain")

        val serviceRef = service.toCFString()
        val accountRef = alias.value.toCFString()
        try {
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrService, serviceRef)
            CFDictionarySetValue(query, kSecAttrAccount, accountRef)
        } finally {
            // Словарь создан с kCFTypeDictionaryValueCallBacks, то есть значения он
            // удержал сам — наши ссылки больше не нужны.
            serviceRef?.let { CFRelease(it) }
            accountRef?.let { CFRelease(it) }
        }
        return query
    }
}

private fun String.toCFString(): CFStringRef? =
    CFStringCreateWithCString(kCFAllocatorDefault, this, kCFStringEncodingUTF8)

private fun ByteArray.toCFData(): CFDataRef? = usePinned { pinned ->
    CFDataCreate(kCFAllocatorDefault, pinned.addressOf(0).reinterpret(), size.convert())
}

private fun CFDataRef.toByteArray(): ByteArray {
    val length = CFDataGetLength(this).toInt()
    if (length == 0) return ByteArray(0)
    val bytes = ByteArray(length)
    val source = CFDataGetBytePtr(this) ?: throw SecretVaultFailure("Keychain вернул данные без байт")
    bytes.usePinned { memcpy(it.addressOf(0), source, length.convert()) }
    return bytes
}

/**
 * Apple: Keychain.
 *
 * `service` разделяет установки: сборки с разным `scope` не видят чужих секретов, и
 * это нужно не для красоты — иначе тесты в симуляторе тёрли бы секрет приложения.
 */
actual fun platformVault(scope: String): SecretVault = KeychainVault("io.tima.secrets.$scope")
