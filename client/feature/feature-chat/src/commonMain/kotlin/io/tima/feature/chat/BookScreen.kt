package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Avatar
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Caption
import io.tima.core.ui.ControlRow
import io.tima.core.ui.IconButton
import io.tima.core.ui.InCenter
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.ProvidePlace
import io.tima.core.ui.Secondary
import io.tima.core.ui.SectionGlyph
import io.tima.core.ui.SectionTitle
import io.tima.core.ui.Tertiary
import io.tima.core.ui.TextPlace
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words
import io.tima.domain.chat.PersonField
import io.tima.domain.chat.PersonLook
import io.tima.domain.chat.letter
import io.tima.domain.chat.line
import io.tima.domain.chat.BookEntry
import io.tima.domain.chat.ChatPerson

/**
 * Вкладка «Контакты» окна «Телефон» — ПЛАН-КОНТАКТОВ.md, Д5.
 *
 * ── ЧТО ЗДЕСЬ ЕСТЬ И ПОЧЕМУ ИМЕННО ТАК ──────────────────────────────────────
 *
 * **Раздел «Телефон» — последний и всегда последний.** В нём те, кого нет в TIMa, и у
 * них вместо звонка «Пригласить». Отметки «в TIMa» у строки нет: она повторяла бы одно
 * и то же на всём списке, а на вопрос «кому можно написать» отвечает место в списке.
 *
 * **Второй строкой номер, а не присутствие.** «В сети» и «была 5 минут назад» сервер не
 * отдаёт; нарисовать их значило бы показать выдуманное состояние живого человека.
 *
 * **Звонков из строки два — голосом и видео** (решение заказчика 2026-09-23, Ж3).
 * «Пригласить» остаётся у тех, кого в TIMa нет: оно ничего не обещает от нас, потому
 * что и делается средствами телефона.
 */
