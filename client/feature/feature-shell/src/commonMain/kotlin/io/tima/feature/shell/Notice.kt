package io.tima.feature.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Caption
import io.tima.core.ui.Secondary
import io.tima.core.ui.SubwindowHeader
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType

/**
 * Событие, о котором приложение говорит человеку при запуске.
 *
 * **Показывается, только если верны все три условия** (решение заказчика 2026-09-06):
 *
 * 1. **уже случилось** — это не предупреждение и не подсказка;
 * 2. **случилось, пока человека не было** — между запусками; иначе он увидел бы это на
 *    своём экране, и окно поверх лишнее;
 * 3. **требует его решения или хотя бы знания** — иначе это шум.
 *
 * Правило записано здесь, потому что такое окно притягивает всё подряд: ход работы,
 * рекламу новых возможностей, успехи, о которых никто не просил. Через месяц его начинают
 * закрывать не читая, и тогда оно не срабатывает в тот единственный раз, когда важно.
 *
 * @param details строки помельче под текстом: подробности, которые читают вторыми.
 */
data class Notice(
    val title: String,
    val text: String,
    val details: List<String> = emptyList(),
)

/**
 * Что можно сделать с событием.
 *
 * **Действие — это переход, а не работа.** Событие рассказывает и уводит туда, где дело
 * делается; делать его прямо здесь значило бы завести второе место с той же работой, а
 * скопированная вёрстка расходится — 2026-09-06 это стоило трёх разных поломок в трёх
 * копиях одного экрана обновления.
 */
data class NoticeAction(
    val label: String,
    val kind: ButtonKind = ButtonKind.Action,
    val onPick: () -> Unit,
)

/**
 * Подокно события — **обычное подокно приложения**, а не всплывающее окно поверх.
 *
 * Шапка та же, что у настроек и переписки ([SubwindowHeader]), и «назад» в ней закрывает —
 * то есть выход есть всегда и находится там же, где человек привык его искать. До
 * 2026-09-06 окно новостей об обновлении было своей вёрсткой без шапки, и выйти из него
 * при отказе от установки было нельзя.
 *
 * Событий за раз показывается **одно** (решение заказчика): следующее — после того, как
 * закрыли предыдущее. Список внутри одного окна потребовал бы порядка важности и
 * прокрутки, а событие у нас пока одно.
 */
@Composable
fun NoticeScreen(
    notice: Notice,
    actions: List<NoticeAction>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) = Column(modifier.fillMaxSize()) {
    SubwindowHeader(title = notice.title, onBack = onClose)
    Column(
        Modifier.fillMaxSize().padding(TimaSpacing.about4).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
    ) {
        Caption(notice.text, fontSize = TimaType.sz4)
        notice.details.forEach { Secondary(it) }
        actions.forEach { action ->
            Button(
                label = action.label,
                onClick = action.onPick,
                kind = action.kind,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
