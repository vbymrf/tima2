package io.tima.core.call

import android.content.Context
import android.view.View
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack

/**
 * Android: дорожка плюс комната, которая умеет завести под неё поверхность.
 *
 * **Комната нужна вместе с дорожкой.** `Room.initVideoRenderer` отдаёт поверхности
 * контекст EGL — общий на всю комнату. Без него отрисовщик показывает чёрное и молчит:
 * ошибки нет, картинки тоже.
 *
 * ── ПОЧЕМУ `TextureViewRenderer`, А НЕ `SurfaceViewRenderer` ────────────────
 *
 * Сначала стоял `SurfaceViewRenderer`, и он принёс две беды сразу (живой прогон
 * 2026-09-20):
 *
 * 1. **Кнопки пропадали.** `SurfaceView` — отдельный слой композитора, а не часть
 *    отрисовки окна: система пробивает под него дыру, и всё, что приложение рисует в
 *    этих границах, теряется. `setZOrderMediaOverlay` порядок слоёв поправил, но дыру
 *    не убрал — «Завершить» так и не появлялась, пока не скроешь видео.
 * 2. **Изображение полосило** на Samsung: слой композитора обновляется не в такт с окном.
 *
 * `TextureViewRenderer` — обычный `View` внутри окна. Кадры идут через отрисовку окна,
 * поэтому дыры нет, порядок с кнопками обычный, разрыва картинки нет. Платим чуть
 * большим расходом на отрисовку — и это дешевле, чем интерфейс, который зависит от того,
 * включено ли видео.
 */
class LiveKitVideoHandle internal constructor(
    private val room: Room,
    /**
     * Сама дорожка. Видна наружу, потому что по ней сверяют: **ручка пересоздаётся
     * только когда сменилась дорожка**. Новый объект на каждое обновление состояния
     * заставлял Compose перебирать поверхность под живым звонком, и картинка замирала,
     * хотя дорожка шла (живой прогон 2026-09-20).
     */
    internal val track: VideoTrack,
) : VideoHandle {

    /** Создать поверхность и начать в неё рисовать. */
    fun open(context: Context): View {
        val view = TextureViewRenderer(context)
        room.initVideoRenderer(view)
        track.addRenderer(view)
        return view
    }

    /** Перестать рисовать и отпустить поверхность. */
    fun close(view: View) {
        if (view !is TextureViewRenderer) return
        // Снять дорожку ДО release: отпущенная поверхность, в которую ещё рисуют, роняет
        // отрисовщик. Порядок здесь важнее, чем выглядит.
        track.removeRenderer(view)
        view.release()
    }
}
