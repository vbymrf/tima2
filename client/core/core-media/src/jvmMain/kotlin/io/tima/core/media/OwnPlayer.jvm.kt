@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package io.tima.core.media

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.kdroidfilter.composemediaplayer.windows.WindowsNativeBridge
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
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.util.concurrent.Executors

/**
 * Свой движок на ПК (ПЛАН-(ВМ) ВМ5): Media Foundation Windows через мост ComposeMediaPlayer
 * (`WindowsNativeBridge`, MIT; DLL 64 КБ лежит в его jar). Звук ведёт сам Media Foundation, кадры
 * забираются по готовности и рисуются в окне Compose — отдельного нативного окна нет.
 *
 * Все вызовы моста — в одном своём потоке: чтение кадра и перемотка в нём не пересекаются.
 * WebM — только с расширениями Windows для VP9, MKV и AVI — нет: не открылось — окно берёт браузерный
 * движок (ВМ6, ВМ7). Звук без картинки — [MediaEngineAudio].
 */
internal class WindowsOwnPlayer : OwnPlayer {
    private val bridge = WindowsNativeBridge
    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "tima-own-player").apply { isDaemon = true } }
    private val native = thread.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + native)
    private val _state = MutableStateFlow(OwnPlayerState())
    override val state: StateFlow<OwnPlayerState> = _state.asStateFlow()

    private val _frame = MutableStateFlow<ImageBitmap?>(null)
    val frame: StateFlow<ImageBitmap?> = _frame.asStateFlow()

    private var instance = 0L
    private var pump: Job? = null

    /** Звук без картинки — Media Engine: мост видео такой файл не открывает (`0xC00D36B3`). */
    private var audio: MediaEngineAudio? = null
    private var audioWatch: Job? = null
    private val bitmaps = arrayOfNulls<Bitmap>(3)
    private var next = 0
    private var bw = 0
    private var bh = 0

    override fun open(url: String, startMs: Long, play: Boolean) {
        scope.launch {
            close()
            _state.value = OwnPlayerState(speed = _state.value.speed)
            if (!started) {
                started = bridge.InitMediaFoundation() >= 0
                if (!started) return@launch fail("Media Foundation не поднялся")
                // Без остановки при выходе процесс падает в KERNELBASE (0x87A): устройство D3D11
                // моста живёт, пока выгружается его DLL. Так же лечит это и сам ComposeMediaPlayer.
                runCatching {
                    Runtime.getRuntime().addShutdownHook(Thread { runCatching { bridge.ShutdownMediaFoundation() } })
                }
            }
            instance = bridge.createInstance()
            if (instance == 0L) return@launch fail("нет экземпляра")
            val hr = bridge.OpenMedia(instance, url, false)
            if (hr < 0) {
                // Видео нет — звук: тем же адресом через Media Engine (mp3, m4a, звук HLS).
                runCatching { bridge.destroyInstance(instance) }
                instance = 0L
                val a = MediaEngineAudio()
                audio = a
                audioWatch = scope.launch { a.state.collect { _state.value = it } }
                a.open(url, startMs, play)
                return@launch
            }
            val size = IntArray(2)
            bridge.GetVideoSize(instance, size)
            // Картинка больше 1280 по стороне — ужимается здесь: в окне её всё равно не видно, а
            // копия кадра 4K — 33 МБ на каждый кадр.
            if (size[0] > 1280 || size[1] > 1280) {
                val k = 1280f / maxOf(size[0], size[1])
                if (bridge.SetOutputSize(instance, (size[0] * k).toInt(), (size[1] * k).toInt()) >= 0) bridge.GetVideoSize(instance, size)
            }
            val duration = LongArray(1)
            bridge.GetMediaDuration(instance, duration)
            val durationMs = duration[0] / 10_000
            _state.value = _state.value.copy(width = size[0], height = size[1], durationMs = durationMs, live = durationMs <= 0)
            if (_state.value.speed != 1f) bridge.SetPlaybackSpeed(instance, _state.value.speed)
            if (startMs > 0) bridge.SeekMedia(instance, startMs * 10_000)
            if (play) {
                bridge.SetPlaybackState(instance, true, false)
                _state.value = _state.value.copy(playing = true)
            }
            pump = scope.launch { pumpFrames() }
        }
    }

    private fun fail(why: String) {
        _state.value = _state.value.copy(error = why, playing = false)
    }

    private suspend fun pumpFrames() {
        val hr = IntArray(1)
        val size = IntArray(2)
        val pos = LongArray(1)
        var lastTick = 0L
        while (scope.isActive && instance != 0L) {
            val now = System.currentTimeMillis()
            if (now - lastTick > 200) {
                lastTick = now
                if (bridge.GetMediaPosition(instance, pos) >= 0) {
                    val ms = pos[0] / 10_000
                    val s = _state.value
                    _state.value = s.copy(positionMs = ms, started = s.started || (s.playing && ms > 300))
                }
                if (bridge.IsEOF(instance)) {
                    _state.value = _state.value.copy(playing = false, ended = true)
                }
            }
            if (!_state.value.playing) {
                delay(50)
                continue
            }
            val src = bridge.ReadVideoFrame(instance, hr)
            if (src == null || hr[0] < 0) {
                delay(5)
                continue
            }
            bridge.GetVideoSize(instance, size)
            val w = size[0]
            val h = size[1]
            if (w <= 0 || h <= 0) {
                bridge.UnlockVideoFrame(instance)
                delay(5)
                continue
            }
            if (w != bw || h != bh || bitmaps[0] == null) {
                val info = ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
                for (i in bitmaps.indices) bitmaps[i] = Bitmap().apply { allocPixels(info) }
                bw = w
                bh = h
            }
            val target = bitmaps[next]!!
            next = (next + 1) % bitmaps.size
            val pix = target.peekPixels()
            val dst = pix?.let { WindowsNativeBridge.nWrapPointer(it.addr, it.rowBytes.toLong() * h) }
            if (dst != null) {
                src.rewind()
                val rowBytes = pix.rowBytes
                if (rowBytes == w * 4) {
                    src.limit(minOf(src.capacity(), w * h * 4))
                    dst.put(src)
                } else {
                    for (y in 0 until h) {
                        src.limit(minOf(src.capacity(), (y + 1) * w * 4)).position(y * w * 4)
                        dst.position(y * rowBytes)
                        dst.put(src)
                    }
                }
                target.notifyPixelsChanged()
            }
            bridge.UnlockVideoFrame(instance)
            if (dst != null) {
                _frame.value = target.asComposeImageBitmap()
                if (!_state.value.started) _state.value = _state.value.copy(started = true, width = w, height = h)
            }
        }
    }

    override fun play() {
        audio?.let { return it.play() }
        scope.launch {
            if (instance == 0L) return@launch
            if (_state.value.ended) {
                bridge.SeekMedia(instance, 0)
                _state.value = _state.value.copy(ended = false)
            }
            bridge.SetPlaybackState(instance, true, false)
            _state.value = _state.value.copy(playing = true)
        }
    }

    override fun pause() {
        audio?.let { return it.pause() }
        scope.launch {
            if (instance == 0L) return@launch
            bridge.SetPlaybackState(instance, false, false)
            _state.value = _state.value.copy(playing = false)
        }
    }

    override fun seek(ms: Long) {
        audio?.let { return it.seek(ms) }
        scope.launch {
            if (instance == 0L) return@launch
            bridge.SeekMedia(instance, ms.coerceAtLeast(0) * 10_000)
            _state.value = _state.value.copy(positionMs = ms, ended = false)
        }
    }

    override fun setSpeed(speed: Float) {
        audio?.let { return it.setSpeed(speed) }
        scope.launch {
            if (instance != 0L) bridge.SetPlaybackSpeed(instance, speed)
            _state.value = _state.value.copy(speed = speed)
        }
    }

    private fun close() {
        audioWatch?.cancel()
        audio?.release()
        audio = null
        pump?.cancel()
        pump = null
        if (instance != 0L) {
            runCatching { bridge.SetPlaybackState(instance, false, true) }
            runCatching { bridge.CloseMedia(instance) }
            runCatching { bridge.destroyInstance(instance) }
            instance = 0L
        }
        _frame.value = null
    }

    override fun release() {
        scope.launch { close() }.invokeOnCompletion {
            scope.cancel()
            thread.shutdown()
        }
    }

    companion object {
        /** Media Foundation поднимается раз на процесс. */
        @Volatile
        private var started = false
    }
}

private val onWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

@Composable
actual fun rememberOwnPlayer(): OwnPlayer? {
    if (!onWindows) return null
    val player = remember { runCatching { WindowsOwnPlayer() }.getOrNull() } ?: return null
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}

@Composable
actual fun OwnPlayerSurface(player: OwnPlayer, modifier: Modifier) {
    val windows = player as? WindowsOwnPlayer ?: return
    val frame by windows.frame.collectAsState()
    Canvas(modifier) {
        val f = frame ?: return@Canvas
        val k = minOf(size.width / f.width, size.height / f.height)
        val w = (f.width * k).toInt()
        val h = (f.height * k).toInt()
        val at = Offset((size.width - w) / 2, (size.height - h) / 2)
        drawImage(f, dstOffset = IntOffset(at.x.toInt(), at.y.toInt()), dstSize = IntSize(w, h))
    }
}