@Composable
fun BookScreen(
    state: BookState,
    onOpen: (BookEntry) -> Unit,
    modifier: Modifier = Modifier,
    /** Выбор раздела на полосе и в плитке; пусто — «Всё» / назад к плитке. */
    onChooseSection: (String) -> Unit = {},
    /** Открыть управление разделами — «Добавить» в плитке. `null` — плитка без него. */
    onSections: (() -> Unit)? = null,
    /** Сколько людей раздела написали новое — янтарная цифра. По ключу полосы. */
    newIn: (String) -> Int = { 0 },
    /** «＋» в правом нижнем углу: завести контакт или раздел. */
    onAdd: (() -> Unit)? = null,
    /**
     * «Обновить» — сверка с телефонной книгой по требованию (Л2).
     *
     * `null` — сверять неоткуда: на ПК телефонной книги нет, и кнопка там была бы
     * обещанием, которое некому исполнить.
     */
    onRefresh: (() -> Unit)? = null,
    /** «Пригласить» у того, кого нет в TIMa. */
    onInvite: ((BookEntry) -> Unit)? = null,
    /**
     * Позвонить прямо из строки книги (ЗВ13, решение заказчика 2026-09-20).
     *
     * **Только у тех, кто в TIMa.** В разделе «Телефон» стоят те, у кого аккаунта нет, и
     * там уже есть своя «Позвонить» — она открывает системный набиратель (`onInvite` →
     * подокно «Пригласить»). Два одинаковых значка с разным поведением в одном списке
     * перепутались бы в первый же день, поэтому наш звонок к ним не приходит.
     *
     * `null` — звонить нечем: нет движка (ПК) или его ещё не собрали.
     */
    onCall: ((BookEntry) -> Unit)? = null,
    /**
     * Видеозвонок прямо из строки книги — Ж3, **решение заказчика 2026-09-23**.
     *
     * Прежнее решение (2026-09-20) было обратным: «Кнопку видео звонок туда не кладём»,
     * и здесь же стоял довод — «в списках одна кнопка, иначе строка книги превращается
     * в панель». Оно отменено, а не забыто, и цена названа: две круглые кнопки по
     * 36 точек с зазором съедают около 82 точек из 360, то есть почти четверть строки,
     * и длинные имена обрезаются раньше.
     *
     * Плату приняли осознанно. Взамен отвергнуто долгое нажатие: оно дешевле по ширине,
     * но **невидимо** — о нём узнают, только если рассказали.
     *
     * Условие то же, что у голосового: только тот, у кого есть аккаунт. `null` —
     * звонить нечем.
     */
    onVideoCall: ((BookEntry) -> Unit)? = null,
    /**
     * Нажали на аватар — личная страница человека (заказчик 2026-09-19).
     *
     * У строки две цели, и так в макете: аватар ведёт к человеку, всё остальное — к
     * действию со строкой. `null` — страницы нет, аватар не нажимается.
     */
    onFace: ((BookEntry) -> Unit)? = null,
    onToggleSection: (String) -> Unit = {},
    /** Разрешение на чтение книги телефона просит платформа, а не этот экран. */
    onAllow: (() -> Unit)? = null,
    /**
     * Нажатие уведёт в настройки, а не поднимет диалог (Л1).
     *
     * Признаком, а не платформенным типом: экран про платформы не знает и знать не
     * должен — `feature-chat` зависит от `core-ui`, а не от `core-contacts`. Слова при
     * этом выбирает он сам, из своего словаря.
     *
     * «Спрашивать нечего» (ПК) приходит иначе — пустым `onAllow`.
     */
    allowInSettings: Boolean = false,
    /**
     * Человек за строкой книги: имя — из книги, имя пользователя и ник — со справочника.
     * По умолчанию — только то, что есть в книге: так собирают проверки без сети.
     */
    personOf: (BookEntry) -> ChatPerson = { ChatPerson(name = it.name, phone = it.phone) },
    /** Аватар человека за строкой, если он есть и уже доехал. */
    faceOf: (BookEntry) -> ImageBitmap? = { null },
) {
    val words = Tima.words.book
    val colors = Tima.colors
    // Книга — та же группа «списки», что и чаты: строка обрезается и обязана быть
    // одной высоты.
    ProvidePlace(TextPlace.LISTS) {
    // Box поверх колонки: «＋» без строки поиска становится плавающей кнопкой в правом
    // нижнем углу — там же, где «написать» в списке чатов.
    Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        when {
            state.needPermission -> InCenter(Modifier.fillMaxSize()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
                    modifier = Modifier.padding(TimaSpacing.about5),
                ) {
                    Name(words.notRead)
                    // Сказано, что будет и чего не будет: разрешение, о котором не
                    // объяснили, отклоняют — и правильно делают.
                    Secondary(words.notReadAbout)
                    // ── КНОПКА НАЗЫВАЕТ СЕБЯ, А НЕ МОЛЧИТ ГЛИФОМ ────────
                    //
                    // Здесь стоял «✓», и за ним пряталось **два разных действия**: в
                    // первый раз системный диалог, а после отказа, когда система
                    // спрашивать больше не станет, — уход в настройки приложения.
                    //
                    // То есть кнопка «перейти в настройки» была и раньше, просто она
                    // была той же самой и молчала о себе. Глиф «✓» означает «согласен»,
                    // а второй раз означает «выйдешь из приложения».
                    //
                    // Кнопки нет вовсе там, где спрашивать нечего (ПК): `onAllow`
                    // приходит пустым. Не «неактивная» — неактивная тоже зовёт нажать.
                    if (onAllow != null) {
                        Button(
                            label = if (allowInSettings) words.openSettings else words.allow,
                            onClick = onAllow,
                            kind = ButtonKind.Action,
                        )
                    }
                }
            }

            state.noBook && state.all.isEmpty() -> InCenter(Modifier.fillMaxSize()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
                    modifier = Modifier.padding(TimaSpacing.about5),
                ) {
                    Name(words.addByHand)
                    // Не «разрешите доступ»: на этой платформе разрешать нечего.
                    Secondary(words.noBookHere)
                }
            }

            state.notFoundNothing -> InCenter(Modifier.fillMaxSize()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Name(words.nobodyFound)
                    Secondary(words.nothingMatches(state.search))
                }
            }

            state.all.isEmpty() -> InCenter(Modifier.fillMaxSize()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
                    modifier = Modifier.padding(TimaSpacing.about5),
                ) {
                    Name(words.bookEmpty)
                    Secondary(words.bookEmptyAbout)
                }
            }

            // Исполнение А: плитка ярлычков, пока раздел не выбран. Выбор уводит внутрь —
            // ниже тот же список, только одного раздела, и строка «назад» над ним.
            state.tiles && state.chosen.isEmpty() && state.search.isBlank() -> Column(Modifier.fillMaxSize()) {
                SectionsTiles(
                    tabs = state.tiles(words),
                    countOf = state::countIn,
                    onOpen = onChooseSection,
                    onAdd = onSections,
                    newIn = newIn,
                    size = state.view.tileSize,
                )
            }

            else -> LazyColumn(Modifier.fillMaxSize()) {
                if (state.tiles && state.chosen.isNotEmpty()) {
                    // Внутри раздела плитки: «назад» к плитке и название — вместо ряда
                    // вкладок, как в `разделы.md` («вкладки заменяются на назад и название»).
                    item(key = "back") {
                        ListLine(
                            onClick = { onChooseSection("") },
                            left = { IconButton(glyph = "‹", onClick = { onChooseSection("") }, live = true) },
                            middle = { Name(state.tiles(words).firstOrNull { it.id == state.chosen }?.name ?: words.everyone) },
                        )
                    }
                }
                state.groups(words).forEach { group ->
                    // В исполнениях с полосой разделы стоят над списком, и заголовок внутри
                    // списка был бы вторым способом сказать то же самое. В плитке внутри
                    // раздела заголовок тоже лишний — раздел уже назван строкой «назад».
                    if (state.view.folders && !state.tiles) {
                        item(key = "section-${group.name}") {
                            SectionHeader(
                                tab = SectionTab(group.id.ifEmpty { COMMON_SECTION }, group.name, group.icon),
                                count = group.people.size,
                                fresh = newIn(group.id.ifEmpty { COMMON_SECTION }),
                                open = group.name !in state.collapsed,
                                onClick = { onToggleSection(group.name) },
                            )
                        }
                    }
                    if (state.view.folders && !state.tiles && group.name in state.collapsed) return@forEach
                    // Ключ — ключ книги, а не номер: у контакта, заведённого по нику,
                    // номера нет, и все такие строки получили бы один и тот же ключ.
                    // Compose на одинаковых ключах начинает путать строки местами (Л0).
                    items(group.people, key = { it.id }) { person ->
                        val who = personOf(person)
                        ListLine(
                            onClick = { onOpen(person) },
                            // Картинка, если человек поставил аватар; иначе буква.
                            left = {
                                Avatar(
                                    letters = who.letter(),
                                    image = faceOf(person),
                                    modifier = if (onFace != null) {
                                        Modifier.clickable { onFace(person) }
                                    } else {
                                        Modifier
                                    },
                                )
                            },
                            middle = {
                                Column {
                                    // Первая строка — имя, ник, имя пользователя по «Виду»
                                    // (галки через запятую, иначе первое, что есть); вторая
                                    // — всегда телефон (решение заказчика 2026-09-18).
                                    Name(who.line(state.view.look(), PERSON_FIRST_LINE) ?: words.nameless)
                                    // Вторая строка — номер, а у кого его нет — ник (Л0).
                                    // Довод про кегль переносится на ник целиком: его так же
                                    // читают и так же называют вслух. Пустая строка на этом
                                    // месте была бы хуже всего — она выглядит как потеря.
                                    Caption(
                                        person.phone.ifBlank { who.nick?.let { "@$it" }.orEmpty() },
                                        fontSize = TimaType.sz6 * 1.3f,
                                        weight = FontWeight.SemiBold,
                                        color = colors.text3,
                                        lineOne = true,
                                    )
                                }
                            },
                            right = when {
                                // Тот, кого нет в TIMa: «Пригласить». Наш звонок ему
                                // некуда вести — у него нет аккаунта.
                                group.outsiders && onInvite != null ->
                                    { { InviteButton(onClick = { onInvite(person) }) } }
                                // Тот, кто в TIMa: позвонить. Проверяем `userId`, а не
                                // раздел: разделы человек перекладывает руками, а
                                // «есть ли аккаунт» — ответ сервера.
                                // Тот, кто в TIMa: позвонить голосом или видео — две
                                // кнопки рядом (Ж3). Видео показывается только если
                                // звонить им есть чем; иначе остаётся одна, а не
                                // пустое место рядом с живой.
                                onCall != null && person.userId != null ->
                                    {
                                        {
                                            CallButtons(
                                                onVoice = { onCall(person) },
                                                onVideo = onVideoCall?.let { { it(person) } },
                                            )
                                        }
                                    }
                                else -> null
                            },
                        )
                    }
                }
            }
        }
    }
        // Завести контакт можно всегда — «＋» плавающей кнопкой в правом нижнем углу,
        // там же, где «написать» в списке чатов. До 2026-09-17 он жил ВНУТРИ строки
        // поиска, и снятая галочка «Показывать поиск» уносила вместе с ним единственный
        // способ добавить человека руками. Строки поиска в списке больше нет вовсе
        // (2026-09-19) — кнопка от неё и не зависит.
        // «Обновить» стоит ЛЕВЕЕ «＋» и рядом с ним, а не в шапке (Л2, решение
        // заказчика 2026-09-24). Довод тот же, по которому «＋» уехал из строки поиска:
        // действие над списком живёт у списка, а не в раме окна, которая у всех вкладок
        // общая.
        //
        // Сверка с телефонной книгой идёт и сама — при открытии вкладки. Кнопка нужна
        // для другого случая: человек только что завёл контакт в телефоне и хочет
        // увидеть его сейчас, а не при следующем заходе.
        if (onAdd != null || onRefresh != null) {
            ControlRow(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(TimaSpacing.about5),
            ) {
                if (onRefresh != null) {
                    IconButton(
                        glyph = "⟳",
                        onClick = onRefresh,
                        modifier = Modifier.testTag(BOOK_REFRESH_TAG),
                    )
                }
                if (onAdd != null) IconButton(glyph = "＋", onClick = onAdd, live = true)
            }
        }
    }
    }
}

