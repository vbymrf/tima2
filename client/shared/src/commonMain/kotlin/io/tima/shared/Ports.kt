package io.tima.shared

import io.tima.core.encryption.DeviceIdentity
import io.tima.core.network.DevicesApi
import io.tima.core.network.EscrowApi
import io.tima.core.network.GroupKeyRecoveryApi
import io.tima.core.network.GroupKeysApi
import io.tima.core.network.GroupsApi
import io.tima.core.network.KeysApi
import io.tima.core.network.UsersApi
import io.tima.domain.account.ConfirmDeviceLink
import io.tima.domain.account.MyDevices

/**
 * Порты композиции: что экраны и подсистемы берут из сети.
 *
 * ── ЗАЧЕМ ЭТО ВМЕСТО ОДНОГО КЛАССА `Сеть` ───────────────────────────────────
 *
 * `Сеть` — двенадцать публичных свойств, и каждое новое окно добавляет туда
 * тринадцатое. Пока экранов было три, это выглядело удобно; на семи оказывается,
 * что окно «состав группы» видит транспорт личных сообщений, а окно «устройства» —
 * escrow. Видит не по нужде, а потому что ему передали всё.
 *
 * Порт называет, что нужно ИМЕННО ЭТОМУ куску приложения. Новый раздел объявляет
 * свой порт и получает свои три-четыре ручки; понадобилось больше — значит шов
 * недоделан, и чинится шов, а не расширяется порт.
 *
 * `Сеть` реализует все три и остаётся внутри композиции: наружу торчат порты.
 */

/** Личные переписки: найти собеседника, назвать автора, попросить ключ группы. */
interface ChatPorts {
    /** Кто скрывается за номером телефона. */
    val directory: UsersApi

    /**
     * Недостающие версии группового ключа: попросить и отдать.
     *
     * Нужен окну переписки, потому что «сообщение не читается» чинится именно
     * отсюда — просьбой к тому, у кого ключ есть.
     */
    val keyRecovery: GroupKeyRecoveryApi
}

/** Группы: создание, состав, ротация ключа при смене состава. */
interface GroupPorts {
    val groups: GroupsApi
    val directory: UsersApi

    /** Ключи устройств участников: на них заворачивается новый групповой ключ. */
    val keys: KeysApi

    /** Ключ эпохи: escrow-блоб выпускается вместе с версией ключа (ADR-0004). */
    val escrow: EscrowApi

    /** Выпуск версии ключа и выдача обёрток. */
    val groupKeys: GroupKeysApi

    /**
     * Передача уже существующей версии тому, кому её не выдавали.
     *
     * Отдельно от [ключиГрупп], потому что это другая работа: там выпуск новой
     * версии, здесь — раздача старой по просьбе.
     */
    val groupKeyRecovery: GroupKeyRecoveryApi
}

/** Устройства: список, отключение, платформа, подтверждение привязки. */
interface DevicePorts {
    val myFleet: MyDevices

    /** Объявление платформы серверу: телефон это или ПК (key-lifecycle.md §2). */
    val devices: DevicesApi

    /**
     * Подтверждение привязки требует ключа ЭТОГО устройства: подпись над данными из
     * кода делается им, а живёт он в хранилище платформы, а не в сети.
     */
    fun linkConfirmation(identity: DeviceIdentity): ConfirmDeviceLink
}
