package io.tima.core.call

import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import org.lwjgl.system.MemoryUtil
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Фильтры своего кадра на ПК (ПЛАН-(ФЗ) ФЗ15, заказчик 2026-11-12: «для ПК — LWJGL»): те же шейдеры и проходы, что на
 * телефоне (`FrameStudio`), — OpenGL без окна (скрытое окно GLFW только ради контекста).
 *
 * ── ЧЕМ ОТЛИЧАЕТСЯ ОТ ТЕЛЕФОНА ─────────────────────────────────────────────────
 *
 * На ПК кадр камеры — RGB в памяти (openpnp-capture → `livekit_ffi`), видеокарты в цепочке нет. Поэтому кадр
 * загружается текстурой, проходит те же проходы и читается обратно RGB — и уходит дальше тем же путём, что и без
 * фильтров. Кадр хранится **сверху вниз** (строка 0 — верх, текстурная t = 0 — верх): так и загружается, и читается,
 * без переворотов; точки лица — тоже сверху вниз. Шейдеры от этого не зависят: всё, что в них «вверх», считается по
 * точкам (зоны морщин — от рта к глазам).
 *
 * Весь OpenGL — на одном своём потоке ([gl]). Нет OpenGL 3.0 (виртуальная машина, удалённый стол, старый драйвер) или
 * любой сбой — [process] отдаёт `null`, кадр уходит без фильтров: звонок важнее фильтра.
 */
class DesktopFilters(dir: File?) {
    private val gl = Executors.newSingleThreadExecutor { Thread(it, "tima-gl").apply { isDaemon = true } }
    private val faces = DesktopFaces(dir)

    private var window = 0L
    private var tried = false

    /** OpenGL не поднялся или сборка кадра упала — фильтров на этом ПК нет до перезапуска. */
    @Volatile
    var broken: Boolean = false
        private set

    private class Program(val id: Int) {
        private val uniforms = HashMap<String, Int>()
        private val attributes = HashMap<String, Int>()
        fun u(name: String): Int = uniforms.getOrPut(name) { GL20.glGetUniformLocation(id, name) }
        fun a(name: String): Int = attributes.getOrPut(name) { GL20.glGetAttribLocation(id, name) }
    }

    private class Fb(var width: Int = 0, var height: Int = 0) {
        var texture = 0
        var frame = 0
    }

    private val programs = HashMap<String, Program>()
    private val textures = HashMap<String, Int>()
    private val textureSizes = HashMap<String, Pair<Int, Int>>()
    private var input = 0
    private val a = Fb()
    private val b = Fb()
    private val out = Fb()
    private val q1 = Fb()
    private val q2 = Fb()
    private val small = Fb()
    private var result: ByteBuffer = ByteBuffer.allocateDirect(4)

    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    private val quad = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val quadTex = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    private val triangles: ShortBuffer = ByteBuffer.allocateDirect(FACE_TRIANGLES.size * 2).order(ByteOrder.nativeOrder())
        .asShortBuffer().put(FACE_TRIANGLES).also { it.position(0) }

    // ── Замер ──
    private var frames = 0L
    private var spentNs = 0L
    private var since = System.nanoTime()
    private var lastReport = 0L

    /** Есть ли на этом ПК OpenGL для фильтров: первый вопрос поднимает контекст. */
    val available: Boolean
        get() = !broken && runCatching { gl.submit<Boolean> { start() }.get() }.getOrDefault(false)

