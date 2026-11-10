package io.tima.feature.call

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
 * Новый вид окна звонка (ПЛАН-(ЗН), заказчик 2026-11-10) в снимках: ряд по макету, цвета прежней
 * панели, лист настроек, кнопки приближения; групповой — без изменений.
 */
class FreshCallScreenTest {

    private val talking = CallState(stage = CallStage.Connected, microphoneOn = true, cameraOn = true)

    private fun fresh(crop: CameraCrop? = null) = FreshCall(
        onMinimize = {}, onSaveTraffic = {}, onScreenShare = {}, crop = crop, onCrop = {},
    )

    @Test
    fun новый_вид_отличается_от_прежнего_и_красит_как_прежний() {
        val old = capture("звонок-прежний-вид", WIDTH, HEIGHT, dark = false) { screen(talking, null) }
        val new = capture("звонок-новый-вид", WIDTH, HEIGHT, dark = false) { screen(talking, fresh()) }
        assertTrue(new.difference(old) > 0.0, "новый вид нарисовался прежним")
        // Цвета кнопок скопированы со старой панели (заказчик 2026-11-10): салатовая и красная.
        assertTrue(new.has(TimaColors.light.navigation, tolerance = 0.04), "нет салатовой кнопки")
        assertTrue(new.has(TimaColors.light.alarm, tolerance = 0.04), "нет красной «Завершить»")
    }

    @Test
    fun групповой_остаётся_прежним() {
        val tiles = listOf(GroupTile("me", "Вы", "В", null, true, false, false, true, userId = "me"))
        val stage = GroupStage("Планёрка", tiles, 1, 25, false, true, {}, {}, GroupView())
        val old = capture("групповой-без-нового", WIDTH, HEIGHT, dark = false) { screen(talking, null, stage) }
        val new = capture("групповой-с-новым", WIDTH, HEIGHT, dark = false) { screen(talking, fresh(), stage) }
        assertEquals(0.0, new.difference(old), "групповой звонок получил новый вид (ответ заказчика 4)")
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
                    onRemoteVideo = {},
                    group = group,
                    fresh = fresh,
                )
            },
        )
    }
}
