package io.tima.core.notify

/** На ПК фон не ограничивается: окно в трее живёт, пока его не закрыли. Сказать нечего. */
actual fun backgroundFacts(): BackgroundFacts = BackgroundFacts()

/** Канала «Звонки» здесь нет — открывать нечего. */
actual fun openCallsChannelSettings() = Unit

/** Полноэкранного вызова здесь нет — открывать нечего. */
actual fun openFullScreenSettings() = Unit