    /**
     * Собрать кадр RGB [width]×[height] из [src] (строки по [rowPixels] пикселей, сверху вниз). Вернёт RGB того же
     * размера, плотно (строка — `width × 3`), действительный до следующего вызова; `null` — уйти без фильтров.
     */
    fun process(src: ByteBuffer, width: Int, height: Int, rowPixels: Int, filters: CallFilters): ByteBuffer? {
        if (broken || !filters.active) return null
        return try {
            gl.submit<ByteBuffer?> {
                if (!start()) return@submit null
                val started = System.nanoTime()
                val made = render(src, width, height, rowPixels, filters)
                frames++
                spentNs += System.nanoTime() - started
                report(filters, width, height)
                made
            }.get()
        } catch (e: Throwable) {
            broken = true
            Journal.trouble(LogCode.CALL, "фильтры ПК: сборка кадра не удалась — кадр уходит без них", "причина" to (e.cause?.message ?: e.message ?: e::class.simpleName))
            null
        }
    }

    /** Нашлось ли лицо — для проверки и журнала. */
    internal fun faceFound(): Boolean = faces.points() != null

    fun close() {
        faces.close()
        gl.execute {
            if (window != 0L) {
                runCatching { GLFW.glfwDestroyWindow(window) }
                window = 0L
            }
        }
    }

    // ── Контекст ─────────────────────────────────────────────────────────────────

    private fun start(): Boolean {
        if (window != 0L) return true
        if (tried) return false
        tried = true
        return runCatching {
            check(GLFW.glfwInit()) { "GLFW не запустился" }
            GLFW.glfwDefaultWindowHints()
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
            val made = GLFW.glfwCreateWindow(16, 16, "tima-filters", MemoryUtil.NULL, MemoryUtil.NULL)
            check(made != MemoryUtil.NULL) { "окно OpenGL не создалось" }
            GLFW.glfwMakeContextCurrent(made)
            val caps = GL.createCapabilities()
            check(caps.OpenGL30) { "нет OpenGL 3.0" }
            window = made
            Journal.note(
                LogCode.CALL, "фильтры ПК: OpenGL готов",
                "версия" to GL11.glGetString(GL11.GL_VERSION), "видеокарта" to GL11.glGetString(GL11.GL_RENDERER),
            )
            true
        }.getOrElse {
            broken = true
            Journal.trouble(LogCode.CALL, "фильтры ПК: OpenGL недоступен — фильтров нет", "причина" to (it.message ?: it::class.simpleName))
            false
        }
    }

    // ── Кадр ─────────────────────────────────────────────────────────────────────

