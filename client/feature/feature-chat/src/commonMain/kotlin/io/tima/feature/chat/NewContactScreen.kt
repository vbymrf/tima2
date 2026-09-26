package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Caption
import io.tima.core.ui.CheckMark
import io.tima.core.ui.Field
import io.tima.core.ui.TextPlace
import io.tima.core.ui.ProvidePlace
import io.tima.core.ui.Name
import io.tima.core.ui.ListLine
import io.tima.core.ui.IconButton
import io.tima.core.ui.PhoneFields
import io.tima.core.ui.Secondary
import io.tima.core.ui.SubwindowHeader
import io.tima.core.ui.Tima
import io.tima.core.ui.words
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaShapes
import io.tima.core.ui.TimaType
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Trouble
import io.tima.domain.chat.BookList
import io.tima.domain.chat.PersonField
import io.tima.domain.chat.field
import io.tima.domain.chat.letter
import io.tima.core.ui.Avatar
import io.tima.core.ui.AvatarSize
import androidx.compose.ui.graphics.ImageBitmap

/**
 * Новый контакт — подокно (ПЛАН-КОНТАКТОВ.md, Д6).
 *
 * **Обязателен только номер.** По нему приложение находит человека; имя и раздел можно не
 * заполнять — имя подставится из телефонной книги, раздел будет общим.
 *
 * **Исход сверки сказан до нажатия и стоит ПЕРВЫМ** (решение заказчика 2026-09-17).
 * Раньше он стоял внизу, под всеми полями: человек набирал номер, имя, раздел — и только
 * потом узнавал, с кем имеет дело. Ответ на «кого я добавляю» обязан быть там, где его
 * увидят, а не там, где до него дочитают.
 *
 * **Ищут одним из двух — телефоном или ником** (заказчик 2026-09-26). Переключатель
 * показывает одно поле; при двух сразу выбранный по нику молча побеждал номер. Найденного
 * показываем карточкой — аватар и что о нём известно, — чтобы человек видел, кого
 * добавляет.
 *
 * **Имя здесь местное.** Оно живёт в нашей книге и обратно в телефон не пишется:
 * «Витя-сосед» — то, как его зовёте вы, а не то, как он назвался.
 */
