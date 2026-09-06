package io.tima.core.diag

import kotlinx.datetime.Clock

/**
 * Общая точка записи в журнал.
 *
 * **Единственный синглтон, который мы себе позволяем, и вот почему.** Журнал — сквозная
 * вещь: пишет сеть, пишет оболочка, пишут точки входа при падении. Протащить его
 * параметром через все конструкторы значит переписать половину сигнатур ради строки
 * «пошёл запрос» — и через месяц кто-нибудь заведёт свой собственный, чтобы не тащить.
 *
 * В v1 это выглядело так же (`AppDiagnostics.INSTANCE`), и оттуда же взята мера
 * предосторожности: журнал **подменяем**. Тест ставит свой с управляемыми часами, ничего
 * не зная про глобальное состояние приложения.
 */
object Journal {

    /** Тот, в кого пишут прямо сейчас. */
    var diary: Diary = Diary(now = { Clock.System.now().toEpochMilliseconds() })
        private set

    /** Подменить журнал — приложение при запуске, тест перед проверкой. */
    fun replace(other: Diary) {
        diary = other
    }

    /**
     * @param code код из [LogCode]; по нему строку ищут и считают.
     * @param text человеческая часть — что это значит.
     * @param details пары «имя=значение» для того, что сравнивается: путь, код, мс.
     */
    fun note(code: String, text: String = "", vararg details: Pair<String, Any?>) =
        diary.note(code, text, *details)

    fun trouble(code: String, text: String = "", vararg details: Pair<String, Any?>) =
        diary.trouble(code, text, *details)
}
