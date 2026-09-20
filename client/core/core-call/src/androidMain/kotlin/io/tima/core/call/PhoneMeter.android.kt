package io.tima.core.call

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import java.io.File

/**
 * Android: числа телефона из `/proc`, `/sys` и `TrafficStats`.
 *
 * **Ни одного вызова, требующего разрешения.** Всё, что здесь читается, приложение читает
 * про себя: свой процесс, свои байты, общий для телефона датчик температуры. Это
 * сознательно — стенд не должен просить у человека ничего сверх звонка.
 */

/** Контекст приложения. Нужен одному заряду батареи; всё остальное берётся из файлов. */
object AndroidPhoneMeter {

    @Volatile
    private var app: Context? = null

    /** Зовётся из `Application.onCreate`, как и остальные представления платформы. */
    fun attach(context: Context) {
        app = context.applicationContext
    }

    internal fun battery(): Int? {
        val context = app ?: return null
        val manager = runCatching {
            context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        }.getOrNull() ?: return null
        val level = runCatching {
            manager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrNull() ?: return null
        // Свойства батареи бывают неизвестны — тогда Android отдаёт Integer.MIN_VALUE.
        return level.takeIf { it in 0..100 }
    }
}

/**
 * Прошлое значение счётчика процессора и момент, когда оно снято.
 *
 * ── ПОЧЕМУ ХРАНИМ ПРОШЛОЕ, А НЕ БЕРЁМ МГНОВЕННОЕ ───────────────────────────
 *
 * Мгновенного процента в Android нет нигде: `/proc/self/stat` отдаёт **накопленные** тики
 * с начала процесса. Процент получается только делением разницы тиков на разницу времени —
 * значит первый замер сравнивать не с чем, и он честно отдаёт `null`.
 *
 * Считать от старта процесса было бы хуже, чем ничего: приложение живёт часами, и средний
 * за всё это время процессор скажет про минуту разговора ровно ничего.
 */
private var lastTicks: Long = -1
private var lastAt: Long = 0

/** Тиков в секунду. `sysconf(_SC_CLK_TCK)` из Kotlin не позвать; на Android он всегда 100. */
private const val TICKS_PER_SECOND = 100.0

actual fun phoneLoad(): PhoneLoad? {
    val now = SystemClock.elapsedRealtime()
    val ticks = readOwnTicks()
    val cpu = if (ticks != null && lastTicks >= 0 && now > lastAt) {
        val seconds = (now - lastAt) / 1000.0
        (ticks - lastTicks) / TICKS_PER_SECOND / seconds * 100.0
    } else {
        null
    }
    if (ticks != null) {
        lastTicks = ticks
        lastAt = now
    }
    val runtime = Runtime.getRuntime()
    val memory = ((runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024).toInt()
    return PhoneLoad(
        cpuPercent = cpu?.takeIf { it.isFinite() && it >= 0 },
        memoryMb = memory,
        temperatureC = hottest(),
        batteryPercent = AndroidPhoneMeter.battery(),
        uptimeMs = now,
    )
}

actual fun phoneTraffic(): PhoneTraffic? {
    val uid = Process.myUid()
    val sent = TrafficStats.getUidTxBytes(uid)
    val received = TrafficStats.getUidRxBytes(uid)
    // UNSUPPORTED (-1) отдают прошивки, где счётчик на процесс не ведётся вовсе. Ноль
    // вместо этого был бы ложью: «ничего не отправлено» и «неизвестно» — разные ответы.
    if (sent < 0 || received < 0) return null
    return PhoneTraffic(sentBytes = sent, receivedBytes = received)
}

/**
 * Тики процессора нашего процесса: поля 14 и 15 из `/proc/self/stat` (utime и stime).
 *
 * Разбор по полям, а не по регулярному выражению: поле 2 — имя процесса в скобках, и оно
 * может содержать пробелы. Считаем от **конца скобки**, как это и делают все, кто читал
 * `proc(5)`.
 */
private fun readOwnTicks(): Long? = runCatching {
    val line = File("/proc/self/stat").readText()
    val tail = line.substring(line.lastIndexOf(')') + 2)
    val fields = tail.split(' ')
    // После имени идёт состояние (поле 3), значит utime — это индекс 11 в хвосте.
    val utime = fields[11].toLong()
    val stime = fields[12].toLong()
    utime + stime
}.getOrNull()

/**
 * Самый горячий датчик, °C.
 *
 * **Берём максимум по всем зонам, а не «зону процессора».** Называются они у каждого
 * вендора по-своему (`cpu-0-0-usr`, `soc_max`, `mtktscpu`), и искать нужную по имени
 * значит найти её на одном телефоне из трёх. Для нашего вопроса — «греется ли он от
 * кодирования» — максимум и есть ответ.
 *
 * Единицы тоже вендорские: почти везде тысячные доли градуса, изредка градусы. Число
 * больше тысячи делим — 1000 °C датчик не покажет, а 45 000 значит 45 °C.
 */
private fun hottest(): Double? = runCatching {
    val zones = File("/sys/class/thermal").listFiles()
        ?.filter { it.name.startsWith("thermal_zone") }
        ?: return null
    zones.mapNotNull { zone ->
        runCatching { File(zone, "temp").readText().trim().toDouble() }.getOrNull()
    }.map { if (it > 1000) it / 1000.0 else it }
        .filter { it in 1.0..150.0 } // отключённая зона отдаёт 0 или -40
        .maxOrNull()
}.getOrNull()