@Composable
fun NewContactScreen(
    state: NewContactState,
    onPhone: (String) -> Unit,
    onCountryCode: (String) -> Unit = {},
    onName: (String) -> Unit,
    onSection: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Завести набранный раздел. `null` — заводить нечем, кнопки не будет. */
    onCreateSection: (() -> Unit)? = null,
    /** Набрана часть ника (Л11). `null` — искать нечем, поля не будет вовсе. */
    onNick: ((String) -> Unit)? = null,
    onFindNick: () -> Unit = {},
    onPickNick: (String) -> Unit = {},
    /**
     * Открыть страницу того, кто уже в контактах (заказчик 2026-09-26). `null` — вести
     * некуда; строка «уже в контактах» остаётся, кнопки нет.
     */
    onOpenPerson: ((String) -> Unit)? = null,
    /** Переключатель «Телефон / Ник». */
    onBy: (AddBy) -> Unit = {},
    /** Аватар найденного; `null` — буквы. */
    face: ImageBitmap? = null,
) {
    var picking by remember { mutableStateOf(false) }
    val colors = Tima.colors
    val words = Tima.words.chat
    Box(Modifier.fillMaxSize()) {
    Column(modifier.fillMaxSize().background(colors.surface)) {
        SubwindowHeader(title = words.newContact, onBack = onBack)

        Box(
            modifier = Modifier
                .fillMaxSize()
                // Прокрутка, а не «уместится как-нибудь». С поднятой клавиатурой окно
                // ужимается (`adjustResize`), и нижняя кнопка уезжала за край: увидеть её
                // можно было, только свернув клавиатуру. Найдено заказчиком 2026-09-17.
                .verticalScroll(rememberScrollState())
                .padding(TimaSpacing.about5),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(TimaSpacing.about4),
            ) {
                // Переключатель — самым первым: от него зависит, какое поле ниже. Без
                // поиска по нику (проверки без сети) переключать не на что — его нет.
                if (onNick != null) {
                    val bookWords = Tima.words.book
                    Row(horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
                        for ((by, label) in listOf(AddBy.Phone to bookWords.phone, AddBy.Nick to bookWords.nickname)) {
                            Button(
                                label = label,
                                kind = if (state.by == by) ButtonKind.Action else ButtonKind.Quiet,
                                onClick = { onBy(by) },
                            )
                        }
                    }
                }

                // Исход сверки — ПЕРВЫМ, до полей. Найденный обведён салатовым: это
                // хорошая новость, и она должна читаться за мгновение, а не вычитываться.
                state.about(words)?.let { said ->
                    if (state.checked == true) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colors.navigation, RoundedCornerShape(TimaShapes.radius))
                                .padding(TimaSpacing.about3),
                        ) {
                            Caption(
                                said,
                                fontSize = TimaType.sz5,
                                weight = FontWeight.Bold,
                                color = colors.onAccent,
                            )
                        }
                    } else {
                        Secondary(said)
                    }
                }

                // Кого нашли — карточкой, сразу под «Найден в TIMa» (заказчик 2026-09-26):
                // «кого я добавляю» решается здесь, а не после нажатия. Те же строки и
                // подписи, что на его странице. По нику плашки нет — карточка стоит там же.
                state.foundPerson?.let { person ->
                    val known = PersonField.entries.mapNotNull { f -> person.field(f)?.let { f to it } }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(letters = person.letter(), image = face, size = AvatarSize.Big)
                        Column {
                            for ((f, value) in known) {
                                Name(value)
                                Tertiary(personFieldLabel(f), lineOne = true)
                            }
                        }
                    }
                }

                // ── ПО НИКУ — ПЕРВЫМ ВХОДОМ, НАРАВНЕ С НОМЕРОМ (Л11) ────────
                //
                // У виртуальных аккаунтов номера нет ВОВСЕ, только ник, и добавить их
                // было бы нечем. Поэтому не «дополнительно», а второй равноправный путь.
                //
                // Ищем по нажатию, а не на каждую букву: это перебор каталога имён, и
                // пределы на сервере заведены ровно против того, чтобы он шёл сам собой.
                if (onNick != null && state.by == AddBy.Nick) {
                    Caption(words.byNickname, fontSize = TimaType.sz5, weight = FontWeight.Bold)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) {
                            Field(value = state.nick, onChange = onNick, hint = words.nicknameHint)
                        }
                        Button(
                            label = words.findByNickname,
                            kind = if (state.canSearch) ButtonKind.Action else ButtonKind.Quiet,
                            onClick = { if (state.canSearch) onFindNick() },
                        )
                    }
                    // Ответили и не нашли — своё слово. «Ничего» на этом месте неотличимо
                    // от «сервер молчит», а это разные беды.
                    if (state.nobodyFound) Secondary(words.nobodyWithNickname)
                    state.found.orEmpty().forEach { hit ->
                        val chosen = hit.userId == state.picked
                        ListLine(
                            onClick = { onPickNick(hit.userId) },
                            middle = {
                                Column {
                                    Name("@${hit.nickname}")
                                    // ── ПОМЕТКА, А НЕ СОКРЫТИЕ (Л18) ────────
                                    //
                                    // Спрятать заблокированного из выдачи нельзя:
                                    // сервер о наших списках не знает и отбирать по ним
                                    // не может. Значит честнее показать и сказать, где
                                    // он у нас, — иначе человек заведёт второй раз того,
                                    // кого сам же и убрал.
                                    when (state.inLists[hit.userId]) {
                                        BookList.Removed -> Tertiary(words.hitRemoved, lineOne = true)
                                        BookList.Blocked -> Tertiary(words.hitBlocked, lineOne = true)
                                        else -> Unit
                                    }
                                }
                            },
                            right = if (chosen) ({ CheckMark(true) }) else null,
                        )
                    }
                }

                if (onNick == null || state.by == AddBy.Phone) {
                    Caption(words.phoneNumber, fontSize = TimaType.sz5, weight = FontWeight.Bold)
                    // Два поля, плюс нарисован: на цифровой клавиатуре его нет (2026-09-15).
                    PhoneFields(
                        countryCode = state.countryCode,
                        number = state.phone,
                        onCountryCode = onCountryCode,
                        onNumber = onPhone,
                        hint = "916 000-11-22",
                    )
                }

                Caption(words.nameYouCall, fontSize = TimaType.sz5, weight = FontWeight.Bold)
                Field(value = state.name, onChange = onName, hint = words.optional)

                Caption(words.section, fontSize = TimaType.sz5, weight = FontWeight.Bold)
                Field(value = state.section, onChange = onSection, hint = words.commonSection)

                // Два входа в раздел: выбрать из заведённых и завести набранный. Раньше
                // было одно поле, и набранное в нём имя несуществующего раздела уводило
                // контакт туда, где его не видно, — без единого слова.
                Row(horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
                    if (state.sections.isNotEmpty()) {
                        Button(
                            label = words.pickSection,
                            kind = ButtonKind.Quiet,
                            onClick = { picking = true },
                        )
                    }
                    if (state.sectionMissing && onCreateSection != null) {
                        Button(
                            label = words.createSectionNamed(state.section.trim()),
                            kind = ButtonKind.Quiet,
                            onClick = onCreateSection,
                        )
                    }
                }

                state.trouble?.let { Trouble(it) }

                // Уже в контактах — сказать, кто это, и дать перейти к нему. Добавлять
                // нечего: «Добавить» переписало бы его запись.
                state.already?.let { entry ->
                    Trouble(words.alreadyInBook(entry.name ?: entry.phone))
                    // Страница — по user_id. Нет его (человека нет в TIMa) — и страницы нет.
                    val userId = entry.userId
                    if (userId != null && onOpenPerson != null) {
                        Button(label = words.openPersonPage, onClick = { onOpenPerson(userId) })
                    }
                }

                // Кнопка не гаснет, а отвечает словами: погашенная кнопка не
                // объясняет, чего ей не хватает, и в неё жмут повторно.
                Button(
                    label = state.saveWord(words),
                    onClick = { if (state.canSave) onSave() },
                    kind = if (state.canSave) ButtonKind.Action else ButtonKind.Quiet,
                )
            }
        }
    }

    // Подокно выбора раздела: затемнение и панель снизу — тот же приём, что у «Вида».
    // Списком, а не набором в поле: заведённые разделы человек уже называл, и набирать
    // их второй раз значит позволить ему ошибиться в собственном же имени.
    if (picking) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.text.copy(alpha = 0.45f))
                .clickable { picking = false },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
                    .clickable(enabled = false) {},
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about3),
                    horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        ProvidePlace(TextPlace.HEADERS) { Name(words.pickSection) }
                    }
                    IconButton(glyph = "✕", onClick = { picking = false })
                }
                // «Общий» первым и всегда: он существует без своей строки в разделах —
                // это пустое значение, а не название.
                ListLine(
                    onClick = {
                        onSection("")
                        picking = false
                    },
                    middle = { Name(words.commonSection) },
                )
                state.sections.forEach { section ->
                    ListLine(
                        onClick = {
                            onSection(section.name)
                            picking = false
                        },
                        middle = { Name(section.name) },
                    )
                }
            }
        }
    }
    }
}
/**
 * Новый раздел книги — то же подокно, второй его смысл.
 *
 * Из «＋» у поиска выбирают, что заводить: человека или раздел. Раздел с существующим
 * названием не заводится дважды — иначе счётчики разделов перестают складываться.
 */
@Composable
fun NewSectionScreen(
    name: String,
    onName: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Tima.colors
    val words = Tima.words.chat
    Column(modifier.fillMaxSize().background(colors.surface)) {
        SubwindowHeader(title = words.newSection, onBack = onBack)

        Box(
            modifier = Modifier.fillMaxSize().padding(TimaSpacing.about5),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(TimaSpacing.about4),
            ) {
                Caption(words.title, fontSize = TimaType.sz5, weight = FontWeight.Bold)
                Field(value = name, onChange = onName, hint = words.sectionExample)
                // Людей в раздел кладут потом: заставлять выбирать их сейчас значит
                // требовать решения там, где человек ещё только придумал имя папки.
                Secondary(words.moveLater)
                Button(
                    label = words.createSection,
                    onClick = { if (name.isNotBlank()) onSave() },
                    kind = if (name.isNotBlank()) ButtonKind.Action else ButtonKind.Quiet,
                )
            }
        }
    }
}
