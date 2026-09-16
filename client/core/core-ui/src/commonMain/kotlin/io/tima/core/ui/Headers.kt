package io.tima.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Шапка окна.
 *
 * **Главное правило: плашка есть только у основных окон.** Салатовая плашка отвечает
 * на вопрос «в каком я окне»; в подокне — чате, звонке, настройках — этот вопрос не
 * стоит, оттуда выходят кнопкой «назад». Поэтому у подокна шапка остаётся полосой на
 * функциональной подложке, и зелёной плашки там нет.
 *
 * **Вся плашка — одна область нажатия**, от края до края. Нажатие открывает
 * переключение окон: то, что говорит «где я», ведёт туда, где это меняют. Кнопки
 * справа перехватывают своё нажатие сами и наружу его не выпускают.
 *
 * Решения заказчика 2026-09-02, расходящиеся с нарисованным макетом, — записаны в
 * `doc/интерфейс.md §1`:
 *
 * - **разделителя под шапкой нет.** В макете у `.зона-1` стоит `border-bottom`, и
 *   линия отрезала шапку от ряда вкладок, хотя подложка у них одна и та же
 *   функциональная. Шапка, вкладки и ряд фильтров теперь читаются как один серый
 *   блок управления, и линия у него одна — снизу, силами каркаса окна;
 * - **логотип есть на всех форматах.** Прежде он зависел от ширины — на ПК макет
 *   отдаёт «Т» шапке рейки, — и на настольной сборке буквы не было вовсе. Условие
 *   снято: логотип перестал зависеть от раскладки.
 *
 * **Имя стоит слева, сразу за логотипом** — как в макете. Промежуточная редакция того
 * же дня ставила его по центру плашки; заказчик уточнил, что центрирование имелось в
 * виду **по вертикали**, и горизонталь вернули. Вместе с ним ушла и раскладка
 * «слева — по центру — справа»: центру нужен был симметричный проём по широкой
 * стороне, а он съедал место у имени тем сильнее, чем больше кнопок справа.
 */
@Composable
fun WindowHeader(
    title: String,
    modifier: Modifier = Modifier,
    /** Буква логотипа. В подокне логотипа нет. */
    logo: String? = null,
    /** Нажатие на плашку: переключение окон. */
    onSwitchWindows: (() -> Unit)? = null,
    /** Кнопки справа: поиск, настройки. Внутри плашки они белые. */
    right: (@Composable () -> Unit)? = null,
) {
    val colors = Tima.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.functional)
            .padding(horizontal = 12.dp, vertical = TimaSpacing.about2),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.navigation, RoundedCornerShape(TimaShapes.smallSquare))
                .heightIn(min = TimaZones.zone1 - TimaSpacing.about4)
                .then(
                    if (onSwitchWindows != null) {
                        Modifier.clickable(onClick = onSwitchWindows)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
            // Вот это и есть «центрировать текст»: по вертикали, посередине плашки.
            verticalAlignment = Alignment.CenterVertically,
        ) {
            logo?.let {
                // Логотип — белый квадрат внутри салатовой плашки. Квадрат, потому
                // что это «что-то», а не «нажми»; белый, потому что он на зелёном.
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(colors.inPlate, RoundedCornerShape(TimaShapes.smallSquare)),
                    contentAlignment = Alignment.Center,
                ) {
                    Caption(it, fontSize = TimaType.sz4, weight = FontWeight.ExtraBold, color = colors.navigation)
                }
            }
            Caption(
                text = title,
                modifier = Modifier.weight(1f),
                // Цвет названия — от заливки, а не свой токен. С 2026-09-02 это
                // белый в обеих темах, то есть ровно `.имя-окна` макета.
                fontSize = TimaType.sz3,
                weight = FontWeight.ExtraBold,
                color = colors.onAccent,
                lineOne = true,
            )
            // Внутри плашки круглые кнопки белые: салатовое на салатовом не видно.
            // Признак ставит плашка, а не тот, кто кладёт в неё кнопки, — см. LocalInPlate.
            right?.let { CompositionLocalProvider(LocalInPlate provides true) { it() } }
        }
    }
}

/**
 * Заголовок шапки: переносится, если слов больше одного; иначе ужимается.
 *
 * ── ПОЧЕМУ ТАК, А НЕ ОДНОЙ СТРОКОЙ С МНОГОТОЧИЕМ ────────────────────────────
 *
 * Многоточие в заголовке — это потеря единственного ответа на вопрос «где я».
 * «Секретная фраза и устройст…» ещё читается, «Frase de recuperación y disp…» уже нет,
 * а именно так выглядел испанский заголовок ПРИ ОБЫЧНОМ размере.
 *
 * Перенести можно то, в чём есть пробел. Одно слово перенести некуда — его ужимает
 * сам `BasicText` (`TextAutoSize.StepBased`), подбирая кегль под ширину. Нижняя граница
 * не ниже `sz5`: мельче — уже не заголовок.
 */
