package io.tima.core.call.desktop

import com.sun.jna.Callback
import com.sun.jna.CallbackThreadInitializer
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import livekit.proto.FfiEvent
import livekit.proto.FfiRequest
import livekit.proto.FfiResponse
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * C-интерфейс `livekit_ffi.dll` — четыре функции, всё остальное protobuf.
 *
 * Имена — ровно те, что экспортирует библиотека (`livekit-ffi/src/cabi.rs`): JNA ищет
 * функцию по имени метода.
 *
 * ── ТИПЫ ВЫБРАНЫ ПОД ABI, А НЕ ПОД УДОБСТВО ────────────────────────────────
 *
 * `bool` у Rust — один байт, а `boolean` у JNA — четыре. Возвращённый `bool` читается из
 * младшего байта регистра, и старшие байты там — мусор: `boolean` принял бы его за
 * `true`. Поэтому `Byte`. `size_t` на x86_64 — восемь байт, то есть `Long`.
 */
@Suppress("FunctionName")
internal interface LiveKitNative : Library {
    fun livekit_ffi_initialize(callback: EventCallback, captureLogs: Byte, sdk: String, sdkVersion: String)
    fun livekit_ffi_request(data: ByteArray, len: Long, resPtr: PointerByReference, resLen: LongByReference): Long
    fun livekit_ffi_drop_handle(handle: Long): Byte
    fun livekit_ffi_dispose()
}

/** Куда библиотека сдаёт события. Зовётся из её потоков — и потому не должна ждать. */
internal fun interface EventCallback : Callback {
    fun invoke(data: Pointer?, len: Long)
}

/**
 * Мост к `livekit-ffi`: загрузка, запросы, события.
 *
 * ── ОДИН НА ПРОЦЕСС ─────────────────────────────────────────────────────────
 *
 * Сервер FFI внутри библиотеки глобальный (`FFI_SERVER` в `lib.rs`): второй
 * `livekit_ffi_initialize` заменил бы обработчик событий первого, и события одного звонка
 * уходили бы другому. Поэтому `object`, а не класс.
 *
 * ── ПОЧЕМУ ЗАГРУЗКА ЛЕНИВАЯ ─────────────────────────────────────────────────
 *
 * В библиотеке 25 МБ — весь libwebrtc. Грузить её при запуске значило бы платить за
 * звонок каждым открытием приложения, в том числе теми, где звонков нет. Грузится при
 * первом звонке; при запуске проверяется только, что файл на месте.
 */
internal object Ffi {

    /** Имя библиотеки в каталоге ресурсов приложения (`build.gradle.kts`, `callNatives`). */
    private const val LIBRARY = "livekit_ffi.dll"

    @Volatile
    private var native: LiveKitNative? = null

    /**
     * Обработчик событий держится ЗДЕСЬ, полем. JNA хранит на него слабую ссылку: объект,
     * собранный сборщиком мусора, библиотека продолжала бы звать по висящему адресу — и
     * процесс падал бы в случайный момент посреди разговора.
     */
    private val callback = EventCallback { data, len -> take(data, len) }

    private val nextAsync = AtomicLong(1)
    private val waiting = ConcurrentHashMap<Long, CompletableDeferred<FfiEvent>>()
    private val listeners = CopyOnWriteArrayList<(FfiEvent) -> Unit>()

    /**
     * Где лежит библиотека. `null` — её нет, и звонков на этом ПК не будет.
     *
     * Каталог даёт Compose Desktop свойством `compose.application.resources.dir` — и при
     * `run`, и в установленном MSI. Тот же путь проверки задают сами.
     */
    fun libraryFile(): File? = System.getProperty("compose.application.resources.dir")
        ?.let { File(it, LIBRARY) }
        ?.takeIf { it.isFile }

