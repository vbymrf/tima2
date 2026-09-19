package io.tima.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import io.tima.core.ui.Avatar
import io.tima.core.ui.AvatarSize
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Caption
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.SectionTitle
import io.tima.core.ui.SubwindowHeader
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words
import io.tima.domain.chat.ChatPerson
import io.tima.domain.chat.PersonField
import io.tima.domain.chat.PersonLook
import io.tima.domain.chat.field
import io.tima.domain.chat.letter
import io.tima.domain.chat.line

/**
 * Личная страница человека — **заглушка** (решение заказчика 2026-09-19).
 *
 * Макет целиком — `doc/Layout-UI-light/телефон/окна/страница-гостя.html`: четыре вкладки,
 * лента, коллекции, группы, каталог. Здесь собрана только шапка профиля, и это
 * осознанно: «сейчас сделаем часть её, потом переработаем». Что именно не собрано и
 * почему — [ПЛАН-ЛИЧНОЙ-СТРАНИЦЫ.md](../../../../../../../../doc_mig/ПЛАН-ЛИЧНОЙ-СТРАНИЦЫ.md).
 *
 * ── ЧТО ПОКАЗАНО ────────────────────────────────────────────────────────────
 *
 * **Крупный аватар и ВСЁ, что мы про человека знаем**: имя, имя пользователя, ник, номер —
 * каждое своей строкой с подписью. Здесь, в отличие от списков, «Вид» не применяется
 * намеренно: список выбирает, чем человека назвать одной строкой, а страница отвечает на
 * другой вопрос — «что вообще известно». Прятать тут поле по галке значило бы отвечать на
 * этот вопрос неполно.
 *
 * ── ДВЕ КНОПКИ И ДВЕ РАЗНЫЕ СУЩНОСТИ ────────────────────────────────────────
 *
 * Решение заказчика того же дня: **контакты и подписки — не одно и то же**.
 *
 * - Контакт — это друг. На друга подписка встаёт **сама**.
 * - Отписаться можно, **не уходя из контактов**.
 * - Подписка в контакты **не добавляет** — поэтому «Добавить в контакты» стоит здесь
 *   отдельной кнопкой, а не подразумевается подпиской.
 *
 * «Подписаться» сегодня **не работает и об этом сказано словами**: читательской подписки
 * на ленту человека на сервере нет вовсе — есть обратная, где доступ раздаёт владелец
 * (ПЛАН-КОНТАКТОВ Д1б). Кнопка без объяснения обещала бы работающее действие; кнопки не
 * было бы вовсе — заказчик просил её видеть.
 */
@Composable
fun GuestPageScreen(
    /** Кого показываем. Пустой человек — законно: карточка могла не доехать. */
    person: ChatPerson,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Картинка аватара; `null` — буквы. */
    face: ImageBitmap? = null,
    /** Он уже в книге: вместо кнопки — строка о том, что добавлять нечего. */
    inContacts: Boolean = false,
    /** Завести контакт. `null` — добавлять нечем: номера мы не знаем. */
    onAddToContacts: (() -> Unit)? = null,
    /** Как называть человека в шапке — тот же «Вид», что в списках окна. */
    look: PersonLook = PersonLook.DEFAULT,
    /**
     * Добавил ли **он** меня к себе: от этого зависит, что из его ленты мне видно.
     * `null` — ещё не спросили или сервер не сказал; «не знаем» и «не дружит» — разное.
     */
    friend: Boolean? = null,
) {
    val colors = Tima.colors
    val words = Tima.words.page
    val bookWords = Tima.words.book
    Column(modifier.fillMaxSize()) {
        SubwindowHeader(
            // В шапке — коротко и по «Виду», как в списках: это то же место, что строка
            // списка, и называться человек там обязан так же.
            title = person.line(look, PERSON_FIRST_LINE) ?: words.guestPage,
            onBack = onBack,
            avatar = person.letter(),
            avatarImage = face,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // Крупный аватар по центру — как в макете: страница про одного человека, и
            // он на ней главный.
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = TimaSpacing.about5),
                contentAlignment = Alignment.Center,
            ) {
                Avatar(letters = person.letter(), image = face, size = AvatarSize.Big)
            }

            val known = PersonField.entries.mapNotNull { field -> person.field(field)?.let { field to it } }
            if (known.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(TimaSpacing.about4)) {
                    Tertiary(words.nothingKnown)
                }
            } else {
                SectionTitle(words.whatWeKnow)
                for ((field, value) in known) {
                    ListLine(
                        middle = {
                            Column {
                                // Значение крупнее подписи: спрашивают значение, подпись
                                // объясняет, что это такое.
                                Name(value)
                                Tertiary(bookWords.field(field), lineOne = true)
                            }
                        },
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth().padding(TimaSpacing.about4),
                verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            ) {
                // Тихая, а не действующая: она ничего не делает, и вид обязан это
                // повторять. Объяснение строкой ниже — молчаливо неработающая кнопка
                // неотличима от сломанной.
                Button(
                    label = words.subscribe,
                    kind = ButtonKind.Quiet,
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                )
                // Что подписка сделает, когда заработает, — решение заказчика 2026-09-19:
                // заберёт историю человека и попросит его добавить вас в контакты.
                Caption(words.subscribeAsks, fontSize = TimaType.sz6, color = colors.text2)
                Caption(words.subscribeNotYet, fontSize = TimaType.sz6, color = colors.text3)

                // В друзьях мы или нет — заказчик просил показывать это уже в заглушке.
                // Вопрос про ЕГО список, а не про мой: дружба односторонняя (Д1б).
                Caption(
                    when (friend) {
                        true -> words.theyAddedYou
                        false -> words.theyDidNotAddYou
                        null -> words.friendshipUnknown
                    },
                    fontSize = TimaType.sz6,
                    weight = FontWeight.SemiBold,
                    color = if (friend == true) colors.text2 else colors.text3,
                )

                if (inContacts) {
                    Caption(
                        words.alreadyInContacts + " · " + words.contactMeansFriend,
                        fontSize = TimaType.sz6,
                        weight = FontWeight.SemiBold,
                        color = colors.text2,
                    )
                } else if (onAddToContacts != null) {
                    Button(
                        label = Tima.words.chat.addToContacts,
                        onClick = onAddToContacts,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Caption(words.contactMeansFriend, fontSize = TimaType.sz6, color = colors.text3)
                }
            }
        }
    }
}
