package io.tima.app.platform

/**
 * Не давать экрану гаснуть, пока идёт звонок или аудио-чат. Погасший экран — это уход
 * приложения в фон, а фоновому приложению Android глушит микрофон и камеру.
 */
expect fun keepScreenOn(on: Boolean)
