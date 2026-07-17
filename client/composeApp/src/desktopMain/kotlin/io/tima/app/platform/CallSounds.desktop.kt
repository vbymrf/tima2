package io.tima.app.platform

import java.awt.Toolkit

// Desktop: системного рингтона нет — обозначаем входящий звонок системным сигналом.
actual fun startRinging(incoming: Boolean) {
    if (incoming) runCatching { Toolkit.getDefaultToolkit().beep() }
}

actual fun stopRinging() = Unit
