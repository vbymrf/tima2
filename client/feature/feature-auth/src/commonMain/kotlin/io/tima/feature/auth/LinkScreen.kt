package io.tima.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import io.tima.core.ui.Беда
import io.tima.core.ui.Второстепенное
import io.tima.core.ui.ВидКнопки
import io.tima.core.ui.Кнопка
import io.tima.core.ui.Подпись
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.Тима
import io.tima.core.ui.Третьестепенное

/**
 * «Подтвердить подключение?» — экран на телефоне после скана.
 *
 * Отдельный экран, а не всплывающее сообщение поверх переписки: человек только что навёл
 * камеру на код и должен понять, **что именно** он сейчас разрешает. Всплывающее закрывают
 * не читая.
 *
 * Цена названа прямо: подключённое устройство получит доступ к новым сообщениям аккаунта.
 * Без этой строки «Доверить» читается как «ок».
 */
@Composable
fun ЭкранПривязки(
    состояние: ПривязкаState,
    onДоверить: () -> Unit,
    onОтмена: () -> Unit,
    modifier: Modifier = Modifier,
) = Column(
    modifier = modifier
        .fillMaxSize()
        .background(Тима.цвета.поверхность)
        .padding(TimaSpacing.о4),
    verticalArrangement = Arrangement.spacedBy(TimaSpacing.о3),
) {
    when (состояние) {
        is ПривязкаState.Спрашиваем -> Спрашиваем(состояние, onДоверить, onОтмена)

        is ПривязкаState.Готово -> {
            Подпись("Устройство подключено", вес = FontWeight.ExtraBold)
            Второстепенное(
                "Новые сообщения будут приходить и на него. Прежняя переписка туда не " +
                    "переедет: ключи старых сообщений оборачивались на другие устройства.",
            )
            Кнопка("Готово", onClick = onОтмена, modifier = Modifier.fillMaxWidth())
        }

        ПривязкаState.НеНашКод -> {
            Подпись("Это не код подключения", вес = FontWeight.ExtraBold)
            Второстепенное(
                "Отсканирован другой код. Откройте на компьютере «Подключить к аккаунту» " +
                    "и наведите камеру на код оттуда.",
            )
            Кнопка("Закрыть", onClick = onОтмена, вид = ВидКнопки.Тихая, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Спрашиваем(
    состояние: ПривязкаState.Спрашиваем,
    onДоверить: () -> Unit,
    onОтмена: () -> Unit,
) {
    Подпись("Подтвердить подключение?", вес = FontWeight.ExtraBold)

    // Имя устройства — то, что человек видел минуту назад на своём компьютере. Если его
    // в коде не было, так и говорим: подставленное имя он примет за настоящее.
    Второстепенное(состояние.имя?.let { "Устройство: $it" } ?: "Устройство себя не назвало")

    Третьестепенное(
        "Подключённое устройство сможет читать новые сообщения этого аккаунта и писать " +
            "от вашего имени. Отключить его можно в списке устройств.",
    )

    состояние.беда?.let { Беда(it) }

    Кнопка(
        надпись = if (состояние.ждём) "Подключаем…" else "Доверить",
        onClick = onДоверить,
        modifier = Modifier.fillMaxWidth(),
    )
    Кнопка(
        надпись = "Отклонить",
        onClick = onОтмена,
        вид = ВидКнопки.Опасная,
        modifier = Modifier.fillMaxWidth(),
    )
}
