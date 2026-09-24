package io.tima.core.notify

/**
 * iOS: показа нет, и это честная пустота, а не заглушка.
 *
 * Приложения под iOS мы не раздаём (ПЛАН-УВЕДОМЛЕНИЙ §9). Цель здесь одна — чтобы общий
 * код собирался под все цели, а не обрастал условиями «а на этой платформе не зовём».
 *
 * Появится раздача — появится `UNUserNotificationCenter` и разрешение вместе с ним;
 * место для него уже есть, и договор менять не придётся.
 */
actual fun notifyAccessWay(): NotifyAccessWay = NotifyAccessWay.Given

actual fun askNotifyAccess(onResult: (Boolean) -> Unit) = onResult(false)

actual fun platformNotifier(): Notifier = Notifier.NONE

actual fun awakeAllowed(): Boolean = true

actual fun askAwake() = Unit
