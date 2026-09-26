package io.tima.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Ввод телефона — **два поля: код страны и номер**, плюс нарисован.
 *
 * ── ПОЧЕМУ ДВА ПОЛЯ, А НЕ ОДНО (решение заказчика 2026-09-15) ────────────────
 *
 * На цифровой клавиатуре телефона **нет плюса**. Одно поле с подсказкой «+7…» и
 * цифровой клавиатурой предлагало набрать то, что набрать нельзя; человек набирал
 * номер без кода страны и получал отказ сервера — при том, что на экране всё выглядело
 * правильно. На входе это было починено 2026-09-06 двумя полями; здесь то же самое
 * вынесено в один компонент, чтобы «кому написать», «пригласить в группу» и «участники»
 * не решали этот вопрос каждый по-своему — а они решали: три экрана с «+7…».
 *
 * Клавиатуру задаём мы — [Field] с `numeric = true` ставит `KeyboardType.Number`, и
 * система показывает свою цифровую. Плюс в ней не нужен: он часть разметки, а не
 * значения, — иначе «+7» и «7» были бы разными кодами одной страны.
 *
 * Код страны и номер — разные величины и для человека: код меняется раз в жизни,
 * номер набирают каждый раз. Слитое поле заставляло стирать «+7» вместе с номером.
 *
 * Что уходит серверу, собирает [fullPhone] — та же пара, тем же правилом, везде.
 */
@Composable
fun PhoneFields(
    countryCode: String,
    number: String,
    onCountryCode: (String) -> Unit,
    onNumber: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "999 000 00 00",
    /**
     * Фокус ушёл из обоих полей — код и номер одно целое, и переход между ними уходом не
     * считается. Нужен сверке номера: спросить, когда человек закончил набирать.
     */
    onLeave: () -> Unit = {},
) {
    var inside by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth().onFocusChanged { focus ->
            if (inside && !focus.hasFocus) onLeave()
            inside = focus.hasFocus
        },
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Caption("+", fontSize = TimaType.sz3, weight = FontWeight.ExtraBold)
        Box(Modifier.width(CODE_WIDTH)) {
            Field(
                value = countryCode,
                // Код — только цифры и не длиннее четырёх: длиннее кодов стран не бывает.
                onChange = { onCountryCode(it.filter(Char::isDigit).take(4)) },
                hint = "7",
                numeric = true,
            )
        }
        Box(Modifier.weight(1f)) {
            Field(value = number, onChange = onNumber, hint = hint, numeric = true)
        }
    }
}

/**
 * Номер для сервера: E.164, без пробелов и скобок, из пары «код страны, номер».
 *
 * **Номер, начатый с плюса, берётся целиком.** Так выглядит вставка из буфера: человек
 * скопировал номер полностью, и приписать к нему код страны значит получить «+77999…»
 * — номер, которого нет. Поймано тестом входа, который вставлял именно так.
 *
 * Пустой номер даёт пустую строку, а не «+7»: с пустым сервер не зовут, и проверять
 * это удобнее по пустоте, чем по длине.
 */
fun fullPhone(countryCode: String, number: String): String {
    val entered = number.trim()
    if (entered.isEmpty()) return ""
    if (entered.startsWith("+")) return "+" + entered.drop(1).filter(Char::isDigit)
    return "+" + countryCode.filter(Char::isDigit) + entered.filter(Char::isDigit)
}

/** Ширина поля кода: четыре цифры с полями. */
private val CODE_WIDTH = 72.dp
