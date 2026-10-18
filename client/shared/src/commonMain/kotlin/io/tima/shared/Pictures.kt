package io.tima.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.github.panpf.sketch.LocalPlatformContext
import com.github.panpf.sketch.Sketch
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.core.media.Media
import io.tima.core.media.MediaFailure
import io.tima.core.media.timaSketch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Откуда загрузчик картинок берёт байты (ПЛАН-(ЗК) ЗК3).
 *
 * Загрузчик один на приложение и живёт выше сессии, а медиа-хранилище появляется вместе с
 * сессией и меняется при смене аккаунта. Поэтому между ними — эта прослойка: корень ставит
 * хранилище, когда оно есть, загрузчик спрашивает её.
 *
 * Порядок источника по плану: своя папка → наше хранилище → расшифровка ключом из сообщения.
 * Аватары лежат открытыми — расшифровывать нечего; картинка внутри сообщения закрыта ключом из
 * его тела (ПЛАН-(РС) РС4), ключ приходит в адресе. Своя папка — фото и файлы вне пузыря,
 * которые забрал почтальон (ПЛАН-(РС) РС8): у них срок на сервере, и показ без сети берёт отсюда.
 */
class PictureSource {
    /** Медиа-хранилище текущей сессии; `null` — сессии нет, картинки не грузятся. */
    var media: Media? = null

    /** Своя папка аккаунта: закрытые байты фото вне пузыря; `null` — смотреть не во что. */
    var local: ((String) -> ByteArray?)? = null

    suspend fun bytes(mediaId: String, key: ByteArray? = null): ByteArray? {
        val raw = local?.invoke(mediaId) ?: media?.download(mediaId) ?: return null
        if (key == null) return raw
        // Ключ не подошёл — так и сказать журналу: «нет файла» и «не тот ключ» чинятся по-разному.
        return io.tima.core.encryption.MediaFiles.open(key, raw)
            ?: throw io.tima.core.media.MediaDecryptException("ключ файла $mediaId не подошёл")
    }
}

/** Прослойка загрузчика — ставит корень, читает сессия. */
internal val LocalPictureSource = staticCompositionLocalOf<PictureSource?> { null }

/**
 * Номера файлов, о которых журнал уже сказал за этот запуск: одна строка на файл. Неудачи
 * приходят из потоков загрузчика, поэтому множество меняется атомарно, а не простым `add`.
 */
private val reportedFailures = MutableStateFlow<Set<String>>(emptySet())

/** Неудача загрузки картинки — строкой `MEDIA-LOAD`, одной на файл за запуск. */
internal fun noteMediaFailure(mediaId: String, reason: MediaFailure, detail: String) {
    val before = reportedFailures.getAndUpdate { it + mediaId }
    if (mediaId in before) return
    val (why, text) = when (reason) {
        MediaFailure.NO_BYTES -> "нет-байтов" to "картинка не показана: файла нет ни в своей папке, ни на сервере, либо сеть не ответила"
        MediaFailure.DECRYPT -> "расшифровка" to "картинка не показана: ключ не подошёл"
        MediaFailure.DECODE -> "раскодирование" to "картинка не показана: битый файл или формат не по силам"
    }
    Journal.trouble(LogCode.MEDIA_LOAD, text, "файл" to mediaId, "причина" to why, "подробно" to detail.take(120))
}

/** Загрузчик приложения — один, под нашу прослойку и журнал. */
@Composable
internal fun rememberTimaSketch(source: PictureSource): Sketch {
    val context = LocalPlatformContext.current
    return remember(context, source) {
        timaSketch(context, source = { id, key -> source.bytes(id, key) }, onFailure = ::noteMediaFailure)
    }
}
