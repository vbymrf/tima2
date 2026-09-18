package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tima.core.ui.Avatar
import io.tima.core.ui.Caption
import io.tima.core.ui.CheckMark
import io.tima.core.ui.IconButton
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.ProvidePlace
import io.tima.core.ui.RadioMark
import io.tima.core.ui.SectionTitle
import io.tima.core.ui.Tertiary
import io.tima.core.ui.TextPlace
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaShapes
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaZones
import io.tima.core.ui.words
import io.tima.domain.chat.ChatPerson
import io.tima.domain.chat.PersonField
import io.tima.domain.chat.letter
import io.tima.domain.chat.line

/**
 * Подокно «Вид» — ПЛАН-КОНТАКТОВ.md, Д5; переустроено 2026-09-18 по решению заказчика.
 *
 * **Три входа вместо одного длинного списка.** Первым — «Разделы» (управление набором),
 * дальше два пункта с переходом: «Вид разделов» (папки/меню, ярлычки/имена, размер) и
 * «Отображать пользователя как» (порядок и галки полей). У каждого подокна сверху —
 * **образец**, который меняется вместе с выбором, как в «Шрифтах и размерах»: без него
 * выбор делается вслепую, а последствия видны только после выхода.
 *
 * Образец рисуется **теми же композициями**, что настоящие экраны ([SectionsRow],
 * [SectionsTiles], [SectionHeader], [ListLine]), а не их копией: копия разойдётся с
 * оригиналом и начнёт показывать не то.
 *
 * **Ни одной галки — тоже ответ.** Тогда показывается первое сверху, что у человека есть.
 * Галки квадратные по замыслу макета: круг означал бы «одно из».
 */
@Composable
fun BookViewScreen(
    view: BookView,
    onChange: (BookView) -> Unit,
    modifier: Modifier = Modifier,
    /** Открыть управление разделами. `null` — пункта в меню не будет. */
    onSections: (() -> Unit)? = null,
    /**
     * Список — про людей: есть «раздел Телефон» и «поиск». У набора сообществ (каталог)
     * этих пунктов нет — там группы.
     */
    forPeople: Boolean = true,
    /** Открыть подокно выбора: вид разделов или как называть человека. */
    onOpen: (ViewPage) -> Unit = {},
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = TimaSpacing.about2),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about1),
    ) {
        val words = Tima.words.book
        // «Разделы» — в самый верх: это то, зачем чаще всего открывают «Вид».
        if (onSections != null) {
            ListLine(
                onClick = onSections,
                middle = {
                    Column {
                        Name(words.sectionsItem)
                        Tertiary(words.sectionsItemAbout, lineOne = true)
                    }
                },
                right = { Tertiary("›", lineOne = true) },
            )
        }
        ListLine(
            onClick = { onOpen(ViewPage.Sections) },
            middle = {
                Column {
                    Name(words.sectionsLook)
                    Tertiary(
                        (if (view.folders) words.folders else words.menu) + " · " +
                            (if (view.icons) words.labelsIcons else words.labelsNames),
                        lineOne = true,
                    )
                }
            },
            right = { Tertiary("›", lineOne = true) },
        )
        ListLine(
            onClick = { onOpen(ViewPage.Person) },
            middle = {
                Column {
                    Name(words.showPersonAs)
                    Tertiary(
                        view.order.filter { view.checked(it) }.map { words.field(it) }.joinToString(", ")
                            .ifBlank { words.field(view.order.first()) },
                        lineOne = true,
                    )
                }
            },
            right = { Tertiary("›", lineOne = true) },
        )

        if (forPeople) {
            SectionTitle(words.whatToShow)
            Check(words.showSearch, words.showSearchAbout, view.showSearch) {
                onChange(view.copy(showSearch = it))
            }
            Check(words.showOutsiders, words.showOutsidersAbout, view.showOutsiders) {
                onChange(view.copy(showOutsiders = it))
            }
        }
    }
}

/** Подокна «Вида» с образцом. */
enum class ViewPage { Sections, Person }

/**
 * Подокно «Вид разделов»: образец сверху, ниже — папки/меню, ярлычки/имена, размер.
 * Образец — те же строки и плитки, что на вкладке, на выдуманных разделах.
 */
