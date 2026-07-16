package io.tima.app.platform

/** Поделиться текстом: Android — системное «Поделиться»; Desktop — буфер обмена + файл. */
expect suspend fun shareText(title: String, text: String)
