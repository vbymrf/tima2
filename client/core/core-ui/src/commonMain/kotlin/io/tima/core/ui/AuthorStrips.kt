package io.tima.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Цвета полос авторов в группе — решение заказчика 2026-09-19 по пробе
 * `Layout-UI-light/пробы-социум/цвета-авторов.html` (и `…-тьма.html`).
 *
 * **Сто оттенков одной светлоты и насыщенности**, по кругу шагом в золотое сечение (≈222°):
 * соседние номера — далёкие цвета, первые десять авторов различимы без подбора. Оттенок №0 —
 * оттенок салатового; один номер — один оттенок в обоих наборах.
 *
 * Два набора под тему, потому что фон пузыря разный: на белом читается **тёмный** набор
 * (OKLCH L 0,635 — «№101–200» пробы), на тёмном `#3d3d3d` — **средний** (L 0,55 — «№401–500»):
 * набор одной светлоты с тёмным салатовым темы `#024408` на тёмном пузыре не виден вовсе
 * (1,06 : 1), это измерено, а не предположено.
 *
 * **Владелец группы — салатовый темы** (`navigation`), тот, что был у всех до этого решения и
 * настраивается в «Цветах». Остальные — по порядку первого сообщения в переписке, с №1: №0
 * близок к салатовому и пропускается. **Своё сообщение** без полосы, как и было.
 *
 * Таблицы сгенерированы из OKLCH и не правятся руками: правка одного цвета сломает «одна
 * светлота на все».
 */
object AuthorStrips {
    /** Набор светлой темы: «№101–200», L 0,635. */
    val light: List<Color> = listOf(
        0xFF669E1A, 0xFFD35993, 0xFF039BB6, 0xFFB67F00, 0xFF9D6FDD,
        0xFF01A473, 0xFFDD5B54, 0xFF1A8FE8, 0xFF8F9205, 0xFFC35FB4,
        0xFF009FA0, 0xFFCC6F04, 0xFF7B7BEB, 0xFF41A341, 0xFFD9587C,
        0xFF0098C6, 0xFFA88707, 0xFFAD68D0, 0xFF04A288, 0xFFDB6037,
        0xFF4E87ED, 0xFF799900, 0xFFCE5BA0, 0xFF069CAD, 0xFFBE7A03,
        0xFF9173E4, 0xFF02A561, 0xFFDC5964, 0xFF0594DA, 0xFF9A8D06,
        0xFFBC62C0, 0xFF00A097, 0xFFD66707, 0xFF6C7FED, 0xFF59A02B,
        0xFFD6598A, 0xFF009ABC, 0xFFB18201, 0xFFA36CD9, 0xFF00A37C,
        0xFFDC5C49, 0xFF348CEB, 0xFF889401, 0xFFC85DAD, 0xFF0C9EA5,
        0xFFC67402, 0xFF8478E9, 0xFF2CA54C, 0xFFDB5873, 0xFF0697CC,
        0xFFA38901, 0xFFB366CA, 0xFF03A18E, 0xFFD9622A, 0xFF5A84ED,
        0xFF6D9C07, 0xFFD15A98, 0xFF059CB2, 0xFFB87D06, 0xFF9870E0,
        0xFF01A46D, 0xFFDD5A5A, 0xFF0591E5, 0xFF939002, 0xFFC160B9,
        0xFF039F9D, 0xFFD06C00, 0xFF767CEC, 0xFF4BA239, 0xFFD85881,
        0xFF0399C2, 0xFFAB8507, 0xFFAA6AD4, 0xFF0BA284, 0xFFDC5E3E,
        0xFF4589EC, 0xFF7F9704, 0xFFCC5CA5, 0xFF019DAA, 0xFFC17805,
        0xFF8C75E6, 0xFF00A657, 0xFFDC5869, 0xFF0095D5, 0xFF9D8C00,
        0xFFB964C4, 0xFF0AA094, 0xFFD76519, 0xFF6581ED, 0xFF619E21,
        0xFFD4598F, 0xFF0B9BB8, 0xFFB38005, 0xFF9F6EDC, 0xFF05A377,
        0xFFDD5B50, 0xFF268EE9, 0xFF8C9304, 0xFFC55FB1, 0xFF0C9EA2,
    ).map(::Color)

