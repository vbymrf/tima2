package io.tima.core.media

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.PointerByReference
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch

/**
 * WebView2 (Edge) в окне проигрывания ПК (ПЛАН-(ВМ) Р15) — напрямую по COM через JNA: компилятора
 * C++ на машине сборки нет, а нужных вызовов десяток. Загрузчик `WebView2Loader.dll` (Microsoft,
 * лицензия BSD) лежит в ресурсах; сам движок — WebView2 Runtime, он есть в Windows 10 и 11.
 *
 * Всё с WebView2 — в одном своём потоке с очередью сообщений Windows (STA): ответы WebView2 приходят
 * через неё. Окно-хозяин — окно проигрывания Compose; WebView2 — его дочернее окно на месте картинки.
 *
 * Места функций в таблицах — по `WebView2.h` пакета Microsoft.Web.WebView2 1.0.4258.31.
 */
internal class WebView2(private val userData: File, private val pageDir: File) {

    private interface Loader : Library {
        fun CreateCoreWebView2EnvironmentWithOptions(browserFolder: WString?, userDataFolder: WString?, options: Pointer?, handler: Pointer): Int
    }

    private interface QueryInterface : Callback { fun invoke(self: Pointer?, riid: Pointer?, out: Pointer?): Int }
    private interface RefCount : Callback { fun invoke(self: Pointer?): Int }
    private interface InvokeResult : Callback { fun invoke(self: Pointer?, hr: Int, result: Pointer?): Int }
    private interface InvokeEvent : Callback { fun invoke(self: Pointer?, sender: Pointer?, args: Pointer?): Int }
    private interface GetText : Callback { fun invoke(self: Pointer?, out: Pointer?): Int }
    private interface PutAny : Callback { fun invoke(self: Pointer?, value: Pointer?): Int }

    /** Сообщение страницы (`chrome.webview.postMessage`) — в поток окна. */
    var onMessage: ((String) -> Unit)? = null

    /** WebView2 не поднялся: нет Runtime, отказ COM. Причина — для журнала. */
    var onFailure: ((String) -> Unit)? = null

    private val tasks = ConcurrentLinkedQueue<() -> Unit>()
    private val keep = mutableListOf<Any>()
    @Volatile private var running = true
    private var env: Pointer? = null
    private var controller: Pointer? = null
    private var view: Pointer? = null
    private var page = 0
    private val thread = Thread(::loop, "tima-webview2").apply { isDaemon = true }

    fun start(parent: Pointer) {
        thread.start()
        post { createEnvironment(parent) }
    }

    fun post(task: () -> Unit) {
        tasks += task
    }

    private fun loop() {
        Ole32.INSTANCE.CoInitializeEx(null, COINIT_APARTMENTTHREADED)
        val msg = WinUser.MSG()
        while (running) {
            while (User32.INSTANCE.PeekMessage(msg, null, 0, 0, PM_REMOVE)) {
                User32.INSTANCE.TranslateMessage(msg)
                User32.INSTANCE.DispatchMessage(msg)
            }
            while (true) {
                val t = tasks.poll() ?: break
                runCatching(t).onFailure { onFailure?.invoke(it.message ?: it.javaClass.simpleName) }
            }
            Thread.sleep(5)
        }
        runCatching { controller?.let { call(it, CONTROLLER_CLOSE) } }
        listOfNotNull(view, controller, env).forEach { runCatching { call(it, RELEASE) } }
        Ole32.INSTANCE.CoUninitialize()
    }

    private fun createEnvironment(parent: Pointer) {
        val loader = runCatching { Native.load(loaderPath().absolutePath, Loader::class.java) }.getOrElse {
            return onFailure?.invoke("нет загрузчика WebView2: ${it.message}") ?: Unit
        }
        userData.mkdirs()
        val handler = comObject(object : InvokeResult {
            override fun invoke(self: Pointer?, hr: Int, result: Pointer?): Int {
                if (hr < 0 || result == null) {
                    onFailure?.invoke("WebView2 Runtime: 0x${hr.toUInt().toString(16)}")
                    return 0
                }
                call(result, ADD_REF)
                env = result
                createController(parent)
                return 0
            }
        })
        val hr = loader.CreateCoreWebView2EnvironmentWithOptions(null, WString(userData.absolutePath), options(), handler)
        if (hr < 0) onFailure?.invoke("WebView2 не создался: 0x${hr.toUInt().toString(16)}")
    }

    private fun createController(parent: Pointer) {
        val handler = comObject(object : InvokeResult {
            override fun invoke(self: Pointer?, hr: Int, result: Pointer?): Int {
                if (hr < 0 || result == null) {
                    onFailure?.invoke("окно WebView2: 0x${hr.toUInt().toString(16)}")
                    return 0
                }
                call(result, ADD_REF)
                controller = result
                val out = PointerByReference()
                call(result, CONTROLLER_GET_CORE_WEBVIEW2, out.pointer)
                view = out.value
                setup()
                ready.countDown()
                return 0
            }
        })
        call(env!!, ENV_CREATE_CONTROLLER, parent, handler)
    }

