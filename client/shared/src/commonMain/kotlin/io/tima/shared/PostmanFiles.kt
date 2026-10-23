package io.tima.shared

import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.core.media.Media
import io.tima.core.media.MediaFolder
import io.tima.domain.chat.RichBlock
import io.tima.domain.chat.RichContent
import io.tima.domain.chat.RichMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Файлы почтальона на устройстве (Решения §6, ПЛАН-(ПФ) ПФ4).
 *
 * - **Нажатия не ждём**: пришло сообщение с фото или файлом вне пузыря — файл забирается сразу, как
 *   сообщение, и ложится в свою папку закрытым своим ключом.
 * - **Опоздавшее устройство** (срок у почтальона прошёл) просит файл снова; загружает его снова то
 *   устройство, у которого он есть, — по событию `media.wanted`.
 * - **Галочки**: получатель забрал — событие `media.fetched` отправителю; отметка лежит в той же
 *   папке пустым файлом, чтобы пережить перезапуск.
 * - **Фото целиком, превью нет** (ADR-0052): забранное фото лента ужимает сама. У почтальона файла
 *   нет и попросить снова не у кого — отметка «нет» (раздел [GONE]): лента такого фото не показывает.
 * - **Мобильная сеть** («Память и трафик», заказчик 2026-10-22): [mayDownload] говорит, можно ли
 *   забирать сейчас; нельзя — файл ждёт смены сети ([resume]).
 */