/**
 * Полоса раздела: название, число людей и шеврон.
 *
 * Число стоит у названия, а не считается глазами: в свёрнутом разделе список не виден,
 * и без числа непонятно, стоит ли его разворачивать.
 */
@Composable
fun SectionHeader(tab: SectionTab, count: Int, fresh: Int, open: Boolean, onClick: () -> Unit) {
    val colors = Tima.colors
    // Строка раздела по макету `новости.html` («Каталог»), правило `.разд`: знак, зелёный
    // кружок «сколько внутри», имя; справа янтарный «сколько нового» и круглый шеврон.
    // Строка узкая — 7 точек поля сверху и снизу, — поэтому шеврон 28, а не 36, как у
    // кнопок подокна переходов: 36 раздул бы строку до строки списка, а она заголовок.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.functional)
            .clickable(onClick = onClick)
            .padding(start = TimaSpacing.about4, end = 14.dp, top = 7.dp, bottom = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Mark(tab, size = 16.dp, color = colors.text)
        Badge(count, amber = false)
        // Имя обычным регистром, кеглем строки (`.имя-раздела`: щ4, 800), а не капителью
        // заголовка списка: раздел — полка с именем от человека, а не служебная надпись.
        Box(Modifier.weight(1f)) { Name(tab.name) }
        if (fresh > 0) Badge(fresh, amber = true, small = true)
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(colors.quiet, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Caption(if (open) "▾" else "▸", fontSize = TimaType.sz5, weight = FontWeight.Black, color = colors.text2)
        }
    }
}

