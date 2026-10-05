package io.tima.shared

import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.core.encryption.DeviceIdentity
import io.tima.core.encryption.HistoryFrame
import io.tima.core.encryption.KeyCopy
import io.tima.core.network.DeviceKeysResult
import io.tima.core.network.KeyCopyApi
import io.tima.core.network.KeysApi
import io.tima.crypto.MessageSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Копия ключей по модели Matrix (ПЛАН-УСТРОЙСТВ-И-ИСТОРИИ §3а, Р43–Р47, М1–М4).
 *
 * **Пополняет копию** каждое своё устройство: получило или отправило сообщение — заворачивает
 * его ключ под открытый ключ копии; лёг ключ группы — тоже. Для этого нужен только открытый
 * ключ, и он берётся с сервера **с проверкой подписи ключом личности** — сервер свою пару не
 * подсунет.
 *
 * **Открывает копию** тот, у кого фраза: при входе по фразе пара выводится из слов. На
 * телефоне секрет копии сохраняется в хранилище платформы (Р46) — телефон потом открывает
 * копию для своего нового устройства по QR (М4). ПК секрет не хранит.
 *
 * Пополнение — без гарантии доставки: не дошедшая обёртка означает, что этого сообщения в
 * копии нет, а не что оно потеряно — у устройств оно есть, и передача по QR (М4) идёт с
 * телефона. Отказ сервера по эпохе (её сменили) сбрасывает кэш ключа.
 */
