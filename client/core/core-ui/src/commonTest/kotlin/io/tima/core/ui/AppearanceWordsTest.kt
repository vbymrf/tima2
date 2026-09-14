package io.tima.core.ui

import io.tima.core.words.Language
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **У каждого цветового места и каждой темы есть имя на каждом языке** (ПЛАН-ЯЗЫКА, Я-D).
 *
 * Обход жил в `WordsTest`, пока перечни лежали рядом со словарём. Теперь словарь их не
 * знает, и полноту проверяет тот, чьи это ключи, — дизайн-система.
 *
 * Пустая надпись здесь — пропущенный перевод, прошедший компилятор: имя есть, значения
 * нет. Пояснение (`about`) в счёт не идёт: у трёх мест оно пусто по решению.
 */
class AppearanceWordsTest {

    @Test
    fun у_темы_и_цветового_места_есть_имя_на_каждом_языке() {
        for (language in Language.entries) {
            val words = language.words?.appearance ?: continue
            assertTrue(
                ThemeChoice.entries.none { words.theme(it).isBlank() },
                "в словаре «${language.ownName}» у темы нет названия",
            )
            assertTrue(
                ColorSlot.entries.none { words.slot(it).isBlank() },
                "в словаре «${language.ownName}» у цветового места нет названия",
            )
            assertTrue(
                VitalPair.entries.none { words.place(it).isBlank() },
                "в словаре «${language.ownName}» у защищаемой пары нет описания места",
            )
        }
    }

    @Test
    fun беда_с_цветом_говорит_на_каждом_языке() {
        // Фраза собирается из частей, и части приходят из словаря: склейка на экране
        // развалилась бы первой — на другом языке порядок слов другой.
        for (language in Language.entries) {
            val words = language.words?.appearance ?: continue
            val troubles = listOf(
                ColorTrouble.Empty,
                ColorTrouble.NotHex("Ж"),
                ColorTrouble.WrongLength(5),
            )
            assertTrue(
                troubles.none { words.colorTrouble(it).isBlank() },
                "в словаре «${language.ownName}» беда с цветом осталась без слов",
            )
            assertTrue(
                words.colorTrouble(ColorTrouble.NotHex("Ж")).contains("Ж"),
                "в словаре «${language.ownName}» набранные знаки не попали во фразу",
            )
            assertTrue(
                words.colorTrouble(ColorTrouble.WrongLength(5)).contains("5"),
                "в словаре «${language.ownName}» длина не попала во фразу",
            )
        }
    }
}
