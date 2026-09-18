package io.tima.feature.group

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import io.tima.core.ui.Field
import io.tima.core.ui.IconButton
import io.tima.core.ui.ProvidePlace
import io.tima.core.ui.TextPlace
import io.tima.core.ui.TimaZones
import io.tima.domain.chat.ChatPerson
import io.tima.domain.chat.PersonLook
import io.tima.domain.chat.letter
import io.tima.domain.chat.line
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
import io.tima.core.ui.PhoneFields
import io.tima.core.ui.EmptyArea
import io.tima.core.ui.ListLine
import io.tima.core.ui.TimaSpacing
import io.tima.core.words.SocialWords
import io.tima.core.ui.Tima
import io.tima.core.ui.words
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
    onCountryCode: (String) -> Unit = {},
    onInvite: () -> Unit,
    onRemove: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Открыть подокно «Доступ». Кнопка стоит в шапке состава: доступ открывают, глядя на
     * людей, а не на сообщения, — там же, где решают, кто вообще в группе.
     */
    onAccess: (() -> Unit)? = null,
    /** Как называть людей — «Вид» набора сообществ, тот же, что у реплик в группе. */
    look: PersonLook = PersonLook.DEFAULT,
    /** Позвать по нику (заказчик 2026-09-18). `null` — поля нет. */
    onNick: ((String) -> Unit)? = null,
    onInviteNick: () -> Unit = {},
    /** Позвать из книги: кандидаты — кто в TIMa и ещё не в группе. `null` — кнопки нет. */
    contacts: List<InviteCandidate>? = null,
    onContacts: (Boolean) -> Unit = {},
    onInviteUser: (String) -> Unit = {},
) {
    val colors = Tima.colors
    val words = Tima.words.social
    Box(modifier.fillMaxSize().background(colors.surface)) {
    Column(Modifier.fillMaxSize()) {
        SubwindowHeader(
            title = words.members,
            onBack = onBack,
            right = onAccess?.let { open ->
                { Chip(words.access, kind = ChipKind.Selected, onClick = open) }
            },
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
                        PhoneFields(
                            countryCode = state.countryCode,
                            number = state.number,
                            onCountryCode = onCountryCode,
                            onNumber = onNumber,
                        )
                    }
                    Button(label = if (state.expect) "…" else words.invite, onClick = onInvite)
                }
                // По нику — вторым полем: ник публичен, и звать по нему можно того, чьего
                // номера нет (заказчик 2026-09-18).
                if (onNick != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) {
                            Field(value = state.nick, onChange = onNick, hint = "@" + words.nickField.lowercase())
                        }
                        Button(label = if (state.expect) "…" else words.invite, onClick = onInviteNick)
                    }
                }
                if (contacts != null) {
                    Button(label = words.fromContacts, onClick = { onContacts(true) })
                }
            }

            if (state.members.isEmpty()) {
                EmptyArea(
                    glyph = "👥",
                    title = if (state.expect) words.readingMembers else words.nobodyHereYet,
                    explanation = if (state.expect) null else words.inviteByPhone,
                )
            } else {
                for (member in state.members) {
                    MemberLine(
                        member = member,
                        person = state.people[member.userId] ?: ChatPerson.EMPTY,
                        look = look,
                        removeMay = state.memberEdit && !member.role.deliveryEdits,
                        onRemove = { onRemove(member.userId) },
                    )
                }
            }
        }
    }
    if (state.contactsOpen && contacts != null) {
        ContactsSheet(
            contacts = contacts,
            members = state.members.map { it.userId }.toSet(),
            look = look,
            busy = state.expect,
            trouble = state.trouble,
            onInvite = onInviteUser,
            onClose = { onContacts(false) },
        )
    }
    }
}

/** Кому можно послать приглашение из книги: человек в TIMa, идентификатор известен. */
data class InviteCandidate(val userId: String, val person: ChatPerson)

/**
 * Подокно «Из контактов»: список тех, кого можно позвать, с «＋» у каждой строки. Не
 * закрывается после нажатия — за один раз зовут нескольких (заказчик 2026-09-18);
 * позванный тут же отмечается «уже в группе».
 */
@Composable
private fun ContactsSheet(
    contacts: List<InviteCandidate>,
    members: Set<String>,
    look: PersonLook,
    busy: Boolean,
    trouble: String?,
    onInvite: (String) -> Unit,
    onClose: () -> Unit,
) {
    val colors = Tima.colors
    val words = Tima.words.social
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.text.copy(alpha = 0.45f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = TimaZones.zone1)
                .background(colors.surface)
                .clickable(enabled = false) {},
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.functional)
                    .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about3),
                horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) { ProvidePlace(TextPlace.HEADERS) { Name(words.fromContacts) } }
                IconButton(glyph = "✕", onClick = onClose)
            }
            trouble?.let { Trouble(it, Modifier.padding(TimaSpacing.about4)) }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(contacts, key = { it.userId }) { candidate ->
                    val inside = candidate.userId in members
                    ListLine(
                        left = { Avatar(letters = candidate.person.letter()) },
                        middle = {
                            Column {
                                Name(candidate.person.line(look) ?: Tima.words.book.nameless)
                                candidate.person.phone?.let { Tertiary(it, lineOne = true) }
                            }
                        },
                        right = {
                            if (inside) {
                                Tertiary(words.alreadyMember, lineOne = true)
                            } else {
                                IconButton(glyph = if (busy) "…" else "＋", onClick = { onInvite(candidate.userId) }, live = !busy)
                            }
                        },
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
    person: ChatPerson,
    look: PersonLook,
    removeMay: Boolean,
    onRemove: () -> Unit,
) {
    val words = Tima.words.social
    ListLine(
        left = { Avatar(letters = person.letter()) },
        right = {
            if (removeMay) {
                Button(label = words.exclude, onClick = onRemove)
            } else {
                Tertiary(roleCaption(member.role, words))
            }
        },
        middle = {
            Column {
                // Как в переписке группы: по «Виду», а не идентификатором. Идентификатор —
                // последнее прибежище, когда о человеке не известно ничего.
                Name(person.line(look) ?: Tima.words.chat.someone)
                member.bannedUntil?.let { Secondary(words.bannedUntil(it)) }
            }
        },
    )
}

private fun roleCaption(role: GroupRole, words: SocialWords): String = when (role) {
    GroupRole.Owner -> words.owner
    GroupRole.Admin -> words.admin
    GroupRole.Moderator -> words.moderator
    GroupRole.Member -> words.member
    // Роль, которой этот клиент не знает: сервер новее нас. Показать «участник» значило бы
    // соврать про права, которых мы не понимаем.
    GroupRole.Unknown -> words.roleUnknown
}
