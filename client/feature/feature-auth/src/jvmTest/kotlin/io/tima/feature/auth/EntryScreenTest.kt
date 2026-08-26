package io.tima.feature.auth

import androidx.compose.runtime.Composable
import io.tima.testui.FOREIGN_BACKGROUND
import io.tima.testui.bothThemes
import io.tima.testui.capture
import io.tima.testui.theme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Экран входа в снимках (К5.1).
 *
 * Проверяется то, что иначе ловится глазами на чужом устройстве: экран рисуется в обеих
 * темах, отказ видно, подсказка стенда видна, а плашки окна на входе нет — уходить с этого
 * экрана некуда, и вопрос «в каком я окне» здесь не стоит.
 */
class EntryScreenTest {

    /**
     * **Экран заливает свой фон** (находка 29).
     *
     * Снимается на краске, которой в палитре нет: видно её — значит экран показывает то,
     * что под ним. Подложка цветом темы такого не поймала бы никогда, потому что красит
     * ровно то же, что покрасил бы экран.
     */
    @Test
    fun экран_заливает_свой_фон() {
        val snapshots = bothThemes("вход-фон", WIDTH, HEIGHT, backdrop = FOREIGN_BACKGROUND) { screen(AuthState.Phone()) }
        for ((name, snapshot) in snapshots) {
            assertTrue(!snapshot.has(FOREIGN_BACKGROUND), "$name: сквозь экран видна подложка")
        }
    }

    @Test
    fun экран_рисуется_в_обеих_темах() {
        val snapshots = bothThemes("вход-телефон", WIDTH, HEIGHT) { screen(AuthState.Phone()) }
        val difference = snapshots.getValue("светлая").difference(snapshots.getValue("тёмная"))
        assertTrue(difference > 0.10, "темы расходятся лишь на ${(difference * 100).toInt()}%")
    }

    /**
     * Отказ виден, и виден **словами**.
     *
     * Красного в палитре нет вовсе; на этом экране беда и без цвета заметна — она
     * единственное, что изменилось. Но если она не нарисовалась вообще, человек остаётся с
     * кнопкой, которая «не работает».
     */
    @Test
    fun отказ_нарисован() {
        val without = capture("вход-без-беды", WIDTH, HEIGHT, dark = false) { screen(AuthState.Phone(number = "+79990000001")) }
        val with = capture("вход-беда", WIDTH, HEIGHT, dark = false) {
            screen(AuthState.Phone(number = "+79990000001", trouble = "Нет связи с сервером — повторим через 5 с"))
        }

        assertTrue(with.difference(without) > 0.0, "отказ не нарисовался вовсе")
    }

    /** Подсказка стенда видна: без неё сквозной прогон требовал бы настоящей SMS. */
    @Test
    fun подсказка_стенда_нарисована() {
        val without = capture("вход-код", WIDTH, HEIGHT, dark = false) { screen(code()) }
        val with = capture("вход-код-стенд", WIDTH, HEIGHT, dark = false) {
            screen(code().copy(standHint = "424242"))
        }

        assertTrue(with.difference(without) > 0.0)
    }

    /**
     * Плашки окна на входе нет.
     *
     * Салатовая плашка отвечает на вопрос «в каком я окне». На входе окно одно, уйти из
     * него некуда, и плашка сообщала бы то, чего человек не спрашивал.
     */
    @Test
    fun плашки_окна_на_входе_нет() {
        for ((name, snapshot) in bothThemes("вход-шапка", WIDTH, HEIGHT) { screen(AuthState.Phone()) }) {
            assertTrue(
                !snapshot.patchHas(theme(name).navigation, y = 0 until 60, side = 8),
                "$name: на входе появилась плашка окна",
            )
        }
    }

    private companion object {
        const val WIDTH = 380
        const val HEIGHT = 800

        fun code() = AuthState.Code(requestId = "r-1", phone = "+79990000001", code = "1234")

        @Composable
        fun screen(state: AuthState) = EntryScreen(
            state = state,
            onNumber = {},
            onCodeCountry = {},
            onCode = {},
            onRequest = {},
            onConfirm = {},
            onBack = {},
        )
    }
}
