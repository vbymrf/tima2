package io.tima.core.media

import com.github.panpf.sketch.PlatformContext
import com.github.panpf.sketch.Sketch
import com.github.panpf.sketch.cache.CachePolicy
import com.github.panpf.sketch.decode.supportAnimatedWebp
import com.github.panpf.sketch.fetch.FetchResult
import com.github.panpf.sketch.fetch.Fetcher
import com.github.panpf.sketch.request.ImageData
import com.github.panpf.sketch.request.ImageOptions
import com.github.panpf.sketch.request.Interceptor
import com.github.panpf.sketch.request.RequestContext
import com.github.panpf.sketch.source.ByteArrayDataSource
import com.github.panpf.sketch.source.DataFrom
import kotlin.coroutines.cancellation.CancellationException

/**
 * Загрузчик картинок — Sketch 4.4.0 под наш формат (ПЛАН-(ЗК), ЗК2 и ЗК3).
 *
 * **Источник — номер файла, а не адрес.** Картинка просится как `tima-media://<номер>`; байты
 * по номеру даёт [MediaSource], который подставляет корень приложения: своя папка → наше
 * хранилище → расшифровка ключом из сообщения. Сам загрузчик о сети и шифровании не знает.
 *
 * **Сети у загрузчика нет.** Автоподключение модулей выключено, сетевые модули Sketch в сборку
 * не входят: адрес из чужого сообщения загрузчик открыть не может, даже если его попросят.
 *
 * **Кеша на диске нет.** Кеш готовых и скачанных картинок выключен — иначе расшифрованное легло
 * бы на диск открытым. В памяти — кеш Sketch с вытеснением по байтам.
 */
const val MEDIA_SCHEME = "tima-media"

/** Адрес файла для загрузчика: номер, без сети. */
fun mediaUri(mediaId: String): String = "$MEDIA_SCHEME://$mediaId"

/** Байты по номеру файла: своя папка, хранилище, расшифровка. `null` — файла нет. */
typealias MediaSource = suspend (mediaId: String) -> ByteArray?

/** Почему картинка не показалась — для журнала (ПЛАН-(ЖС) §1). */
enum class MediaFailure {
    /** Байтов нет: файла нет ни в своей папке, ни на сервере, либо сеть не ответила. */
    NO_BYTES,

    /** Байты есть, расшифровка не прошла. */
    DECRYPT,

    /** Расшифровали, но картинка не раскодировалась: битый файл или формат не по силам. */
    DECODE,
}

/** Расшифровка не прошла — источник бросает её, чтобы журнал отличил её от отсутствия байтов. */
class MediaDecryptException(message: String) : Exception(message)

private class MediaMissingException(mediaId: String) : Exception("файла $mediaId нет")

/** Номер файла из адреса `tima-media://<номер>`; не наш адрес — `null`. */
fun mediaIdOf(uri: String): String? {
    val prefix = "$MEDIA_SCHEME://"
    if (!uri.startsWith(prefix)) return null
    return uri.removePrefix(prefix).takeIf { it.isNotEmpty() && '/' !in it }
}

class TimaMediaFetcher(
    private val mediaId: String,
    private val source: MediaSource,
) : Fetcher {

    override suspend fun fetch(): Result<FetchResult> = runCatching {
        val bytes = source(mediaId) ?: throw MediaMissingException(mediaId)
        FetchResult(ByteArrayDataSource(bytes, DataFrom.LOCAL), mimeType = null)
    }

    override fun equals(other: Any?): Boolean = other is TimaMediaFetcher && other.mediaId == mediaId
    override fun hashCode(): Int = mediaId.hashCode()
    override fun toString(): String = "TimaMediaFetcher($mediaId)"

    class Factory(private val source: MediaSource) : Fetcher.Factory {
        override fun create(requestContext: RequestContext): TimaMediaFetcher? {
            val id = mediaIdOf(requestContext.request.uri.toString()) ?: return null
            return TimaMediaFetcher(id, source)
        }

        override fun equals(other: Any?): Boolean = other is Factory && other.source === source
        override fun hashCode(): Int = source.hashCode()
        override fun toString(): String = "TimaMediaFetcher.Factory"
    }
}

/**
 * Неудачи загрузки — одной строкой на номер файла, наружу. Удачная загрузка молчит: иначе
 * прокрутка ленты утопила бы журнал. Отмена (картинка ушла с экрана) — не неудача.
 */
private class FailureInterceptor(private val onFailure: (mediaId: String, MediaFailure, String) -> Unit) : Interceptor {
    override val key: String = "TimaFailureInterceptor"
    override val sortWeight: Int = 0

    override suspend fun intercept(chain: Interceptor.Chain): Result<ImageData> {
        val result = chain.proceed(chain.request)
        val error = result.exceptionOrNull()
        if (error != null && error !is CancellationException) {
            val id = mediaIdOf(chain.request.uri.toString())
            if (id != null) onFailure(id, classify(error), error.message ?: error::class.simpleName.orEmpty())
        }
        return result
    }

    private fun classify(error: Throwable): MediaFailure {
        var e: Throwable? = error
        while (e != null) {
            if (e is MediaMissingException) return MediaFailure.NO_BYTES
            if (e is MediaDecryptException) return MediaFailure.DECRYPT
            e = e.cause
        }
        return MediaFailure.DECODE
    }

    override fun equals(other: Any?): Boolean = other is FailureInterceptor && other.onFailure === onFailure
    override fun hashCode(): Int = onFailure.hashCode()
    override fun toString(): String = "TimaFailureInterceptor"
}

/**
 * Один загрузчик на приложение. Только свой источник и анимированный WebP; кешей на диске нет;
 * не больше 4 загрузок и 4 раскодирований разом.
 *
 * @param onFailure неудача по номеру файла — корень пишет её в журнал (`MEDIA-*`).
 */
fun timaSketch(
    context: PlatformContext,
    source: MediaSource,
    onFailure: (mediaId: String, reason: MediaFailure, detail: String) -> Unit = { _, _, _ -> },
): Sketch =
    Sketch.Builder(context)
        .componentLoaderEnabled(false)
        .components {
            addFetcher(TimaMediaFetcher.Factory(source))
            addInterceptor(FailureInterceptor(onFailure))
            supportAnimatedWebp()
        }
        .globalImageOptions(
            ImageOptions {
                resultCachePolicy(CachePolicy.DISABLED)
                downloadCachePolicy(CachePolicy.DISABLED)
            },
        )
        .networkParallelismLimited(4)
        .decodeParallelismLimited(4)
        .build()
