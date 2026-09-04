package io.tima.feature.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Avatar
import io.tima.core.ui.Trouble
import io.tima.core.ui.Secondary
import io.tima.core.ui.Name
import io.tima.core.ui.Button
import io.tima.core.ui.Chip
import io.tima.core.ui.ChipKind
import io.tima.core.ui.Field
import io.tima.core.ui.EmptyArea
import io.tima.core.ui.ListLine
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.Tima
import io.tima.core.ui.Tertiary
import io.tima.core.ui.SubwindowHeader
import io.tima.domain.chat.GroupMember
import io.tima.domain.chat.GroupRole

/**
 * Состав группы — подокно.
 *
 * **Предупреждение о несменившемся ключе стоит НАД списком и своим цветом.** Оно не про
 * конкретную строку, а про всю группу: исключённый человек уже не в списке, но читает
 * переписку дальше. Спрятать это в строку невозможно — строки уже нет.
 *
 * **Управление составом не показывается тому, кому нельзя.** Кнопка, которая отвечает
 * отказом, — худший вид объяснения: человек узнаёт о запрете, уже нажав.
 *
 * Чистый рендер [СоставState]. Решения — в [СоставStore].
 */
@Composable
fun MemberScreen(
    state: MembersState,
    onNumber: (String) -> Unit,
    onInvite: () -> Unit,
    onRemove: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Открыть подокно «Доступ». Кнопка стоит в шапке состава: доступ открывают, глядя на
     * людей, а не на сообщения, — там же, где решают, кто вообще в группе.
     */
    onAccess: (() -> Unit)? = null,
) {
    val colors = Tima.colors
    Column(modifier.fillMaxSize().background(colors.surface)) {
        SubwindowHeader(
            title = "Участники",
            onBack = onBack,
            right = onAccess?.let { open -> { Chip("Доступ", kind = ChipKind.Selected, onClick = open) } },
        )

        Column(
            modifier = Modifier.fillMaxSize().padding(TimaSpacing.about4),
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
        ) {
            state.trouble?.let { Trouble(it) }
            state.warning?.let { Trouble(it) }

            if (state.memberEdit) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        Field(
                            value = state.number,
                            onChange = onNumber,
                            hint = "+7…",
                            numeric = true,
                        )
                    }
                    Button(label = if (state.expect) "…" else "Позвать", onClick = onInvite)
                }
            }

            if (state.members.isEmpty()) {
                EmptyArea(
                    glyph = "👥",
                    title = if (state.expect) "Читаем состав" else "Здесь пока никого",
                    explanation = if (state.expect) null else "Позовите людей по номеру телефона",
                )
            } else {
                for (member in state.members) {
                    MemberLine(
                        member = member,
                        removeMay = state.memberEdit && !member.role.deliveryEdits,
                        onRemove = { onRemove(member.userId) },
                    )
                }
            }
        }
    }
}

/**
 * Строка участника.
 *
 * Владельца и админа исключить нельзя, и кнопки у них нет: правило сервера, повторённое
 * здесь, чтобы отказ не пришлось объяснять после нажатия.
 */
@Composable
private fun MemberLine(
    member: GroupMember,
    removeMay: Boolean,
    onRemove: () -> Unit,
) {
    ListLine(
        left = { Avatar(letters = member.userId.take(2).uppercase()) },
        right = {
            if (removeMay) {
                Button(label = "Исключить", onClick = onRemove)
            } else {
                Tertiary(roleCaption(member.role))
            }
        },
        middle = {
            Column {
                Name(member.userId)
                member.bannedUntil?.let { Secondary("заблокирован до $it") }
            }
        },
    )
}

private fun roleCaption(role: GroupRole): String = when (role) {
    GroupRole.Owner -> "владелец"
    GroupRole.Admin -> "админ"
    GroupRole.Moderator -> "модератор"
    GroupRole.Member -> "участник"
    // Роль, которой этот клиент не знает: сервер новее нас. Показать «участник» значило бы
    // соврать про права, которых мы не понимаем.
    GroupRole.Unknown -> "роль неизвестна"
}