class PostmanFiles(
    private val media: () -> Media,
    private val folder: MediaFolder,
    private val scope: CoroutineScope,
    private val mayDownload: suspend () -> Boolean = { true },
) {
    private val _fetched = MutableStateFlow(folder.entries(FETCHED).map { it.name }.toSet())

    /** Файлы, которые получатель уже забрал, — галочки у своих сообщений. */
    val fetched: StateFlow<Set<String>> = _fetched.asStateFlow()

    private val _gone = MutableStateFlow(folder.entries(GONE).map { it.name }.toSet())

    /** Файлы, которых нет и уже не будет: у почтальона их нет, попросить снова не у кого. */
    val gone: StateFlow<Set<String>> = _gone.asStateFlow()

    private val _waitingWifi = MutableStateFlow(folder.entries(WAIT).map { it.name }.toSet())

    /**
     * Ждут сети без счёта трафика: мобильная, а забирать по ней человек не велел. Отметка — в папке
     * (раздел [WAIT], внутри — раздел файла), чтобы пережить перезапуск; лента говорит это на месте
     * фото (заказчик 2026-10-22): иначе фото просто нет, и непонятно почему.
     */
    val waitingWifi: StateFlow<Set<String>> = _waitingWifi.asStateFlow()

    /** Свой раздел папки: фото без имени — «Фото», голосовые — «Голосовые», остальное — «Файлы». */
    fun kindOf(m: RichMedia): String = when {
        m.voice -> MediaFolder.VOICE
        m.name.isEmpty() && m.mime.startsWith("image/") -> MediaFolder.PHOTO
        else -> MediaFolder.FILE
    }

    /** Лежит ли файл на устройстве — закрытые байты, как пришли с сервера. */
    fun local(mediaId: String): ByteArray? =
        folder.get(MediaFolder.PHOTO, mediaId) ?: folder.get(MediaFolder.FILE, mediaId) ?: folder.get(MediaFolder.VOICE, mediaId)

    /** Своё отправленное — сразу в папку: показать без сети и загрузить снова, если попросят. */
    fun keepSent(mediaId: String, sealed: ByteArray, photo: Boolean, voice: Boolean = false) {
        runCatching { folder.put(if (voice) MediaFolder.VOICE else if (photo) MediaFolder.PHOTO else MediaFolder.FILE, mediaId, sealed) }
    }

    /** Исход забора: байты, «попросили снова» (на сервере уже нет), или не вышло (связь). */
    sealed interface Obtained {
        class Got(val sealed: ByteArray) : Obtained
        data object Asked : Obtained
        data object Failed : Obtained
    }

    /** Взять файл: из папки, иначе у почтальона; нет у него — попросить снова. */
    suspend fun obtain(m: RichMedia): Obtained = obtain(m.mediaId, kindOf(m))

    private suspend fun obtain(mediaId: String, kind: String): Obtained {
        local(mediaId)?.let { return Obtained.Got(it) }
        return when (val got = media().fetch(mediaId)) {
            is Media.MediaFetch.Got -> {
                runCatching { folder.put(kind, mediaId, got.bytes) }
                if (mediaId in _gone.value) {
                    runCatching { folder.delete(GONE, mediaId) }
                    _gone.value = _gone.value - mediaId
                }
                if (mediaId in _waitingWifi.value) {
                    runCatching { folder.delete(WAIT, mediaId) }
                    _waitingWifi.value = _waitingWifi.value - mediaId
                }
                Journal.note(LogCode.FILE_GET, "забран", "файл" to mediaId.take(8), "размер" to got.bytes.size)
                Obtained.Got(got.bytes)
            }
            Media.MediaFetch.Gone -> {
                // Голосовое — файл на срок (ПЛАН-(ВМ) Р3): срок вышел — его нет, просить не у кого.
                val asked = kind != MediaFolder.VOICE && media().requestAgain(mediaId)
                Journal.note(LogCode.FILE_GET, "у почтальона нет — попросили снова", "файл" to mediaId.take(8), "ушло" to asked.toString())
                if (!asked) {
                    // Попросить не у кого — файла не будет: лента его не показывает (ADR-0052).
                    runCatching { folder.put(GONE, mediaId, ByteArray(0)) }
                    _gone.value = _gone.value + mediaId
                }
                if (asked) Obtained.Asked else Obtained.Failed
            }
            Media.MediaFetch.Failed -> Obtained.Failed
        }
    }

    /** Пришло сообщение: файлы вне пузыря — забрать сразу, не дожидаясь нажатия. */
    fun received(content: RichContent?) {
        receivedMedia(outsideOf(content ?: return))
    }

    /** Пришли файлы — сообщения или правки, заменившей фото (ADR-0052): забрать сразу. */
    fun receivedMedia(files: List<RichMedia>) {
        if (files.isEmpty()) return
        scope.launch {
            if (!mayDownload()) {
                val files = files.filter { local(it.mediaId) == null }
                if (files.isEmpty()) return@launch
                for (m in files) runCatching { folder.put(WAIT, m.mediaId, kindOf(m).encodeToByteArray()) }
                _waitingWifi.value = _waitingWifi.value + files.map { it.mediaId }
                Journal.note(LogCode.FILE_GET, "ждёт сети без счёта трафика", "файлов" to files.size)
                return@launch
            }
            for (m in files) runCatching { obtain(m) }
        }
    }

    /** Сеть сменилась или человек разрешил мобильную — забрать отложенное. */
    fun resume() {
        scope.launch {
            if (_waitingWifi.value.isEmpty() || !mayDownload()) return@launch
            val ids = _waitingWifi.value
            Journal.note(LogCode.FILE_GET, "забираем отложенное", "файлов" to ids.size)
            for (id in ids) {
                val kind = folder.get(WAIT, id)?.decodeToString()?.takeIf { it == MediaFolder.PHOTO || it == MediaFolder.FILE || it == MediaFolder.VOICE } ?: MediaFolder.PHOTO
                runCatching { obtain(id, kind) }
            }
        }
    }

    /** Событие канала: просьба загрузить снова или «получатель забрал». */
    suspend fun event(wanted: Boolean, mediaId: String) {
        if (!wanted) {
            Journal.note(LogCode.FILE_GET, "получатель забрал", "файл" to mediaId.take(8))
            runCatching { folder.put(FETCHED, mediaId, ByteArray(0)) }
            _fetched.value = _fetched.value + mediaId
            return
        }
        val sealed = local(mediaId) ?: return
        val done = media().reupload(mediaId, sealed)
        Journal.note(LogCode.FILE_GET, "загружен снова по просьбе", "файл" to mediaId.take(8), "вышло" to done.toString())
    }

    companion object {
        /** Раздел отметок «получатель забрал» — пустые файлы по номеру файла. */
        const val FETCHED = "fetched"

        /** Раздел отметок «файла нет и не будет» — пустые файлы по номеру (ADR-0052). */
        const val GONE = "gone"

        /** Раздел отметок «ждёт Wi-Fi» — внутри раздел файла (`photo`, `file`). */
        const val WAIT = "wait"

        /** Настройка «Качать файлы по мобильной сети»: `0` — нет; иначе — да (по умолчанию). */
        const val MOBILE_KEY = "media.mobile"

        /** Фото без текста и файлы — вне пузыря: их держит почтальон (Решения §2, §6). */
        fun outsideOf(content: RichContent): List<RichMedia> {
            val indexes = content.blocks.flatMap { b ->
                when (b) {
                    is RichBlock.Album -> b.media
                    is RichBlock.File -> listOf(b.media)
                    is RichBlock.Voice -> listOf(b.media)
                    else -> emptyList()
                }
            }
            return indexes.mapNotNull { content.media.getOrNull(it) }
        }
    }
}
