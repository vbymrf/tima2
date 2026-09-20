package io.tima.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import io.tima.core.ui.Caption
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaShapes
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words

/**
 * Идущий звонок — то, что должно быть видно из любого окна (макет `21-call.md`,
 * ПЛАН-ДОРАБОТКИ-ЗВОНКОВ ЗВ12).
 *
 * @param seconds сколько идёт разговор. На плашке стоит **время**, а не одно слово
 *   «звонок»: человек тогда видит не только что звонок есть, но и сколько он уже длится,
 *   не заходя внутрь.
 * @param onOpen перейти в окно 0.
 */
data class ActiveCall(val seconds: Int, val onOpen: () -> Unit)

/**
 * Идёт ли звонок — **композиционным местным, а не параметром**.
 *
 * Окон пять, и все пять приходят в [WindowFrame] через обёртки `Windows.kt`, каждая со
 * своим набором параметров. Протащить звонок через них значило бы добавить один и тот же
 * аргумент в пяти местах, а потом не забывать про шестое.
 *
 * Звонок при этом — **факт приложения, а не свойство окна**: он один на процесс, как язык
 * и как раскладка, и обе эти вещи живут здесь тем же способом (`LayoutLocal`,
 * `Tima.words`).
 *
 * `null` — звонка нет **или** мы уже в окне 0: плашка «перейти в звонок» внутри самого
 * звонка была бы предложением пойти туда, где стоишь.
 */
val LocalActiveCall = compositionLocalOf<ActiveCall?> { null }

/**
 * Плашка идущего звонка: под верхней панелью, справа.
 *
 * **Занимает свою строку, а не висит поверх содержимого.** Плавающая кнопка в правом
 * верхнем углу накрыла бы первую строку списка — а это ровно то место, куда человек
 * смотрит, открыв окно. Макет и пишет её отдельным рядом (`::: row {right}`).
 */
@Composable
internal fun ActiveCallBadge(call: ActiveCall) {
    val colors = Tima.colors
    val words = Tima.words.call
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
        horizontalArrangement = Arrangement.End,
    ) {
        Row(
            modifier = Modifier
                .testTag(ACTIVE_CALL_TAG)
                .background(colors.navigation, RoundedCornerShape(TimaShapes.smallSquare))
                .clickable(onClick = call.onOpen)
                .padding(horizontal = TimaSpacing.about3, vertical = TimaSpacing.about2),
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Надпись белая: плашка салатовая, и цвет берётся тот же, что у названия
            // окна внутри салатовой шапки, — правило `onAccent` уже заведено там.
            Caption(
                text = "📞 " + words.activeCall + " — " + words.duration(call.seconds),
                fontSize = TimaType.sz5,
                weight = FontWeight.SemiBold,
                color = colors.onAccent,
            )
        }
    }
}

/**
 * Метка плашки для живых сценариев.
 *
 * Назначена, а не выведена из ключа: перечня для неё нет, как и у телефонной кнопки в
 * шапке переписки (`chat:call`). Надпись отбору не годится — на ней время, и оно меняется
 * каждую секунду.
 */
const val ACTIVE_CALL_TAG: String = "call:active"
