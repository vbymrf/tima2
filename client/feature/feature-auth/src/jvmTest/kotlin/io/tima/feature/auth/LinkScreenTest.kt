package io.tima.feature.auth

import io.tima.testui.FOREIGN_BACKGROUND
import io.tima.testui.bothThemes
import io.tima.testui.capture
import io.tima.testui.theme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Привязка устройства в снимках.
 *
 * Здесь проверяется то, чего не видит ни один тест поведения: **QR действительно
 * нарисовался**. Кодировщик может быть верен, состояние верно, а на экране пусто — Canvas
 * с нулевым размером, полотно не той стороной, клетка меньше пикселя. Обнаружилось бы это
 * на живом устройстве, где человек просто не может отсканировать «код».
 */
class LinkScreenTest {

    /**
     * Код нарисован тёмным по светлому.
     *
     * Пятно, а не пиксель: одиночный пиксель врёт на сглаживании. Сторона чернил взята
     * с запасом — 10 точек: при ширине экрана 380 клетка кода около пяти точек, значит
     * тёмная середина узора поиска даёт квадрат в пятнадцать. Буква такого сплошного
     * квадрата не даёт, и проверка не спутает код с текстом.
     *
     * Тёмное на светлом ищется **в обеих темах**: код читает чужая программа, и часть
     * сканеров не берёт светлые модули на тёмном фоне.
     */
    @Test
    fun код_нарисован_в_обеих_темах() {
        for ((name, snapshot) in bothThemes("привязка-код", WIDTH, HEIGHT) { codeScreen(CODE) }) {
            assertTrue(
                snapshot.patchHas(theme(name).paperCode, side = 6),
                "$name: светлой бумаги кода нет — QR не нарисовался",
            )
            assertTrue(
                snapshot.patchHas(theme(name).inkCode, side = 10),
                "$name: тёмных модулей нет — нарисовалось пустое полотно",
            )
        }
    }

    /**
     * Пока кода нет — и полотна нет.
     *
     * Пустой белый квадрат человек попробует отсканировать и решит, что сломан телефон.
     */
    @Test
    fun без_кода_полотна_нет() {
        val withCode = capture("привязка-с-кодом", WIDTH, HEIGHT, dark = false) { codeScreen(CODE) }
        val without = capture("привязка-ждём", WIDTH, HEIGHT, dark = false) { codeScreen(null) }

        assertTrue(withCode.difference(without) > 0.05, "экран без кода не отличается от экрана с кодом")
    }

    /** Экран подтверждения заливает свой фон — см. `ЭкранУстройствTest`. */
    @Test
    fun вопрос_заливает_свой_фон() {
        val snapshots = bothThemes("привязка-фон", WIDTH, HEIGHT, backdrop = FOREIGN_BACKGROUND) {
            LinkScreen(LinkState.Ask(name = "Компьютер"), {}, {})
        }
        for ((name, snapshot) in snapshots) {
            assertTrue(
                !snapshot.has(FOREIGN_BACKGROUND),
                "$name: сквозь экран видна подложка — он не залил свой фон",
            )
        }
    }

    /** Вопрос телефона рисуется в обеих темах. */
    @Test
    fun вопрос_подтверждения_рисуется() {
        val snapshots = bothThemes("привязка-вопрос", WIDTH, HEIGHT) {
            LinkScreen(
                state = LinkState.Ask(name = "Компьютер"),
                onTrust = {},
                onCancel = {},
            )
        }
        val difference = snapshots.getValue("светлая").difference(snapshots.getValue("тёмная"))
        assertTrue(difference > 0.10, "темы расходятся лишь на ${(difference * 100).toInt()}%")
    }

    /**
     * Отказ на вопросе нарисован.
     *
     * «Подтвердить может только телефон» — единственное, что скажет человеку, почему
     * ничего не произошло.
     */
    @Test
    fun отказ_на_вопросе_нарисован() {
        val without = capture("привязка-вопрос-чисто", WIDTH, HEIGHT, dark = false) {
            LinkScreen(LinkState.Ask(name = "Компьютер"), {}, {})
        }
        val with = capture("привязка-вопрос-беда", WIDTH, HEIGHT, dark = false) {
            LinkScreen(
                LinkState.Ask(
                    name = "Компьютер",
                    trouble = "Подтвердить подключение может только телефон",
                ),
                {},
                {},
            )
        }

        assertTrue(with.difference(without) > 0.0, "отказ не нарисовался вовсе")
    }

    private companion object {
        const val WIDTH = 380
        const val HEIGHT = 800

        /** Настоящий код привязки: та строка, какую строит сервер. */
        const val CODE = "tima://link/v1?session_id=aaaaaaaa-0000-0000-0000-00000000c4a7" +
            "&secret=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8" +
            "&encryption_key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8" +
            "&signing_key=AAMGCQwPEhUYGx4hJCcqLTAzNjk8P0JFSEtOUVRXWl0" +
            "&name=0JrQvtC80L_RjNGO0YLQtdGA"

        @androidx.compose.runtime.Composable
        fun codeScreen(code: String?) = EntryScreen(
            state = AuthState.DisplayCode(code = code),
            onNumber = {},
            onCodeCountry = {},
            onCode = {},
            onRequest = {},
            onConfirm = {},
            onBack = {},
        )
    }
}