    /**
     * Загрузить и подключить обработчик событий. Повторный вызов — ничего.
     *
     * @return `null` — готово; иначе причина словами для журнала.
     */
    @Synchronized
    fun start(): String? {
        if (native != null) return null
        val file = libraryFile() ?: return "нет $LIBRARY в каталоге ресурсов приложения"
        return try {
            val started = System.nanoTime()
            val lib = Native.load(file.absolutePath, LiveKitNative::class.java)
            // Потоки библиотеки JNA пристёгивает к JVM при каждом вызове и отстёгивает
            // после. Для видео это тридцать пристёгиваний в секунду; держим пристёгнутыми,
            // а демонами — чтобы они не держали процесс при выходе.
            Native.setCallbackThreadInitializer(callback, CallbackThreadInitializer(true, false, "livekit-ffi"))
            lib.livekit_ffi_initialize(callback, 0, "tima-desktop", "1")
            native = lib
            Journal.note(
                LogCode.CALL, "движок звонков ПК загружен",
                "мс" to (System.nanoTime() - started) / 1_000_000,
            )
            null
        } catch (e: Throwable) {
            e.message ?: e::class.simpleName ?: "библиотека не загрузилась"
        }
    }

    /** Синхронный запрос. Ответ библиотека держит в своём буфере — копируем и отпускаем. */
    fun request(request: FfiRequest): FfiResponse {
        val lib = checkNotNull(native) { "движок звонков не загружен" }
        val bytes = FfiRequest.ADAPTER.encode(request)
        val resPtr = PointerByReference()
        val resLen = LongByReference()
        val handle = lib.livekit_ffi_request(bytes, bytes.size.toLong(), resPtr, resLen)
        check(handle != 0L) { "livekit-ffi отверг запрос" }
        try {
            val answer = resPtr.value.getByteArray(0, resLen.value.toInt())
            return FfiResponse.ADAPTER.decode(answer)
        } finally {
            lib.livekit_ffi_drop_handle(handle)
        }
    }

    /**
     * Запрос с ответом-событием: подключение, публикация, выход.
     *
     * Номер ожидания назначаем **мы** и регистрируем ДО запроса. Номер от библиотеки
     * пришёл бы в ответе — а событие с ним может успеть раньше, из другого потока, и
     * упало бы в пустоту.
     */
    suspend fun call(timeoutMs: Long, build: (asyncId: Long) -> FfiRequest): FfiEvent? {
        val id = nextAsync.getAndIncrement()
        val answer = CompletableDeferred<FfiEvent>()
        waiting[id] = answer
        try {
            request(build(id))
            return withTimeoutOrNull(timeoutMs) { answer.await() }
        } finally {
            waiting.remove(id)
        }
    }

    /** Отпустить ручку, которую библиотека выдала нам во владение. `0` — ничего. */
    fun drop(handle: Long) {
        if (handle != 0L) native?.livekit_ffi_drop_handle(handle)
    }

    fun listen(listener: (FfiEvent) -> Unit) {
        listeners += listener
    }

    fun unlisten(listener: (FfiEvent) -> Unit) {
        listeners -= listener
    }

    fun nextAsyncId(): Long = nextAsync.getAndIncrement()

    /**
     * Событие из потока библиотеки. **Ждать здесь нельзя** (`cabi.rs`: «callback must be
     * threadsafe and not block»): разбор, раздача слушателям — и всё.
     */
    private fun take(data: Pointer?, len: Long) {
        if (data == null || len <= 0) return
        val event = try {
            FfiEvent.ADAPTER.decode(data.getByteArray(0, len.toInt()))
        } catch (e: Throwable) {
            Journal.trouble(LogCode.CALL, "событие livekit-ffi не разобралось", "причина" to (e.message ?: "?"))
            return
        }
        asyncIdOf(event)?.let { id -> waiting[id]?.let { it.complete(event); return } }
        for (listener in listeners) {
            try {
                listener(event)
            } catch (e: Throwable) {
                // Упавший слушатель не должен уронить поток библиотеки: следующее событие
                // ушло бы в никуда, а разговор продолжался бы вслепую.
                Journal.trouble(LogCode.CALL, "слушатель события звонка упал", "причина" to (e.message ?: "?"))
            }
        }
    }

    /** Номер ожидания у ответов, которые мы ждём. Остальные события его не несут. */
    private fun asyncIdOf(event: FfiEvent): Long? =
        event.connect?.async_id
            ?: event.disconnect?.async_id
            ?: event.publish_track?.async_id
            ?: event.unpublish_track?.async_id
            ?: event.get_stats?.async_id
}
