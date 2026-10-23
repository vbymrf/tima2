package io.tima.core.media

import androidx.compose.runtime.Composable

/**
 * Рамка окна проигрывания (ПЛАН-(ВМ) §3.1): на телефоне — во весь экран поверх приложения, поворот
 * — во весь экран по горизонтали (Р44); на ПК — **отдельное окно**, чтобы браузерный движок не
 * перекрывал меню основного.
 */
@Composable
expect fun MediaWindow(title: String, onClose: () -> Unit, content: @Composable () -> Unit)
