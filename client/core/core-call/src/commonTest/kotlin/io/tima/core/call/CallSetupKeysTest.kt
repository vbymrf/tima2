package io.tima.core.call

import kotlin.test.Test
import kotlin.test.assertEquals

/** Выбор микрофона, колонок и камеры из настроек устройства. */
class CallSetupKeysTest {

    @Test
    fun пустые_настройки_дают_систему_и_включённую_обработку() {
        // Обработка звука по умолчанию включена: эхо и шум без неё — первое, на что жалуются.
        assertEquals(CallSetup(), CallSetupKeys.read(emptyMap()))
    }

    @Test
    fun пустая_строка_значит_как_в_системе() {
        // Экран пишет «как в системе» пустой строкой: удалить ключ он не умеет.
        val setup = CallSetupKeys.read(mapOf(CallSetupKeys.MICROPHONE to "", CallSetupKeys.CAMERA to "cam-1"))
        assertEquals(null, setup.microphone)
        assertEquals("cam-1", setup.camera)
    }

    @Test
    fun ноль_выключает_обработку() {
        val setup = CallSetupKeys.read(mapOf(CallSetupKeys.ECHO to "0", CallSetupKeys.NOISE to "1", CallSetupKeys.GAIN to "0"))
        assertEquals(false, setup.echoCancellation)
        assertEquals(true, setup.noiseSuppression)
        assertEquals(false, setup.autoGain)
    }
}
