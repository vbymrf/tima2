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

/** Записанное голосовое: файл Ogg Opus, длительность и рисунок волны для сообщения. */
class VoiceClip(val ogg: ByteArray, val durationMs: Long, val wave: ByteArray)

/**
 * Запись голосового (ПЛАН-(ВМ) ВМ2): микрофон → кадры по 20 мс → Opus → пакеты. Пишет сразу при
 * создании; [stop] отдаёт [VoiceClip]. Предел — [VOICE_MAX_MS]: на нём запись встаёт сама
 * ([stoppedByLimit]), записанное остаётся.
 */
class VoiceRecording(
    private val source: PcmSource,
    private val encoder: VoiceEncoder,
    scope: CoroutineScope,
) {
    private val _elapsedMs = MutableStateFlow(0L)

    /** Сколько записано, мс. */
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    private val _levels = MutableStateFlow<List<Float>>(emptyList())

    /** Живая волна: громкость последних кусков, 0…1. */
    val levels: StateFlow<List<Float>> = _levels.asStateFlow()

    private val _stoppedByLimit = MutableStateFlow(false)
    val stoppedByLimit: StateFlow<Boolean> = _stoppedByLimit.asStateFlow()

    private val _failed = MutableStateFlow(false)

    /** Микрофон перестал отдавать звук. */
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    private val packets = ArrayList<ByteArray>()
    private val wave = VoiceWave()
    private var samples48 = 0L
    private var clip: VoiceClip? = null

    private val job: Job = scope.launch(Dispatchers.IO) {
        val frame = ShortArray(encoder.frame)
        val per48 = encoder.frame.toLong() * 48_000 / encoder.rate
        while (isActive) {
            var got = 0
            while (got < frame.size) {
                val n = source.read(frame, got, frame.size - got)
                if (n < 0) {
                    _failed.value = true
                    return@launch
                }
                got += n
                if (!isActive) break
            }
            if (got < frame.size) break
            wave.add(frame, frame.size)
            packets += encoder.encode(frame)
            samples48 += per48
            _elapsedMs.value = samples48 / 48
            _levels.value = wave.recent(LIVE_BARS)
            if (samples48 / 48 >= VOICE_MAX_MS) {
                _stoppedByLimit.value = true
                break
            }
        }
    }

    /** Остановить и собрать файл; ничего не записано — `null`. Повторный вызов отдаёт то же. */
    suspend fun stop(): VoiceClip? {
        clip?.let { return it }
        job.cancel()
        job.join()
        runCatching { source.close() }
        if (packets.isEmpty()) return null
        val ogg = OggOpus.write(packets, encoder.preSkip, samples48, encoder.rate)
        return VoiceClip(ogg, samples48 / 48, wave.bars()).also { clip = it }
    }

    /** Бросить запись. */
    fun cancel() {
        job.cancel()
        runCatching { source.close() }
    }

    companion object {
        /** Сколько столбиков в живой волне области записи. */
        const val LIVE_BARS = 40
    }
}