class KeyCopyService(
    private val api: KeyCopyApi,
    private val keys: KeysApi,
    /** Список своих личных переписок — для смены пары (М5). */
    private val history: io.tima.core.network.HistoryApi,
    private val userId: String,
    private val deviceId: String,
    private val identity: DeviceIdentity,
    private val scope: CoroutineScope,
    /** Хранилище секрета копии — только у телефона (Р46); у ПК `null`. */
    private val secrets: KeyCopySecrets?,
) {
    private val lock = Mutex()
    private var cached: Pair<Int, ByteArray>? = null

    /** Секрет копии, выведенный из фразы в этом запуске, — для восстановления без хранилища (ПК). */
    @kotlin.concurrent.Volatile
    private var fromPhrase: DeviceIdentity? = null

    /** Действующий открытый ключ копии, проверенный подписью ключа личности; `null` — нет или не сошлась. */
    suspend fun pub(): Pair<Int, ByteArray>? = lock.withLock {
        cached ?: load()?.also { cached = it }
    }

    private suspend fun load(): Pair<Int, ByteArray>? {
        val key = (api.current() as? KeyCopyApi.Current.Published)?.key ?: return null
        val identityPub = (keys.devicesOf(userId) as? DeviceKeysResult.Devices)?.identityPub ?: return null
        if (!KeyCopy.verify(identityPub, key.epoch, key.pub, key.sig)) {
            Journal.trouble(LogCode.DEVICE_TRUST, "ключ копии не подписан ключом личности — копию не пополняем", "эпоха" to key.epoch)
            return null
        }
        return key.epoch to key.pub
    }

    /**
     * Фраза известна этому устройству (вход по фразе, новый аккаунт, «Подтвердить фразой»):
     * вывести пару копии, опубликовать открытый ключ, если его ещё нет, и на телефоне сохранить
     * секрет (Р46).
     */
    suspend fun onPhrase(words: List<String>) {
        val epoch = when (val now = api.current()) {
            is KeyCopyApi.Current.Published -> now.key.epoch
            KeyCopyApi.Current.Missing -> 1
            KeyCopyApi.Current.Unknown -> return
        }
        val copy = KeyCopy.fromWords(words, epoch) ?: return
        fromPhrase = copy
        secrets?.put(epoch, copy.exportRaw())
        if (epoch == 1 && pub() == null) {
            val sig = KeyCopy.sign(words, 1, copy.encryptionPublic) ?: return
            val ok = api.publish(KeyCopyApi.Key(1, copy.encryptionPublic, sig))
            Journal.note(LogCode.DEVICE_TRUST, "ключ копии опубликован", "принят" to ok)
            lock.withLock { cached = null }
        }
    }

    /** Секрет копии, которым это устройство может её открыть; `null` — не может. */
    fun copyIdentity(): DeviceIdentity? =
        fromPhrase ?: secrets?.get()?.let { (_, raw) -> runCatching { DeviceIdentity.fromRaw(raw) }.getOrNull() }

    /** Не заведена ли копия у личности (Р44); `null` — не узнали. */
    suspend fun missing(): Boolean? = when (api.current()) {
        KeyCopyApi.Current.Missing -> true
        is KeyCopyApi.Current.Published -> false
        KeyCopyApi.Current.Unknown -> null
    }

    /**
     * Завести копию фразой (Р44) — для работающих устройств: фразу они больше не вводят, а копия
     * обязана быть. Фраза сверяется с ключом личности: чужая фраза завела бы копию, которую
     * личность не откроет.
     */
    suspend fun start(words: List<String>): Rotation {
        val mine = (keys.devicesOf(userId) as? DeviceKeysResult.Devices)?.identityPub ?: return Rotation.OFFLINE
        val claimed = io.tima.core.encryption.AccountIdentitiesOverKodium.fromWords(words) ?: return Rotation.WRONG_PHRASE
        if (!claimed.contentEquals(mine)) return Rotation.WRONG_PHRASE
        onPhrase(words)
        return if (missing() == false) Rotation.DONE else Rotation.OFFLINE
    }

    /** Пора ли сменить пару копии: отключили своё устройство (М5). */
    suspend fun rotationDue(): Boolean =
        (api.current() as? KeyCopyApi.Current.Published)?.key?.rotationDue == true

    /** Исход смены пары копии (М5). */
    enum class Rotation { DONE, WRONG_PHRASE, OFFLINE }

    /**
     * Перевести копию на новую пару (М5): отключённое устройство могло унести секрет.
     *
     * Из фразы выводятся и прежняя пара, и новая. Всё, что есть в копии, разворачивается
     * прежней и заворачивается под новую; затем публикуется новая эпоха и заливаются обёртки.
     * До заливки копия под новой эпохой пуста — это минуты, а не потеря: прежние обёртки на
     * сервере заменяются, а не стираются.
     */
    suspend fun rotate(words: List<String>): Rotation {
        val current = (api.current() as? KeyCopyApi.Current.Published)?.key ?: return Rotation.OFFLINE
        val mine = (keys.devicesOf(userId) as? DeviceKeysResult.Devices)?.identityPub ?: return Rotation.OFFLINE
        val claimed = io.tima.core.encryption.AccountIdentitiesOverKodium.fromWords(words) ?: return Rotation.WRONG_PHRASE
        if (!claimed.contentEquals(mine)) return Rotation.WRONG_PHRASE
        val old = KeyCopy.fromWords(words, current.epoch) ?: return Rotation.WRONG_PHRASE
        val epoch = current.epoch + 1
        val next = KeyCopy.fromWords(words, epoch) ?: return Rotation.WRONG_PHRASE

        val groups = api.groupKeys() ?: return Rotation.OFFLINE
        val groupItems = groups.mapNotNull { item ->
            val key = KeyCopy.openGroupKey(old, item.wrapped) ?: return@mapNotNull null
            KeyCopy.wrapGroupKey(next.encryptionPublic, key)?.let { KeyCopyApi.GroupItem(item.groupId, item.gkVersion, it) }
        }
        val chats = history.personalChats() ?: return Rotation.OFFLINE
        val perChat = HashMap<String, MutableList<Pair<Long, ByteArray>>>()
        for (chat in chats) {
            var before = 0L
            while (true) {
                val page = api.page(chat.chatId, before) ?: return Rotation.OFFLINE
                if (page.isEmpty()) break
                for (item in page) {
                    val r = KeyCopy.rewrapFor(item.envelope, item.wrapEphemeral, old, next.encryptionPublic) ?: continue
                    perChat.getOrPut(chat.chatId) { mutableListOf() } += item.messageId to (r.ephemeralPub + r.wrapped)
                }
                if (page.size < KeyCopyApi.PAGE) break
                before = page.last().messageId
            }
        }

        val sig = KeyCopy.sign(words, epoch, next.encryptionPublic) ?: return Rotation.WRONG_PHRASE
        if (!api.publish(KeyCopyApi.Key(epoch, next.encryptionPublic, sig))) return Rotation.OFFLINE
        lock.withLock { cached = epoch to next.encryptionPublic }
        fromPhrase = next
        secrets?.put(epoch, next.exportRaw())
        for (part in groupItems.chunked(KeyCopyApi.PAGE)) api.saveGroupKeys(epoch, part)
        var moved = 0
        for ((chatId, items) in perChat) {
            for (part in items.chunked(KeyCopyApi.PAGE)) {
                if (api.saveMessages(chatId, epoch, part) == KeyCopyApi.Saved.OK) moved += part.size
            }
        }
        Journal.note(LogCode.DEVICE_TRUST, "копия ключей переведена на новую пару", "эпоха" to epoch, "сообщений" to moved, "ключей групп" to groupItems.size)
        return Rotation.DONE
    }

    /** Сообщение получено или отправлено — его ключ в копию (М2). Без ожидания. */
    fun feedMessage(chatId: String, stored: ByteArray) {
        scope.launch {
            runCatching {
                val (eph, envelope) = HistoryFrame.split(stored)
                val (epoch, copyPub) = pub() ?: return@launch
                val blob = KeyCopy.wrapMessage(envelope, eph, deviceId, identity, copyPub) ?: return@launch
                val messageId = MessageSerializer.decodeEnvelope(envelope).getOrThrow().meta.messageId.toLong()
                if (api.saveMessages(chatId, epoch, listOf(messageId to blob)) == KeyCopyApi.Saved.STALE) {
                    lock.withLock { cached = null }
                }
            }
        }
    }

    /** Ключ группы лёг на устройство — в копию (М2). Без ожидания. */
    fun feedGroupKey(groupId: String, version: Int, key: ByteArray) {
        scope.launch {
            runCatching {
                val (epoch, copyPub) = pub() ?: return@launch
                val blob = KeyCopy.wrapGroupKey(copyPub, key) ?: return@launch
                if (api.saveGroupKeys(epoch, listOf(KeyCopyApi.GroupItem(groupId, version, blob))) == KeyCopyApi.Saved.STALE) {
                    lock.withLock { cached = null }
                }
            }
        }
    }
}

/**
 * Слова фразы для копии ключей — от входа до сборки аккаунта (М1), как [PhraseOnce].
 * Отдельно от него: [PhraseOnce] берёт один потребитель, а копия — второй.
 */
object KeyCopyPhrase {
    @kotlin.concurrent.Volatile
    private var words: List<String>? = null

    @kotlin.concurrent.Volatile
    private var at = 0L

    fun hold(words: List<String>) {
        this.words = words.toList()
        at = msNow()
    }

    fun take(): List<String>? {
        val held = words
        words = null
        return held?.takeIf { msNow() - at <= KEEP_MS }
    }

    private const val KEEP_MS = 10 * 60 * 1000L
}
