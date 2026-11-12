package io.tima.feature.call

import androidx.compose.ui.graphics.compositeOver
import io.tima.core.call.CallStage
import io.tima.core.call.CallState
import io.tima.core.call.CameraCrop
import io.tima.core.ui.Stage
import io.tima.core.ui.TimaColors
import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Окно звонка (ПЛАН-(ЗН), заказчик 2026-11-10) в снимках: ряд по макету, цвета прежней панели, лист
 * настроек, кнопки приближения. С ЗН9 (заказчик 2026-11-12) вид один — и в групповом, с «👥» под «≡».
 */
class FreshCallScreenTest {

    private val talking = CallState(stage = CallStage.Connected, microphoneOn = true, cameraOn = true)

    private fun fresh(crop: CameraCrop? = null) = FreshCall(
        onMinimize = {}, onSaveTraffic = {}, onScreenShare = {}, crop = crop, onCrop = {},
    )

    @Test
    fun ряд_красит_как_прежняя_панель() {
        val new = capture("звонок-новый-вид", WIDTH, HEIGHT, dark = false) { screen(talking, fresh()) }
        // Цвета кнопок скопированы со старой панели (заказчик 2026-11-10): салатовая и красная.
        assertTrue(new.has(TimaColors.light.navigation, tolerance = 0.04), "нет салатовой кнопки")
        assertTrue(new.has(TimaColors.light.alarm, tolerance = 0.04), "нет красной «Завершить»")
    }

    @Test
    fun групповой_в_том_же_виде_с_группой_под_настройками() {
        // Заказчик 2026-11-12: «Прежний убираем совсем… переделать и для групповых звонков кнопки… кнопка Группа кто
        // в звонке ниже настроек». Ряд тот же, что на двоих: внизу слева «Свернуть»; под «≡» — ещё кнопка, ряд выше.
        val tiles = listOf(GroupTile("me", "Вы", "В", null, true, false, false, true, userId = "me"))
        val stage = GroupStage("Планёрка", tiles, 1, 25, false, true, {}, {}, GroupView())
        val group = capture("групповой-новый-вид", WIDTH, HEIGHT, dark = false) { screen(talking, fresh(), stage) }
        val pair = capture("звонок-новый-вид-для-группы", WIDTH, HEIGHT, dark = false) { screen(talking, fresh()) }
        // Серый кружок — полупрозрачная «линия» поверх белого фона.
        val grey = TimaColors.light.line.compositeOver(TimaColors.light.surface)
        val left = 0 until 70
        val bottom = HEIGHT - 170 until HEIGHT
        assertTrue(group.patchHas(grey, x = left, y = bottom), "в групповом нет «Свернуть» нового ряда")
        assertTrue(pair.patchHas(grey, x = left, y = bottom), "на двоих нет «Свернуть»")
        // «👥» под «≡» — серый круг там, где на двоих его нет.
        val underSheet = 230 until 330
        val low = HEIGHT - 60 until HEIGHT
        assertTrue(group.patchHas(grey, x = underSheet, y = low, side = 6), "нет «👥 Группа» под «≡»")
    }

    @Test
    fun режим_адаптации_рисует_кнопки_поверх_изображения() {
        val without = capture("звонок-без-адаптации", WIDTH, HEIGHT, dark = false) { screen(talking, fresh()) }
        val with = capture("звонок-адаптация-2x", WIDTH, HEIGHT, dark = false) { screen(talking, fresh(CameraCrop(2f))) }
        assertTrue(with.difference(without) > 0.0, "кнопок приближения не видно")
        // Камера выключена — приближать нечего, кнопок нет.
        val off = talking.copy(cameraOn = false)
        val a = capture("звонок-адаптация-без-камеры", WIDTH, HEIGHT, dark = false) { screen(off, fresh(CameraCrop(2f))) }
        val b = capture("звонок-без-камеры", WIDTH, HEIGHT, dark = false) { screen(off, fresh()) }
        assertEquals(0.0, a.difference(b), "кнопки приближения при выключенной камере")
    }

    @Test
    fun лист_настроек_показывает_выключатели() {
        val off = capture("лист-звонка-выкл", WIDTH, 260, dark = false) {
            Stage(column = { CallSheet(saving = false, onSaveTraffic = {}, sharing = false, onScreenShare = {}) })
        }
        val on = capture("лист-звонка-вкл", WIDTH, 260, dark = false) {
            Stage(column = { CallSheet(saving = true, onSaveTraffic = {}, sharing = true, onScreenShare = {}) })
        }
        assertTrue(on.has(TimaColors.light.navigation, tolerance = 0.04), "включённые выключатели не горят")
        assertTrue(!off.has(TimaColors.light.navigation, tolerance = 0.04), "выключенные горят")
    }

    @Test
    fun лист_фильтров_рисует_ползунки() {
        // ПЛАН-(ФЗ) ФЗ7: включённые фильтры и ненулевые ползунки — салатовые круги.
        val on = io.tima.core.call.CallFilters(on = true, smooth = 60, whiten = 30, thin = 20)
        val shot = capture("фильтры-лист", WIDTH, 380, dark = false) {
            Stage(column = { FiltersPanel(filters = on, onChange = { _, _ -> }, onClose = {}) })
        }
        assertTrue(shot.has(TimaColors.light.navigation, tolerance = 0.04), "включённые фильтры не видны")
    }

    private companion object {
        const val WIDTH = 380
        const val HEIGHT = 800

        @androidx.compose.runtime.Composable
        fun screen(state: CallState, fresh: FreshCall?, group: GroupStage? = null) = Stage(
            column = {
                CallScreen(
                    state = state,
                    peer = "Аня Борисова",
                    incoming = false,
                    seconds = 42,
                    onAccept = {},
                    onDecline = {},
                    onHangUp = {},
                    onMicrophone = {},
                    onCamera = {},
                    onSpeaker = {},
                    group = group,
                    fresh = fresh,
                )
            },
        )
    }
}
