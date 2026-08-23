package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Беда
import io.tima.core.ui.Второстепенное
import io.tima.core.ui.Кнопка
import io.tima.core.ui.Поле
import io.tima.core.ui.Подпись
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.Тима
import io.tima.core.ui.ШапкаПодокна

/**
 * Новая переписка — подокно.
 *
 * **Подокно, а не окно**, поэтому плашки нет и есть «назад»: человек пришёл сюда из списка
 * и вернётся туда же. Это то самое правило макета, из которого следует вся раскладка: у
 * подокна вопрос «в каком я окне» не стоит.
 *
 * Чистый рендер [НоваяПерепискаState]. Решения — в [НоваяПерепискаStore].
 */
@Composable
fun ЭкранНовойПереписки(
    состояние: НоваяПерепискаState,
    onНомер: (String) -> Unit,
    onНайти: () -> Unit,
    onНазад: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val цвета = Тима.цвета
    Column(modifier.fillMaxSize().background(цвета.поверхность)) {
        ШапкаПодокна(название = "Новая переписка", onНазад = onНазад)

        Box(
            modifier = Modifier.fillMaxSize().padding(TimaSpacing.о5),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                // Тот же предел, что у входа: поле во всю ширину ПК читается как поиск.
                modifier = Modifier.widthIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(TimaSpacing.о4),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Подпись("Кому написать", кегль = TimaType.щ2, вес = FontWeight.ExtraBold)
                Второстепенное("Номер телефона в TIMA")

                Поле(
                    значение = состояние.номер,
                    onИзменение = onНомер,
                    подсказка = "+7…",
                    числовое = true,
                )

                состояние.беда?.let { Беда(it) }

                // Не беда, а предложение: человека, которого нет в TIMA, надо позвать.
                if (состояние.позвать) {
                    Беда("Этого номера в TIMA нет — позовите человека")
                }

                Кнопка(
                    надпись = if (состояние.ждём) "Ищем…" else "Найти",
                    onClick = onНайти,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
