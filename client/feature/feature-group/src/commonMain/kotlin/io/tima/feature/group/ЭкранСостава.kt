package io.tima.feature.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Аватар
import io.tima.core.ui.Беда
import io.tima.core.ui.Второстепенное
import io.tima.core.ui.Имя
import io.tima.core.ui.Кнопка
import io.tima.core.ui.Поле
import io.tima.core.ui.ПустаяОбласть
import io.tima.core.ui.СтрокаСписка
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.Тима
import io.tima.core.ui.Третьестепенное
import io.tima.core.ui.ШапкаПодокна
import io.tima.domain.chat.GroupMember
import io.tima.domain.chat.GroupRole

/**
 * Состав группы — подокно.
 *
 * **Предупреждение о несменившемся ключе стоит НАД списком и своим цветом.** Оно не про
 * конкретную строку, а про всю группу: исключённый человек уже не в списке, но читает
 * переписку дальше. Спрятать это в строку невозможно — строки уже нет.
 *
 * **Управление составом не показывается тому, кому нельзя.** Кнопка, которая отвечает
 * отказом, — худший вид объяснения: человек узнаёт о запрете, уже нажав.
 *
 * Чистый рендер [СоставState]. Решения — в [СоставStore].
 */
@Composable
fun ЭкранСостава(
    состояние: СоставState,
    onНомер: (String) -> Unit,
    onПозвать: () -> Unit,
    onИсключить: (String) -> Unit,
    onНазад: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val цвета = Тима.цвета
    Column(modifier.fillMaxSize().background(цвета.поверхность)) {
        ШапкаПодокна(название = "Участники", onНазад = onНазад)

        Column(
            modifier = Modifier.fillMaxSize().padding(TimaSpacing.о4),
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.о3),
        ) {
            состояние.беда?.let { Беда(it) }
            состояние.предупреждение?.let { Беда(it) }

            if (состояние.правлюСоставом) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(TimaSpacing.о2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        Поле(
                            значение = состояние.номер,
                            onИзменение = onНомер,
                            подсказка = "+7…",
                            числовое = true,
                        )
                    }
                    Кнопка(надпись = if (состояние.ждём) "…" else "Позвать", onClick = onПозвать)
                }
            }

            if (состояние.участники.isEmpty()) {
                ПустаяОбласть(
                    знак = "👥",
                    заголовок = if (состояние.ждём) "Читаем состав" else "Здесь пока никого",
                    пояснение = if (состояние.ждём) null else "Позовите людей по номеру телефона",
                )
            } else {
                for (участник in состояние.участники) {
                    СтрокаУчастника(
                        участник = участник,
                        можноИсключать = состояние.правлюСоставом && !участник.role.правитПоставом,
                        onИсключить = { onИсключить(участник.userId) },
                    )
                }
            }
        }
    }
}

/**
 * Строка участника.
 *
 * Владельца и админа исключить нельзя, и кнопки у них нет: правило сервера, повторённое
 * здесь, чтобы отказ не пришлось объяснять после нажатия.
 */
@Composable
private fun СтрокаУчастника(
    участник: GroupMember,
    можноИсключать: Boolean,
    onИсключить: () -> Unit,
) {
    СтрокаСписка(
        слева = { Аватар(буквы = участник.userId.take(2).uppercase()) },
        справа = {
            if (можноИсключать) {
                Кнопка(надпись = "Исключить", onClick = onИсключить)
            } else {
                Третьестепенное(подписьРоли(участник.role))
            }
        },
        середина = {
            Column {
                Имя(участник.userId)
                участник.bannedUntil?.let { Второстепенное("заблокирован до $it") }
            }
        },
    )
}

private fun подписьРоли(роль: GroupRole): String = when (роль) {
    GroupRole.Владелец -> "владелец"
    GroupRole.Админ -> "админ"
    GroupRole.Модератор -> "модератор"
    GroupRole.Участник -> "участник"
    // Роль, которой этот клиент не знает: сервер новее нас. Показать «участник» значило бы
    // соврать про права, которых мы не понимаем.
    GroupRole.Неизвестная -> "роль неизвестна"
}