@Composable
fun SectionsLookPage(view: BookView, onChange: (BookView) -> Unit, modifier: Modifier = Modifier) {
    val words = Tima.words.book
    val colors = Tima.colors
    Column(modifier.fillMaxWidth().padding(vertical = TimaSpacing.about2), verticalArrangement = Arrangement.spacedBy(TimaSpacing.about1)) {
        SectionTitle(words.lookSample)
        val tabs = listOf(
            SectionTab(if (view.folders && view.icons) ALL_SECTION else "", words.everyone, 0),
            SectionTab("work", "Работа", 2),
            SectionTab("home", "Дом", 1),
            SectionTab(COMMON_SECTION, words.commonSection, 0),
        )
        val counts = mapOf(tabs[0].id to 7, "work" to 3, "home" to 2, COMMON_SECTION to 2)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4)
                .clip(RoundedCornerShape(TimaShapes.square)).background(colors.functional),
        ) {
            when {
                view.folders && view.icons -> SectionsTiles(
                    tabs = tabs,
                    countOf = { counts[it] ?: 0 },
                    onOpen = {},
                    onAdd = null,
                    newIn = { if (it == "work") 1 else 0 },
                    size = view.tileSize,
                )
                view.folders -> Column {
                    SectionHeader(tabs[1], count = 3, fresh = 1, open = true, onClick = {})
                    SampleLine("Анна")
                    SectionHeader(tabs[2], count = 2, fresh = 0, open = false, onClick = {})
                }
                else -> Column {
                    SectionsRow(tabs, chosen = "work", icons = view.icons, onPick = {}, newIn = { if (it == "work") 1 else 0 })
                    SampleLine("Анна")
                }
            }
        }

        SectionTitle(words.subsections)
        Choice(words.folders, words.foldersAbout, view.folders) { onChange(view.copy(folders = true)) }
        Choice(words.menu, words.menuAbout, !view.folders) { onChange(view.copy(folders = false)) }

        SectionTitle(words.labelsTitle)
        Choice(words.labelsIcons, words.labelsIconsAbout, view.icons) { onChange(view.copy(icons = true)) }
        Choice(words.labelsNames, words.labelsNamesAbout, !view.icons) { onChange(view.copy(icons = false)) }

        // Размер — только у плитки: в гармошке и на полосе размер задаёт строка.
        if (view.icons && view.folders) {
            SectionTitle(words.tileSizeTitle)
            for (size in TileSize.entries) {
                Choice(
                    title = when (size) {
                        TileSize.Small -> words.tileSmall
                        TileSize.Normal -> words.tileNormal
                        TileSize.Large -> words.tileLarge
                    },
                    hint = "",
                    chosen = view.tileSize == size,
                    onClick = { onChange(view.copy(tileSize = size)) },
                )
            }
        }
    }
}

/**
 * Подокно «Отображать пользователя как»: образец — строка контакта и строка автора в
 * группе на выдуманном человеке, у которого есть все четыре поля; ниже — список полей
 * со стрелками порядка и галками.
 */
@Composable
fun PersonLookPage(view: BookView, onChange: (BookView) -> Unit, modifier: Modifier = Modifier) {
    val words = Tima.words.book
    val colors = Tima.colors
    val sample = ChatPerson(name = "Анна Петрова", userName = "Anna P.", nick = "anna_p", phone = "+7 999 000-00-00")
    val look = view.look()
    Column(modifier.fillMaxWidth().padding(vertical = TimaSpacing.about2), verticalArrangement = Arrangement.spacedBy(TimaSpacing.about1)) {
        SectionTitle(words.lookSample)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4)
                .clip(RoundedCornerShape(TimaShapes.square)).background(colors.functional),
        ) {
            // Строка контакта: первая — по галкам без телефона, вторая — всегда телефон.
            ListLine(
                onClick = {},
                left = { Avatar(letters = sample.letter()) },
                middle = {
                    Column {
                        Name(sample.line(look, CONTACT_FIRST_LINE) ?: words.nameless)
                        Tertiary(sample.phone!!, lineOne = true)
                    }
                },
            )
            // Строка автора в группе: одной строкой, по всем галкам.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
                horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(letters = sample.letter())
                Caption(sample.line(look) ?: Tima.words.chat.someone, fontSize = 22.sp, weight = FontWeight.ExtraBold, color = colors.text2, lineOne = true)
            }
        }

        SectionTitle(words.showPersonAs)
        for ((index, field) in view.order.withIndex()) {
            PersonFieldRow(
                title = words.field(field),
                hint = words.fieldAbout(field),
                on = view.checked(field),
                upMay = index > 0,
                downMay = index < view.order.lastIndex,
                onToggle = { onChange(view.withChecked(field, !view.checked(field))) },
                onUp = { onChange(view.moved(field, up = true)) },
                onDown = { onChange(view.moved(field, up = false)) },
            )
        }
    }
}

/** Поля первой строки контакта: телефон стоит второй строкой всегда и в первую не берётся. */
val CONTACT_FIRST_LINE = setOf(PersonField.Name, PersonField.Nick, PersonField.UserName)

private fun io.tima.core.words.BookWords.field(field: PersonField): String = when (field) {
    PersonField.Name -> name
    PersonField.UserName -> userName
    PersonField.Nick -> nickname
    PersonField.Phone -> phone
}

