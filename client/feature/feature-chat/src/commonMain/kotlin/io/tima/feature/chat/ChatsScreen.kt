package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.tima.core.ui.MarkKind
import io.tima.core.ui.LayoutLocal
import io.tima.core.ui.ButtonCircle
import io.tima.core.ui.Avatar
import io.tima.core.ui.Secondary
import io.tima.core.ui.Name
import io.tima.core.ui.Mark
import io.tima.core.ui.EmptyArea
import io.tima.core.ui.ControlRow
import io.tima.core.ui.WithCluster
import io.tima.core.ui.Side
import io.tima.core.ui.Arrow
import io.tima.core.ui.ListLine
import io.tima.core.ui.Counter
import io.tima.core.ui.Caption
import io.tima.core.ui.TimaType
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.Tertiary
import io.tima.core.words.ChatWords
import io.tima.core.ui.Tima
import io.tima.core.ui.TextPlace
import io.tima.core.ui.ProvidePlace
import io.tima.core.ui.words
import io.tima.core.ui.WindowHeader
import androidx.compose.ui.graphics.ImageBitmap
import io.tima.domain.chat.ChatKind
import io.tima.domain.chat.ChatPerson
import io.tima.domain.chat.PersonLook
import io.tima.domain.chat.letter
import io.tima.domain.chat.line
import io.tima.domain.chat.ChatSummary
import io.tima.domain.chat.MessageDisplay

/**
 * Окно переписок — «окно 1» из канона, К5.2.
 *
 * Тот же принцип, что у [ЭкранЧата]: **чистый рендер состояния**. Порядок строк, превью и
 * счётчики считает запрос к базе, здесь только вид.
 *
 * Три решения макета, которые здесь важнее вида:
 *
 * 1. **Строка списка — линия, а не карточка.** Между строками линия по нижней границе
 *    самой строки: разделитель отдельным элементом пришлось бы вставлять вызывающему, и
 *    он однажды поставил бы лишний в конце.
 * 2. **Гроздь создания живёт в [СГроздью]** и сама знает, где ей стоять: на телефоне
 *    висит над списком, на широком формате опускается в полосу у нижнего края колонки.
 *    Экран про формат не спрашивает.
 * 3. **Переписка без имени всё равно показывается.** Имя приезжает с профилем, и его
 *    может не быть; спрятать строку значило бы спрятать сообщение.
 */