    private val ready = CountDownLatch(1)

    private fun setup() {
        val v = view ?: return
        val settings = PointerByReference()
        if (call(v, WV_GET_SETTINGS, settings.pointer) >= 0 && settings.value != null) {
            call(settings.value, SETTINGS_PUT_STATUS_BAR, 0)
            call(settings.value, SETTINGS_PUT_DEVTOOLS, 0)
            call(settings.value, SETTINGS_PUT_CONTEXT_MENUS, 0)
            call(settings.value, SETTINGS_PUT_ZOOM, 0)
            call(settings.value, RELEASE)
        }
        // Своя страница — с настоящего адреса https (YouTube отказывает странице без адреса).
        val v3 = PointerByReference()
        if (call(v, QUERY_INTERFACE, guid(IID_WEBVIEW2_3), v3.pointer) >= 0 && v3.value != null) {
            pageDir.mkdirs()
            call(v3.value, WV3_SET_VIRTUAL_HOST, WString(HOST), WString(pageDir.absolutePath), HOST_ACCESS_ALLOW)
            call(v3.value, RELEASE)
        }
        val handler = comObject(object : InvokeEvent {
            override fun invoke(self: Pointer?, sender: Pointer?, args: Pointer?): Int {
                if (args == null) return 0
                val out = PointerByReference()
                if (call(args, ARGS_TRY_GET_STRING, out.pointer) >= 0 && out.value != null) {
                    val text = out.value.getWideString(0)
                    Ole32.INSTANCE.CoTaskMemFree(out.value)
                    onMessage?.invoke(text)
                }
                return 0
            }
        })
        call(v, WV_ADD_WEB_MESSAGE_RECEIVED, handler, Memory(8))
        // Что просили до готовности — сейчас: место на окне и страницу.
        wantBounds?.let { (x, y, w, h) -> bounds(x, y, w, h) }
        wantHtml?.let { navigate(it) }
    }

    /** Последние место и страница: просьбы до готовности WebView2 не теряются. */
    private var wantHtml: String? = null
    private var wantBounds: IntArray? = null

    /** Открыть страницу: пишется в свою папку и открывается с адреса `https://player.tima.local/`. */
    fun navigate(html: String) = post {
        wantHtml = html
        val v = view ?: return@post
        val name = "page-${++page}.html"
        File(pageDir, name).writeText(html)
        call(v, WV_NAVIGATE, WString(WebPage.ORIGIN + name))
    }

    fun script(code: String) = post {
        val v = view ?: return@post
        call(v, WV_EXECUTE_SCRIPT, WString(code), noop)
    }

    /** Место на окне-хозяине, в пикселях его клиентской части. */
    fun bounds(x: Int, y: Int, w: Int, h: Int) = post {
        wantBounds = intArrayOf(x, y, w, h)
        val c = controller ?: return@post
        val rect = Memory(16).apply { setInt(0, x); setInt(4, y); setInt(8, x + w); setInt(12, y + h) }
        call(c, CONTROLLER_PUT_BOUNDS, rect)
        call(c, CONTROLLER_PUT_IS_VISIBLE, 1)
    }

    fun close() {
        running = false
    }

    // ── COM ───────────────────────────────────────────────────────────────────

    private val noop: Pointer by lazy {
        comObject(object : InvokeResult {
            override fun invoke(self: Pointer?, hr: Int, result: Pointer?): Int = 0
        })
    }

    /**
     * Настройки окружения (`ICoreWebView2EnvironmentOptions`): проигрывание без нажатия на странице —
     * человек уже нажал на видео в TIMA, второе нажатие внутри окна было бы лишним.
     */
    private fun options(): Pointer {
        fun text(s: String, out: Pointer?): Int {
            val bytes = (s.length + 1) * 2L
            val mem = Ole32.INSTANCE.CoTaskMemAlloc(bytes)
            mem.setWideString(0, s)
            out?.setPointer(0, mem)
            return 0
        }
        val qi = object : QueryInterface {
            override fun invoke(self: Pointer?, riid: Pointer?, out: Pointer?): Int {
                val asked = riid?.let { com.sun.jna.platform.win32.Guid.GUID(it).toGuidString().uppercase() }
                return if (asked == IID_ENV_OPTIONS || asked == IID_UNKNOWN) {
                    out?.setPointer(0, self)
                    0
                } else {
                    out?.setPointer(0, Pointer.NULL)
                    E_NOINTERFACE
                }
            }
        }
        val ref = object : RefCount { override fun invoke(self: Pointer?): Int = 1 }
        val args = object : GetText { override fun invoke(self: Pointer?, out: Pointer?) = text(BROWSER_ARGS, out) }
        val language = object : GetText { override fun invoke(self: Pointer?, out: Pointer?) = text("", out) }
        val version = object : GetText { override fun invoke(self: Pointer?, out: Pointer?) = text(MIN_RUNTIME, out) }
        val sso = object : GetText {
            override fun invoke(self: Pointer?, out: Pointer?): Int {
                out?.setInt(0, 0)
                return 0
            }
        }
        val put = object : PutAny { override fun invoke(self: Pointer?, value: Pointer?): Int = 0 }
        val methods = listOf<Callback>(qi, ref, ref, args, put, language, put, version, put, sso, put)
        val table = Memory(methods.size.toLong() * Native.POINTER_SIZE)
        methods.forEachIndexed { i, cb -> table.setPointer(i.toLong() * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(cb)) }
        val obj = Memory(Native.POINTER_SIZE.toLong()).apply { setPointer(0, table) }
        keep.addAll(methods + listOf(table, obj))
        return obj
    }

