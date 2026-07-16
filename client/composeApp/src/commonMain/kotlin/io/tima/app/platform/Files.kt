package io.tima.app.platform

/** Выбранный файл: содержимое, имя и mime. */
class PickedFile(val bytes: ByteArray, val name: String, val mime: String)

/** Системный выбор произвольного файла; null — отмена. */
expect suspend fun pickFile(): PickedFile?

/** Открыть/сохранить расшифрованный файл системным приложением (Android — просмотрщик, Desktop — ассоциация). */
expect suspend fun openFile(bytes: ByteArray, name: String, mime: String)
