package io.tima.feature.auth

import io.tima.domain.account.AccountDevice
import io.tima.testui.FOREIGN_BACKGROUND
import io.tima.testui.bothThemes
import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Список устройств в снимках.
 *
 * Проверяется то, что решает судьбу нажатия: **своё устройство отличимо от чужого**. Строки
 * похожи — «Телефон» и «Телефон», — и если пометка не нарисовалась, человек отключит то
 * устройство, с которого смотрит.
 */
class DeviceScreenTest {

    @Test
    fun список_рисуется_в_обеих_темах() {
        val snapshots = bothThemes("устройства", WIDTH, HEIGHT) { screen(STATE) }
        val difference = snapshots.getValue("светлая").difference(snapshots.getValue("тёмная"))
        assertTrue(difference > 0.10, "темы расходятся лишь на ${(difference * 100).toInt()}%")
    }

    /**
     * Пометка своего устройства нарисована.
     *
     * Сравнением с тем же списком, где своего устройства нет: если разницы нет вовсе,
     * значит пометка не рисуется, и все строки выглядят одинаково отключаемыми.
     */
    @Test
    fun своё_устройство_отличимо() {
        val withMark = capture("устройства-своё", WIDTH, HEIGHT, dark = false) { screen(STATE) }
        val markWithout = capture("устройства-чужие", WIDTH, HEIGHT, dark = false) {
            screen(
                STATE.copy(
                    devices = STATE.devices.map {
                        AccountDevice(it.deviceId, it.name, it.createdAt, current = false)
                    },
                ),
            )
        }

        assertTrue(withMark.difference(markWithout) > 0.0, "пометка своего устройства не нарисовалась")
    }

    /**
     * **Экран заливает свой фон.**
     *
     * Найдено глазами на телефоне: экран без фона показывает то, что под ним, и в светлой
     * теме это выглядело как тёмный прямоугольник ниже шапки. Снимки этого не видели, потому
     * что снимают компонент, а не окно; поэтому проверка спрашивает прямо — есть ли на
     * снимке большое пятно цвета поверхности.
     */
    @Test
    fun экран_заливает_свой_фон() {
        val snapshots = bothThemes("устройства-фон", WIDTH, HEIGHT, backdrop = FOREIGN_BACKGROUND) {
            screen(STATE)
        }
        for ((name, snapshot) in snapshots) {
            assertTrue(
                !snapshot.has(FOREIGN_BACKGROUND),
                "$name: сквозь экран видна подложка — он не залил свой фон",
            )
        }
    }

    /** Вопрос перед отключением занимает экран: решение должно выглядеть решением. */
    @Test
    fun вопрос_заменяет_список() {
        val list = capture("устройства-список", WIDTH, HEIGHT, dark = false) { screen(STATE) }
        val question = capture("устройства-вопрос", WIDTH, HEIGHT, dark = false) {
            screen(STATE.copy(ask = "d-2"))
        }

        assertTrue(question.difference(list) > 0.05, "вопрос не заменил список")
    }

    private companion object {
        const val WIDTH = 380
        const val HEIGHT = 700

        val STATE = DevicesState(
            devices = listOf(
                AccountDevice("d-1", "Телефон", "2026-08-20T10:00:00Z", current = true),
                AccountDevice("d-2", "Компьютер", "2026-08-23T10:00:00Z", current = false),
            ),
        )

        @androidx.compose.runtime.Composable
        fun screen(state: DevicesState) = DeviceScreen(
            state = state,
            onAsk = {},
            onConfirm = {},
            onChangedMind = {},
        )
    }
}
