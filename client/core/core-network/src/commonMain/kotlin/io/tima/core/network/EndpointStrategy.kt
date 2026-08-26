package io.tima.core.network

import io.tima.core.outbox.SendOutcome

/**
 * Память маршрутов: какой кандидат работал прошлый раз.
 *
 * **Зачем.** Список кандидатов существует потому, что часть адресов может быть
 * недоступна. Без памяти каждый запуск приложения начинал бы обход списка с начала —
 * и человек, у которого работает третий кандидат, каждый раз платил бы двумя
 * таймаутами за первые два. Это и есть та задержка старта, которая в v1 выглядела как
 * «мессенджер долго думает».
 *
 * Реализация — `core-database` (одна строка настроек). Здесь только договор: слой
 * сети не должен знать, чем именно пишется память.
 */
interface RouteMemory {

    /** Ключ кандидата, сработавшего последним; `null` — памяти нет (первый запуск). */
    fun lastGood(): String?

    fun remember(key: String)

    /** Забыть — при выходе из аккаунта и при смене списка кандидатов на несовместимый. */
    fun forget()
}

/** Память в памяти процесса: для тестов и для первого запуска до появления базы. */
class InMemoryRouteMemory(private var key: String? = null) : RouteMemory {
    override fun lastGood(): String? = key
    override fun remember(key: String) { this.key = key }
    override fun forget() { key = null }
}

/**
 * Выбор кандидата — последняя часть К3.1.
 *
 * Три правила, и каждое закрывает свой способ ошибиться:
 *
 * **1. Начинаем с того, что работало.** Сработавший кандидат помнится между
 * запусками ([RouteMemory]); список обходится с него, а не с начала.
 *
 * **2. Окончательный отказ кандидата не меняет.** Это различие дороже, чем кажется:
 * `403` на подпись означает, что негоден **конверт**, а адрес как раз в порядке.
 * Крутить из-за него список — значит уйти с рабочего адреса из-за собственной
 * ошибки в конверте, и уйти всем списком, потому что подпись не сойдётся нигде.
 * Крутит только временный отказ ([SendOutcome.Retry]) и обрыв связи.
 *
 * **3. Один отказ адреса не теряет.** Мобильная сеть роняет соединения без причины,
 * и уход с рабочего адреса после единственного обрыва означал бы вечную карусель.
 * Нужно [failuresBeforeRotation] подряд; успех счётчик обнуляет.
 */
class EndpointStrategy(
    candidates: List<RouteConfig>,
    private val memory: RouteMemory = InMemoryRouteMemory(),
    /**
     * Сколько временных отказов подряд означают «адрес не работает». Два — не
     * магическое число: один отказ это обычная мобильная сеть, а ждать трёх значит
     * держать человека на мёртвом адресе ощутимо долго.
     */
    private val failuresBeforeRotation: Int = 2,
) {

    private val candidates: List<RouteConfig> = candidates.toList()

    init {
        require(this.candidates.isNotEmpty()) { "список кандидатов пуст: ходить некуда" }
        require(failuresBeforeRotation >= 1) { "отказов до смены адреса не может быть меньше одного" }
    }

    private var index: Int = remembered()
    private var consecutiveFailures: Int = 0

    /** Текущий кандидат. Меняется только по правилам выше. */
    val current: RouteConfig get() = candidates[index]

    /** Ключ текущего кандидата — он же лежит в памяти маршрутов. */
    val currentKey: String get() = keyOf(current)

    /** Сколько кандидатов всего: для показа «пробуем 2 из 3», не для логики. */
    val size: Int get() = candidates.size

    /**
     * Отправка удалась: адрес рабочий.
     *
     * Запоминается **здесь, а не при выборе**: запомнить кандидата до первого успеха
     * значило бы сохранить между запусками адрес, который ни разу не ответил.
     */
    fun onSuccess() {
        consecutiveFailures = 0
        memory.remember(currentKey)
    }

    /**
     * Попытка не удалась.
     *
     * @return сменился ли кандидат.
     */
    fun onOutcome(outcome: SendOutcome): Boolean = when (outcome) {
        is SendOutcome.Accepted, is SendOutcome.Duplicate -> {
            onSuccess()
            false
        }
        // Конверт негоден по сути — адрес тут не при чём, и уходить с рабочего адреса
        // из-за своей же ошибки нельзя: подпись не сойдётся ни у одного кандидата.
        is SendOutcome.Permanent -> false
        is SendOutcome.Retry -> onTemporaryFailure()
    }

    /** Обрыв связи, таймаут: то же, что временный отказ. */
    fun onTemporaryFailure(): Boolean {
        if (candidates.size == 1) {
            // Крутить нечего. Счётчик всё равно ведём: он виден в диагностике, и по нему
            // отличается «сеть моргнула» от «адрес мёртв, а другого нет».
            consecutiveFailures++
            return false
        }
        if (++consecutiveFailures < failuresBeforeRotation) return false
        consecutiveFailures = 0
        index = (index + 1) % candidates.size
        return true
    }

    /** Сколько временных отказов подряд у текущего адреса — для диагностики. */
    fun failures(): Int = consecutiveFailures

    private fun remembered(): Int {
        val key = memory.lastGood() ?: return 0
        val found = candidates.indexOfFirst { keyOf(it) == key }
        if (found >= 0) return found
        // Память указывает на кандидата, которого в списке больше нет — обновился
        // подписанный конфиг. Держаться за такую память нельзя, и чистим её сразу:
        // иначе она будет сбивать выбор при каждом запуске.
        memory.forget()
        return 0
    }

    private companion object {
        /**
         * Ключ кандидата. Собирается из собранного маршрута, а не из полей конфигурации:
         * два по-разному записанных кандидата (`пацак.рф` и `xn--80aa4ar0b.xn--p1ai`) —
         * это один адрес, и память обязана узнать его после смены записи в конфиге.
         */
        fun keyOf(config: RouteConfig): String = ServerRoute.from(config).let {
            "${it.serverHost}|${it.connectHost}:${it.connectPort}"
        }
    }
}
