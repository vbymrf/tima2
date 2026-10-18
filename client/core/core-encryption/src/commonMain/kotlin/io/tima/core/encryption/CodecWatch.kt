package io.tima.core.encryption

import kotlin.time.TimeSource

/**
 * Что разбор тела сообщает журналу (ПЛАН-(ЖС) §2, §4): показ без оформления, медленный разбор строки
 * ввода, медленный показ. Модуль журнала сюда не тянется — его слушатели ставит сборка приложения;
 * не поставлены (проверки) — молчит. Текст сообщения не передаётся никогда: только правила и числа.
 */
object CodecWatch {
    /** Принятое показано без оформления: коды правил, размер разметки в байтах. */
    var markupDropped: ((rules: List<String>, markupBytes: Int) -> Unit)? = null

    /** Разбор быстрого оформления дольше [PARSE_SLOW_MS]: длина строки, мс. */
    var slowParse: ((length: Int, ms: Long) -> Unit)? = null

    /** Разбор сообщения для показа дольше кадра [RENDER_SLOW_MS]: блоков, мс. */
    var slowRender: ((blocks: Int, ms: Long) -> Unit)? = null

    const val PARSE_SLOW_MS = 50L
    const val RENDER_SLOW_MS = 16L

    internal inline fun <T> timed(limitMs: Long, block: () -> T, report: (T, Long) -> Unit): T {
        val mark = TimeSource.Monotonic.markNow()
        val result = block()
        val ms = mark.elapsedNow().inWholeMilliseconds
        if (ms > limitMs) runCatching { report(result, ms) }
        return result
    }
}
