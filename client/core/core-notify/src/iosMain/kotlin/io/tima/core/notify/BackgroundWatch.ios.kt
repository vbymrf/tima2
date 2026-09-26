package io.tima.core.notify

/** Приложения под iOS мы не раздаём — порт есть, сведений нет. */
actual fun backgroundFacts(): BackgroundFacts = BackgroundFacts()

/** Канала «Звонки» здесь нет — открывать нечего. */
actual fun openCallsChannelSettings() = Unit

/** Полноэкранного вызова здесь нет — открывать нечего. */
actual fun openFullScreenSettings() = Unit
