package io.tima.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Каким вышел ответ на действие: от этого — первое слово плашки. */
enum class AnswerTone { Done, Trouble, Waiting, Info }

/**
 * Ответ на действие — один вид на всё приложение (макет `пробы-ответ.html`, вариант 01,
 * решение заказчика 2026-10-06).
 *
 * Раньше ответ выходил строкой серого текста под панелью — тем же кеглем и цветом, что
 * пояснения, — и его принимали за подпись, а ниже края экрана не видели вовсе (отчёт DGAR).
 *
 * Правила из проб: ответ стоит **сразу под тем, что нажали**; форма — квадрат со скруглением,
 * а не пилюля (пилюля значит «нажми»); итог — первым словом и жирно, подробность ниже; успех и
 * беда — словом, а не цветом. **Ответ сменился — второй цвет**: первый — цвет навигации,
 * новый — цвет активности, следующий — снова первый. Иначе плашка с другим текстом выглядит
 * так, будто ничего не произошло.
 */
@Composable
fun Answer(text: String?, tone: AnswerTone, modifier: Modifier = Modifier) {
    val colors = Tima.colors
    val flip = remember { Flip() }
    val second = flip.on(text to tone)
    val accent = if (second) colors.activity else colors.navigation
    val words = Tima.words.common
    val title = when (tone) {
        AnswerTone.Done -> words.answerDone
        AnswerTone.Trouble -> words.answerTrouble
        AnswerTone.Waiting -> words.answerWaiting
        AnswerTone.Info -> null
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(TimaShapes.smallRadius))
            .background(accent.copy(alpha = if (second) 0.20f else 0.16f)),
    ) {
        Spacer(Modifier.width(4.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(horizontal = TimaSpacing.about3, vertical = 10.dp)) {
            title?.let { Caption(it, fontSize = TimaType.sz5, weight = FontWeight.ExtraBold) }
            text?.takeIf { it.isNotBlank() }?.let { Caption(it, fontSize = TimaType.sz5, color = colors.text2) }
        }
    }
}

/**
 * Помнит, какой ответ был, и переключает цвет на каждом новом. Не состояние Compose: число
 * переключений не нужно ни экрану, ни сохранению — нужно только «тот же или другой».
 */
private class Flip {
    private var last: Any? = null
    private var second = false

    fun on(answer: Any?): Boolean {
        if (answer != last) {
            if (last != null) second = !second
            last = answer
        }
        return second
    }
}
