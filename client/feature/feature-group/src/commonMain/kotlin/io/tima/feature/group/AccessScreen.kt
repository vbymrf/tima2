package io.tima.feature.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.tima.core.ui.Avatar
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Chip
import io.tima.core.ui.ChipKind
import io.tima.core.ui.EmptyArea
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.Secondary
import io.tima.core.ui.SubwindowHeader
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.Trouble
import io.tima.domain.chat.AccessGrant
import io.tima.domain.chat.AccessState

/**
 * Подокно «Доступ» — третий круг (макет `подокна/доступ.html`).
 *
 * **У участника и у админа здесь разные экраны, и это не два вида одного списка.** Участник
 * видит своё состояние и одну кнопку — «попросить». Админ видит, кто просит, у кого доступ
 * есть и до какого срока. Показать участнику чужие просьбы значило бы раздать сведения,
 * которых у него быть не должно.
 *
 * **Отказ назван словом.** «Отказано» и «ещё не решено» — разные состояния, и человек,
 * которому отказали, должен это видеть, а не ждать вечно.
 *
 * **Срок — год и месяц.** Он заканчивается вместе с эпохой; в макете он же нарисован
 * календарём, но выбор дня ничего не изменил бы — сервер считает месяцами.
 */
@Composable
fun AccessScreen(
    state: AccessState2,
    onAsk: () -> Unit,
    onDecide: (String, Boolean, String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onCloseTrouble: () -> Unit = {},
) {
    val colors = Tima.colors
    Column(modifier.fillMaxSize().background(colors.surface)) {
        SubwindowHeader(title = "Доступ к закрытым записям", onBack = onBack)

        Column(
            modifier = Modifier.fillMaxSize().padding(TimaSpacing.about4),
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
        ) {
            state.trouble?.let {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Trouble(it)
                    Chip("Скрыть", kind = ChipKind.Quiet, onClick = onCloseTrouble)
                }
            }

            if (!state.admin) {
                MyAccess(state, onAsk)
                return@Column
            }

            if (state.grants.isEmpty()) {
                EmptyArea(
                    if (state.loaded) {
                        "Доступ никому не открыт и никто его не просит"
                    } else {
                        "Загружаем…"
                    },
                )
                return@Column
            }

            for (grant in state.grants) {
                GrantLine(
                    grant = grant,
                    busy = grant.userId in state.deciding,
                    terms = state.terms,
                    onDecide = onDecide,
                )
            }
        }
    }
}

/** Что видит участник: своё состояние и одну кнопку. */
@Composable
private fun MyAccess(state: AccessState2, onAsk: () -> Unit) = Column(
    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
) {
    when {
        state.myLevel >= 3 -> {
            Name("Доступ открыт")
            Secondary("Вы видите закрытые записи этой группы. Срок покажет админ в описании.")
        }

        state.mine == AccessState.Asked || state.asked -> {
            Name("Просьба ушла")
            Secondary("Админ ответит — ответ придёт сюда же. Повторно просить не нужно.")
        }

        // Отказ — отдельное состояние, а не «пока нет доступа». Иначе человек будет
        // просить снова и снова, считая, что его просто не заметили.
        state.mine == AccessState.Declined -> {
            Name("Отказано")
            Secondary("Админ не открыл доступ. Попросить можно снова — решение не вечно.")
            Button("Попросить снова", onClick = onAsk, kind = ButtonKind.Quiet)
        }

        else -> {
            Name("Доступа нет")
            Secondary(
                "Часть записей вам не показана. Их существование не скрыто — скрыто содержимое.",
            )
            Button("Попросить", onClick = onAsk)
        }
    }
}

/** Строка состава глазами админа: состояние, срок и два решения. */
@Composable
private fun GrantLine(
    grant: AccessGrant,
    busy: Boolean,
    terms: List<AccessTerm>,
    onDecide: (String, Boolean, String) -> Unit,
) = Column(
    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about1),
) {
    ListLine(left = { Avatar(grant.userId.take(1).uppercase()) }) {
        Column {
            Name(grant.userId)
            // Состояние словом: «отказано» и «доступа нет» — разные вещи, и одинаковая
            // подпись у них заставила бы человека просить снова там, где уже отказали.
            Secondary(
                when (grant.state) {
                    AccessState.Asked -> "просит доступ"
                    AccessState.Granted -> if (grant.untilEpoch.isBlank()) {
                        "доступ открыт · бессрочно"
                    } else {
                        "доступ открыт · до ${grant.untilEpoch}"
                    }
                    AccessState.Declined -> "отказано"
                    AccessState.None -> "доступа нет"
                },
                lineOne = true,
            )
        }
    }
    if (!busy) {
        Row(horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
            // Три срока и отказ — ровно то, что в макете. Дня в сроке нет: сервер считает
            // месяцами, и день в интерфейсе обещал бы точность, которой нет.
            Chip("Бессрочно", kind = ChipKind.Quiet, onClick = { onDecide(grant.userId, true, "") })
            for (term in terms) {
                Chip(term.title, kind = ChipKind.Quiet, onClick = { onDecide(grant.userId, true, term.epoch) })
            }
            if (grant.state != AccessState.Declined) {
                Chip("Отказать", kind = ChipKind.Quiet, onClick = { onDecide(grant.userId, false, "") })
            }
        }
    } else {
        Tertiary("решаем…", lineOne = true)
    }
}
