package io.tima.app

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock

/**
 * Один экземпляр TIMA на пользователя Windows.
 *
 * Отчёт 2026-10-15: на ПК работали три TIMA сразу — автозагрузка (спрятанная в трей) и два
 * запуска ярлыком. Ярлык не видел спрятанного окна и поднимал ещё один процесс; все три писали в
 * одну базу, и днём раньше ПК упал на «база занята» (SG9S). Теперь второй запуск находит первый,
 * просит его показать окно и выходит.
 *
 * Как: замок файла `instance.lock` в каталоге данных держит первый процесс до конца жизни
 * (Windows снимает его сама, когда процесс умер, — даже упавший). Рядом в `instance.port` — порт,
 * который первый слушает только на петле: туда второй пишет «show».
 */
internal object SingleInstance {
    private const val LOCK_NAME = "instance.lock"
    private const val PORT_NAME = "instance.port"
    private const val SHOW = "show"

    /**
     * Сколько ждать, если первый занят замком, но не отвечает: он закрывается — например, его
     * закрыла установка обновления, а новую версию уже запустили.
     */
    private const val WAIT_MS = 15_000L

    // Держатся до конца процесса: собранный сборщиком мусора канал снял бы замок.
    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    @Volatile private var onShow: (() -> Unit)? = null
    @Volatile private var showPending = false

    /** Сколько раз второй запуск просил показать окно — для журнала первого. */
    @Volatile var asked = 0
        private set

    /**
     * Занять место единственного экземпляра. `false` — TIMA уже запущена: окно ей показать велено
     * (кроме запуска автозагрузкой, [show] = false), этому процессу — выйти.
     */
    fun claim(catalog: File, show: Boolean): Boolean {
        catalog.mkdirs()
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (true) {
            if (tryLock(catalog)) {
                listen(catalog)
                return true
            }
            if (askFirst(catalog, if (show) SHOW else "")) return false
            if (System.currentTimeMillis() > deadline) {
                // Замок держит процесс, который не отвечает и не уходит. Второй экземпляр — меньшее
                // зло, чем TIMA, которая не запускается вовсе.
                return true
            }
            Thread.sleep(500)
        }
    }

    /** Окно готово показываться по просьбе второго запуска. Просьба, пришедшая раньше, — сразу. */
    fun onShow(action: () -> Unit) {
        onShow = action
        if (showPending) {
            showPending = false
            action()
        }
    }

    private fun tryLock(catalog: File): Boolean {
        val ch = runCatching { RandomAccessFile(File(catalog, LOCK_NAME), "rw").channel }.getOrNull() ?: return false
        val got = runCatching { ch.tryLock() }.getOrNull()
        if (got == null) {
            ch.close()
            return false
        }
        channel = ch
        lock = got
        return true
    }

    private fun listen(catalog: File) {
        val server = runCatching { ServerSocket(0, 4, InetAddress.getLoopbackAddress()) }.getOrNull() ?: return
        runCatching { File(catalog, PORT_NAME).writeText(server.localPort.toString()) }
        Thread({
            while (true) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    client.use {
                        it.soTimeout = 2_000
                        val line = it.getInputStream().bufferedReader().readLine().orEmpty()
                        it.getOutputStream().write("ok\n".toByteArray())
                        if (line.trim() == SHOW) {
                            asked++
                            val action = onShow
                            if (action != null) action() else showPending = true
                        }
                    }
                }
            }
        }, "tima-single-instance").apply { isDaemon = true }.start()
    }

    /** Достучаться до первого: ответил «ok» — он жив и просьбу получил. */
    private fun askFirst(catalog: File, request: String): Boolean {
        val port = runCatching { File(catalog, PORT_NAME).readText().trim().toInt() }.getOrNull() ?: return false
        return runCatching {
            Socket(InetAddress.getLoopbackAddress(), port).use {
                it.soTimeout = 2_000
                it.getOutputStream().write("$request\n".toByteArray())
                it.getOutputStream().flush()
                it.getInputStream().bufferedReader().readLine()?.trim() == "ok"
            }
        }.getOrDefault(false)
    }
}
