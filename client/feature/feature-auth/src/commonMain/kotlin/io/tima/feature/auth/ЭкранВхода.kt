package io.tima.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.tima.core.ui.ВидКнопки
import io.tima.core.ui.Второстепенное
import io.tima.core.ui.Кнопка
import io.tima.core.ui.Подпись
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.Третьестепенное
import io.tima.core.ui.Тима

/**
 * Экран входа — К5.1.
 *
 * Два шага, а не пять: телефон и код. Email, пароль, десять резервных кодов и временный
 * режим описаны в `doc_UI/22-auth-registration.md`, но **на сервере их нет вовсе** —
 * измерено, и вопрос вынесен заказчику (Д1, Д2). Сделано то, что сервер умеет; это
 * подмножество любого из двух решений.
 *
 * Экран — чистый рендер [AuthState]. Все решения — в [AuthStore]: что делать с набранным
 * при отказе, когда кнопка занята, куда возвращаться при просроченном коде.
 *
 * **Плашки окна здесь нет намеренно.** Салатовая плашка отвечает на вопрос «в каком я
 * окне», а на входе этот вопрос не стоит: окно одно, и уйти из него некуда.
 */
@Composable
fun ЭкранВхода(
    состояние: AuthState,
    onНомер: (String) -> Unit,
    onКод: (String) -> Unit,
    onЗапросить: () -> Unit,
    onПодтвердить: () -> Unit,
    onНазад: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val цвета = Тима.цвета
    Box(
        modifier = modifier.fillMaxSize().background(цвета.поверхность).padding(TimaSpacing.о5),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            // Ширина ограничена: поле ввода на всю ширину ПК выглядит как поле поиска, а
            // не как «введите номер». Тот же предел, что у содержимого переписки.
            modifier = Modifier.widthIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.о4),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (состояние) {
                is AuthState.Телефон -> Телефон(состояние, onНомер, onЗапросить)
                is AuthState.Код -> Код(состояние, onКод, onПодтвердить, onНазад)
                // Оба конечных состояния экран не рисует: приложение уже ушло дальше.
                // Показывать «готово» было бы лишним шагом на пути, который человек и так
                // прошёл.
                is AuthState.Готово, AuthState.УжеЗаведено -> Unit
            }
        }
    }
}

@Composable
private fun Телефон(состояние: AuthState.Телефон, onНомер: (String) -> Unit, onЗапросить: () -> Unit) {
    Подпись("Добро пожаловать", кегль = TimaType.щ2, вес = FontWeight.ExtraBold)
    Второстепенное("Введите номер телефона — пришлём код")

    Поле(
        значение = состояние.номер,
        подсказка = "+7…",
        onИзменение = onНомер,
        числовое = true,
    )

    состояние.беда?.let { Беда(it) }

    Кнопка(
        надпись = if (состояние.ждём) "Отправляем…" else "Получить код",
        onClick = onЗапросить,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Код(
    состояние: AuthState.Код,
    onКод: (String) -> Unit,
    onПодтвердить: () -> Unit,
    onНазад: () -> Unit,
) {
    Подпись("Подтверждение", кегль = TimaType.щ2, вес = FontWeight.ExtraBold)
    Второстепенное("Код отправлен на ${состояние.телефон}")

    Поле(
        значение = состояние.код,
        подсказка = "······",
        onИзменение = onКод,
        числовое = true,
        поЦентру = true,
    )

    // Подсказка стенда: код приходит в ответе только там, где сервер сам его прислал.
    // Написано вслух, чтобы её не приняли за «код виден всем».
    состояние.подсказкаСтенда?.let { Третьестепенное("Стенд прислал код в ответе: $it") }

    состояние.беда?.let { Беда(it) }

    Кнопка(
        надпись = if (состояние.ждём) "Проверяем…" else "Подтвердить",
        onClick = onПодтвердить,
        modifier = Modifier.fillMaxWidth(),
    )
    Кнопка(
        надпись = "Изменить номер",
        onClick = onНазад,
        вид = ВидКнопки.Тихая,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Поле ввода: пилюля на тихой подложке.
 *
 * `BasicTextField` из foundation, а не готовое поле material3: у нас своя система форм и
 * цветов, и брать чужую значило бы спорить с макетом в каждом состоянии поля.
 */
@Composable
private fun Поле(
    значение: String,
    подсказка: String,
    onИзменение: (String) -> Unit,
    числовое: Boolean = false,
    поЦентру: Boolean = false,
) {
    val цвета = Тима.цвета
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(цвета.акцентМягкий, CircleShape)
            .padding(horizontal = TimaSpacing.о5, vertical = 14.dp),
        contentAlignment = if (поЦентру) Alignment.Center else Alignment.CenterStart,
    ) {
        if (значение.isEmpty()) {
            Подпись(подсказка, кегль = TimaType.щ3, цвет = цвета.текст3)
        }
        BasicTextField(
            value = значение,
            onValueChange = onИзменение,
            textStyle = TextStyle(
                fontSize = TimaType.щ3,
                color = цвета.текст,
                textAlign = if (поЦентру) TextAlign.Center else TextAlign.Start,
            ),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (числовое) KeyboardType.Number else KeyboardType.Text,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Отказ словами.
 *
 * **Без красного**: красного в палитре нет вовсе, и опасное отличается словом и местом.
 * На этом экране беда и без цвета заметна — она единственное, что изменилось.
 */
@Composable
private fun Беда(текст: String) = Box(
    modifier = Modifier
        .fillMaxWidth()
        .background(Тима.цвета.акцентМягкий, CircleShape)
        .padding(horizontal = TimaSpacing.о4, vertical = TimaSpacing.о2),
) {
    Подпись(текст, кегль = TimaType.щ5, вес = FontWeight.Bold)
}
