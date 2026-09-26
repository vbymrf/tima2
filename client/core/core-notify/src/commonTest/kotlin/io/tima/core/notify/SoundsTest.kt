package io.tima.core.notify

import kotlin.test.Test
import kotlin.test.assertEquals

/** Выбор звука живёт строкой в настройках устройства — и обязан из неё возвращаться. */
class SoundsTest {

    @Test
    fun выбор_переживает_строку() {
        for (choice in listOf(
            SoundChoice.Default,
            SoundChoice.Silent,
            SoundChoice.System("content://media/internal/audio/media/42", "Утро"),
            SoundChoice.File("/data/user/0/io.tima.app.v2/files/sounds/ring.mp3", "Моя | песня"),
        )) {
            assertEquals(choice, soundChoiceOf(choice.wire()))
        }
    }

    @Test
    fun пустое_и_мусор_это_как_в_системе() {
        assertEquals(SoundChoice.Default, soundChoiceOf(null))
        assertEquals(SoundChoice.Default, soundChoiceOf(""))
        assertEquals(SoundChoice.Default, soundChoiceOf("system|"))
        assertEquals(SoundChoice.Default, soundChoiceOf("что-то"))
    }

    @Test
    fun своя_мелодия_у_контакта_своим_ключом() {
        assertEquals("sound.ring.u-1", SoundKeys.ringOf("u-1"))
    }
}
