package io.tima.app.platform

/** Записанное голосовое: сжатые байты, mime и длительность. */
class RecordedAudio(val bytes: ByteArray, val mime: String, val durationMs: Int)

/** Поддерживается ли запись голоса на этой платформе (Desktop — нет). */
expect fun voiceRecordingSupported(): Boolean

/** Начать запись с микрофона (запрашивает разрешение). true — запись пошла. */
expect suspend fun startVoiceRecording(): Boolean

/** Остановить запись и вернуть результат; null — ошибка или слишком короткая запись. */
expect suspend fun stopVoiceRecording(): RecordedAudio?

/** Отменить запись без сохранения. */
expect fun cancelVoiceRecording()

/** Проиграть голосовое (уже расшифрованные байты); suspend — возвращается по завершении. */
expect suspend fun playVoice(bytes: ByteArray, mime: String)

/** Прервать текущее воспроизведение. */
expect fun stopVoice()