@Composable
private fun HeaderTitle(title: String) {
    val colors = Tima.colors
    val scale = LocalTextScale.current
    val крупный = TimaType.sz3 * scale
    if (title.trim().contains(' ')) {
        Caption(title, fontSize = TimaType.sz3, weight = FontWeight.ExtraBold, maxLines = 2)
    } else {
        BasicText(
            text = title,
            style = TextStyle(
                color = colors.text,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = LocalFontFamily.current,
            ),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(
                minFontSize = TimaType.sz5,
                maxFontSize = крупный,
                stepSize = STEP,
            ),
        )
    }
}

/** Шаг подбора кегля: мельче точки разница не видна, а проб становится втрое больше. */
private val STEP = 1.sp

/**
 * Шапка подокна: полоса без плашки.
 *
 * «Назад» здесь **салатовая** — это навигация, и она главная кнопка шапки подокна.
 * Название набрано обычным текстом: плашки нет, значит и текста на заливке нет.
 */
@Composable
fun SubwindowHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Подпись под названием: «в сети», «3 участника». */
    caption: String? = null,
    /**
     * Буквы аватара собеседника — между «назад» и именем, как в макете подокна чата.
     *
     * `null` — аватара нет: у настроек, у секретной фразы, у любого подокна, за которым
     * не стоит человек. Аватар там был бы чужой картинкой на месте, где никого нет.
     */
    avatar: String? = null,
    right: (@Composable () -> Unit)? = null,
) {
    val colors = Tima.colors
    ProvidePlace(TextPlace.HEADERS) {
    // FlowRow, а не Row: если содержимое шапки в строку не влезает, правый блок уходит на
    // ВТОРУЮ строку, а имя занимает первую целиком. Так в макете — у зоны 1 стоит
    // `flex-wrap: wrap`.
    //
    // Поймано 2026-09-16 на телефоне: как только между «назад» и именем встал аватар,
    // групповой переписке с чипами «Доступность» и «Участники» осталось на имя так мало,
    // что «наша» переносилось ПО БУКВАМ — «наш» и «а». Row умеет только ужимать, и ужимал
    // он то, ради чего шапка и нужна.
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.functional)
            .bottomLine(colors.line)
            // heightIn(MIN), а не фиксированная высота: шапка РАСТЁТ под перенесённый
            // заголовок — решение заказчика 2026-09-16. До Ш2 заголовок стоял в одну
            // строку и молча обрезался: «Секретная фраза и устройст…» уже при ×1.3, а
            // по-испански — при обычном размере.
            .heightIn(min = TimaZones.zone1)
            .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        // «Назад» рисуется, а не набирается глифом: «‹» есть не во всяком шрифте, а
        // пропавший знак навигации — это кнопка без надписи. См. Знаки.kt.
        ButtonCircle(onClick = onBack, live = true) {
            Arrow(Side.Left, color = colors.onAccent)
        }
        // Аватар стоит СРАЗУ за «назад» и до имени — так в макете
        // `doc/Layout-UI-light/телефон/подокна/чат.html`, зона 1. Мелкий: шапка не должна
        // расти от него, она и так растёт от перенесённого заголовка.
        avatar?.let { Avatar(letters = it, size = AvatarSize.Small) }
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.weight(1f).widthIn(min = TITLE_MIN),
        ) {
            // Многословный заголовок переносится, однословный ужимается по ширине:
            // на плашке одно слово перенести некуда, а обрезать его нельзя — от него
            // и зависит, понял ли человек, где он (решение заказчика 2026-09-16).
            HeaderTitle(title)
            // Подпись шапки — одна строка: шапка не растёт от длинного имени.
            caption?.let { Tertiary(it, lineOne = true) }
        }
        right?.invoke()
    }
    }
}

/**
 * Нижний предел ширины имени в шапке.
 *
 * Без него правый блок шапки отжимает заголовок до нечитаемого: 2026-09-16 на телефоне
 * групповая переписка с чипами «Доступность» и «Участники» ломала слово «наша» по буквам —
 * «наш» и «а». Предел заставляет правый блок уйти на вторую строку, а не давить имя.
 */
private val TITLE_MIN = 96.dp
