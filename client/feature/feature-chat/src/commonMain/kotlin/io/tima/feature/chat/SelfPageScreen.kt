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
import io.tima.core.ui.Avatar
import io.tima.core.ui.AvatarSize
import io.tima.core.ui.Button
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.SectionTitle
import io.tima.core.ui.Secondary
import io.tima.core.ui.SubwindowHeader
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.words
import io.tima.domain.chat.PersonField

/**
 * Своя страница — «Я» (заказчик 2026-09-25).
 *
 * Открывается из панели переключения окон: значком в шапке или нажатием на свой аватар,
 * имя, телефон. Раньше там было «Изменить», и человек попадал сразу в правку — а что о
 * его аккаунте известно, не видел нигде. В частности, **нигде не было сказано, что
 * аккаунт временный** и удаляется при неактивности.
 *
 * Про срок — одна фраза, без даты и числа дней: решение заказчика того же дня. Сервер о
 * состоянии аккаунта не спрашивается, пока не решено, когда аккаунт становится
 * постоянным: сейчас постоянным он не становится никогда, и фраза верна для всех.
 *
 * Правка — кнопкой здесь же: она ведёт на прежний экран профиля.
 */
@Composable
fun SelfPageScreen(
    state: ProfileState,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    /** Картинка аватара; `null` — буквы. */
    face: ImageBitmap? = null,
) {
    val words = Tima.words.page
    val bookWords = Tima.words.book
    // Без имени — первая буква слова «Без имени» из словаря, а не буква строкой в экране.
    val letter = state.loadedName.ifBlank { Tima.words.chat.nameless }.take(1).uppercase()
    Column(modifier.fillMaxSize()) {
        SubwindowHeader(title = words.myPage, onBack = onBack, avatar = letter, avatarImage = face)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = TimaSpacing.about5),
                contentAlignment = Alignment.Center,
            ) {
                Avatar(letters = letter, image = face, size = AvatarSize.Big)
            }

            // Те же подписи, что у чужой страницы: человек сравнивает, как видит себя и
            // других, и одинаковые поля обязаны называться одинаково.
            val known = listOfNotNull(
                state.loadedName.takeIf { it.isNotBlank() }?.let { it to bookWords.field(PersonField.UserName) },
                state.phone.takeIf { it.isNotBlank() }?.let { it to bookWords.field(PersonField.Phone) },
                state.savedNickname.takeIf { it.isNotBlank() }?.let { "@$it" to bookWords.field(PersonField.Nick) },
            )
            if (known.isNotEmpty()) {
                SectionTitle(words.whatWeKnow)
                for ((value, label) in known) {
                    ListLine(
                        middle = {
                            Column {
                                Name(value)
                                Tertiary(label, lineOne = true)
                            }
                        },
                    )
                }
            }

            SectionTitle(words.account)
            Box(Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2)) {
                Secondary(words.accountTemporary)
            }

            Column(
                modifier = Modifier.fillMaxWidth().padding(TimaSpacing.about4),
                verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            ) {
                Button(label = words.editProfile, onClick = onEdit, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