@Composable
fun ChatsScreen(
    state: ChatsState,
    onOpen: (ChatSummary) -> Unit,
    modifier: Modifier = Modifier,
    onSettings: () -> Unit = {},
    onNew: () -> Unit = {},
    /**
     * Собеседник ЛИЧНОЙ переписки; `null` — переписка не личная либо собеседник неизвестен.
     *
     * Ради него всё и заведено (заказчик 2026-09-19: «отображение единое для окна
     * „Телефон“»). До этого строка переписки показывала `title` — имя, записанное в саму
     * переписку при её заведении, — а строка книги показывала человека по «Виду». Один и
     * тот же человек назывался на двух вкладках по-разному, и настройки «Вида» на «Чатах»
     * не действовали вовсе.
     */
    personOf: (ChatSummary) -> ChatPerson? = { null },
    /** Картинка аватара собеседника; `null` — её нет или она ещё не доехала. */
    faceOf: (ChatSummary) -> ImageBitmap? = { null },
    /**
     * Нажали на аватар личной переписки — страница собеседника; строка — сама переписка.
     * Так же, как у «Контактов» (заказчик 2026-10-06: «единый стиль для клика»). `null` —
     * аватар не нажимается.
     */
    onFace: ((ChatSummary) -> Unit)? = null,
    /** Как называть человека — тот же «Вид», что у книги. */
    look: PersonLook = PersonLook.DEFAULT,
    /**
     * Число строки — сколько уведомлений у сущности, из журнала уведомлений
     * (ПЛАН-(ЖУ)-ЖУРНАЛА-УВЕДОМЛЕНИЙ.md, ЖУ2): сообщения и, у личной, пропущенные звонки её
     * собеседника — redmi с тем и другим показывает 2. `null` — прежнее число
     * непрочитанных сообщений.
     */
    countOf: ((ChatSummary) -> Int)? = null,
    /**
     * Третья строка — пометка переписки: у временной группы звонка «удалится через N ч»
     * (ПЛАН-(ГЗ)-ГРУППОВЫХ-ЗВОНКОВ, решение 11). `null` — строки нет.
     */
    tagOf: (ChatSummary) -> String? = { null },
    /** Группа звонка: создатель и его аватар (заказчик 2026-10-02); `null` — обычная. */
    callGroupOf: (ChatSummary) -> CallGroupLook? = { null },
    /** «Доставлено» и «прочитано» своего последнего (ПЛАН-(ОП)); `null` — не знаем. */
    receiptOf: (ChatSummary) -> ChatReceipt? = { null },
    /** Собеседник сейчас печатает — «печатает…» вместо превью (ПЛАН-(ОП)). */
    typingOf: (ChatSummary) -> Boolean = { false },
    /** Уведомления переписки отключены — 🔕 у времени (ПЛАН-(ОУ)). */
    mutedOf: (ChatSummary) -> Boolean = { false },
) {
    val colors = Tima.colors
    val words = Tima.words.chat
    // Список чатов — группа «списки» (ПЛАН-(Ш)-ШРИФТОВ Ш3). Строки здесь ОБРЕЗАЮТСЯ, и
    // это решение: иначе от чужого длинного имени растёт строка и список перестаёт
    // быть списком. Поэтому и ручка своя, отдельная от сообщений в переписке.
    ProvidePlace(TextPlace.LISTS) {
    Column(modifier.fillMaxSize().background(colors.surface)) {
        // ── ШАПКИ ЗДЕСЬ БОЛЬШЕ НЕТ ───────────────────────────────────────────
        //
        // Экран стал содержимым вкладки «Чаты», а шапку окна рисует общий каркас:
        // одна на все пять окон. Пока экран был единственным, шапка жила в нём, и
        // это было верно ровно до второго окна.
        //
        // ── ВХОДА «ГРУППА» ЗДЕСЬ БОЛЬШЕ НЕТ ──────────────────────────────────
        //
        // Внизу списка стоял второй вход — салатовый чип «Группа», открывавший мастер
        // создания группы. Убран 2026-09-03 решением заказчика: **в макете его нет**.
        // `Layout-UI-light/пк/телефон.html`, низ колонки — там ровно один круг с
        // подписью «Написать», и на телефоне тоже один.
        //
        // Появился он тогда, когда графического интерфейса ещё не было и класть вход
        // было некуда. Это его и объясняет: не замысел, а след времени, когда макета
        // не существовало.
        //
        // **Мастер создания группы при этом никуда не делся** — он собран, работает и
        // остался без входа. Так и записано в `doc_mig/ИНТЕРФЕЙС/02-телефон/`, в
        // разделе «нет входа»: построенное, но недостижимое, обязано числиться
        // отдельно от готового. Место входа — каталог окна 2, там же, где остальные
        // группы и каналы.
        WithCluster(
            cluster = {
                ButtonCircle(onClick = onNew, live = true) {
                    Arrow(Side.Right, color = colors.onAccent)
                }
            },
            caption = words.write,
            modifier = Modifier.weight(1f),
        ) {
            when {
                // Пустой список до первого ответа базы — это не «переписок нет».
                // Показывать «переписок нет» в первую секунду после запуска значит врать.
                !state.read -> Box(Modifier.fillMaxSize())

                state.chats.isEmpty() -> EmptyArea(
                    title = words.noChatsYet,
                    explanation = words.writeFirst,
                )

                else -> List(state.chats, onOpen, personOf, faceOf, look, countOf, tagOf, callGroupOf, onFace, receiptOf, typingOf, mutedOf)
            }
        }
    }
    }
}

/**
 * Группы человека — вкладка «Группы» окна 5 «Страница».
 *
 * **Почему не в окне 1.** Окно 1 — это телефон: личные переписки, книга, звонки. Группы,
 * каналы, сообщества и голосовые комнаты по решению заказчика живут в каталоге окна 2, а
 * свои — на этой вкладке (`ИНТЕРФЕЙС/ПРАВИЛО.md`). До 2026-09-17 группы стояли в списке
 * чатов вперемешку с личными: это было временное размещение, и так оно и записано в
 * `ИНТЕРФЕЙС/04-социум/ФУНКЦИОНАЛ.md` — «личные группы работают, но живут во временном
 * входе окна 1».
 *
 * **Строки те же, что у чатов, намеренно.** Группа в списке отвечает на те же вопросы:
 * кто, о чём последнее, когда. Своя строка отличалась бы только тем, что её писали бы
 * второй раз.
 */