    /** Набор тёмной темы: «№401–500», L 0,55. */
    val dark: List<Color> = listOf(
        0xFF537F1A, 0xFFAA4977, 0xFF047E94, 0xFF936705, 0xFF7E5AB2,
        0xFF05855D, 0xFFB24B45, 0xFF1B74BB, 0xFF747600, 0xFF9E4E91,
        0xFF048182, 0xFFA75900, 0xFF6464BD, 0xFF378436, 0xFFAF4864,
        0xFF037BA1, 0xFF886D05, 0xFF8C55A8, 0xFF00836E, 0xFFB04E2F,
        0xFF406EBF, 0xFF617C00, 0xFFA64B81, 0xFF007F8D, 0xFF9B6200,
        0xFF755EB7, 0xFF03864E, 0xFFB24951, 0xFF0278B2, 0xFF7D7202,
        0xFF98509B, 0xFF03827B, 0xFFAD5410, 0xFF5868BF, 0xFF498126,
        0xFFAC4970, 0xFF087D98, 0xFF906900, 0xFF8458AF, 0xFF018464,
        0xFFB24C3D, 0xFF2C72BD, 0xFF6E7800, 0xFFA14D8B, 0xFF038086,
        0xFFA25D01, 0xFF6B61BB, 0xFF28853F, 0xFFB0495D, 0xFF037AA7,
        0xFF846F00, 0xFF9153A3, 0xFF028373, 0xFFAF5025, 0xFF4A6BBF,
        0xFF597E11, 0xFFA94A7B, 0xFF077E91, 0xFF966502, 0xFF7B5CB4,
        0xFF018558, 0xFFB24A49, 0xFF0875BA, 0xFF777505, 0xFF9B4F95,
        0xFF07817F, 0xFFAA5701, 0xFF5F65BE, 0xFF3E8330, 0xFFAE4969,
        0xFF077C9D, 0xFF8B6B05, 0xFF8956AA, 0xFF03846A, 0xFFB14D34,
        0xFF396FBE, 0xFF677B00, 0xFFA44B85, 0xFF067F8A, 0xFF9D6000,
        0xFF715FB9, 0xFF108648, 0xFFB14956, 0xFF0279AD, 0xFF807105,
        0xFF95529E, 0xFF008278, 0xFFAE5319, 0xFF5269BF, 0xFF50801F,
        0xFFAB4974, 0xFF007E96, 0xFF926803, 0xFF815AB1, 0xFF018560,
        0xFFB24B42, 0xFF2373BC, 0xFF727704, 0xFF9F4D8F, 0xFF048083,
    ).map(::Color)

    /**
     * Цвет полосы для автора с порядковым номером [order] среди участников, кроме владельца
     * и меня (0 — первый написавший). №0 набора пропускается; после 99 — по кругу.
     */
    fun of(order: Int, dark: Boolean): Color {
        val table = if (dark) this.dark else light
        return table[1 + (order.coerceAtLeast(0) % (table.size - 1))]
    }
}

/**
 * Как красить полосы авторов — из «Цветов»: тёмный или светлый набор и красить ли вообще.
 * `colored = false` — полоса у всех цвета контура (решение заказчика 2026-09-19).
 */
data class StripLook(val dark: Boolean = false, val colored: Boolean = true)

val LocalStripLook = staticCompositionLocalOf { StripLook() }

/**
 * Цвет полосы автора для пузыря: владелец — салатовый темы, остальные — из набора темы по
 * порядку появления; выключены — контур у всех. [order] `null` — автор неизвестен (или это
 * личная переписка): салатовый темы, как было.
 */
@Composable
fun authorStrip(order: Int?, owner: Boolean): Color {
    val look = LocalStripLook.current
    val colors = Tima.colors
    return when {
        !look.colored -> colors.border
        owner || order == null -> colors.navigation
        else -> AuthorStrips.of(order, look.dark)
    }
}
