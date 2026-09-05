package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.tima.core.ui.Avatar
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.domain.chat.BookEntry

/**
 * Подокно «Пригласить» — ПЛАН-КОНТАКТОВ.md, Д6.
 *
 * **Все три способа — средствами телефона.** Приложения у человека ещё нет, и отправить
 * ему что-либо через TIMa нельзя по определению.
 *
 * **«Позвонить» живёт здесь, а не в строке списка.** В разделе «Телефон» у строки одна
 * кнопка — «Пригласить», — и «📞» рядом с ней читалось бы как наш звонок. Внутри подокна
 * двусмысленности нет: подпись прямо говорит, что звонит телефон.
 *
 * **Отметки «приглашён» нет.** Приложение не узнаёт, дошло ли СМС и открыл ли человек
 * ссылку: нажатие и отправка — разные события.
 */
@Composable
fun InviteScreen(
    person: BookEntry,
    onSms: () -> Unit,
    onCall: () -> Unit,
    onShare: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Чего эта платформа не умеет: на ПК не умеет ничего из трёх. */
    trouble: String? = null,
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
                .clickable(enabled = false, onClick = {}),
        ) {
            ListLine(
                left = { Avatar(letters = (person.name ?: person.phone).take(1).uppercase()) },
                middle = {
                    Column {
                        Name(person.name ?: person.phone)
                        Tertiary("${person.phone} · нет в TIMa", lineOne = true)
                    }
                },
                right = { Tertiary("✕", lineOne = true) },
                onClick = onClose,
            )

            Way("Отправить СМС", "откроется приложение сообщений с готовым текстом", onSms)
            Way("Позвонить", "обычный звонок телефоном", onCall)
            Way("Поделиться", "ссылка в любое приложение на телефоне", onShare)

            trouble?.let { ListLine(middle = { Tertiary(it, lineOne = false) }) }
        }
    }
}

@Composable
private fun Way(title: String, hint: String, onClick: () -> Unit) {
    ListLine(
        onClick = onClick,
        middle = {
            Column {
                Name(title)
                Tertiary(hint, lineOne = true)
            }
        },
    )
}
