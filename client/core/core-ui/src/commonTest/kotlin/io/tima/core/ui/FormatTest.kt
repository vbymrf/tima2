package io.tima.core.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Форматы (У.4): одна разметка, три формата, пороги посчитаны, а не выбраны.
 *
 * Проверяется не «на планшете три полосы» — это видно и глазами, — а то, что числа
 * порогов **следуют из ширин полос**. Расхождение таблицы с вёрсткой и есть та поломка,
 * которую иначе находят на чужом устройстве.
 */
class FormatTest {

    @Test
    fun три_формата_на_ширинах_макета() {
        // Ровно те размеры, что стоят в индексах макета.
        assertEquals(Format.Phone, layoutFor(380.dp).format)
        assertEquals(Format.Tablet, layoutFor(1024.dp).format)
        assertEquals(Format.DESKTOP, layoutFor(1440.dp).format)
    }

    @Test
    fun порог_полос_ровно_на_840() {
        // Число из Plan.md §К5. Внешнее, но не произвольное: см. следующий тест.
        assertEquals(Format.Phone, layoutFor(839.dp).format)
        assertEquals(Format.Tablet, layoutFor(840.dp).format)
    }

    /**
     * Порог 840 не притёрт: на нём три полосы действительно живут.
     *
     * Если рейка или колонка когда-нибудь станут шире, тест покраснеет — и это правильный
     * момент, чтобы пересмотреть порог, а не молча выдать главной области полоску.
     */
    @Test
    fun на_пороге_полос_главной_области_хватает_места() {
        val layout = layoutFor(FormatTima.ПОРОГ_ПОЛОС)
        val taken = layout.rail!! + layout.column!!
        val main = FormatTima.ПОРОГ_ПОЛОС - taken
        assertTrue(
            main >= FormatTima.МИНИМУМ_ГЛАВНОЙ,
            "на пороге 840 главной области остаётся $main при минимуме ${FormatTima.МИНИМУМ_ГЛАВНОЙ}",
        )
    }

    /**
     * Порог ПК — сумма полос, а не выбранное число.
     *
     * «Правая панель появляется, только когда на неё хватило места» — правило макета.
     * Поэтому порог считается: рейка с подписями, колонка, минимум главной, панель. Станет
     * панель шире — порог сдвинется сам, и не будет случая, когда числа в таблице
     * устройств разошлись с числами в вёрстке.
     */
    @Test
    fun порог_пк_есть_сумма_полос() {
        // 1300 с 2026-09-02: рейка выросла со 196 до 260, чтобы вместить самое длинное
        // имя окна («Свободное общение», 152 точки) вместо многоточия. Порог сдвинулся
        // сам — ровно то, ради чего он и считается суммой. Число держат здесь, чтобы
        // сдвиг был виден: раскладка ПК начинается на 64 точки позже, чем вчера.
        assertEquals(1300.dp, FormatTima.DESKTOP_THRESHOLD)
        assertEquals(Format.Tablet, layoutFor(FormatTima.DESKTOP_THRESHOLD - 1.dp).format)
        assertEquals(Format.DESKTOP, layoutFor(FormatTima.DESKTOP_THRESHOLD).format)
    }

    /** На телефоне полос нет вовсе: список и есть экран, подокно открывается перерисовкой. */
    @Test
    fun на_телефоне_полос_нет() {
        val layout = layoutFor(380.dp)
        assertNull(layout.rail, "на телефоне переключение окон — подокно, а не рейка")
        assertNull(layout.column, "колонка на телефоне занимает окно целиком")
        assertNull(layout.panel)
        assertTrue(layout.phone)
    }

    /** На планшете панели нет: страница объекта открывается перерисовкой, как на телефоне. */
    @Test
    fun панель_только_на_пк() {
        assertNull(layoutFor(1024.dp).panel)
        assertEquals(FormatTima.PANEL, layoutFor(1440.dp).panel)
    }

    /** Рейка значками на планшете, с подписями на ПК — та же рейка, другая ширина. */
    @Test
    fun подписи_в_рейке_появляются_только_на_пк() {
        assertEquals(false, layoutFor(1024.dp).railCaption)
        assertEquals(FormatTima.РЕЙКА_ЗНАЧКИ, layoutFor(1024.dp).rail)
        assertEquals(true, layoutFor(1440.dp).railCaption)
        assertEquals(FormatTima.CAPTION_RAIL, layoutFor(1440.dp).rail)
    }

    /**
     * Полосы никогда не съедают главную область.
     *
     * Проверяется на всех ширинах от порога до трёх тысяч точек: сумма полос обязана
     * оставить главной области её минимум. Это то самое, что ломается незаметно — сначала
     * на одной ширине, а находят на другой.
     */
    @Test
    fun главной_области_всегда_остаётся_минимум() {
        var width = FormatTima.ПОРОГ_ПОЛОС
        while (width <= 3000.dp) {
            val layout = layoutFor(width)
            val taken = (layout.rail ?: 0.dp) + (layout.column ?: 0.dp) + (layout.panel ?: 0.dp)
            val main = width - taken
            assertTrue(
                main >= FormatTima.МИНИМУМ_ГЛАВНОЙ,
                "на ширине $width главной области досталось $main",
            )
            width += 1.dp
        }
    }

    /**
     * Колонок в сетке медиа ровно столько, сколько в макете: 2, 3, 4.
     *
     * Карточка ленты в одну колонку на широком экране оставляла бы половину поля пустой.
     */
    @Test
    fun колонок_медиа_по_формату() {
        assertEquals(2, layoutFor(380.dp).mediaColumns)
        assertEquals(3, layoutFor(1024.dp).mediaColumns)
        assertEquals(4, layoutFor(1440.dp).mediaColumns)
    }
}
