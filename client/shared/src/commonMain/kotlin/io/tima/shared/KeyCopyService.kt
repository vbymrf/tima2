package io.tima.shared

import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.core.encryption.DeviceIdentity
import io.tima.core.encryption.HistoryFrame
import io.tima.core.encryption.KeyCopy
import io.tima.core.network.DeviceKeysResult
import io.tima.core.network.KeyCopyApi
import io.tima.core.network.KeysApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Копия ключей по модели Matrix (ПЛАН-(ДУ+ИУ)-УСТРОЙСТВ-И-ИСТОРИИ §3а, Р43–Р47, М1–М4).
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
    /** Ключи групп этого устройства, все версии: `группа, версия, ключ` — для дозаливки. */
    private val localGroupKeys: () -> List<Triple<String, Int, ByteArray>> = { emptyList() },
    /** Отметка «дозалито под эпоху» — в настройках аккаунта; `null` — не отмечаем (тесты). */
    private val settings: io.tima.domain.chat.Settings? = null,
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

    private companion object {
        /** Эпоха, под которую это устройство уже дозалило копию. */
        const val BACKFILL_MARK = "key-copy.backfill.v1"

        /** Пополнение на каждом сообщении: ответ «копии нет» живёт десять минут. */
        const val QUIET_ANSWER_MS = 10 * 60_000L

        /** Экран устройств спрашивает дважды подряд («нет ли копии», «не пора ли сменить») — один запрос. */
        const val SCREEN_ANSWER_MS = 5_000L
    }

    /**
     * Ответ сервера о копии с памятью (заказчик 2026-10-06, батарея). Без неё у аккаунта без
     * копии каждое отправленное и полученное сообщение спрашивало сервер заново и получало тот
     * же 404: отчёт DGAR — 24 таких запроса за сутки, по два подряд. «Не знаю» не запоминается.
     */
    @kotlin.concurrent.Volatile
    private var answer: Pair<Long, KeyCopyApi.Current>? = null

    private suspend fun current(maxAgeMs: Long): KeyCopyApi.Current {
        val now = msNow()
        answer?.let { (at, said) -> if (now - at < maxAgeMs) return said }
        return api.current().also { if (it != KeyCopyApi.Current.Unknown) answer = now to it }
    }

    /** Копию только что опубликовали или сменили — прежний ответ неверен. */
    private fun forgetAnswer() {
        answer = null
    }

    private suspend fun load(): Pair<Int, ByteArray>? {
        val key = (current(QUIET_ANSWER_MS) as? KeyCopyApi.Current.Published)?.key ?: return null
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
            forgetAnswer()
            lock.withLock { cached = null }
        }
    }

    /**
     * Дозалить в копию то, что у устройства уже есть (Р44), — один раз на эпоху.
     *
     * Пополнение (М2) кладёт в копию только новое: сообщение, пришедшее или ушедшее после того,
     * как копия завелась, и версию ключа группы, легшую после. Всё прежнее — личные переписки
     * до «Завести копию» и ключи групп, полученные раньше, — в неё не попадало, и новое
     * устройство их не поднимало: проверка 2026-10-05 на Redmi — вернулись два сообщения из
     * копии, а прежние пропали вместе с ключом служебной группы, то есть с книгой контактов.
     * Matrix при включении копии заливает все имеющиеся ключи; так и здесь.
     *
     * Личные сообщения берутся с сервера — те, где есть обёртка под это устройство, то есть за
     * срок хранения обёрток (90 дней); ключи групп — из местной базы, все версии. Повтор не
     * вредит: сервер обёртку той же эпохи не переписывает.
     *
     * @return `true` — дозалито или уже было; `false` — копии нет или сеть не ответила, повторить позже.
     */
    suspend fun backfillOnce(): Boolean {
        val (epoch, copyPub) = pub() ?: return false
        val mark = BACKFILL_MARK
        val done = settings?.let { s -> runCatching { s.all().first()[mark] }.getOrNull() }
        if (done == epoch.toString()) return true
        val groupItems = localGroupKeys().mapNotNull { (groupId, version, key) ->
            KeyCopy.wrapGroupKey(copyPub, key)?.let { KeyCopyApi.GroupItem(groupId, version, it) }
        }
        var ok = true
        for (part in groupItems.chunked(KeyCopyApi.PAGE)) {
            if (api.saveGroupKeys(epoch, part) != KeyCopyApi.Saved.OK) ok = false
        }
        val chats = history.personalChats() ?: return false
        var messages = 0
        for (chat in chats) {
            var before = 0L
            while (true) {
                val page = history.page(chat.chatId, before) ?: return false
                if (page.isEmpty()) break
                val items = page.mapNotNull { item ->
                    KeyCopy.wrapMessage(item.envelope, item.wrapEphemeral, deviceId, identity, copyPub)?.let { item.messageId to it }
                }
                when (api.saveMessages(chat.chatId, epoch, items)) {
                    KeyCopyApi.Saved.OK -> messages += items.size
                    KeyCopyApi.Saved.STALE -> {
                        forgetAnswer()
                        lock.withLock { cached = null }
                        return false
                    }
                    KeyCopyApi.Saved.FAILED -> ok = false
                }
                if (page.size < io.tima.core.network.HistoryApi.PAGE) break
                before = page.last().messageId
            }
        }
        Journal.note(LogCode.DEVICE_TRUST, "копия ключей дозалита", "эпоха" to epoch, "сообщений" to messages, "ключей групп" to groupItems.size, "полностью" to ok)
        if (ok) settings?.let { s -> runCatching { s.put(mark, epoch.toString()) } }
        return ok
    }

    /** Секрет копии, которым это устройство может её открыть; `null` — не может. */
    fun copyIdentity(): DeviceIdentity? =
        fromPhrase ?: secrets?.get()?.let { (_, raw) -> runCatching { DeviceIdentity.fromRaw(raw) }.getOrNull() }

    /** Не заведена ли копия у личности (Р44); `null` — не узнали. */
    suspend fun missing(): Boolean? = when (current(SCREEN_ANSWER_MS)) {
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
        if (missing() != false) return Rotation.OFFLINE
        // Всё, что у устройства уже есть, — в копию сразу, а не при следующем запуске.
        scope.launch { runCatching { backfillOnce() } }
        return Rotation.DONE
    }

    /** Пора ли сменить пару копии: отключили своё устройство (М5). */
    suspend fun rotationDue(): Boolean =
        (current(SCREEN_ANSWER_MS) as? KeyCopyApi.Current.Published)?.key?.rotationDue == true

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
        forgetAnswer()
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
        // Перезавёрнуто всё содержимое копии — дозаливать под новую эпоху нечего.
        settings?.let { s -> runCatching { s.put(BACKFILL_MARK, epoch.toString()) } }
        return Rotation.DONE
    }

    /** Исход переноса копии прежней личности. */
    enum class Adopt { DONE, NO_COPY, WRONG_PHRASE, OFFLINE }

    /**
     * Перенести копию прежней личности [priorUserId] в свою (М6, Р55): перерегистрация завела
     * новую личность, а человек тот же — его история с ним.
     *
     * Пара копии прежней выводится из её фразы [priorWords] (её вводили для перерегистрации
     * минуты назад) и сверяется с опубликованным ключом. Всё, что в копии прежней, — ключи групп
     * и ключи сообщений её личных переписок — разворачивается ею и заворачивается под ключ своей
     * копии. Сервер пускает в копию прежней только личность того же аккаунта и принимает
     * обёртки в её переписки. Сама копия прежней не трогается: исход перерегистрации может
     * вернуть аккаунт ей.
     */
    suspend fun adoptPrior(priorWords: List<String>, priorUserId: String): Adopt {
        val theirs = when (val c = api.current(owner = priorUserId)) {
            is KeyCopyApi.Current.Published -> c.key
            KeyCopyApi.Current.Missing -> return Adopt.NO_COPY
            KeyCopyApi.Current.Unknown -> return Adopt.OFFLINE
        }
        val old = KeyCopy.fromWords(priorWords, theirs.epoch) ?: return Adopt.WRONG_PHRASE
        if (!old.encryptionPublic.contentEquals(theirs.pub)) return Adopt.WRONG_PHRASE
        val (epoch, copyPub) = pub() ?: return Adopt.OFFLINE

        val groups = api.groupKeys(owner = priorUserId) ?: return Adopt.OFFLINE
        val groupItems = groups.mapNotNull { item ->
            val key = KeyCopy.openGroupKey(old, item.wrapped) ?: return@mapNotNull null
            KeyCopy.wrapGroupKey(copyPub, key)?.let { KeyCopyApi.GroupItem(item.groupId, item.gkVersion, it) }
        }
        for (part in groupItems.chunked(KeyCopyApi.PAGE)) api.saveGroupKeys(epoch, part)

        val chats = history.personalChats(allIdentities = true) ?: return Adopt.OFFLINE
        var moved = 0
        for (chat in chats.filter { it.ownerId == priorUserId }) {
            var before = 0L
            while (true) {
                val page = api.page(chat.chatId, before, owner = priorUserId) ?: return Adopt.OFFLINE
                if (page.isEmpty()) break
                val items = page.mapNotNull { item ->
                    KeyCopy.rewrapFor(item.envelope, item.wrapEphemeral, old, copyPub)?.let { item.messageId to (it.ephemeralPub + it.wrapped) }
                }
                if (api.saveMessages(chat.chatId, epoch, items) == KeyCopyApi.Saved.OK) moved += items.size
                if (page.size < KeyCopyApi.PAGE) break
                before = page.last().messageId
            }
        }
        Journal.note(LogCode.DEVICE_TRUST, "копия прежней личности перенесена", "эпоха" to epoch, "сообщений" to moved, "ключей групп" to groupItems.size)
        return Adopt.DONE
    }

    /** Сообщение получено или отправлено — его ключ в копию (М2). Без ожидания. */
    fun feedMessage(chatId: String, stored: ByteArray) {
        scope.launch {
            runCatching {
                val (eph, envelope) = HistoryFrame.split(stored)
                val (epoch, copyPub) = pub() ?: return@launch
                val blob = KeyCopy.wrapMessage(envelope, eph, deviceId, identity, copyPub) ?: return@launch
                val messageId = KeyCopy.messageIdOf(envelope) ?: return@launch
                if (api.saveMessages(chatId, epoch, listOf(messageId to blob)) == KeyCopyApi.Saved.STALE) {
                    forgetAnswer()
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
                    forgetAnswer()
                    lock.withLock { cached = null }
                }
            }
        }
    }
}

/**
 * Фраза прежней личности — от «Перерегистрации» до сборки новой (М6, Р55): новая переносит к
 * себе копию прежней. Как [KeyCopyPhrase]: в памяти процесса, берётся один раз и сама забывается.
 */
object PriorCopyPhrase {
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

    private const val KEEP_MS = 30 * 60 * 1000L
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
