package io.tima.core.media

import androidx.compose.runtime.Composable

/**
 * Рамка окна проигрывания (ПЛАН-(ВМ) §3.1): на телефоне — во весь экран поверх приложения, поворот
 * — во весь экран по горизонтали (Р44); на ПК — **отдельное окно**, чтобы браузерный движок не
 * перекрывал меню основного.
 */
@Composable
expect fun MediaWindow(title: String, onClose: () -> Unit, content: @Composable () -> Unit)

/**
 * Экран не гаснет, пока [on] — видео идёт (заказчик 2026-10-23: «чтобы телефон не уходил в сон»). На ПК
 * ничего не делает: там видео смотрят в своём окне, а гашение экрана — настройка Windows.
 */
@Composable
expect fun KeepScreenOn(on: Boolean)
