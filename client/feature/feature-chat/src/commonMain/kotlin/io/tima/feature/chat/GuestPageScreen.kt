package io.tima.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import io.tima.core.ui.Avatar
import io.tima.core.ui.AvatarSize
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Caption
import io.tima.core.ui.Field
import io.tima.core.ui.IconButton
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
 * Подпись поля на странице человека. У двух имён — пояснение в скобках (заказчик
 * 2026-09-26): «Имя (мной задано)» и «Имя пользователя (как себя назвал)». Без него два
 * «имени» подряд не различить.
 */
@Composable
internal fun personFieldLabel(field: PersonField): String {
    val bookWords = Tima.words.book
    val pageWords = Tima.words.page
    return when (field) {
        PersonField.Name -> bookWords.field(field) + " (" + pageWords.nameSetByMe + ")"
        PersonField.UserName -> bookWords.field(field) + " (" + pageWords.nameSelfChosen + ")"
        else -> bookWords.field(field)
    }
}

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
    /**
     * Четыре действия под аватаром — ЗВ7, решение заказчика 2026-09-20: позвонить,
     * видеозвонок, групповой звонок, написать.
     *
     * Каждое `null` — кнопки нет вовсе, а не погашена: погашенная спрашивает «почему»,
     * отсутствующая не спрашивает ничего.
     *
     * [groupCall] — **заглушка, и обратного вызова у неё нет намеренно.** Групповых
     * звонков в проекте нет ни в одном плане, звать снаружи нечего, а лямбда, ничего не
     * делающая у вызывающего, через месяц читается как забытая. Поэтому объяснение
     * показывает сам экран, а снаружи приходит только «есть ли чем звонить вообще».
     *
     * Кнопка при этом **говорит**: немая читается как поломка, и в неё жмут повторно.
     */
    onCall: (() -> Unit)? = null,
    onVideoCall: (() -> Unit)? = null,
    groupCall: Boolean = false,
    onWrite: (() -> Unit)? = null,
    /**
     * Поменять **наше** имя этого человека — ✎ напротив «Имя» (заказчик 2026-09-26: «а где
     * изменить имя?»). Пустое снимает наше имя, и снова показывается имя из телефонной
     * книги. `null` — править нечего: человека нет в книге, и нашего имени у него нет.
     */
    onRename: ((String?) -> Unit)? = null,
    /** Наше имя как есть — с него начинается правка. `null` — не задавали. */
    ownName: String? = null,
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

            // Ряд действий — сразу под аватаром: страница отвечает на вопрос «кто это»,
            // а действия с человеком — первое, зачем её открывают.
            var groupCallAsked by remember { mutableStateOf(false) }
            if (onCall != null || onVideoCall != null || groupCall || onWrite != null) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
                    horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
                    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
                ) {
                    onCall?.let { Button(label = words.call, onClick = it) }
                    onVideoCall?.let { Button(label = words.videoCall, kind = ButtonKind.Quiet, onClick = it) }
                    if (groupCall) {
                        Button(
                            label = words.groupCall,
                            kind = ButtonKind.Quiet,
                            onClick = { groupCallAsked = !groupCallAsked },
                        )
                    }
                    onWrite?.let { Button(label = words.write, kind = ButtonKind.Quiet, onClick = it) }
                }
            }

            // Сказано только тому, кто спросил: строка появляется по нажатию и уходит
            // по второму. Висеть всегда ей незачем — это ответ, а не свойство человека.
            if (groupCallAsked) {
                Box(Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2)) {
                    Tertiary(words.groupCallLater)
                }
            }

            // «Имя» при возможности правки показывается и пустым: иначе ✎ негде поставить,
            // а задать имя тому, у кого его нет, — ровно тот случай, когда правка нужна.
            val known = PersonField.entries.mapNotNull { field ->
                (person.field(field) ?: "".takeIf { field == PersonField.Name && onRename != null })
                    ?.let { field to it }
            }
            var renaming by remember(person) { mutableStateOf(false) }
            var draft by remember(person) { mutableStateOf(ownName ?: person.name.orEmpty()) }
            if (known.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(TimaSpacing.about4)) {
                    Tertiary(words.nothingKnown)
                }
            } else {
                SectionTitle(words.whatWeKnow)
                for ((field, value) in known) {
                    if (field == PersonField.Name && renaming && onRename != null) {
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
                            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
                        ) {
                            Tertiary(personFieldLabel(field), lineOne = true)
                            Field(value = draft, onChange = { draft = it }, hint = bookWords.name)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
                                Button(label = bookWords.save, onClick = {
                                    onRename(draft.trim().ifEmpty { null })
                                    renaming = false
                                })
                                Button(
                                    label = Tima.words.common.cancel,
                                    kind = ButtonKind.Quiet,
                                    onClick = {
                                        draft = ownName ?: person.name.orEmpty()
                                        renaming = false
                                    },
                                )
                            }
                        }
                        continue
                    }
                    ListLine(
                        middle = {
                            Column {
                                // Значение крупнее подписи: спрашивают значение, подпись
                                // объясняет, что это такое.
                                Name(value.ifEmpty { "—" })
                                Tertiary(personFieldLabel(field), lineOne = true)
                            }
                        },
                        right = if (field == PersonField.Name && onRename != null) {
                            { IconButton(glyph = "✎", onClick = { renaming = true }) }
                        } else {
                            null
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
