package io.tima.core.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Проигрывание голосового (ПЛАН-(ВМ) ВМ4): файл расшифрован в память, пакеты Opus раскодируются
 * по ходу — 10 минут целиком в отсчётах заняли бы 57 МБ, а пакеты — 1,8. Скорость 1× · 1,5× · 2×
 * с сохранением тона ([TimeStretch]), перемотка — к любому месту.
 *
 * Перемотка начинается на четыре пакета раньше нужного места (80 мс, RFC 7845 §4.6): декодеру
 * нужно время войти в звук, иначе первый кусок после перемотки хрипит.
 */
class VoicePlayback(
    private val stream: OggOpus.Stream,
    private val scope: CoroutineScope,
    private val sinkOf: () -> PcmSink? = VoiceDevices::speaker,
    private val decoderOf: () -> VoiceDecoder? = VoiceDevices::decoder,
) {
    data class State(
        val playing: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val speed: Float = 1f,
        /** Доиграло до конца. */
        val ended: Boolean = false,
        /** Колонки или декодер недоступны. */
        val failed: Boolean = false,
    )

    private val _state = MutableStateFlow(State(durationMs = stream.durationMs))
    val state: StateFlow<State> = _state.asStateFlow()

    /** Доиграло до конца — для «голосовые подряд» (Р40). */
    var onEnded: (() -> Unit)? = null

    /** Начало каждого пакета в отсчётах 48 кГц от начала файла (с задержкой кодера). */
    private val starts: LongArray = LongArray(stream.packets.size).also { a ->
        var at = 0L
        for (i in a.indices) {
            a[i] = at
            at += OggOpus.packetSamples(stream.packets[i])
        }
    }

    private var job: Job? = null
    private var sink: PcmSink? = null

    fun play() {
        val s = _state.value
        if (s.playing) return
        start(if (s.ended || s.positionMs >= s.durationMs) 0 else s.positionMs)
    }

    fun pause() {
        job?.cancel()
        job = null
        sink?.flush()
        _state.value = _state.value.copy(playing = false)
    }

    fun seek(ms: Long) {
        val to = ms.coerceIn(0, stream.durationMs)
        if (_state.value.playing) start(to) else _state.value = _state.value.copy(positionMs = to, ended = false)
    }

    fun setSpeed(speed: Float) {
        val s = _state.value
        _state.value = s.copy(speed = speed)
        if (s.playing) start(s.positionMs)
    }

    fun release() {
        job?.cancel()
        job = null
        sink?.let { runCatching { it.close() } }
        sink = null
        _state.value = _state.value.copy(playing = false)
    }

    private fun start(fromMs: Long) {
        job?.cancel()
        sink?.flush()
        val speed = _state.value.speed
        _state.value = _state.value.copy(playing = true, positionMs = fromMs, ended = false, failed = false)
        job = scope.launch(Dispatchers.IO) {
            val out = sink ?: sinkOf()?.also { sink = it }
            val decoder = decoderOf()
            if (out == null || decoder == null) {
                _state.value = _state.value.copy(playing = false, failed = true)
                return@launch
            }
            val stretch = if (speed == 1f) null else TimeStretch(out.rate, speed)
            val target = fromMs * 48 + stream.preSkip
            val end = stream.preSkip + stream.samples
            var k = starts.indexOfLast { it <= target }.coerceAtLeast(0)
            k = (k - PREROLL).coerceAtLeast(0)
            val pcm = ShortArray(MAX_FRAME)
            while (isActive && k < stream.packets.size) {
                val n = decoder.decode(stream.packets[k], pcm)
                val from = starts[k]
                k++
                if (n <= 0) continue
                // Нужная часть пакета: не раньше места начала и не дальше конца записи.
                val skip = (target - from).coerceIn(0, n.toLong()).toInt()
                val keep = (minOf(from + n, end) - from).toInt()
                if (keep <= skip) {
                    if (from + n >= end) break
                    continue
                }
                val piece = pcm.copyOfRange(skip, keep)
                val sound = stretch?.put(piece, piece.size) ?: piece
                if (sound.isNotEmpty()) out.write(sound, sound.size)
                if (!isActive) break
                _state.value = _state.value.copy(positionMs = ((from + keep - stream.preSkip) / 48).coerceIn(0, stream.durationMs))
                if (from + n >= end) break
            }
            if (isActive) {
                _state.value = _state.value.copy(playing = false, ended = true, positionMs = stream.durationMs)
                onEnded?.invoke()
            }
        }
    }

    private companion object {
        const val PREROLL = 4
        const val MAX_FRAME = 5760 // 120 мс при 48 кГц — самый длинный пакет Opus
    }
}
