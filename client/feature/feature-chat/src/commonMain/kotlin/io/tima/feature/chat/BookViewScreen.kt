package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import io.tima.core.ui.ListLine
import io.tima.core.ui.Tima
import io.tima.core.ui.RadioMark
import io.tima.core.ui.CheckMark
import io.tima.core.ui.words
import io.tima.core.ui.Name
import io.tima.core.ui.ProvidePlace
import io.tima.core.ui.TextPlace
import io.tima.core.ui.SectionTitle
import io.tima.core.ui.Tertiary
import io.tima.core.ui.IconButton
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaZones

/**
 * Подокно «Вид» — ПЛАН-КОНТАКТОВ.md, Д5.
 *
 * **Подокно, а не перебор по кругу.** Настроек здесь много, и они независимы: два вида
 * разделов, два переключателя и четыре галки имени. Кнопка, перебирающая такое число
 * состояний, не даёт угадать следующее.
 *
 * **Ни одной галки — тоже ответ.** Тогда работает порядок по умолчанию, тот же самый:
 * имя → имя пользователя → ник → телефон → «Без имени». Поэтому галки квадратные по
 * замыслу макета: круг означал бы «одно из».
 */
@Composable
fun BookViewScreen(
    view: BookView,
    onChange: (BookView) -> Unit,
    modifier: Modifier = Modifier,
    /** Открыть управление разделами. `null` — пункта в меню не будет. */
    onSections: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = TimaSpacing.about2),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about1),
    ) {
        val words = Tima.words.book
        // ── Меню «Вида» (решение заказчика 2026-09-18) ────────────────────────
        // Одна кнопка вместо двух тумблеров: здесь и режимы, и управление набором, и
        // свойства вкладки. Первые два раздела — те самые независимые тумблеры из
        // `разделы.md`, дающие четыре исполнения; третий ведёт в набор.
        SectionTitle(words.subsections)
        Choice(
            title = words.folders,
            hint = words.foldersAbout,
            chosen = view.folders,
            onClick = { onChange(view.copy(folders = true)) },
        )
        Choice(
            title = words.menu,
            hint = words.menuAbout,
            chosen = !view.folders,
            onClick = { onChange(view.copy(folders = false)) },
        )

        SectionTitle(words.labelsTitle)
        Choice(
            title = words.labelsIcons,
            hint = words.labelsIconsAbout,
            chosen = view.icons,
            onClick = { onChange(view.copy(icons = true)) },
        )
        Choice(
            title = words.labelsNames,
            hint = words.labelsNamesAbout,
            chosen = !view.icons,
            onClick = { onChange(view.copy(icons = false)) },
        )

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

        SectionTitle(words.showPersonAs)
        Check(words.name, words.nameAbout, view.showName) {
            onChange(view.copy(showName = it))
        }
        Check(words.userName, words.userNameAbout, view.showUserName) {
            onChange(view.copy(showUserName = it))
        }
        Check(words.nickname, words.nicknameAbout, view.showNickname) {
            onChange(view.copy(showNickname = it))
        }
        Check(words.phone, words.phoneAbout, view.showPhone) {
            onChange(view.copy(showPhone = it))
        }

        SectionTitle(words.whatToShow)
        Check(words.showSearch, words.showSearchAbout, view.showSearch) {
            onChange(view.copy(showSearch = it))
        }
        Check(
            words.showOutsiders,
            words.showOutsidersAbout,
            view.showOutsiders,
        ) {
            onChange(view.copy(showOutsiders = it))
        }
    }
}

/** Выбор из двух: отмечается тот, что выбран сейчас. */
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
 * Подокно «Вид»: панель снизу поверх вкладки.
 *
 * Снизу, а не по центру: до низа экрана палец дотягивается, до середины — как повезёт.
 * Затемнение и касание вне закрывают его так же, как «✕», — оба входа обязаны быть,
 * потому что касание вне угадывают не все, а «✕» ищут глазами.
 */
@Composable
fun BookViewSheet(
    view: BookView,
    onChange: (BookView) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onSections: (() -> Unit)? = null,
) {
    val colors = Tima.colors
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
                .background(colors.surface)
                // Проглатывает касание: нажатие внутри панели не должно её закрывать.
                .clickable(enabled = false, onClick = {}),
        ) {
            // Шапка подокна, а не строка списка. Прежде здесь стоял `ListLine` с
            // третьестепенным «✕»: название читалось как пункт, а крестик — как текст,
            // и оба были мельче всего вокруг. Крестик к тому же не выглядел кнопкой —
            // у него не было круглой подложки, которая есть у всех прочих (подокно
            // переходов, шапки окон). Замечено заказчиком 2026-09-17.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = TimaZones.zone1)
                    .padding(horizontal = TimaSpacing.about4),
                horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Кегль шапки, а не строки списка: подокно называет себя так же, как
                // называют себя окна. `ProvidePlace` берёт размер из настроек человека —
                // «Шрифты и размеры», группа «шапки».
                Box(Modifier.weight(1f)) {
                    ProvidePlace(TextPlace.HEADERS) { Name(Tima.words.book.view) }
                }
                IconButton(glyph = "✕", onClick = onClose)
            }
            BookViewScreen(view = view, onChange = onChange, onSections = onSections)
        }
    }
}
