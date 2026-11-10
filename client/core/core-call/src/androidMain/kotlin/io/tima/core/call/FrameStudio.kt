package io.tima.core.call

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.opengl.EGL14
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import livekit.org.webrtc.GlShader
import livekit.org.webrtc.GlTextureFrameBuffer
import livekit.org.webrtc.GlUtil
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.TextureBufferImpl
import livekit.org.webrtc.VideoFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot
import kotlin.math.max

/**
 * Сборка своего кадра на видеокарте (ПЛАН-(ФЗ) ФЗ1): обрезка и поле за краем (ЗН5), «красота», цвет, изменение лица,
 * макияж — **до отправки**: собеседник видит то же, что своё окошко (решение 5).
 *
 * ── ГДЕ ЭТО КРУТИТСЯ ──────────────────────────────────────────────────────────
 *
 * В обработчике кадров источника видео, то есть **на потоке камеры**: у него текущий контекст OpenGL того же семейства,
 * что у кодера (общий EglBase), и текстура камеры доступна прямо здесь. Кадр не читается в память — проход за проходом
 * по текстурам, наружу уходит своя текстура RGB в `TextureBufferImpl`. Контекста нет, кадр не текстура или любой сбой —
 * [process] отдаёт `null`, и обработчик шлёт кадр прежним путём: звонок важнее фильтра.
 *
 * Выходные текстуры — по кругу из [POOL]: кодер и своё окошко держат кадр, пока рисуют, и писать в занятую нельзя.
 * Все заняты — кадр уходит без фильтров (счётчик в журнале).
 */
internal class FrameStudio(private val assets: AssetManager) {
    private var context: android.opengl.EGLContext? = null
    private var broken = false

    private var copyOes: GlShader? = null
    private var copy: GlShader? = null
    private var blur: GlShader? = null
    private var beauty: GlShader? = null
    private var color: GlShader? = null
    private var reshape: GlShader? = null
    private var makeup: GlShader? = null

    private var a: GlTextureFrameBuffer? = null
    private var b: GlTextureFrameBuffer? = null
    private var q1: GlTextureFrameBuffer? = null
    private var q2: GlTextureFrameBuffer? = null
    private var small: GlTextureFrameBuffer? = null

    private class Out(val fb: GlTextureFrameBuffer, val busy: AtomicBoolean = AtomicBoolean(false))

    private val outs = mutableListOf<Out>()
    private val textures = mutableMapOf<String, Int>()

    // ── Замер (ФЗ0) ──
    private var frames = 0L
    private var gpuNs = 0L
    private var skipped = 0L
    private var since = System.nanoTime()
    private var lastReport = 0L

    private val identity = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
    private val quad = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val quadTex = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    /**
     * Собрать кадр. `null` — собрать нельзя (нет контекста, не текстура, все выходы заняты, сбой): пусть уйдёт прежним
     * путём. [faces] — где взять точки лица и куда отдать кадр для их поиска.
     */
    fun process(frame: VideoFrame, crop: CameraCrop, filters: CallFilters, faces: FaceWatch?): VideoFrame? {
        if (broken) return null
        val input = frame.buffer as? TextureBufferImpl ?: return null
        val current = EGL14.eglGetCurrentContext()
        if (current == null || current == EGL14.EGL_NO_CONTEXT) return null
        return try {
            if (current != context) {
                // Новый контекст (камеру открыли заново) — всё прежнее принадлежит старому и здесь недействительно.
                forget()
                context = current
            }
            val started = System.nanoTime()
            val out = render(frame, input, crop, filters, faces)
            if (out == null) skipped++ else {
                frames++
                gpuNs += System.nanoTime() - started
            }
            report(filters, input.width, input.height, faces)
            out
        } catch (e: Throwable) {
            broken = true
            Journal.trouble(LogCode.CALL, "фильтры: сборка кадра на видеокарте не удалась — кадр уходит без них", "причина" to (e.message ?: e::class.simpleName))
            null
        }
    }

