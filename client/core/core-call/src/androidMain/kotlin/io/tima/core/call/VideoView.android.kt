package io.tima.core.call

import android.content.Context
import android.view.View
import io.livekit.android.renderer.SurfaceViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack

/**
 * Android: дорожка плюс комната, которая умеет завести под неё поверхность.
 *
 * **Комната нужна вместе с дорожкой.** `Room.initVideoRenderer` отдаёт поверхности
 * контекст EGL — общий на всю комнату. Без него `SurfaceViewRenderer` показывает чёрное и
 * молчит: ошибки нет, картинки тоже.
 */
class LiveKitVideoHandle internal constructor(
    private val room: Room,
    private val track: VideoTrack,
) : VideoHandle {

    /** Создать поверхность и начать в неё рисовать. */
    fun open(context: Context): View {
        val view = SurfaceViewRenderer(context)
        // ── ПОВЕРХНОСТЬ НЕ ДОЛЖНА ЛЕЗТЬ ПОВЕРХ ОКНА ─────────────────────────
        //
        // `SurfaceViewRenderer` — это `SurfaceView`, то есть отдельный слой композитора, а
        // не часть отрисовки Compose. По умолчанию он складывается с окном так, что
        // накрывает нарисованное поверх него: кнопки и полоса событий уходили под
        // картинку (заказчик 2026-09-20, «затеняет задний фон с оповещениями и кнопками»).
        //
        // `setZOrderMediaOverlay(true)` кладёт слой **выше других поверхностей, но ниже
        // окна приложения** — ровно то, что нужно: видео под кнопками, кнопки видны.
        view.setZOrderMediaOverlay(true)
        room.initVideoRenderer(view)
        track.addRenderer(view)
        return view
    }

    /** Перестать рисовать и отпустить поверхность. */
    fun close(view: View) {
        if (view !is SurfaceViewRenderer) return
        // Снять дорожку ДО release: отпущенная поверхность, в которую ещё рисуют, роняет
        // отрисовщик. Порядок здесь важнее, чем выглядит.
        track.removeRenderer(view)
        view.release()
    }
}