/**
 * «Пригласить» вместо звонка — в две строки: одной «Пригласить в TIMa» не влезает, на
 * строку контакта остаётся немного места.
 *
 * Звонить такому человеку можно телефоном, но это делает телефон, а не мы: «📞» здесь
 * обещал бы наш звонок.
 */
@Composable
private fun InviteButton(onClick: () -> Unit) {
    ControlRow { IconButton(glyph = "↗", onClick = onClick) }
}

/**
 * «Позвонить» и «Видеозвонок» в строке книги — ЗВ13 и Ж3.
 *
 * **Порядок не случаен: голос слева, видео справа.** Голосом звонят чаще, а правый край
 * строки ближе к большому пальцу. Поменяй их местами — и привычное действие окажется
 * дальше привычного.
 *
 * Метки здесь не для красоты: тот же глиф телефонной трубки носит окно «Телефон» в рейке
 * и кнопка в шапке переписки, а в списке таких кнопок столько же, сколько строк. Отбор
 * по видимому тексту в живом сценарии попадал бы в случайную.
 */
@Composable
private fun CallButtons(onVoice: () -> Unit, onVideo: (() -> Unit)?) {
    ControlRow {
        IconButton(
            glyph = "\uD83D\uDCDE",
            onClick = onVoice,
            modifier = Modifier.testTag(BOOK_CALL_TAG),
        )
        if (onVideo != null) {
            IconButton(
                glyph = "\uD83D\uDCF9",
                onClick = onVideo,
                modifier = Modifier.testTag(BOOK_VIDEO_CALL_TAG),
            )
        }
    }
}

/** Метка кнопки «обновить» — сверка с телефонной книгой. */
const val BOOK_REFRESH_TAG: String = "book:refresh"

/** Метка кнопки «позвонить» в строке книги. */
const val BOOK_CALL_TAG: String = "book:call"

/** Метка кнопки «видеозвонок» в строке книги. */
const val BOOK_VIDEO_CALL_TAG: String = "book:video-call"


