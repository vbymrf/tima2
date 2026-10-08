package io.tima.feature.shell

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Горизонтальный свайп между окнами — **только в средней зоне экрана**.
 *
 * ── ЗАЧЕМ ЗОНЫ, А НЕ ВЕСЬ ЭКРАН ─────────────────────────────────────────────
 *
 * Правило `интерфейс.md §1 «Жесты»` говорит: **одно движение — один смысл в этом
 * месте экрана**. Горизонталь в приложении занята дважды, и оба раза законно:
 *
 * - **сверху** — ряд историй окна «Общение»: он прокручивается вбок;
 * - **снизу** — перемотка видео и переключение вложений в слайдах окна «Медиа».
 *
 * Отдать всю горизонталь окнам значило бы отнять её у них — или, что хуже,
 * оставить оба смысла и получить экран, где один и тот же жест делает разное в
 * зависимости от того, успел ли внутренний список его перехватить.
 *
 * Поэтому экран делится на три полосы:
 *
 * | Полоса | Чья горизонталь | Почему такая высота |
 * |---|---|---|
 * | верхняя | истории и верхние ленты | высота ряда историй по макету: кольцо 56 + поля и подпись |
 * | средняя | **смена окна** | всё, что осталось, — и это большая часть экрана |
 * | нижняя | перемотка видео, вложения слайда | полоса прогресса со временем; над ней в макете поднимается столбик действий |
 *
 * Границы не выдуманы: обе взяты из `Layout-UI-light/стиль.css` — `.истории` и
 * `.прогресс-слайда ~ .действия-слайда { bottom: 46px }`.
 *
 * **Жест ничего не заменяет.** Видимый способ сменить окно — логотип с именем — есть
 * всегда, и свайп только ускоряет. Поэтому его отсутствие в верхней и нижней полосе
 * ничего не ломает: там просто работает то, что нарисовано.
 */
fun Modifier.windowSwipe(
    onLeft: () -> Unit,
    onRight: () -> Unit,
    enabled: Boolean = true,
    beforeContent: Boolean = true,
): Modifier = composed {
    if (!enabled) return@composed this

    // Обработчики — через rememberUpdatedState, а не ключами pointerInput: новая лямбда
    // перезапустила бы жест посреди движения пальца.
    val left by rememberUpdatedState(onLeft)
    val right by rememberUpdatedState(onRight)

    pointerInput(beforeContent) {
        val top = STORIES_ZONE.toPx()
        val bottom = SEEK_ZONE.toPx()
        val threshold = THRESHOLD.toPx()
        val flingDistance = FLING_DISTANCE.toPx()
        val flingSpeed = FLING_SPEED.toPx()
        // ── ПОЧЕМУ ПРОХОД Initial (Redmi A3, 2026-10-08) ─────────────────────
        //
        // Прежде жест слушал проход Main, а он идёт от ребёнка к родителю: вертикальный
        // список «Телефона» видел каждое движение раньше окна и забирал жест, едва палец
        // сдвигался вниз на порог. На слабом телефоне движения приходят пачкой раз в
        // кадр, и первое же событие несёт и dx, и dy сверх порога — побеждал список, даже
        // если вели вбок. Хуже того, катящийся по инерции список забирает касание сразу,
        // без порога: первое касание его останавливало, листало окна только второе.
        // В «Общении» и на «Странице» списков нет — поэтому там свайп и шёл с первого раза.
        //
        // Initial идёт от родителя к ребёнку: окно решает первым. Решает **направление**:
        // по горизонтали больше, чем по вертикали, — жест окна, и с этого события он
        // поглощается (у списка прокрутка отменяется сама); по вертикали больше — жест
        // отдаётся списку. Горизонтальных прокруток внутри средней полосы окон нет (ряд
        // разделов — в шапке, ряд историй — в верхней полосе), отнимать нечего.
        //
        // Окно звонка решает после содержимого (`beforeContent = false`): там внутри
        // картинки свои горизонтальные жесты — ряд участников, переключатель вида.
        val pass = if (beforeContent) PointerEventPass.Initial else PointerEventPass.Main
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = pass)
            // Решает ТОЧКА НАЧАЛА, а не текущая: жест, начатый в средней полосе,
            // остаётся сменой окна, даже если палец уехал к краю экрана. Иначе
            // длинный свайп по диагонали терял бы смысл на середине пути.
            if (down.position.y <= top || down.position.y >= size.height - bottom) return@awaitEachGesture
            val slop = viewConfiguration.touchSlop
            val tracker = VelocityTracker()
            tracker.addPointerInputChange(down)
            var ours = false
            while (true) {
                val change = awaitPointerEvent(pass).changes.firstOrNull { it.id == down.id } ?: break
                // После содержимого: ребёнок забрал движение раньше, чем жест стал нашим, —
                // жест его, как и было.
                if (!ours && !beforeContent && change.isConsumed) break
                tracker.addPointerInputChange(change)
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                if (!ours) {
                    if (abs(dx) > slop && abs(dx) > abs(dy)) {
                        ours = true
                    } else if (abs(dy) > slop) {
                        break
                    }
                }
                // Событие потребляется, только когда жест наш: иначе средняя полоса
                // съедала бы прокрутку у всего, что окажется внутри неё.
                if (ours) change.consume()
                if (!change.pressed) {
                    if (ours) {
                        // Длинный свайп — по расстоянию; короткий росчерк — по расстоянию
                        // и скорости вместе, как у листаемых страниц Android.
                        val speed = tracker.calculateVelocity().x
                        val toLeft = dx <= -threshold || (dx <= -flingDistance && speed <= -flingSpeed)
                        val toRight = dx >= threshold || (dx >= flingDistance && speed >= flingSpeed)
                        if (toLeft) left() else if (toRight) right()
                    }
                    break
                }
            }
        }
    }
}

/**
 * Высота верхней полосы — ряд историй окна «Общение».
 *
 * Из макета: `.истории` — поля 8 сверху и 12 снизу, `.кольцо` 56, под ним подпись
 * именем. Округлено вверх до 96: лучше отдать историям лишние точки, чем отнять у
 * них жест на границе.
 */
private val STORIES_ZONE: Dp = 96.dp

/**
 * Высота нижней полосы — перемотка и вложения слайда.
 *
 * Из макета: `.прогресс-слайда ~ .действия-слайда { bottom: 46px }` — ровно та
 * высота, на которую в слайдах поднимается столбик действий, чтобы не соседствовать
 * с полосой прокрутки. Значит и полоса занимает её.
 */
private val SEEK_ZONE: Dp = 46.dp

/**
 * Сколько надо провести, чтобы окно сменилось.
 *
 * Меньше — окна начнут меняться от случайного движения при прокрутке списка;
 * больше — жест перестанет срабатывать с одного движения пальца.
 */
private val THRESHOLD: Dp = 72.dp

/**
 * Короткий росчерк: столько пути хватает, если палец шёл быстро ([FLING_SPEED] в секунду).
 * Числа — те же, что у листаемых страниц Android (`ViewPager`: 25 точек и 400 в секунду),
 * округлены вверх, чтобы случайное движение при прокрутке окна не меняло.
 */
private val FLING_DISTANCE: Dp = 32.dp

private val FLING_SPEED: Dp = 400.dp
