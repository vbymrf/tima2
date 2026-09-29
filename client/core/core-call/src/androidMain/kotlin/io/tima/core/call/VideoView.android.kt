package io.tima.core.call

import android.content.Context
import android.view.View
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.video.ViewVisibility
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode

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
        val view = WakingRenderer(context)
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

/**
 * Окно видео, которое **сообщает LiveKit о пробуждении экрана** (Honor, отчёт NVZL,
 * 2026-09-29).
 *
 * С Adaptive Stream LiveKit сам следит, видно ли окно видео, и невидимое просит сервер не
 * слать. Пересчитывает он видимость по перерисовке разметки, прокрутке и смене видимости
 * самого окна или его родителей. Смена видимости **окна приложения** — экран погас и
 * загорелся — в этот перечень не входит: `TextureViewRenderer` её не слушает.
 *
 * На Honor при засыпании перерисовка проходила, и видео помечалось невидимым; при
 * пробуждении перерисовки не было, пересчёта не было, и видео собеседника не возвращалось
 * до конца звонка — своё при этом уходило. Помогало только «скрыть и показать видео»:
 * окно пересоздавалось и видимость считалась заново.
 *
 * Здесь пересчёт просится явно на каждую смену видимости окна приложения. После разметки
 * (`post`), а не сразу: в момент смены прямоугольник окна может быть ещё старым.
 */
private class WakingRenderer(context: Context) : TextureViewRenderer(context) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        post { viewVisibility?.recalculate() }
    }

    /** Что последним ушло в журнал — пишется только переход, а не каждый пересчёт. */
    private var told: Boolean? = null

    /**
     * Видимость для сервера — в журнал (заказчик 2026-09-29, отчёт NVZL).
     *
     * LiveKit заводит наблюдателя видимости только для **чужого** видео и только при
     * Adaptive Stream — своя камера сюда не приходит. От «не видно» сервер перестаёт слать
     * видео, и до этой строки остановку можно было заметить лишь по тому, что строки
     * «приходящее видео» перестали появляться.
     */
    override var viewVisibility: ViewVisibility?
        get() = super.viewVisibility
        set(value) {
            super.viewVisibility = value
            value?.addObserver { _, _ ->
                val seen = value.isVisible()
                if (seen == told) return@addObserver
                told = seen
                val size = value.size()
                Journal.note(
                    LogCode.CALL,
                    "видимость для сервера",
                    "видно" to seen,
                    "окно" to "" + size.width + "×" + size.height,
                )
            }
        }
}
