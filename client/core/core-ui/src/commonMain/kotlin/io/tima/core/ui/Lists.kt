package io.tima.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

/**
 * Строка списка: **линия снизу, без карточки и без фона**.
 *
 * Правило разделения из макета, и оно короткое: **рамка** — у сообщения переписки,
 * **линия** — между записями списка и ленты, **заливка** — только там, где цвет что-то
 * означает. Фон содержимого белый, поэтому карточек на подложке больше нет, и
 * разделять приходится линией.
 *
 * Прошлая редакция делала обратное — карточка на серой подложке, — и тогда список из
 * двадцати чатов был двадцатью карточками, каждая из которых просила внимания.
 */
/**
 * Вид строк списка «Чатов» и «Каталога» Социума — раздел «Список» страницы «Вид разделов и списка» (заказчик
 * 2026-11-20). Свой у каждого окна, как и разделы; вне этих двух списков — умолчание, то есть прежний вид.
 *
 * @param lines строк в строке списка: 2 — как было; 3 — сообщение получает ещё строку, аватар крупнее.
 * @param startPercent отступ у левого края от прежнего, в процентах: 100 — прежний (он же наибольший), шаг 10, до 50.
 * @param endPercent то же у правого края.
 */
@androidx.compose.runtime.Immutable
data class ListLook(
    val lines: Int = 2,
    val startPercent: Int = 100,
    val endPercent: Int = 100,
    /** Пауза порядка после прокрутки, секунды (ПЛАН-(ВС) ВС3); 0 — без паузы. Вне окон с «Видом» — без паузы. */
    val pauseSeconds: Int = 0,
) {
    val three: Boolean get() = lines >= 3

    /** Две строки — обычный аватар (42), три — крупный (56), одинаково в «Чатах» и «Каталоге». */
    val avatar: AvatarSize get() = if (three) AvatarSize.Row else AvatarSize.Normal

    companion object {
        val PERCENTS = listOf(100, 90, 80, 70, 60, 50)
        val PAUSES = listOf(0, 5, 10, 20, 30, 60)

        /** Вне окон с «Видом» — прежний вид: отступы полные, паузы нет. */
        val DEFAULT = ListLook()

        /** Умолчания окна с «Видом» (ПЛАН-(ВС) 3, 6): слева 70%, пауза 10 с. */
        const val START_DEFAULT = 70
        const val PAUSE_DEFAULT = 10

        /** Сохранённое — в допустимое: чужое число строк — две, процент или пауза вне ряда — умолчание окна. */
        fun of(lines: Int?, startPercent: Int?, endPercent: Int?, pauseSeconds: Int? = null) = ListLook(
            lines = if (lines == 3) 3 else 2,
            startPercent = startPercent?.takeIf { it in PERCENTS } ?: START_DEFAULT,
            endPercent = endPercent?.takeIf { it in PERCENTS } ?: 100,
            pauseSeconds = pauseSeconds?.takeIf { it in PAUSES } ?: PAUSE_DEFAULT,
        )
    }
}

/**
 * Пауза порядка списка после прокрутки (ПЛАН-(ВС) ВС3, заказчик 2026-11-20): пока человек листает и ещё
 * [ListLook.pauseSeconds] после последнего движения, строки стоят на местах — новое сообщение обновляет строку, но не
 * поднимает её. Потом список встаёт по порядку. Движение ловится вложенной прокруткой: [connection] вешается на
 * родителя списка, и годится любой список внутри — ленивый, с разделами, гармошка.
 */
@androidx.compose.runtime.Stable
class CalmOrder internal constructor(private val pauseMs: Long, private val scope: kotlinx.coroutines.CoroutineScope) {
    private var frozen by androidx.compose.runtime.mutableStateOf<List<Any>?>(null)
    private var shown: List<Any> = emptyList()
    private var release: kotlinx.coroutines.Job? = null

