package io.tima.core.notify

/** Приложения под iOS мы не раздаём — порт есть, сведений нет. */
actual fun backgroundFacts(): BackgroundFacts = BackgroundFacts()
