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
 */
class PostmanFiles(
    private val media: () -> Media,
    private val folder: MediaFolder,
    private val scope: CoroutineScope,
) {
    private val _fetched = MutableStateFlow(folder.entries(FETCHED).map { it.name }.toSet())

    /** Файлы, которые получатель уже забрал, — галочки у своих сообщений. */
    val fetched: StateFlow<Set<String>> = _fetched.asStateFlow()

    /** Свой раздел папки: фото без имени — «Фото», остальное — «Файлы» («Память и трафик»). */
    fun kindOf(m: RichMedia): String = if (m.name.isEmpty() && m.mime.startsWith("image/")) MediaFolder.PHOTO else MediaFolder.FILE

    /** Лежит ли файл на устройстве — закрытые байты, как пришли с сервера. */
    fun local(mediaId: String): ByteArray? = folder.get(MediaFolder.PHOTO, mediaId) ?: folder.get(MediaFolder.FILE, mediaId)

    /** Своё отправленное — сразу в папку: показать без сети и загрузить снова, если попросят. */
    fun keepSent(mediaId: String, sealed: ByteArray, photo: Boolean) {
        runCatching { folder.put(if (photo) MediaFolder.PHOTO else MediaFolder.FILE, mediaId, sealed) }
    }

    /** Исход забора: байты, «попросили снова» (на сервере уже нет), или не вышло (связь). */
    sealed interface Obtained {
        class Got(val sealed: ByteArray) : Obtained
        data object Asked : Obtained
        data object Failed : Obtained
    }

    /** Взять файл: из папки, иначе у почтальона; нет у него — попросить снова. */
    suspend fun obtain(m: RichMedia): Obtained {
        local(m.mediaId)?.let { return Obtained.Got(it) }
        return when (val got = media().fetch(m.mediaId)) {
            is Media.MediaFetch.Got -> {
                runCatching { folder.put(kindOf(m), m.mediaId, got.bytes) }
                Obtained.Got(got.bytes)
            }
            Media.MediaFetch.Gone -> {
                val asked = media().requestAgain(m.mediaId)
                Journal.note(LogCode.MEDIA_LOAD, "файла у почтальона уже нет — попросили снова", "файл" to m.mediaId.take(8), "ушло" to asked.toString())
                if (asked) Obtained.Asked else Obtained.Failed
            }
            Media.MediaFetch.Failed -> Obtained.Failed
        }
    }

    /** Пришло сообщение: файлы вне пузыря — забрать сразу, не дожидаясь нажатия. */
    fun received(content: RichContent?) {
        val outside = outsideOf(content ?: return)
        if (outside.isEmpty()) return
        scope.launch { for (m in outside) runCatching { obtain(m) } }
    }

    /** Событие канала: просьба загрузить снова или «получатель забрал». */
    suspend fun event(wanted: Boolean, mediaId: String) {
        if (!wanted) {
            runCatching { folder.put(FETCHED, mediaId, ByteArray(0)) }
            _fetched.value = _fetched.value + mediaId
            return
        }
        val sealed = local(mediaId) ?: return
        val done = media().reupload(mediaId, sealed)
        Journal.note(LogCode.MEDIA_LOAD, "файл почтальона загружен снова по просьбе", "файл" to mediaId.take(8), "вышло" to done.toString())
    }

    companion object {
        /** Раздел отметок «получатель забрал» — пустые файлы по номеру файла. */
        const val FETCHED = "fetched"

        /** Фото без текста и файлы — вне пузыря: их держит почтальон (Решения §2, §6). */
        fun outsideOf(content: RichContent): List<RichMedia> {
            val indexes = content.blocks.flatMap { b ->
                when (b) {
                    is RichBlock.Album -> b.media
                    is RichBlock.File -> listOf(b.media)
                    else -> emptyList()
                }
            }
            return indexes.mapNotNull { content.media.getOrNull(it) }
        }
    }
}
