package io.tima.core.call.desktop

import livekit.proto.FfiRequest
import livekit.proto.NewApmRequest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Мост к настоящей `livekit_ffi.dll` — та же библиотека, что уедет в установщик.
 *
 * Проверяется ABI, а не логика: загрузилась ли библиотека, дошёл ли запрос protobuf и
 * вернулся ли ответ, который разбирается нашим кодом протокола. Ошибка в типе аргумента
 * JNA или разошедшиеся версии протокола и библиотеки ловятся здесь, а не посреди звонка.
 */
class FfiTest {

    @Test
    fun библиотека_грузится_и_отвечает_на_запрос() {
        assertNotNull(Ffi.libraryFile(), "нет livekit_ffi.dll — задача callNatives не отработала")
        assertNull(Ffi.start(), "библиотека не загрузилась")

        val answer = Ffi.request(FfiRequest(new_apm = NewApmRequest(
            echo_canceller_enabled = true,
            gain_controller_enabled = true,
            high_pass_filter_enabled = true,
            noise_suppression_enabled = true,
        )))
        val apm = assertNotNull(answer.new_apm, "ответ пришёл, но не на тот запрос")
        assertTrue(apm.apm.handle.id != 0L, "ручка обработки звука пустая")
        Ffi.drop(apm.apm.handle.id)
    }

    @Test
    fun повторный_запуск_ничего_не_ломает() {
        // Сервер FFI глобальный: второй `livekit_ffi_initialize` подменил бы обработчик
        // событий первого. Повторный `start` обязан быть пустым.
        assertNull(Ffi.start())
        assertNull(Ffi.start())
    }
}