@Composable
fun GroupsScreen(
    state: ChatsState,
    onOpen: (ChatSummary) -> Unit,
    modifier: Modifier = Modifier,
    /** Выбранный раздел набора сообществ — ключ полосы; пусто — «Всё». */
    chosen: String = "",
    /** Число строки группы — из журнала уведомлений, см. [ChatsScreen]. */
    countOf: ((ChatSummary) -> Int)? = null,
    /** «Удалится через N ч» у временной группы звонка (решение 11). */
    tagOf: (ChatSummary) -> String? = { null },
    /** Группа звонка: создатель и его аватар (заказчик 2026-10-02); `null` — обычная. */
    callGroupOf: (ChatSummary) -> CallGroupLook? = { null },
    /** Уведомления группы отключены — 🔕 у времени (ПЛАН-(ОУ)). */
    mutedOf: (ChatSummary) -> Boolean = { false },
) {
    val wanted = if (chosen == COMMON_SECTION) "" else chosen
    val groups = if (chosen.isEmpty()) state.groups else state.groups.filter { it.sectionId == wanted }
    Box(modifier = modifier.fillMaxSize()) {
        when {
            !state.read -> Unit
            groups.isEmpty() -> EmptyArea(
                title = Tima.words.chat.noGroups,
                explanation = Tima.words.chat.noGroupsAbout,
            )

            else -> List(chats = groups, onOpen = onOpen, countOf = countOf, tagOf = tagOf, callGroupOf = callGroupOf, mutedOf = mutedOf)
        }
    }
}

@Composable
private fun List(
    chats: List<ChatSummary>,
    onOpen: (ChatSummary) -> Unit,
    personOf: (ChatSummary) -> ChatPerson? = { null },
    faceOf: (ChatSummary) -> ImageBitmap? = { null },
    look: PersonLook = PersonLook.DEFAULT,
    countOf: ((ChatSummary) -> Int)? = null,
    tagOf: (ChatSummary) -> String? = { null },
    /** Группа звонка: создатель и его аватар (заказчик 2026-10-02); `null` — обычная. */
    callGroupOf: (ChatSummary) -> CallGroupLook? = { null },
    onFace: ((ChatSummary) -> Unit)? = null,
    receiptOf: (ChatSummary) -> ChatReceipt? = { null },
    typingOf: (ChatSummary) -> Boolean = { false },
    mutedOf: (ChatSummary) -> Boolean = { false },
) = LazyColumn(
    modifier = Modifier.fillMaxSize(),
) {
    items(chats, key = { it.chatId }) { chat ->
        ChatLine(
            chat, personOf(chat), faceOf(chat), look, countOf?.invoke(chat) ?: chat.unread,
            onClick = { onOpen(chat) }, tag = tagOf(chat), call = callGroupOf(chat),
            receipt = receiptOf(chat), typing = typingOf(chat), muted = mutedOf(chat),
            // Страница есть только у собеседника личной переписки.
            onFace = onFace?.takeIf { chat.kind == ChatKind.Personal && chat.peerId != null }?.let { { it(chat) } },
        )
    }
}