private fun io.tima.core.words.BookWords.fieldAbout(field: PersonField): String = when (field) {
    PersonField.Name -> nameAbout
    PersonField.UserName -> userNameAbout
    PersonField.Nick -> nicknameAbout
    PersonField.Phone -> phoneAbout
}

@Composable
private fun SampleLine(name: String) {
    ListLine(
        onClick = {},
        left = { Avatar(letters = name.take(1)) },
        middle = {
            Column {
                Name(name)
                Tertiary("+7 999 000-00-00", lineOne = true)
            }
        },
    )
}

@Composable
private fun Choice(title: String, hint: String, chosen: Boolean, onClick: () -> Unit) {
    ListLine(
        onClick = onClick,
        middle = {
            Column {
                Name(title)
                if (hint.isNotEmpty()) Tertiary(hint, lineOne = true)
            }
        },
        right = { RadioMark(chosen) },
    )
}

/**
 * Галка: включено или нет.
 *
 * Знак квадратный, а не круглый: круг у нас означает «одно из», квадрат — «сколько
 * угодно, в том числе ничего».
 */
@Composable
private fun Check(title: String, hint: String, on: Boolean, onChange: (Boolean) -> Unit) {
    ListLine(
        onClick = { onChange(!on) },
        middle = {
            Column {
                Name(title)
                Tertiary(hint, lineOne = true)
            }
        },
        right = { CheckMark(on) },
    )
}

/**
 * Строка поля человека: стрелки порядка, название с подсказкой, галка. Нажатие на строку —
 * галка; стрелки — свои кнопки, чтобы одно не путалось с другим.
 */
@Composable
private fun PersonFieldRow(
    title: String,
    hint: String,
    on: Boolean,
    upMay: Boolean,
    downMay: Boolean,
    onToggle: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    ListLine(
        onClick = onToggle,
        left = {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(glyph = "▲", onClick = onUp, live = upMay)
                IconButton(glyph = "▼", onClick = onDown, live = downMay)
            }
        },
        middle = {
            Column {
                Name(title)
                Tertiary(hint, lineOne = true)
            }
        },
        right = { CheckMark(on) },
    )
}

/**
 * Подокно «Вид»: панель снизу поверх вкладки.
 *
 * Снизу, а не по центру: до низа экрана палец дотягивается, до середины — как повезёт.
 * Затемнение и касание вне закрывают его так же, как «✕», — оба входа обязаны быть,
 * потому что касание вне угадывают не все, а «✕» ищут глазами.
 *
 * Подокна выбора («Вид разделов», «Отображать пользователя как») открываются в той же
 * панели со стрелкой «назад» в шапке: вторая панель поверх первой была бы лестницей.
 */
@Composable
fun BookViewSheet(
    view: BookView,
    onChange: (BookView) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onSections: (() -> Unit)? = null,
    forPeople: Boolean = true,
) {
    val colors = Tima.colors
    val words = Tima.words.book
    var page by remember { mutableStateOf<ViewPage?>(null) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.text.copy(alpha = 0.45f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Поле сверху — прозрачное: сквозь него видно затемнение, и нажатие туда
                // закрывает подокно даже при самом длинном содержимом.
                .padding(top = TimaZones.zone1)
                .background(colors.surface)
                // Проглатывает касание: нажатие внутри панели не должно её закрывать.
                .clickable(enabled = false, onClick = {}),
        ) {
            // Шапка подокна на подложке, как первая строка подокна переходов. Кегль —
            // шапки, а не строки списка: подокно называет себя так же, как окна.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.functional)
                    .heightIn(min = TimaZones.zone1)
                    .padding(horizontal = TimaSpacing.about4),
                horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val current = page
                if (current != null) IconButton(glyph = "‹", onClick = { page = null }, live = true)
                Box(Modifier.weight(1f)) {
                    ProvidePlace(TextPlace.HEADERS) {
                        Name(
                            when (current) {
                                null -> words.view
                                ViewPage.Sections -> words.sectionsLook
                                ViewPage.Person -> words.showPersonAs
                            },
                        )
                    }
                }
                IconButton(glyph = "✕", onClick = onClose)
            }
            // `weight(fill = false)`: короткое содержимое — панель по содержимому, длинное —
            // не выше экрана, а дальше едет.
            val scrolling = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
            when (page) {
                null -> BookViewScreen(
                    view = view,
                    onChange = onChange,
                    onSections = onSections,
                    modifier = scrolling,
                    forPeople = forPeople,
                    onOpen = { page = it },
                )
                ViewPage.Sections -> SectionsLookPage(view, onChange, scrolling)
                ViewPage.Person -> PersonLookPage(view, onChange, scrolling)
            }
        }
    }
}