    private fun render(src: ByteBuffer, w: Int, h: Int, rowPixels: Int, f: CallFilters): ByteBuffer {
        if (input == 0) input = GL11.glGenTextures()
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, input)
        params()
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1)
        GL11.glPixelStorei(GL12.GL_UNPACK_ROW_LENGTH, rowPixels)
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB8, w, h, 0, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, src)
        GL11.glPixelStorei(GL12.GL_UNPACK_ROW_LENGTH, 0)

        fb(a, w, h)
        fb(b, w, h)
        fb(out, w, h)
        drawCopy(input, a)

        if (f.needsFace) faces.offer(w, h) { sw, sh, sink -> readSmall(a, sw, sh, sink) }
        val points = if (f.needsFace) faces.points() else null

        val passes = mutableListOf<(Fb, Fb) -> Unit>()
        if (f.beauty) passes += { s, d -> drawBeauty(s, d, w, h, f, points) }
        if (f.colour) passes += { s, d -> drawColor(s, d, f) }
        if (f.reshape && points != null) passes += { s, d -> drawReshape(s, d, w, h, f, points) }
        if (f.makeup && points != null) passes += { s, d -> drawMakeup(s, d, f, points) }
        if (passes.isEmpty()) passes += { s, d -> drawCopy(s.texture, d) }
        var s = a
        passes.forEachIndexed { i, pass ->
            val d = if (i == passes.lastIndex) out else if (s === a) b else a
            pass(s, d)
            s = d
        }
        target(out)
        if (result.capacity() != w * h * 3) result = ByteBuffer.allocateDirect(w * h * 3)
        result.clear()
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1)
        GL11.glReadPixels(0, 0, w, h, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, result)
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
        result.rewind()
        return result
    }

    private fun drawCopy(texture: Int, dst: Fb) {
        val p = program("copy", FS_COPY)
        target(dst)
        GL20.glUseProgram(p.id)
        bind(p, "uTex", 0, texture)
        GL20.glUniformMatrix4fv(p.u("uTexMatrix"), false, identity)
        strip(p, quad, quadTex)
    }

    private fun drawBeauty(src: Fb, dst: Fb, w: Int, h: Int, f: CallFilters, points: FloatArray?) {
        val qw = max(2, w / 4)
        val qh = max(2, h / 4)
        fb(q1, qw, qh)
        fb(q2, qw, qh)
        val blur = program("blur", FS_BOX_BLUR)
        target(q1)
        GL20.glUseProgram(blur.id)
        bind(blur, "uTex", 0, src.texture)
        GL20.glUniformMatrix4fv(blur.u("uTexMatrix"), false, identity)
        GL20.glUniform2f(blur.u("uStep"), 4f / w, 0f)
        strip(blur, quad, quadTex)
        target(q2)
        bind(blur, "uTex", 0, q1.texture)
        GL20.glUniform2f(blur.u("uStep"), 0f, 4f / h)
        strip(blur, quad, quadTex)

        val p = program("beauty", FS_BEAUTY)
        target(dst)
        GL20.glUseProgram(p.id)
        bind(p, "uTex", 0, src.texture)
        bind(p, "uMean", 1, q2.texture)
        bind(p, "uLookGray", 2, image("lookup_gray.png"))
        bind(p, "uLookOrigin", 3, image("lookup_origin.png"))
        bind(p, "uLookSkin", 4, image("lookup_skin.png"))
        bind(p, "uLookCustom", 5, image("lookup_light.png"))
        GL20.glUniformMatrix4fv(p.u("uTexMatrix"), false, identity)
        GL20.glUniform2f(p.u("uTexel"), 1f / w, 1f / h)
        GL20.glUniform1f(p.u("uSmooth"), f.smooth / 100f)
        GL20.glUniform1f(p.u("uSharpen"), f.sharpen / 100f)
        GL20.glUniform1f(p.u("uWhiten"), f.whiten / 100f)
        GL20.glUniform1f(p.u("uDelta"), 14f)
        GL20.glUniform1f(p.u("uAspect"), w.toFloat() / h)
        val z = if (f.wrinkles && points != null) WrinkleZones.of(points, w, h, f) else WrinkleZones.NONE
        GL20.glUniform4fv(p.u("uZone"), z.zones)
        GL20.glUniform2fv(p.u("uZoneAxis"), z.axes)
        GL20.glUniform1fv(p.u("uZoneK"), z.strengths)
        GL20.glUniform4fv(p.u("uEye"), z.eyes)
        GL20.glUniform2f(p.u("uEyeAxis"), z.eyeAxis[0], z.eyeAxis[1])
        strip(p, quad, quadTex)
    }

    private fun drawColor(src: Fb, dst: Fb, f: CallFilters) {
        val p = program("color", FS_COLOR)
        target(dst)
        GL20.glUseProgram(p.id)
        bind(p, "uTex", 0, src.texture)
        val lut = when (f.look) {
            ColorLook.Light -> "lookup_light.png"
            ColorLook.Custom -> "lookup_custom.png"
            ColorLook.None -> null
        }
        bind(p, "uLut", 1, lut?.let { image(it) } ?: 0)
        GL20.glUniformMatrix4fv(p.u("uTexMatrix"), false, identity)
        GL20.glUniform1f(p.u("uBright"), f.brightness / 100f)
        GL20.glUniform1f(p.u("uContrast"), f.contrast / 100f)
        GL20.glUniform1f(p.u("uSat"), f.saturation / 100f)
        GL20.glUniform1f(p.u("uWarm"), f.warmth / 100f)
        GL20.glUniform1f(p.u("uLutMix"), if (lut == null) 0f else f.lookStrength / 100f)
        strip(p, quad, quadTex)
    }

    private fun drawReshape(src: Fb, dst: Fb, w: Int, h: Int, f: CallFilters, points: FloatArray) {
        val p = program("reshape", FS_RESHAPE)
        target(dst)
        GL20.glUseProgram(p.id)
        bind(p, "uTex", 0, src.texture)
        GL20.glUniformMatrix4fv(p.u("uTexMatrix"), false, identity)
        val pts = FloatArray(RESHAPE_POINTS.size * 2)
        RESHAPE_POINTS.forEachIndexed { i, n ->
            pts[i * 2] = points[n * 2]
            pts[i * 2 + 1] = points[n * 2 + 1]
        }
        GL20.glUniform2fv(p.u("uP"), pts)
        GL20.glUniform1f(p.u("uAspect"), w.toFloat() / h)
        GL20.glUniform1f(p.u("uThin"), f.thinDelta)
        GL20.glUniform1f(p.u("uEyes"), f.eyes / 100f * 0.2f)
        strip(p, quad, quadTex)
    }

    private fun drawMakeup(src: Fb, dst: Fb, f: CallFilters, points: FloatArray) {
        drawCopy(src.texture, dst)
        val p = program("makeup", FS_MAKEUP, VS_MAKEUP)
        GL20.glUseProgram(p.id)
        bind(p, "uTex", 0, src.texture)
        val pos = FloatArray(FacePoints.FACE_POINTS * 2) { i -> points[i] * 2f - 1f }
        if (f.lipstick > 0) mesh(p, pos, f.lipColor.image, LIPSTICK_BOUNDS, f.lipstick / 100f, LIPSTICK_SOFT)
        if (f.blush > 0) mesh(p, pos, "blusher.png", BLUSH_BOUNDS, f.blush / 100f, 0f)
    }

    private fun mesh(p: Program, pos: FloatArray, image: String, bounds: FloatArray, intensity: Float, soft: Float) {
        val tex = FloatArray(FacePoints.FACE_POINTS * 2) { i ->
            val v = FACE_TEMPLATE[i] * 1280f
            if (i % 2 == 0) (v - bounds[0]) / bounds[2] else (v - bounds[1]) / bounds[3]
        }
        bind(p, "uMakeup", 1, image(image))
        val (iw, ih) = textureSizes[image] ?: (1 to 1)
        GL20.glUniform2f(p.u("uSoft"), soft / iw, soft / ih)
        GL20.glUniform1f(p.u("uIntensity"), intensity)
        attribute(p, "aPos", floats(*pos))
        attribute(p, "aTex", floats(*tex))
        triangles.position(0)
        GL11.glDrawElements(GL11.GL_TRIANGLES, triangles)
    }

    /** Маленький кадр для поиска лица: весь кадр, сжатый до [width]×[height], RGBA сверху вниз. */
    private fun readSmall(src: Fb, width: Int, height: Int, sink: ByteBuffer) {
        fb(small, width, height)
        drawCopy(src.texture, small)
        sink.clear()
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1)
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, sink)
        sink.rewind()
    }

    // ── Помощники ────────────────────────────────────────────────────────────────

    private fun params() {
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
    }

    private fun fb(fb: Fb, w: Int, h: Int) {
        if (fb.texture == 0) {
            fb.texture = GL11.glGenTextures()
            fb.frame = GL30.glGenFramebuffers()
        }
        if (fb.width == w && fb.height == h) return
        fb.width = w
        fb.height = h
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, fb.texture)
        params()
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null as ByteBuffer?)
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fb.frame)
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, fb.texture, 0)
        val status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
        check(status == GL30.GL_FRAMEBUFFER_COMPLETE) { "буфер кадра не собрался: $status" }
    }

    private fun target(fb: Fb) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fb.frame)
        GL11.glViewport(0, 0, fb.width, fb.height)
    }

    private fun bind(p: Program, name: String, unit: Int, texture: Int) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
        GL20.glUniform1i(p.u(name), unit)
    }

    private fun strip(p: Program, pos: FloatBuffer, tex: FloatBuffer) {
        attribute(p, "aPos", pos)
        attribute(p, "aTex", tex)
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun attribute(p: Program, name: String, data: FloatBuffer) {
        val at = p.a(name)
        if (at < 0) return
        data.position(0)
        GL20.glEnableVertexAttribArray(at)
        GL20.glVertexAttribPointer(at, 2, GL11.GL_FLOAT, false, 0, data)
    }

    /** Шейдер телефона — для ПК: GLSL 1.20, точность — пустыми словами (на ПК её нет). */
    private fun program(name: String, fragment: String, vertex: String = VS_QUAD): Program = programs.getOrPut(name) {
        fun desktop(src: String) = "#version 120\n#define lowp\n#define mediump\n#define highp\n" +
            src.lines().filterNot { it.trim().startsWith("precision ") }.joinToString("\n")
        fun compile(type: Int, src: String): Int {
            val id = GL20.glCreateShader(type)
            GL20.glShaderSource(id, desktop(src))
            GL20.glCompileShader(id)
            check(GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) != 0) { "шейдер $name: " + GL20.glGetShaderInfoLog(id) }
            return id
        }
        val id = GL20.glCreateProgram()
        GL20.glAttachShader(id, compile(GL20.GL_VERTEX_SHADER, vertex))
        GL20.glAttachShader(id, compile(GL20.GL_FRAGMENT_SHADER, fragment))
        GL20.glLinkProgram(id)
        check(GL20.glGetProgrami(id, GL20.GL_LINK_STATUS) != 0) { "программа $name: " + GL20.glGetProgramInfoLog(id) }
        Program(id)
    }

    /** Картинка фильтров из ресурсов (`filters/…`, те же, что на телефоне) — текстурой, сверху вниз. */
    private fun image(name: String): Int = textures.getOrPut(name) {
        val picture = DesktopFilters::class.java.getResourceAsStream("/filters/$name")?.use { javax.imageio.ImageIO.read(it) }
            ?: error("нет картинки filters/$name")
        val w = picture.width
        val h = picture.height
        textureSizes[name] = w to h
        val argb = picture.getRGB(0, 0, w, h, null, 0, w)
        val bytes = ByteBuffer.allocateDirect(w * h * 4)
        for (px in argb) {
            bytes.put((px shr 16).toByte()).put((px shr 8).toByte()).put(px.toByte()).put((px ushr 24).toByte())
        }
        bytes.flip()
        val id = GL11.glGenTextures()
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id)
        params()
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1)
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, bytes)
        id
    }

    private fun report(f: CallFilters, w: Int, h: Int) {
        val now = System.nanoTime()
        if (now - lastReport < REPORT_MS * 1_000_000L) return
        lastReport = now
        val seconds = (now - since) / 1e9
        Journal.note(
            LogCode.CALL, "фильтры ПК: замер",
            "к/с" to if (seconds > 0) ((frames / seconds) * 10).toLong() / 10.0 else 0.0,
            "мс на кадр" to if (frames > 0) (spentNs / frames / 10_000) / 100.0 else 0.0,
            "кадр" to "${w}×$h",
            "что" to f.describe(),
            *faces.stats(),
        )
        frames = 0
        spentNs = 0
        since = now
    }

    private companion object {
        const val REPORT_MS = 10_000L
        val RESHAPE_POINTS = intArrayOf(3, 29, 7, 25, 10, 22, 14, 18, 16, 44, 45, 46, 49, 74, 72, 77, 75)
        val LIPSTICK_BOUNDS = floatArrayOf(502.5f, 710f, 262.5f, 167.5f)
        val BLUSH_BOUNDS = floatArrayOf(395f, 520f, 489f, 209f)
        const val LIPSTICK_SOFT = 2.5f

        fun floats(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(v).also { it.position(0) }
    }
}
