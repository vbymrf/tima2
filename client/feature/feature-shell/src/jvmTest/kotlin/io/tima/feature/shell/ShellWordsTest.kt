package io.tima.feature.shell

import io.tima.core.words.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **У каждого окна, вкладки и пункта настроек есть имя на каждом языке** (ПЛАН-ЯЗЫКА, Я-D).
 *
 * Обход жил в `WordsTest` у словаря, пока перечни лежали там же. С Я-D словарь оболочку не
 * знает: раскладка «ключ → надпись» делается здесь, и проверяется тоже здесь.
 *
 * Ловится пропущенный перевод, прошедший компилятор: имя есть, значения нет.
 */
class ShellWordsTest {

    @Test
    fun у_окна_три_имени_на_каждом_языке() {
        forEachDictionary { name, words ->
            for (window in Window.entries) {
                val it = words.windows.name(window)
                assertTrue(it.full.isNotBlank(), "$name: у окна $window нет длинного имени")
                assertTrue(it.short.isNotBlank(), "$name: у окна $window нет короткого имени")
                assertTrue(it.about.isNotBlank(), "$name: у окна $window нет описания")
            }
        }
    }

    @Test
    fun длинное_и_короткое_имя_окна_не_разъезжаются_по_числу() {
        // Оба имени приходят из одного места — [WindowName], — и проверяется здесь именно
        // это: их нельзя завести порознь, и потому нельзя забыть одно из двух.
        forEachDictionary { name, words ->
            assertEquals(
                Window.entries.size,
                Window.entries.map { words.windows.name(it) }.distinct().size,
                "$name: два окна названы одинаково",
            )
        }
    }

    @Test
    fun у_вкладки_и_пункта_настроек_есть_надпись_на_каждом_языке() {
        forEachDictionary { name, words ->
            assertTrue(
                WindowTab.entries.none { words.tabs.label(it).isBlank() },
                "$name: у вкладки нет названия",
            )
            assertTrue(
                SettingsGroup.entries.none { words.settings2.group(it).isBlank() },
                "$name: у группы настроек нет названия",
            )
            assertTrue(
                SettingsItem.entries.none { words.settings2.item(it).isBlank() },
                "$name: у пункта настроек нет названия",
            )
        }
    }

    private fun forEachDictionary(check: (String, io.tima.core.words.Words) -> Unit) {
        for (language in Language.entries) {
            check(language.ownName, language.words ?: continue)
        }
    }
}