    /** Объект COM с одним `Invoke`: таблица QueryInterface, AddRef, Release, Invoke. Живёт, пока жив [WebView2]. */
    private fun comObject(invoke: Callback): Pointer {
        val qi = object : QueryInterface {
            override fun invoke(self: Pointer?, riid: Pointer?, out: Pointer?): Int {
                out?.setPointer(0, self)
                return 0
            }
        }
        val ref = object : RefCount {
            override fun invoke(self: Pointer?): Int = 1
        }
        val table = Memory(4L * Native.POINTER_SIZE)
        listOf<Callback>(qi, ref, ref, invoke).forEachIndexed { i, cb ->
            table.setPointer(i.toLong() * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(cb))
        }
        val obj = Memory(Native.POINTER_SIZE.toLong())
        obj.setPointer(0, table)
        keep.addAll(listOf(qi, ref, invoke, table, obj))
        return obj
    }

    private fun call(obj: Pointer, index: Int, vararg args: Any?): Int {
        val table = obj.getPointer(0)
        val fn = Function.getFunction(table.getPointer(index.toLong() * Native.POINTER_SIZE))
        return fn.invokeInt(arrayOf(obj, *args))
    }

    private fun guid(text: String): Pointer = com.sun.jna.platform.win32.Guid.GUID.fromString(text).pointer

    private fun loaderPath(): File {
        val dir = File(userData.parentFile, "loader").apply { mkdirs() }
        val file = File(dir, "WebView2Loader.dll")
        val bytes = WebView2::class.java.getResourceAsStream("/webview2/WebView2Loader-x64.dll")!!.use { it.readBytes() }
        if (!file.exists() || file.length() != bytes.size.toLong()) file.writeBytes(bytes)
        return file
    }

    companion object {
        const val HOST = "player.tima.local"

        private const val COINIT_APARTMENTTHREADED = 2
        private const val PM_REMOVE = 1
        private const val HOST_ACCESS_ALLOW = 1
        private const val IID_WEBVIEW2_3 = "{A0D6DF20-3B92-416D-AA0C-437A9C727857}"
        private const val IID_ENV_OPTIONS = "{2FDE08A8-1E9A-4766-8C05-95A9CEB9D1C5}"
        private const val IID_UNKNOWN = "{00000000-0000-0000-C000-000000000046}"
        private const val E_NOINTERFACE = 0x80004002.toInt()
        private const val BROWSER_ARGS = "--autoplay-policy=no-user-gesture-required"

        /** Самый старый WebView2 Runtime, который нам годится: виртуальная папка (ICoreWebView2_3) — с 86. */
        private const val MIN_RUNTIME = "86.0.616.0"

        private const val QUERY_INTERFACE = 0
        private const val ADD_REF = 1
        private const val RELEASE = 2
        private const val ENV_CREATE_CONTROLLER = 3
        private const val CONTROLLER_PUT_IS_VISIBLE = 4
        private const val CONTROLLER_PUT_BOUNDS = 6
        private const val CONTROLLER_CLOSE = 24
        private const val CONTROLLER_GET_CORE_WEBVIEW2 = 25
        private const val WV_GET_SETTINGS = 3
        private const val WV_NAVIGATE = 5
        private const val WV_EXECUTE_SCRIPT = 29
        private const val WV_ADD_WEB_MESSAGE_RECEIVED = 34
        private const val WV3_SET_VIRTUAL_HOST = 71
        private const val SETTINGS_PUT_STATUS_BAR = 10
        private const val SETTINGS_PUT_DEVTOOLS = 12
        private const val SETTINGS_PUT_CONTEXT_MENUS = 14
        private const val SETTINGS_PUT_ZOOM = 18
        private const val ARGS_TRY_GET_STRING = 5
    }
}
