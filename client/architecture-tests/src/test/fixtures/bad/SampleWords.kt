// Заведомо плохой словарь: доказательство, что правило «core-words без зависимостей»
// ловит, а не выключено. Лежит вне наборов исходников и не компилируется.
package io.tima.core.words

import io.tima.feature.shell.Window

interface SampleWords {
    // Вот так перечень оболочки и уезжает под словарь: сначала параметром, потом целиком.
    fun full(window: Window): String
}
