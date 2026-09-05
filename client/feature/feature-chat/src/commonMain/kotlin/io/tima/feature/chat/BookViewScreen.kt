package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import io.tima.core.ui.ListLine
import io.tima.core.ui.Tima
import io.tima.core.ui.Name
import io.tima.core.ui.SectionTitle
import io.tima.core.ui.Tertiary
import io.tima.core.ui.TimaSpacing

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
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = TimaSpacing.about2),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about1),
    ) {
        SectionTitle("Отображение подразделов")
        Choice(
            title = "Папки",
            hint = "разделы полосами, сворачиваются",
            chosen = view.folders,
            onClick = { onChange(view.copy(folders = true)) },
        )
        Choice(
            title = "Меню",
            hint = "разделы строкой под вкладками",
            chosen = !view.folders,
            onClick = { onChange(view.copy(folders = false)) },
        )

        SectionTitle("Отображать пользователя как")
        Check("Имя", "своё, иначе из телефонной книги", view.showName) {
            onChange(view.copy(showName = it))
        }
        Check("Имя пользователя", "как он сам себя назвал", view.showUserName) {
            onChange(view.copy(showUserName = it))
        }
        Check("Ник", "если человек его задал", view.showNickname) {
            onChange(view.copy(showNickname = it))
        }
        Check("Телефон", "номер из книги", view.showPhone) {
            onChange(view.copy(showPhone = it))
        }

        SectionTitle("Что показывать")
        Check("Показывать поиск", "строкой над списком", view.showSearch) {
            onChange(view.copy(showSearch = it))
        }
        Check("Показывать тех, кого нет в TIMa", "раздел «Телефон» в конце списка", view.showOutsiders) {
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
                Tertiary(hint, lineOne = true)
            }
        },
        right = { Tertiary(if (chosen) "●" else "○", lineOne = true) },
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
        right = { Tertiary(if (on) "☑" else "☐", lineOne = true) },
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
            ListLine(
                onClick = onClose,
                middle = { Name("Вид") },
                right = { Tertiary("✕", lineOne = true) },
            )
            BookViewScreen(view = view, onChange = onChange)
        }
    }
}
