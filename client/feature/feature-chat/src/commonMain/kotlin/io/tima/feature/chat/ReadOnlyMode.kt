package io.tima.feature.chat

import androidx.compose.runtime.mutableStateOf

/**
 * Аккаунт с потерянным входом открыт «только для чтения» (заказчик 2026-11-20): переписки на телефоне видны, писать
 * нельзя — сервер это устройство больше не признаёт. Вместо строки ввода — [note] и кнопка [onSignIn].
 *
 * Одно состояние на процесс: открыт один аккаунт, и режим относится к нему; сменился аккаунт — режим снимается.
 */
object ReadOnlyMode {
    data class Lost(val note: String, val signIn: String, val onSignIn: () -> Unit)

    /** `null` — писать можно. */
    val lost = mutableStateOf<Lost?>(null)
}