    private fun render(frame: VideoFrame, input: TextureBufferImpl, crop: CameraCrop, f: CallFilters, faces: FaceWatch?): VideoFrame? {
        val w = input.width
        val h = input.height
        val out = outs.firstOrNull { it.fb.width == w && it.fb.height == h && it.busy.compareAndSet(false, true) }
            ?: if (outs.size < POOL) {
                Out(GlTextureFrameBuffer(GLES20.GL_RGBA).apply { setSize(w, h) }).also { it.busy.set(true); outs += it }
            } else {
                // Размер сменился (адаптация WebRTC) — освободившиеся выходы прежнего размера переделываются.
                outs.firstOrNull { it.busy.compareAndSet(false, true) }?.also { it.fb.setSize(w, h) } ?: return null
            }
        val bufA = fb(a, w, h).also { a = it }
        val bufB = fb(b, w, h).also { b = it }

        // ── Проход 0: кадр камеры → A, с обрезкой и полем за краем ──
        drawInput(input, crop, frame.rotation, bufA, w, h)

        // ── Точки лица: маленький прямой кадр — тому, кто их ищет ──
        if (f.needsFace && faces != null) faces.offer { width, height, sink -> readUpright(bufA, frame.rotation, width, height, sink) }
        val points = if (f.needsFace) faces?.points(frame.rotation) else null

        // ── Проходы по порядку: каждый пишет в следующий буфер, последний — в выходной ──
        val passes = mutableListOf<(GlTextureFrameBuffer, GlTextureFrameBuffer) -> Unit>()
        if (f.beauty) passes += { src, dst -> drawBeauty(src, dst, w, h, f, points) }
        if (f.colour) passes += { src, dst -> drawColor(src, dst, f) }
        if (f.reshape && points != null) passes += { src, dst -> drawReshape(src, dst, w, h, f, points) }
        if (f.makeup && points != null) passes += { src, dst -> drawMakeup(src, dst, f, points) }
        if (passes.isEmpty()) passes += { src, dst -> drawCopy(src, dst) }
        var src = bufA
        passes.forEachIndexed { i, pass ->
            val dst = if (i == passes.lastIndex) out.fb else if (src === bufA) bufB else bufA
            pass(src, dst)
            src = dst
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        // Кодер и своё окошко читают текстуру из других потоков (общий контекст) — она должна быть готова к их чтению.
        GLES20.glFinish()
        val buffer = TextureBufferImpl(
            w, h, VideoFrame.TextureBuffer.Type.RGB, out.fb.textureId, Matrix(),
            input.toI420Handler, input.yuvConverter,
        ) { out.busy.set(false) }
        return VideoFrame(buffer, frame.rotation, frame.timestampNs)
    }

    // ── Проходы ───────────────────────────────────────────────────────────────

    private fun drawInput(input: TextureBufferImpl, crop: CameraCrop, rotation: Int, dst: GlTextureFrameBuffer, w: Int, h: Int) {
        val oes = input.type == VideoFrame.TextureBuffer.Type.OES
        val shader = if (oes) shader(::copyOes, FS_COPY_OES) else shader(::copy, FS_COPY)
        target(dst)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        // Откуда (src) и куда (dst) — в пикселях буфера, верх — ноль; дальше — в координаты OpenGL (низ — ноль).
        val (src, place) = when {
            crop.full -> CameraCrop.Region(0, 0, w, h) to CameraCrop.Region(0, 0, w, h)
            crop.beyond -> crop.cut(w, h, rotation).let { it.src to it.dst }
            else -> crop.region(w, h, rotation) to CameraCrop.Region(0, 0, w, h)
        }
        val pos = rect(place, w, h)
        val tex = floats(
            src.left / w.toFloat(), 1f - (src.top + src.height) / h.toFloat(),
            (src.left + src.width) / w.toFloat(), 1f - (src.top + src.height) / h.toFloat(),
            src.left / w.toFloat(), 1f - src.top / h.toFloat(),
            (src.left + src.width) / w.toFloat(), 1f - src.top / h.toFloat(),
        )
        shader.useProgram()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(if (oes) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D, input.textureId)
        GLES20.glUniform1i(shader.getUniformLocation("uTex"), 0)
        GLES20.glUniformMatrix4fv(shader.getUniformLocation("uTexMatrix"), 1, false, RendererCommon.convertMatrixFromAndroidGraphicsMatrix(input.transformMatrix), 0)
        drawStrip(shader, pos, tex)
        GLES20.glBindTexture(if (oes) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D, 0)
    }

    private fun drawCopy(src: GlTextureFrameBuffer, dst: GlTextureFrameBuffer) {
        val shader = shader(::copy, FS_COPY)
        target(dst)
        shader.useProgram()
        bind(shader, "uTex", 0, src.textureId)
        GLES20.glUniformMatrix4fv(shader.getUniformLocation("uTexMatrix"), 1, false, identity, 0)
        drawStrip(shader, quad, quadTex)
    }

    private fun drawBeauty(src: GlTextureFrameBuffer, dst: GlTextureFrameBuffer, w: Int, h: Int, f: CallFilters, points: FloatArray?) {
        // Среднее — на четверти размера, двумя проходами коробочного размытия (как gpupixel: радиус 4, шаг 4).
        val qw = max(2, w / 4)
        val qh = max(2, h / 4)
        val m1 = fb(q1, qw, qh).also { q1 = it }
        val m2 = fb(q2, qw, qh).also { q2 = it }
        val blurShader = shader(::blur, FS_BOX_BLUR)
        target(m1)
        blurShader.useProgram()
        bind(blurShader, "uTex", 0, src.textureId)
        GLES20.glUniformMatrix4fv(blurShader.getUniformLocation("uTexMatrix"), 1, false, identity, 0)
        GLES20.glUniform2f(blurShader.getUniformLocation("uStep"), 4f / w, 0f)
        drawStrip(blurShader, quad, quadTex)
        target(m2)
        bind(blurShader, "uTex", 0, m1.textureId)
        GLES20.glUniform2f(blurShader.getUniformLocation("uStep"), 0f, 4f / h)
        drawStrip(blurShader, quad, quadTex)

        val shader = shader(::beauty, FS_BEAUTY)
        target(dst)
        shader.useProgram()
        bind(shader, "uTex", 0, src.textureId)
        bind(shader, "uMean", 1, m2.textureId)
        bind(shader, "uLookGray", 2, texture("lookup_gray.png"))
        bind(shader, "uLookOrigin", 3, texture("lookup_origin.png"))
        bind(shader, "uLookSkin", 4, texture("lookup_skin.png"))
        bind(shader, "uLookCustom", 5, texture("lookup_light.png"))
        GLES20.glUniformMatrix4fv(shader.getUniformLocation("uTexMatrix"), 1, false, identity, 0)
        GLES20.glUniform2f(shader.getUniformLocation("uTexel"), 1f / w, 1f / h)
        GLES20.glUniform1f(shader.getUniformLocation("uSmooth"), f.smooth / 100f)
        GLES20.glUniform1f(shader.getUniformLocation("uSharpen"), f.sharpen / 100f)
        GLES20.glUniform1f(shader.getUniformLocation("uWhiten"), f.whiten / 100f)
        GLES20.glUniform1f(shader.getUniformLocation("uDelta"), 14f)
        GLES20.glUniform1f(shader.getUniformLocation("uAspect"), w.toFloat() / h)
        val z = if (f.wrinkles && points != null) WrinkleZones.of(points, w, h, f) else WrinkleZones.NONE
        GLES20.glUniform4fv(shader.getUniformLocation("uZone"), 8, z.zones, 0)
        GLES20.glUniform2fv(shader.getUniformLocation("uZoneAxis"), 8, z.axes, 0)
        GLES20.glUniform1fv(shader.getUniformLocation("uZoneK"), 8, z.strengths, 0)
        drawStrip(shader, quad, quadTex)
    }

    private fun drawColor(src: GlTextureFrameBuffer, dst: GlTextureFrameBuffer, f: CallFilters) {
        val shader = shader(::color, FS_COLOR)
        target(dst)
        shader.useProgram()
        bind(shader, "uTex", 0, src.textureId)
        val lut = when (f.look) {
            ColorLook.Light -> "lookup_light.png"
            ColorLook.Custom -> "lookup_custom.png"
            ColorLook.None -> null
        }
        bind(shader, "uLut", 1, lut?.let { texture(it) } ?: 0)
        GLES20.glUniformMatrix4fv(shader.getUniformLocation("uTexMatrix"), 1, false, identity, 0)
        GLES20.glUniform1f(shader.getUniformLocation("uBright"), f.brightness / 100f)
        GLES20.glUniform1f(shader.getUniformLocation("uContrast"), f.contrast / 100f)
        GLES20.glUniform1f(shader.getUniformLocation("uSat"), f.saturation / 100f)
        GLES20.glUniform1f(shader.getUniformLocation("uWarm"), f.warmth / 100f)
        GLES20.glUniform1f(shader.getUniformLocation("uLutMix"), if (lut == null) 0f else f.lookStrength / 100f)
        drawStrip(shader, quad, quadTex)
    }

    private fun drawReshape(src: GlTextureFrameBuffer, dst: GlTextureFrameBuffer, w: Int, h: Int, f: CallFilters, points: FloatArray) {
        val shader = shader(::reshape, FS_RESHAPE)
        target(dst)
        shader.useProgram()
        bind(shader, "uTex", 0, src.textureId)
        GLES20.glUniformMatrix4fv(shader.getUniformLocation("uTexMatrix"), 1, false, identity, 0)
        val p = FloatArray(RESHAPE_POINTS.size * 2)
        RESHAPE_POINTS.forEachIndexed { i, n ->
            p[i * 2] = points[n * 2]
            p[i * 2 + 1] = points[n * 2 + 1]
        }
        GLES20.glUniform2fv(shader.getUniformLocation("uP"), RESHAPE_POINTS.size, p, 0)
        GLES20.glUniform1f(shader.getUniformLocation("uAspect"), w.toFloat() / h)
        // Силы — как у gpupixel: тоньше до 0,05, глаза до 0,2 на всём ползунке.
        GLES20.glUniform1f(shader.getUniformLocation("uThin"), f.thin / 100f * 0.05f)
        GLES20.glUniform1f(shader.getUniformLocation("uEyes"), f.eyes / 100f * 0.2f)
        drawStrip(shader, quad, quadTex)
    }

    private fun drawMakeup(src: GlTextureFrameBuffer, dst: GlTextureFrameBuffer, f: CallFilters, points: FloatArray) {
        drawCopy(src, dst)
        val shader = shader(::makeup, FS_MAKEUP, VS_MAKEUP)
        shader.useProgram()
        bind(shader, "uTex", 0, src.textureId)
        // Сетка — точки лица в координатах экрана прохода (−1…1).
        val pos = FloatArray(FACE_POINTS * 2) { i -> points[i] * 2f - 1f }
        val indices = ShortBuffer.wrap(FACE_TRIANGLES).let { short ->
            ByteBuffer.allocateDirect(FACE_TRIANGLES.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().put(short).also { it.position(0) }
        }
        if (f.lipstick > 0) drawMesh(shader, pos, "mouth.png", LIPSTICK_BOUNDS, f.lipstick / 100f, indices)
        if (f.blush > 0) drawMesh(shader, pos, "blusher.png", BLUSH_BOUNDS, f.blush / 100f, indices)
    }

    private fun drawMesh(shader: GlShader, pos: FloatArray, image: String, bounds: FloatArray, intensity: Float, indices: ShortBuffer) {
        val tex = FloatArray(FACE_POINTS * 2) { i ->
            val v = FACE_TEMPLATE[i] * 1280f
            if (i % 2 == 0) (v - bounds[0]) / bounds[2] else (v - bounds[1]) / bounds[3]
        }
        bind(shader, "uMakeup", 1, texture(image))
        GLES20.glUniform1f(shader.getUniformLocation("uIntensity"), intensity)
        shader.setVertexAttribArray("aPos", 2, floats(*pos))
        shader.setVertexAttribArray("aTex", 2, floats(*tex))
        indices.position(0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, FACE_TRIANGLES.size, GLES20.GL_UNSIGNED_SHORT, indices)
    }

    /**
     * Маленький прямой кадр для поиска лица: A повёрнут на [rotation], как его видит человек, сжат до [width]×[height]
     * и прочитан строками сверху вниз (для этого рисуется перевёрнутым: первая прочитанная строка — верх).
     */
    private fun readUpright(src: GlTextureFrameBuffer, rotation: Int, width: Int, height: Int, sink: ByteBuffer) {
        val dst = fb(small, width, height).also { small = it }
        val shader = shader(::copy, FS_COPY)
        target(dst)
        shader.useProgram()
        bind(shader, "uTex", 0, src.textureId)
        GLES20.glUniformMatrix4fv(shader.getUniformLocation("uTexMatrix"), 1, false, identity, 0)
        // Углы маленького кадра (u — вправо, v — вниз, как у картинки) → откуда брать в A.
        val corners = listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)
        val tex = FloatArray(8)
        corners.forEachIndexed { i, (u, v) ->
            val (bx, by) = FacePoints.uprightToBuffer(u, v, rotation)
            tex[i * 2] = bx
            tex[i * 2 + 1] = 1f - by
        }
        drawStrip(shader, quad, floats(*tex))
        sink.clear()
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, sink)
        sink.rewind()
    }