@Composable
private fun ChatLine(
    chat: ChatSummary,
    who: ChatPerson?,
    face: ImageBitmap?,
    look: PersonLook,
    /** Число строки: уведомления сущности (ЖУ2) либо непрочитанные. */
    count: Int,
    onClick: () -> Unit,
    tag: String? = null,
    call: CallGroupLook? = null,
    onFace: (() -> Unit)? = null,
    receipt: ChatReceipt? = null,
    typing: Boolean = false,
    muted: Boolean = false,
) = ListLine(
    onClick = onClick,
    // Картинка, если она есть, иначе буква — та же, что в книге. У группы человека нет,
    // и буквы берутся из её названия. Группа звонка — аватар создателя и «ГЗ» поверх.
    left = {
        if (call != null) {
            val badge = Tima.words.groupCall.badge
            Avatar(letters = badge, image = call.face, overlay = badge)
        } else {
            Avatar(
                letters = who?.letter() ?: letters(chat),
                image = face,
                modifier = if (onFace != null) Modifier.clickable(onClick = onFace) else Modifier,
            )
        }
    },
    right = {
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about1),
        ) {
            // Времени может не быть вовсе: у пустой переписки нет последнего сообщения.
            // Ставить сюда 1970 год или «—» незачем — пустое место говорит то же самое и
            // не спорит с именем за внимание.
            // Верхняя строка — время и за ним отметка своего последнего: чёрная галочка —
            // сервер принял; нижняя — день: «Сегодня», «Вчера», число (заказчик 2026-10-08).
            // Время не переносится: строка списка держит высоту.
            Row(
                horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (muted) Tertiary("🔕", lineOne = true)
                chat.atMs?.let { Tertiary(time(it), lineOne = true) }
                if (chat.lastOutgoing) markOf(chat.lastDisplay, chat.atMs, receipt)?.let { Mark(it) }
            }
            // Без fillMaxWidth: в ряду строки этот столбец не взвешен, и растянутый на
            // всю ширину он съедал середину — имя и превью получали нулевую ширину и
            // просто не рисовались. Поймал снимок.
            Row(
                horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (count > 0) Counter(count)
                chat.atMs?.let { Tertiary(day(it, Tima.words.callLog.today, Tima.words.callLog.yesterday), lineOne = true) }
            }
        }
    },
    middle = {
        // Имя — по «Виду», тем же правилом и тем же набором полей, что первая строка
        // контакта ([PERSON_FIRST_LINE]): телефон туда не идёт, потому что вторую строку
        // здесь занимает превью. Человека нет (группа) или он пуст — остаётся название
        // переписки. Имени может не быть и вовсе: профиль не приезжал. Строку это не
        // отменяет — сообщение есть, и человек должен его видеть.
        Name(who?.line(look, PERSON_FIRST_LINE) ?: chat.title ?: Tima.words.chat.nameless)
        // Группа звонка — имя создателя отдельной строкой (заказчик 2026-10-02).
        call?.creator?.let { Secondary(it, lineOne = true) }
        // Превью обрезается: иначе строка списка растёт от чужого длинного сообщения.
        if (typing) {
            Caption(Tima.words.chat.typing, fontSize = TimaType.sz5, color = Tima.colors.navigation, lineOne = true)
        } else {
            Secondary(preview(chat, Tima.words.chat, Tima.words.groupCall.title), lineOne = true)
        }
        tag?.let { Tertiary(it, lineOne = true) }
    },
)

/**
 * Превью строки.
 *
 * У неразобранного или нечитаемого входящего текста нет, и вместо него — слова, а не
 * пустота: пустая строка выглядит как поломка списка, а не как состояние сообщения.
 */
private fun preview(chat: ChatSummary, words: ChatWords, invite: String): String = chat.preview
    // Приглашение в групповой звонок — словами, а не ссылкой: в самом сообщении только она.
    ?.let { text -> if (CallInviteLink.groupOf(text) != null) "🔊 $invite" else text }
    ?: when (chat.lastDisplay) {
        MessageDisplay.UNREADABLE -> words.messageUnreadable
        else -> words.newMessage
    }

/**
 * Буквы аватара.
 *
 * Из имени, если оно есть. Без имени — вопросительный знак: он честнее первых букв
 * идентификатора, которые выглядят как имя и им не являются.
 */
private fun letters(chat: ChatSummary): String =
    chat.title?.trim()?.takeIf { it.isNotEmpty() }
        ?.split(" ")
        ?.take(2)
        ?.mapNotNull { it.firstOrNull()?.uppercase() }
        ?.joinToString("")
        ?: "?"

/**
 * Подходит ли переписка поиску (заказчик 2026-09-19: «для Телефон реализуй функционал
 * поиска»).
 *
 * Ищется по тому, что человек ВИДИТ в строке: имя в шапке и первая строка последнего
 * сообщения. Ни по чему другому искать и нечем — остальной текст переписки лежит на
 * устройстве зашифрованным, и поиск по нему означал бы расшифровку всей истории на
 * каждую набранную букву.
 *
 * Пустой запрос подходит всему: «ничего не набрано» — это не «ничего не найдено».
 */
fun ChatSummary.matches(request: String): Boolean {
    val clean = request.trim()
    if (clean.isEmpty()) return true
    return title?.contains(clean, ignoreCase = true) == true ||
        preview?.contains(clean, ignoreCase = true) == true
}

/**
 * Как показать группу звонка в списке и шапке: имя создателя (не ник — заказчик 2026-10-02)
 * и его аватар, на который ложится «ГЗ».
 */
data class CallGroupLook(val creator: String?, val face: ImageBitmap?)
