package io.tima.app.platform

// Desktop: запись/воспроизведение голосовых (m4a/AAC) штатными средствами JVM не поддержано.
// Голосовые — фича телефона; на десктопе кнопка записи скрыта, проигрывание — no-op.
actual fun voiceRecordingSupported(): Boolean = false

actual suspend fun startVoiceRecording(): Boolean = false

actual suspend fun stopVoiceRecording(): RecordedAudio? = null

actual fun cancelVoiceRecording() {}

actual suspend fun playVoice(bytes: ByteArray, mime: String) {}

actual fun stopVoice() {}
