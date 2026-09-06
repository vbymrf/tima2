package io.tima.shared

import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.core.diag.scrub
import io.tima.core.network.ProblemPost
import io.tima.core.network.ProblemSendResult
import io.tima.core.network.ProblemsOverHttp
import kotlinx.serialization.json.Json

/**
 * Где платформа держит неотправленные отчёты (ПЛАН-ОТЛАДКИ.md, Б3 и Б7).
 *
 * Две лямбды, как у [AppearanceStore], и по той же причине: хранить надо одну строку, а
 * платформенного здесь ровно столько же — место, где эта строка лежит.
 *
 * **Не база и не хранилище секретов.** База открывается после входа и своя у каждого
 * аккаунта, а отчёт нужен именно тогда, когда войти не получается. Секреты — не сюда:
 * отчёт не тайна, он всего лишь ждёт связи.
 */
class ReportsStore(
    val load: () -> String?,
    val save: (String) -> Unit,
) {
    companion object {
        /** Хранилище, которое не хранит: для проверок и для платформы без места. */
        val Forgetful: ReportsStore = ReportsStore(load = { null }, save = {})
    }
}

/**
 * Очередь неотправленных отчётов.
 *
 * **Нужна ровно потому, что жалуются на обрыв связи при обрыве связи.** Отчёт, который
 * можно отправить только при работающей сети, теряет самый частый случай — и человек,
 * которому сказали «попробуйте позже», второй раз не приходит.
 *
 * Здесь же оседают отчёты о падениях: отправлять их из умирающего процесса нельзя, и они
 * ждут следующего запуска (решение заказчика 2026-09-06).
 *
 * Хранит [ProblemPost] — тот же тип, что уходит на сервер. Второй, «почти такой же», тип
 * для очереди однажды разошёлся бы с отправляемым.
 */
class ReportQueue(private val store: ReportsStore, private val limit: Int = LIMIT) {

    private val json = Json { ignoreUnknownKeys = true }

    fun waiting(): List<ProblemPost> {
        val raw = store.load()?.takeIf { it.isNotBlank() } ?: return emptyList()
        return try {
            json.decodeFromString<List<ProblemPost>>(raw)
        } catch (e: Throwable) {
            // Разбор не удался — очередь испорчена. Держаться за неё нечего: отчёты не
            // ценность сами по себе, а испорченный список будет мешать новым.
            emptyList()
        }
    }

    /** Положить в очередь. Старые вытесняются: телефон не склад отчётов. */
    fun add(post: ProblemPost) {
        val kept = (waiting() + post).takeLast(limit)
        write(kept)
    }

    /** Убрать то, что ушло. Сравнение по значению — отчёты не имеют своих номеров до отправки. */
    fun forget(post: ProblemPost) {
        write(waiting().filterNot { it == post })
    }

    fun clear() = write(emptyList())

    private fun write(posts: List<ProblemPost>) {
        store.save(if (posts.isEmpty()) "" else json.encodeToString(posts))
    }

    private companion object {
        /** Больше десяти неотправленных означает, что дело не в сети, а в сервере. */
        const val LIMIT = 10
    }
}

/**
 * Записать падение в очередь — уйдёт при следующем запуске (ПЛАН-ОТЛАДКИ.md, Б7).
 *
 * **Отправлять отсюда нельзя.** Процесс умирает: сетевой вызов не успеет ни установить
 * соединение, ни дождаться ответа, а держать умирающий процесс ради этого — верный способ
 * получить второе падение поверх первого. Поэтому отчёт кладётся на диск и ждёт.
 *
 * Отправляется он **сам**, без вопроса, — решение заказчика 2026-09-06. Человек узнаёт об
 * этом заранее: строка на экране «Сообщить о проблеме» говорит, что отчёты о внезапном
 * закрытии уходят тем же составом.
 *
 * Журнал прикладывается тот же, что и к обычному отчёту: падению предшествует то же
 * самое, что человек успел сделать.
 */
fun rememberCrash(
    store: ReportsStore,
    platform: String,
    model: String,
    os: String,
    build: String,
    stream: String,
    log: String,
    error: Throwable,
) {
    // Стек проходит ту же чистку, что журнал: в аргументах метода легко оказывается текст
    // сообщения, и обещание «наружу не уходит содержимое» действует и здесь.
    val stack = scrub(error.stackTraceToString()).take(CRASH_LIMIT)
    // Падение попадает и в журнал: отчёт о падении уйдёт при следующем запуске, а строка
    // нужна здесь и сейчас — чтобы в журнале было видно, чем кончился прошлый сеанс.
    Journal.trouble(
        LogCode.CRASH,
        "приложение закрылось само",
        "ошибка" to (error::class.simpleName ?: "неизвестно"),
    )
    ReportQueue(store).add(
        ProblemPost(
            kind = "crash",
            text = "Приложение закрылось само: " + (error.message?.let { scrub(it) } ?: error::class.simpleName.orEmpty()),
            origin = "",
            platform = platform,
            model = model,
            os = os,
            build = build,
            stream = stream,
            nickname = "",
            log = stack + STACK_SPLIT + log,
        ),
    )
}

/** Предел стека: дальше идут кадры фреймворка, которые ничего не объясняют. */
private const val CRASH_LIMIT = 8000

/** Черта между стеком и журналом: читающему отчёт нужно видеть, где кончилось одно. */
private const val STACK_SPLIT = "\n--- журнал до падения ---\n"

/**
 * Отправка отчёта: сеть, а при неудаче — очередь.
 *
 * Порядок именно такой. Сначала пробуем отправить: связь чаще есть, чем нет, и человеку
 * важно увидеть номер обращения сразу. Не вышло — кладём в очередь и говорим об этом
 * словами, а не прячем за «ошибка отправки».
 */
class Reporting(
    private val network: ProblemsOverHttp,
    private val queue: ReportQueue,
) {

    /** `null` в номере — не отправлено, лежит в очереди. */
    suspend fun send(post: ProblemPost): ProblemSendResult {
        val result = try {
            network.send(post)
        } catch (e: Throwable) {
            queue.add(post)
            return ProblemSendResult.Refused(0, "не дошло, лежит в очереди")
        }
        if (result !is ProblemSendResult.Sent) queue.add(post)
        return result
    }

    /**
     * Досылка накопленного. Зовётся при запуске и после прохода очереди сообщений.
     *
     * Отчёт, ушедший успешно, из очереди убирается сразу: повторная отправка того же
     * отчёта — это второй такой же в нашей базе и лишний вопрос «а это то же самое или
     * новое».
     */
    suspend fun deliver(): Int {
        var sent = 0
        for (post in queue.waiting()) {
            val result = try {
                network.send(post)
            } catch (e: Throwable) {
                return sent
            }
            when (result) {
                is ProblemSendResult.Sent -> {
                    queue.forget(post)
                    sent++
                }
                // Сервер отказал по существу — отчёт не станет лучше от повторов.
                is ProblemSendResult.Refused -> queue.forget(post)
                // Связи нет — оставляем очередь как есть и уходим до следующего раза.
                is ProblemSendResult.NoConnection -> return sent
            }
        }
        return sent
    }
}
