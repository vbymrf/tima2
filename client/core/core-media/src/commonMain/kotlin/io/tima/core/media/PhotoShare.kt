package io.tima.core.media

import androidx.compose.runtime.Composable

/**
 * Отдать картинку наружу системным «Поделиться» (просмотр фото, заказчик 2026-10-18). `null` —
 * платформа не умеет (ПК): фото сохраняют и отправляют файлом.
 */
@Composable
expect fun rememberShareImage(): ((bytes: ByteArray, mime: String) -> Boolean)?

/**
 * Файл из переписки — наружу (меню файла, заказчик 2026-10-20): «Поделиться» системным окном. `null` —
 * платформа не умеет (ПК): файл сохраняют.
 */
@Composable
expect fun rememberShareFile(): ((bytes: ByteArray, mime: String, name: String) -> Boolean)?

/**
 * «Открыть» файл программой системы (меню файла, заказчик 2026-10-20). `false` — открыть нечем; `null` —
 * платформа не умеет.
 */
@Composable
expect fun rememberOpenFile(): ((bytes: ByteArray, mime: String, name: String) -> Boolean)?

/** Имя файла без знаков, которые не примет файловая система; пустое — «file». */
fun safeFileName(name: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trim('.').ifBlank { "file" }.take(120)
