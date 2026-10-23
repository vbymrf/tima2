package io.tima.core.media

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Звук без картинки на ПК (ПЛАН-(ВМ) §3.7, §3.8): Media Foundation Media Engine напрямую по COM через
 * JNA. Мост ComposeMediaPlayer ждёт видео и файл без него не открывает (`0xC00D36B3`), а «Аудио» и
 * «Поток» — как раз звук: mp3, m4a, AAC, WAV, FLAC и HLS со звуком. Окна не нужно.
 *
 * Места функций — по `mfmediaengine.h` и `mfobjects.h` (mingw-w64).
 */
internal class MediaEngineAudio : OwnPlayer {
    private interface MfPlat : Library {
        fun MFStartup(version: Int, flags: Int): Int
        fun MFCreateAttributes(out: PointerByReference, size: Int): Int
    }

    private interface OleAut : Library {
        fun SysAllocString(s: WString): Pointer?
        fun SysFreeString(p: Pointer?)
    }

    private interface QueryInterface : Callback { fun invoke(self: Pointer?, riid: Pointer?, out: Pointer?): Int }
    private interface RefCount : Callback { fun invoke(self: Pointer?): Int }
    private interface EventNotify : Callback { fun invoke(self: Pointer?, event: Int, param1: Pointer?, param2: Int): Int }

    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "tima-media-engine").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + thread.asCoroutineDispatcher())
    private val _state = MutableStateFlow(OwnPlayerState())
    override val state: StateFlow<OwnPlayerState> = _state.asStateFlow()
    private var engine: Pointer? = null
    private var ticker: Job? = null
    private var pendingSeek = 0.0
    private val keep = mutableListOf<Any>()

    override fun open(url: String, startMs: Long, play: Boolean) {
        scope.launch {
            _state.value = OwnPlayerState(speed = _state.value.speed)
            val e = engine ?: create() ?: return@launch
            pendingSeek = startMs / 1000.0
            val bstr = oleaut.SysAllocString(WString(url))
            val hr = call(e, SET_SOURCE, bstr)
            oleaut.SysFreeString(bstr)
            if (hr < 0) return@launch fail("источник: 0x${hr.toUInt().toString(16)}")
            if (_state.value.speed != 1f) callDouble(e, SET_PLAYBACK_RATE, _state.value.speed.toDouble())
            if (play) call(e, PLAY)
            ticker?.cancel()
            ticker = scope.launch { tick() }
        }
    }

    private suspend fun tick() {
        while (scope.isActive) {
            val e = engine ?: return
            val at = (getDouble(e, GET_CURRENT_TIME) * 1000).toLong()
            val d = getDouble(e, GET_DURATION)
            val paused = call(e, IS_PAUSED) != 0
            val ended = call(e, IS_ENDED) != 0
            val s = _state.value
            _state.value = s.copy(
                playing = !paused && !ended,
                positionMs = at,
                durationMs = if (d.isFinite() && d > 0) (d * 1000).toLong() else 0,
                live = d.isInfinite(),
                ended = ended,
                started = s.started || (!paused && at > 300),
            )
            delay(250)
        }
    }

    private fun fail(why: String) {
        _state.value = _state.value.copy(error = why, playing = false)
    }

    private fun create(): Pointer? {
        if (!onWindows) return null.also { fail("не Windows") }
        Ole32.INSTANCE.CoInitializeEx(null, COINIT_MULTITHREADED)
        if (!started) {
            started = mfplat.MFStartup(MF_VERSION, 0) >= 0
            if (!started) return null.also { fail("Media Foundation не поднялся") }
        }
        val factory = PointerByReference()
        var hr = Ole32.INSTANCE.CoCreateInstance(Guid.GUID.fromString(CLSID_FACTORY), null, CLSCTX_INPROC_SERVER, Guid.GUID.fromString(IID_FACTORY), factory).toInt()
        if (hr < 0) return null.also { fail("Media Engine: 0x${hr.toUInt().toString(16)}") }
        val attrs = PointerByReference()
        mfplat.MFCreateAttributes(attrs, 1)
        call(attrs.value, ATTR_SET_UNKNOWN, Guid.GUID.fromString(MF_MEDIA_ENGINE_CALLBACK).pointer, notify())
        val out = PointerByReference()
        hr = call(factory.value, FACTORY_CREATE_INSTANCE, MF_MEDIA_ENGINE_AUDIOONLY, attrs.value, out)
        call(attrs.value, RELEASE)
        call(factory.value, RELEASE)
        if (hr < 0 || out.value == null) return null.also { fail("Media Engine: 0x${hr.toUInt().toString(16)}") }
        engine = out.value
        return out.value
    }

    /** Отклик Media Engine: метаданные — перемотать к началу, ошибка — сказать. */
    private fun notify(): Pointer {
        val qi = object : QueryInterface {
            override fun invoke(self: Pointer?, riid: Pointer?, out: Pointer?): Int {
                out?.setPointer(0, self)
                return 0
            }
        }
        val ref = object : RefCount { override fun invoke(self: Pointer?): Int = 1 }
        val event = object : EventNotify {
            override fun invoke(self: Pointer?, event: Int, param1: Pointer?, param2: Int): Int {
                when (event) {
                    EVENT_LOADEDMETADATA -> if (pendingSeek > 0) scope.launch {
                        engine?.let { callDouble(it, SET_CURRENT_TIME, pendingSeek) }
                        pendingSeek = 0.0
                    }
                    EVENT_ERROR -> fail("ошибка Media Engine: ${Pointer.nativeValue(param1)}")
                }
                return 0
            }
        }
        val methods = listOf<Callback>(qi, ref, ref, event)
        val table = Memory(methods.size.toLong() * Native.POINTER_SIZE)
        methods.forEachIndexed { i, cb -> table.setPointer(i.toLong() * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(cb)) }
        val obj = Memory(Native.POINTER_SIZE.toLong()).apply { setPointer(0, table) }
        keep.addAll(methods + listOf(table, obj))
        return obj
    }

    override fun play() {
        scope.launch { engine?.let { e -> if (_state.value.ended) callDouble(e, SET_CURRENT_TIME, 0.0); call(e, PLAY) } }
    }

    override fun pause() {
        scope.launch { engine?.let { call(it, PAUSE) } }
    }

    override fun seek(ms: Long) {
        scope.launch { engine?.let { callDouble(it, SET_CURRENT_TIME, ms / 1000.0) } }
    }

    override fun setSpeed(speed: Float) {
        scope.launch {
            engine?.let { callDouble(it, SET_PLAYBACK_RATE, speed.toDouble()) }
            _state.value = _state.value.copy(speed = speed)
        }
    }

    override fun release() {
        scope.launch {
            ticker?.cancel()
            engine?.let {
                call(it, SHUTDOWN)
                call(it, RELEASE)
            }
            engine = null
        }.invokeOnCompletion {
            scope.cancel()
            thread.shutdown()
        }
    }

    private fun call(obj: Pointer, index: Int, vararg args: Any?): Int =
        fn(obj, index).invokeInt(arrayOf(obj, *args))

    private fun callDouble(obj: Pointer, index: Int, value: Double): Int = fn(obj, index).invokeInt(arrayOf(obj, value))

    private fun getDouble(obj: Pointer, index: Int): Double = fn(obj, index).invokeDouble(arrayOf(obj))

    private fun fn(obj: Pointer, index: Int): Function =
        Function.getFunction(obj.getPointer(0).getPointer(index.toLong() * Native.POINTER_SIZE))

    companion object {
        private val onWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")
        private val mfplat by lazy { Native.load("mfplat", MfPlat::class.java) }
        private val oleaut by lazy { Native.load("oleaut32", OleAut::class.java) }
        @Volatile private var started = false

        private const val MF_VERSION = 0x00020070
        private const val COINIT_MULTITHREADED = 0
        private const val CLSCTX_INPROC_SERVER = 1
        private const val MF_MEDIA_ENGINE_AUDIOONLY = 0x1
        private const val CLSID_FACTORY = "{B44392DA-499B-446B-A4CB-005FEAD0E6D5}"
        private const val IID_FACTORY = "{4D645ACE-26AA-4688-9BE1-DF3516990B93}"
        private const val MF_MEDIA_ENGINE_CALLBACK = "{C60381B8-83A4-41F8-A3D0-DE05076849A9}"

        private const val RELEASE = 2
        private const val FACTORY_CREATE_INSTANCE = 3
        private const val ATTR_SET_UNKNOWN = 27
        private const val SET_SOURCE = 6
        private const val GET_CURRENT_TIME = 16
        private const val SET_CURRENT_TIME = 17
        private const val GET_DURATION = 19
        private const val IS_PAUSED = 20
        private const val SET_PLAYBACK_RATE = 24
        private const val IS_ENDED = 27
        private const val PLAY = 32
        private const val PAUSE = 33
        private const val SHUTDOWN = 42

        private const val EVENT_ERROR = 5
        private const val EVENT_LOADEDMETADATA = 10
    }
}
