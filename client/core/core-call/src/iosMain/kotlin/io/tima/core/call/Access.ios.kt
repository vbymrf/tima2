package io.tima.core.call

import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.requestAccessForMediaType

/**
 * Apple: `AVCaptureDevice.requestAccessForMediaType`.
 *
 * Система сама решает, показывать диалог или ответить прежним решением, — спрошенное
 * однажды второй раз не спрашивается. Нам это знать не нужно: ответ по смыслу один.
 *
 * **Камера спрашивается только после микрофона и только для видео.** Два диалога подряд
 * человек читает как один и жмёт наугад; а голосовому звонку камера не нужна вовсе.
 */
actual fun askCallAccess(video: Boolean, onResult: (Boolean) -> Unit) {
    AVCaptureDevice.requestAccessForMediaType(AVMediaTypeAudio) { microphone ->
        if (!microphone || !video) {
            onResult(microphone)
        } else {
            // Камеры может не быть — но звонок голосом от этого не перестаёт быть звонком.
            AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { _ -> onResult(true) }
        }
    }
}

