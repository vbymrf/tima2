package io.tima.core.call

/**
 * Почему видео собеседника нет, хотя он его показывает (заказчик 2026-09-29).
 *
 * Только настоящее «нет»: собеседник публикует камеру, а у нас ни кадра. Смена кодека на
 * запасной сюда не попадает — кадры при ней идут, — как и то, у чего уже есть своё
 * событие: видео скрыто нами, видео погасил сервер нехваткой связи.
 */
sealed interface RemoteVideoLoss {
    /** Видео не приходит вовсе: байтов нет. */
    data object NotArriving : RemoteVideoLoss

    /** Байты приходят, а кадров нет — телефон не может раскодировать [codec]. */
    data class NotDecoding(val codec: String) : RemoteVideoLoss
}

/**
 * Опрос приёма — каждые несколько секунд, и вывод из двух опросов подряд.
 *
 * Один пустой опрос ничего не значит: первый кадр идёт секунду-другую, ключевой кадр после
 * переподключения тоже. Два подряд — уже не случайность. На опросе в три секунды это
 * шесть секунд без картинки.
 *
 * Honor 2026-09-29: дорожка, объявленная H.264 и посланная VP8, до собеседника не доходила
 * вовсе, а посланная запасным — появлялась и замирала. Человек видел чёрное или застывшее
 * и не знал почему; в журнале остановку было видно только по отсутствию строк.
 */
class RemoteVideoWatch {

    /** Что известно в очередной опрос. `bytes` и `frames` — накопленные, `null` — дорожки нет. */
    data class Poll(
        /** Собеседник публикует камеру и не выключил её. */
        val published: Boolean,
        /** Видео скрыто нами или погашено сервером — у этого свои события. */
        val excused: Boolean,
        val bytes: Long?,
        val frames: Long?,
        /** Кодек, которым пришло, если известен. */
        val codec: String?,
    )

    private var lastBytes: Long? = null
    private var lastFrames: Long? = null
    private var badStreak = 0
    private var badKind: RemoteVideoLoss? = null

    /** Следующий опрос. Возвращает причину, если видео нет уже два опроса подряд. */
    fun next(poll: Poll): RemoteVideoLoss? {
        if (!poll.published || poll.excused) {
            reset(poll)
            return null
        }
        val bytes = poll.bytes
        val frames = poll.frames
        val was = lastBytes
        val wasFrames = lastFrames
        lastBytes = bytes
        lastFrames = frames
        val now: RemoteVideoLoss? = when {
            bytes == null -> RemoteVideoLoss.NotArriving
            // Первый опрос с дорожкой: сравнивать не с чем.
            was == null -> return null
            bytes - was <= 0 -> RemoteVideoLoss.NotArriving
            frames != null && wasFrames != null && frames - wasFrames <= 0 ->
                RemoteVideoLoss.NotDecoding(poll.codec ?: "?")
            else -> null
        }
        if (now == null) {
            badStreak = 0
            badKind = null
            return null
        }
        badStreak = if (now == badKind) badStreak + 1 else 1
        badKind = now
        return now.takeIf { badStreak >= STREAK }
    }

    private fun reset(poll: Poll) {
        badStreak = 0
        badKind = null
        lastBytes = poll.bytes
        lastFrames = poll.frames
    }

    private companion object {
        const val STREAK = 2
    }
}