    // ── Помощники ─────────────────────────────────────────────────────────────

    private fun target(fb: GlTextureFrameBuffer) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fb.frameBufferId)
        GLES20.glViewport(0, 0, fb.width, fb.height)
    }

    private fun bind(shader: GlShader, name: String, unit: Int, texture: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(shader.getUniformLocation(name), unit)
    }

    private fun drawStrip(shader: GlShader, pos: FloatBuffer, tex: FloatBuffer) {
        shader.setVertexAttribArray("aPos", 2, pos)
        shader.setVertexAttribArray("aTex", 2, tex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun shader(slot: kotlin.reflect.KMutableProperty0<GlShader?>, fragment: String, vertex: String = VS_QUAD): GlShader =
        slot.get() ?: GlShader(vertex, fragment).also { slot.set(it) }

    private fun fb(old: GlTextureFrameBuffer?, w: Int, h: Int): GlTextureFrameBuffer {
        val fb = old ?: GlTextureFrameBuffer(GLES20.GL_RGBA)
        if (fb.width != w || fb.height != h) fb.setSize(w, h)
        return fb
    }

    /** Картинка из `assets/filters` — текстурой, один раз на контекст. */
    private fun texture(name: String): Int = textures.getOrPut(name) {
        val bitmap = assets.open("filters/$name").use { BitmapFactory.decodeStream(it) }
        val id = GlUtil.generateTexture(GLES20.GL_TEXTURE_2D)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        bitmap.recycle()
        id
    }

    private fun rect(r: CameraCrop.Region, w: Int, h: Int): FloatBuffer {
        val x0 = 2f * r.left / w - 1f
        val x1 = 2f * (r.left + r.width) / w - 1f
        val yTop = 1f - 2f * r.top / h
        val yBottom = 1f - 2f * (r.top + r.height) / h
        return floats(x0, yBottom, x1, yBottom, x0, yTop, x1, yTop)
    }

    /** Замер раз в [REPORT_MS] (ФЗ0): частота, мс на кадр, размер, точки лица. */
    private fun report(f: CallFilters, w: Int, h: Int, faces: FaceWatch?) {
        val now = System.nanoTime()
        if (now - lastReport < REPORT_MS * 1_000_000L) return
        lastReport = now
        val seconds = (now - since) / 1e9
        Journal.note(
            LogCode.CALL, "фильтры: замер",
            "к/с" to if (seconds > 0) ((frames / seconds) * 10).toLong() / 10.0 else 0.0,
            "мс на кадр" to if (frames > 0) (gpuNs / frames / 10_000) / 100.0 else 0.0,
            "кадр" to "${w}×$h",
            "без фильтра" to skipped,
            "что" to f.describe(),
            *(faces?.stats() ?: emptyArray()),
        )
        frames = 0
        gpuNs = 0
        skipped = 0
        since = now
    }

    /** Всё, что принадлежало прежнему контексту, — забыть (удалять нечем: контекста уже нет). */
    private fun forget() {
        copyOes = null; copy = null; blur = null; beauty = null; color = null; reshape = null; makeup = null
        a = null; b = null; q1 = null; q2 = null; small = null
        outs.clear()
        textures.clear()
    }

    companion object {
        /** Сколько выходных текстур по кругу: кодер, своё окошко и запас на следующий кадр. */
        private const val POOL = 4

        private const val REPORT_MS = 10_000L

        const val FACE_POINTS = FacePoints.FACE_POINTS

        /** Какие точки нужны «тоньше лицо» и «глаза» — в порядке `uP` шейдера `FS_RESHAPE`. */
        private val RESHAPE_POINTS = intArrayOf(3, 29, 7, 25, 10, 22, 14, 18, 16, 44, 45, 46, 49, 74, 72, 77, 75)

        /** Где картинка помады и румян лежит на шаблоне лица 1280×1280 (gpupixel `LipstickFilter`, `BlusherFilter`). */
        private val LIPSTICK_BOUNDS = floatArrayOf(502.5f, 710f, 262.5f, 167.5f)
        private val BLUSH_BOUNDS = floatArrayOf(395f, 520f, 489f, 209f)

        private fun floats(vararg v: Float): FloatBuffer = GlUtil.createFloatBuffer(v)
    }
}
