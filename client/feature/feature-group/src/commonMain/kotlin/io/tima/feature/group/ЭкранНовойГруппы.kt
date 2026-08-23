package io.tima.feature.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Аватар
import io.tima.core.ui.Беда
import io.tima.core.ui.Второстепенное
import io.tima.core.ui.Имя
import io.tima.core.ui.Кнопка
import io.tima.core.ui.Поле
import io.tima.core.ui.Подпись
import io.tima.core.ui.СтрокаСписка
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.Тима
import io.tima.core.ui.ШапкаПодокна

/**
 * Новая группа — подокно.
 *
 * Подокно, а не окно: человек пришёл из списка переписок и вернётся туда же, поэтому есть
 * «назад» и нет плашки.
 *
 * **Непозванные номера показаны отдельно от беды и после создания.** Группа создана, и
 * красный текст про сбой здесь означал бы, что дело не сделано. Дело сделано — просто не
 * всех удалось позвать, и это разные вещи.
 *
 * Чистый рендер [НоваяГруппаState]. Решения — в [НоваяГруппаStore].
 */
@Composable
fun ЭкранНовойГруппы(
    состояние: НоваяГруппаState,
    onНазвание: (String) -> Unit,
    onНомер: (String) -> Unit,
    onДобавитьНомер: () -> Unit,
    onУбратьНомер: (String) -> Unit,
    onСоздать: () -> Unit,
    onНазад: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val цвета = Тима.цвета
    Column(modifier.fillMaxSize().background(цвета.поверхность)) {
        ШапкаПодокна(название = "Новая группа", onНазад = onНазад)

        Box(
            modifier = Modifier.fillMaxSize().padding(TimaSpacing.о5),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                // Тот же предел ширины, что у остальных подокон: поле во всю ширину ПК
                // читается как поиск, а не как ввод.
                modifier = Modifier.widthIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(TimaSpacing.о4),
            ) {
                Подпись("Как назовём", кегль = TimaType.щ2, вес = FontWeight.ExtraBold)

                Поле(
                    значение = состояние.название,
                    onИзменение = onНазвание,
                    подсказка = "Название группы",
                )

                Второстепенное("Кого позвать")

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
                    Кнопка(надпись = "Добавить", onClick = onДобавитьНомер)
                }

                for (номер in состояние.номера) {
                    СтрокаСписка(
                        слева = { Аватар(буквы = "№") },
                        справа = { Второстепенное("убрать", Modifier.padding(start = TimaSpacing.о2)) },
                        onClick = { onУбратьНомер(номер) },
                        середина = { Имя(номер) },
                    )
                }

                состояние.беда?.let { Беда(it) }

                Кнопка(
                    надпись = if (состояние.ждём) "Создаём…" else "Создать группу",
                    onClick = onСоздать,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Появляется только после создания: до него говорить о непозванных нечего.
                if (состояние.непозванные.isNotEmpty()) {
                    Второстепенное("Группа создана. Этих номеров в TIMA нет — позовите людей:")
                    for (номер in состояние.непозванные) {
                        Имя(номер)
                    }
                }
            }
        }
    }
}
