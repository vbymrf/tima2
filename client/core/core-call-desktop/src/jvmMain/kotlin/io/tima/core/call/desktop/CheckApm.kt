package io.tima.core.call.desktop

import com.sun.jna.Memory
import com.sun.jna.Pointer
import io.tima.core.call.CallSetup
import livekit.proto.ApmProcessReverseStreamRequest
import livekit.proto.ApmProcessStreamRequest
import livekit.proto.ApmSetStreamDelayRequest
import livekit.proto.FfiRequest
import livekit.proto.NewApmRequest

/**
 * Обработка звука WebRTC (APM) для проверки микрофона — та же, что в звонке.
 *
 * Без неё «Слушать себя» отдавал в колонки сырой микрофон, а он на этом ПК тихий: в звонке
 * его вытягивает автоусиление, в проверке — нет, и себя было не слышно (заказчик
 * 2026-09-26). С ней проверка слышит ровно то, что получит собеседник, и выключатели эха,
 * шума и усиления **можно услышать** до звонка.
 *
 * APM берёт кадры по 10 мс, 16 бит. Микрофон идёт в `process_stream`, то, что играем в
 * колонки, — в `reverse_stream`: это образец, по которому эхоподавление вычитает
 * вернувшийся из колонок звук.
 */
internal class CheckApm(setup: CallSetup, private val rate: Int) {

    private val handle: Long = Ffi.request(
        FfiRequest(
            new_apm = NewApmRequest(
                echo_canceller_enabled = setup.echoCancellation,
                gain_controller_enabled = setup.autoGain,
                high_pass_filter_enabled = true,
                noise_suppression_enabled = setup.noiseSuppression,
            ),
        ),
    ).new_apm?.apm?.handle?.id ?: error("обработка звука не создалась")

    private val frameBytes = rate / 100 * 2
    private val buffer = Memory(frameBytes.toLong())

    init {
        // Задержка между выводом и возвратом в микрофон — примерно буфер вывода. Точной
        // её не знает никто; APM подстраивается сам, но начинать с правдоподобной лучше.
        runCatching { Ffi.request(FfiRequest(apm_set_stream_delay = ApmSetStreamDelayRequest(apm_handle = handle, delay_ms = OUTPUT_DELAY_MS))) }
    }

    /** Обработать микрофон на месте, кадрами по 10 мс. */
    fun capture(data: ByteArray, length: Int) = each(data, length) { ptr ->
        FfiRequest(apm_process_stream = ApmProcessStreamRequest(apm_handle = handle, data_ptr = ptr, size = frameBytes, sample_rate = rate, num_channels = 1))
    }

    /** Показать APM то, что уходит в колонки. Сами данные не меняются. */
    fun render(data: ByteArray, length: Int) = each(data.copyOf(length), length) { ptr ->
        FfiRequest(apm_process_reverse_stream = ApmProcessReverseStreamRequest(apm_handle = handle, data_ptr = ptr, size = frameBytes, sample_rate = rate, num_channels = 1))
    }

    fun close() = Ffi.drop(handle)

    private inline fun each(data: ByteArray, length: Int, request: (Long) -> FfiRequest) {
        var at = 0
        val address = Pointer.nativeValue(buffer)
        while (at + frameBytes <= length) {
            buffer.write(0, data, at, frameBytes)
            Ffi.request(request(address))
            buffer.read(0, data, at, frameBytes)
            at += frameBytes
        }
    }

    private companion object {
        const val OUTPUT_DELAY_MS = 100
    }
}