    val connection = object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
        override fun onPostScroll(
            consumed: androidx.compose.ui.geometry.Offset,
            available: androidx.compose.ui.geometry.Offset,
            source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
        ): androidx.compose.ui.geometry.Offset {
            if (consumed.y != 0f) moved()
            return androidx.compose.ui.geometry.Offset.Zero
        }
    }

    /** Список сдвинулся: заморозить порядок, если ещё не заморожен, и отсчитать паузу заново. */
    fun moved() {
        if (pauseMs <= 0) return
        if (frozen == null) frozen = shown
        release?.cancel()
        release = scope.launch {
            kotlinx.coroutines.delay(pauseMs)
            frozen = null
        }
    }

    /**
     * Строки в показываемом порядке: во время паузы — как стояли (новые — сверху, ушедшие — убраны), иначе — как есть.
     * Звать один раз на список; разные списки одного экрана — каждый со своим ключом.
     */
    fun <T> order(items: List<T>, key: (T) -> Any): List<T> {
        val stood = frozen
        val result = if (stood == null) {
            items
        } else {
            val at = HashMap<Any, Int>(stood.size).apply { stood.forEachIndexed { i, k -> put(k, i) } }
            val (known, fresh) = items.partition { key(it) in at }
            fresh + known.sortedBy { at[key(it)] }
        }
        shown = shown.filterNot { k -> items.any { key(it) == k } } + result.map(key)
        return result
    }
}

/** Пауза порядка по «Виду» списка; без паузы — порядок как есть. */
@Composable
fun rememberCalmOrder(look: ListLook = LocalListLook.current): CalmOrder {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return androidx.compose.runtime.remember(look.pauseSeconds, scope) { CalmOrder(look.pauseSeconds * 1000L, scope) }
}

/** Вид строк списка там, где он задан: «Чаты» и «Каталог». В остальных списках — прежний. */
val LocalListLook = androidx.compose.runtime.staticCompositionLocalOf { ListLook.DEFAULT }

@Composable
fun ListLine(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    /**
     * Слева: обычно [Аватар]. Именно квадрат — «кто-то или что-то».
     */
    left: (@Composable () -> Unit)? = null,
    /**
     * Справа: время, [Счётчик], замок. В макете это `.мета` — столбик, прижатый к
     * правому краю, и он не сжимается: время не должно уезжать от чужого длинного имени.
     */
    right: (@Composable () -> Unit)? = null,
    /** Середина: имя и превью. Занимает остаток и обрезается первой. */
    middle: @Composable () -> Unit,
) {
    val colors = Tima.colors
    val edges = LocalListLook.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            // Линия рисуется по нижней границе самой строки, а не отдельным элементом
            // между строками: разделитель-элемент в списке приходится вставлять
            // вызывающему, и он однажды забудет — либо поставит лишний в конце.
            .drawBehind {
                val thickness = 1.dp.toPx()
                drawLine(
                    color = colors.line,
                    start = Offset(0f, size.height - thickness / 2),
                    end = Offset(size.width, size.height - thickness / 2),
                    strokeWidth = thickness,
                )
            }
            // Поле сверху и снизу — половина прежнего (заказчик 2026-09-19: «уменьши
            // отступы между контактами в два раза, и далее везде по этому принципу»).
            // Края — по «Виду» списка (заказчик 2026-11-20): прежний отступ и есть наибольший.
            .padding(
                start = TimaSpacing.about4 * (edges.startPercent / 100f),
                end = TimaSpacing.about4 * (edges.endPercent / 100f),
                top = TimaSpacing.about3 / 2,
                bottom = TimaSpacing.about3 / 2,
            ),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        left?.invoke()
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) { middle() }
        right?.let {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) { it() }
        }
    }
}

/**
 * Заголовок раздела списка: `.раздел`.
 *
 * Прописными, разряжённо и тихим цветом: он делит список, а не соревнуется с ним за
 * внимание.
 *
 * @param color другой цвет — только там, где заголовок сам несёт сведения: «Разрешения»
 *   красят красным пункт, где разрешение не дано (заказчик 2026-09-27).
 */
@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = Tima.colors.text3,
) = Caption(
    text = text.uppercase(),
    modifier = modifier.padding(
        start = TimaSpacing.about4,
        end = TimaSpacing.about4,
        top = TimaSpacing.about4,
        bottom = TimaSpacing.about2,
    ),
    fontSize = TimaType.sz6,
    weight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
    color = color,
)
