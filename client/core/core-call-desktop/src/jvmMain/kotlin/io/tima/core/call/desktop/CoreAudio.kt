package io.tima.core.call.desktop

import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.FloatByReference
import com.sun.jna.ptr.PointerByReference

/**
 * Микрофон Windows через Core Audio: громкость и уровень (ПЛАН-ЗВОНКОВ-ПК, настройка
 * «Микрофон и камера»).
 *
 * ── ПОЧЕМУ НЕ ЧЕРЕЗ LIVEKIT ─────────────────────────────────────────────────
 *
 * Звук звонка ведёт ADM WebRTC, и ни громкости, ни уровня наружу он не отдаёт: своя
 * дорожка микрофона без комнаты кадров не даёт (проверено 2026-09-26). Громкость же — это
 * ручка самой Windows («Параметры → Звук → Микрофон»), и уровень Windows меряет сама.
 * Идентификатор устройства у ADM и у Core Audio **один и тот же** (`{0.0.1.00000000}.{…}`),
 * поэтому выбор человека находит нужный микрофон без сопоставления по имени.
 *
 * ── КАК ВЫЗЫВАЕТСЯ ──────────────────────────────────────────────────────────
 *
 * COM без обёрток: объект — указатель на таблицу функций, номер функции — её место в
 * интерфейсе по заголовкам Windows SDK (`mmdeviceapi.h`, `endpointvolume.h`). Обёртки
 * jna-platform для этих интерфейсов нет, а своя на три вызова была бы длиннее самих вызовов.
 */
internal class CoreAudio private constructor(private val device: Pointer) {

    private val volume: Pointer? = activate(IID_ENDPOINT_VOLUME)
    private val meter: Pointer? = activate(IID_METER)

    /** Громкость 0…1 или `null`, если Windows не ответила. */
    fun volume(): Float? {
        val v = volume ?: return null
        val out = FloatByReference()
        return if (call(v, VOLUME_GET_SCALAR, out) == S_OK) out.value else null
    }

    fun setVolume(value: Float) {
        val v = volume ?: return
        call(v, VOLUME_SET_SCALAR, value.coerceIn(0f, 1f), null)
    }

    /** Пик сигнала 0…1 с прошлого опроса. Ненулевой только пока микрофон кто-то пишет. */
    fun peak(): Float {
        val m = meter ?: return 0f
        val out = FloatByReference()
        return if (call(m, METER_GET_PEAK, out) == S_OK) out.value else 0f
    }

    fun close() {
        volume?.let { call(it, RELEASE) }
        meter?.let { call(it, RELEASE) }
        call(device, RELEASE)
    }

    private fun activate(iid: String): Pointer? {
        val out = PointerByReference()
        val hr = call(device, DEVICE_ACTIVATE, Guid.GUID.fromString(iid).pointer, CLSCTX_ALL, null, out)
        return if (hr == S_OK) out.value else null
    }

    companion object {
        /**
         * Микрофон по идентификатору ADM или микрофон по умолчанию для связи (`null`).
         * `null` в ответ — Core Audio недоступен: не Windows или COM отказал.
         */
        fun microphone(id: String?): CoreAudio? = runCatching {
            if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return null
            // Многопоточная квартира: зовём из разных потоков, а объекты Core Audio это
            // допускают. Повторная инициализация отвечает S_FALSE — не беда.
            Ole32.INSTANCE.CoInitializeEx(null, COINIT_MULTITHREADED)
            val enumerator = PointerByReference()
            val hr = Ole32.INSTANCE.CoCreateInstance(
                Guid.GUID.fromString(CLSID_ENUMERATOR), null, CLSCTX_ALL,
                Guid.GUID.fromString(IID_ENUMERATOR), enumerator,
            )
            if (hr.toInt() != S_OK) return null
            val device = PointerByReference()
            val found = if (id != null) {
                call(enumerator.value, ENUMERATOR_GET_DEVICE, WString(id), device)
            } else {
                call(enumerator.value, ENUMERATOR_GET_DEFAULT, E_CAPTURE, E_COMMUNICATIONS, device)
            }
            call(enumerator.value, RELEASE)
            if (found != S_OK) null else CoreAudio(device.value)
        }.getOrNull()

        private fun call(obj: Pointer, index: Int, vararg args: Any?): Int {
            val table = obj.getPointer(0)
            val fn = Function.getFunction(table.getPointer(index.toLong() * Native.POINTER_SIZE))
            return fn.invokeInt(arrayOf(obj, *args))
        }

        private const val S_OK = 0
        private const val CLSCTX_ALL = 0x17
        private const val COINIT_MULTITHREADED = 0
        private const val E_CAPTURE = 1
        private const val E_COMMUNICATIONS = 2

        private const val CLSID_ENUMERATOR = "{BCDE0395-E52F-467C-8E3D-C4579291692E}"
        private const val IID_ENUMERATOR = "{A95664D2-9614-4F35-A746-DE8DB63617E6}"
        private const val IID_ENDPOINT_VOLUME = "{5CDF2C82-841E-4546-9722-0CF74078229A}"
        private const val IID_METER = "{C02216F6-8C67-4B5B-9D00-D008E73E0064}"

        // Места функций в таблицах: 0–2 — IUnknown у всех.
        private const val RELEASE = 2
        private const val ENUMERATOR_GET_DEFAULT = 4
        private const val ENUMERATOR_GET_DEVICE = 5
        private const val DEVICE_ACTIVATE = 3
        private const val VOLUME_SET_SCALAR = 7
        private const val VOLUME_GET_SCALAR = 9
        private const val METER_GET_PEAK = 3
    }
}
