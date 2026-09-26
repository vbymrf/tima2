package io.tima.core.notify

import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Что система позволяет приложению в фоне — три вещи, от которых зависит, узнает ли
 * человек о звонке. `null` в поле — узнать нечем (ПК, iOS, ещё нет контекста).
 */
data class BackgroundFacts(
    /** Приложению разрешены уведомления (на Android 13+ — `POST_NOTIFICATIONS`). */
    val notices: Boolean? = null,
    /** Канал «Звонки» не выключен человеком в настройках телефона. */
    val calls: Boolean? = null,
    /** Приложение в белом списке энергосбережения: система не душит его в фоне. */
    val awake: Boolean? = null,
    /**
     * Входящему можно во весь экран на замке (Android 14+, `canUseFullScreenIntent`, ВЗ1).
     * Нельзя — звонок всплывает строкой, а не окном.
     */
    val fullScreen: Boolean? = null,
)

/** Спросить систему сейчас. */
expect fun backgroundFacts(): BackgroundFacts

/**
 * Открыть страницу канала «Звонки» в настройках телефона (ВЗ0г). Там, где страницы нет
 * (ПК, Android до 8), — страница приложения или ничего.
 */
expect fun openCallsChannelSettings()

/** Открыть страницу права «во весь экран» (Android 14+); где его нет — ничего. */
expect fun openFullScreenSettings()

/**
 * Фон глазами журнала — ПЛАН-ВХОДЯЩЕГО-ЗВОНКА.md, ВЗ0в.
 *
 * ── ЗАЧЕМ ─────────────────────────────────────────────────────────────────
 *
 * 2026-09-26 звонки не дошли до человека на двух телефонах, и ни одна из причин не была
 * видна в отчёте о проблеме: на Redmi приложению были запрещены уведомления, ни один
 * телефон не был в белом списке энергосбережения, а жива ли служба канала — угадывалось
 * по косвенным строкам. Разбирали по `adb`, которого у человека с улицы нет.
 *
 * ── СОБЫТИЙНО, А НЕ ПО РАСПИСАНИЮ ─────────────────────────────────────────
 *
 * Решение заказчика того же дня: журнал не перегружать. Поэтому пишется **первое значение
 * за жизнь процесса и каждое изменение** — ничего больше. Проверка дешёвая и зовётся в
 * три момента: запуск процесса, возврат окна (человек мог сходить в настройки и включить),
 * входящий звонок (ровно тогда, когда ответ нужен). Одинаковое значение молчит.
 *
 * Служба канала пишет себя сама: подъём (и отдельно — подъём системой после убийства
 * процесса) и остановку. Сколько она живёт — в снимке отчёта, а не строкой раз в минуту.
 */
object BackgroundWatch {

    @Volatile
    private var last: BackgroundFacts? = null

    @Volatile
    private var serviceUp: TimeMark? = null

    /**
     * Кому сказать про каждую сверку — полосе «звонки не дойдут» (ВЗ0г). Зовётся на
     * **каждой** проверке, а не только на изменении: полоса обязана появиться и у того,
     * кто открыл окно впервые, а журнал при этом молчит об одинаковом.
     */
    @Volatile
    var onCheck: ((BackgroundFacts) -> Unit)? = null

    /**
     * Сверить с системой и записать то, что изменилось.
     *
     * @param reason повод проверки — в хвост строки: «запуск процесса», «входящий звонок».
     * @return то, что ответила система, — вызывающему может понадобиться сразу.
     */
    fun check(reason: String, facts: BackgroundFacts = backgroundFacts()): BackgroundFacts {
        for (line in changes(last, facts)) {
            if (line.bad) {
                Journal.trouble(line.code, line.text, "повод" to reason)
            } else {
                Journal.note(line.code, line.text, "повод" to reason)
            }
        }
        last = facts
        onCheck?.invoke(facts)
        return facts
    }

    /**
     * Служба канала поднялась.
     *
     * @param bySystem подняла система сама (`START_STICKY` без намерения) — значит процесс
     *   перед этим был убит, и тишина в журнале до этой строки — не зависание, а смерть.
     */
    fun serviceStarted(bySystem: Boolean) {
        if (serviceUp != null) return
        serviceUp = TimeSource.Monotonic.markNow()
        if (bySystem) {
            Journal.trouble(LogCode.BG_SERVICE, "служба канала поднята системой заново — процесс перед этим был убит")
        } else {
            Journal.note(LogCode.BG_SERVICE, "служба канала поднята")
        }
    }

    fun serviceStopped() {
        val since = serviceUp ?: return
        serviceUp = null
        Journal.note(LogCode.BG_SERVICE, "служба канала остановлена", "жила" to lasted(since.elapsedNow()))
    }

    /** Строка «разрешения» снимка отчёта — то, что сейчас. */
    fun describe(): String = describe(backgroundFacts(), serviceUp?.elapsedNow())

    internal fun describe(facts: BackgroundFacts, serviceFor: Duration?): String = buildList {
        facts.notices?.let { add("уведомления " + if (it) "разрешены" else "ЗАПРЕЩЕНЫ") }
        facts.calls?.let { add("канал «Звонки» " + if (it) "включён" else "ВЫКЛЮЧЕН") }
        facts.awake?.let { add("экономия батареи " + if (it) "не ограничивает" else "ОГРАНИЧИВАЕТ") }
        facts.fullScreen?.let { add("звонок во весь экран " + if (it) "разрешён" else "ЗАПРЕЩЁН") }
        // Службы на ПК и iOS нет вовсе — про неё молчим там, где о ней нечего сказать.
        if (facts != BackgroundFacts() || serviceFor != null) {
            add("служба канала " + (serviceFor?.let { "жива " + lasted(it) } ?: "не поднята"))
        }
    }.joinToString("; ")

    internal class Line(val code: String, val bad: Boolean, val text: String)

    /** Что записать при переходе от [before] к [now]; `before == null` — первая проверка. */
    internal fun changes(before: BackgroundFacts?, now: BackgroundFacts): List<Line> = buildList {
        if (now.notices != null && now.notices != before?.notices) {
            add(
                if (now.notices) {
                    Line(LogCode.BG_NOTICES, false, "уведомления разрешены")
                } else {
                    Line(LogCode.BG_NOTICES, true, "уведомления запрещены — ни звонок, ни сообщение в фоне не покажутся")
                },
            )
        }
        if (now.calls != null && now.calls != before?.calls) {
            add(
                if (now.calls) {
                    Line(LogCode.BG_NOTICES, false, "канал «Звонки» включён")
                } else {
                    Line(LogCode.BG_NOTICES, true, "канал «Звонки» выключен в настройках телефона — входящий не покажется")
                },
            )
        }
        if (now.fullScreen != null && now.fullScreen != before?.fullScreen) {
            add(
                if (now.fullScreen) {
                    Line(LogCode.BG_NOTICES, false, "звонок во весь экран разрешён")
                } else {
                    Line(LogCode.BG_NOTICES, true, "звонок во весь экран запрещён — на замке входящий покажется строкой, а не окном")
                },
            )
        }
        if (now.awake != null && now.awake != before?.awake) {
            add(
                if (now.awake) {
                    Line(LogCode.BG_POWER, false, "экономия батареи приложение не ограничивает")
                } else {
                    Line(LogCode.BG_POWER, true, "экономия батареи ограничивает приложение — в фоне его могут остановить")
                },
            )
        }
    }

    private fun lasted(d: Duration): String {
        val minutes = d.inWholeMinutes
        return if (minutes < 60) "$minutes мин" else "${minutes / 60} ч ${minutes % 60} мин"
    }
}
