package io.tima.shared

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import io.tima.feature.shell.SettingsItem
import io.tima.feature.shell.Window

/**
 * Переходы приложения — куда человек перешёл и в каком он окне (ПЛАН-(РФ) РФ1, ПЛАН-(МП)).
 *
 * До 2026-11-09 это были две переменные внутри `App()` и 79 мест, где их меняли. Здесь — одно место.
 *
 * **Цепочка окна** (МП М1, М16): у каждого окна свой путь подокон — «Чаты → переписка → страница человека». Верх
 * цепочки и есть [where]; окно без подокна — пустая цепочка. Свайп и рейка возвращают окно с его цепочкой, панель
 * перехода и переключатель окон — в начальное положение (М3).
 *
 * **Цепочка «откуда»** (М17): одна на приложение. Переход в другое окно по уведомлению, в звонок — запоминает, где был
 * человек; «Назад» в начале окна возвращает туда (М7).
 *
 * Обе цепочки — не длиннее [LIMIT] звеньев (М18). Та же сущность подряд нового звена не даёт; повтор не подряд —
 * новое звено (М20).
 */
internal class Navigator(
    windowState: MutableState<Window>,
    /** Строка журнала переходов (М15): что, и подробности. Проверки обходятся без журнала. */
    private val note: (what: String, details: List<Pair<String, Any>>) -> Unit = { _, _ -> },
) {

    /** Окно, в котором человек. Состояние общее с [CallKeep]: окно переживает пересоздание экрана. */
    var window: Window by windowState

    private val chains = mutableStateMapOf<Window, List<Where>>()
    private val from = mutableStateListOf<Point>()

    /** Куда ведёт вход в окно каждым способом (М3); настройка «Переходы» (М5) меняет это. */
    var arriveBy: (Via) -> Arrive = { it.arrive }

    /**
     * Что открыто поверх окна — верх цепочки; [Where.Nothing] — само окно.
     *
     * Запись — открыть ([open]); запись [Where.Nothing] — закрыть верхнее звено. Так прежние 79 мест `App()`, где
     * писали `where = …`, получили цепочку без правки каждого: «назад» в подокне закрывает одно звено.
     */
    var where: Where
        get() = chains[window]?.lastOrNull() ?: Where.Nothing
        set(value) {
            if (value == Where.Nothing) close() else open(value)
        }

    /** Цепочка окна — для хранения (МП4) и проверок. */
    fun chain(of: Window = window): List<Where> = chains[of].orEmpty()

    /** Цепочка «откуда», от старого к новому. */
    fun origins(): List<Point> = from.toList()

    /** Восстановить из хранения (МП4): цепочки окон и «откуда». Черновые подокна не восстанавливаются. */
    fun restore(windows: Map<Window, List<Where>>, origins: List<Point>) {
        chains.clear()
        windows.forEach { (w, chain) -> chains[w] = chain.filterNot { it.transient }.takeLast(LIMIT) }
        from.clear()
        from.addAll(origins.takeLast(LIMIT))
    }

    /**
     * Открыть поверх окна. Правила звеньев:
     * - та же сущность, что наверху, — звено заменяется (М18: подряд нового нет);
     * - та же, что под верхним, — это возврат: верхнее закрывается («назад» подокна, которое называет родителя);
     * - наверху черновое подокно (новая переписка, новая группа, привязка) — оно заменяется: в цепочке ему не место;
     * - иначе — новое звено (М20), старые сверх [LIMIT] отбрасываются.
     */
    fun open(target: Where, by: Via = Via.Open) {
        if (target == Where.Nothing) return close()
        val chain = chain()
        val top = chain.lastOrNull()
        val next = when {
            top != null && top.sameAs(target) -> chain.dropLast(1) + target
            chain.size >= 2 && chain[chain.size - 2].sameAs(target) -> chain.dropLast(1)
            top != null && top.transient -> chain.dropLast(1) + target
            else -> (chain + target).takeLast(LIMIT)
        }
        chains[window] = next
        note("открыто", listOf("как" to by.code, "что" to whereCode(target), "звеньев" to next.size))
    }

    /** Закрыть верхнее звено окна. Пустая цепочка — ничего. */
    fun close() {
        val chain = chain()
        if (chain.isEmpty()) return
        chains[window] = chain.dropLast(1)
    }

    /**
     * Войти в окно способом [by] (М3). Начальное положение — цепочка окна пустеет; точка — цепочка как была.
     * Вкладку начального положения ставит вызывающий: вкладки живут у окон, а не здесь. Возвращает, куда пришли.
     */
    fun enter(target: Window, by: Via): Arrive {
        val arrive = arriveBy(by)
        val before = window
        if (arrive == Arrive.Start) chains[target] = emptyList()
        if (by.remembers && target != before) remember()
        window = target
        note("окно", listOf("как" to by.code, "откуда" to before.name, "куда" to target.name, "в" to arrive.code, "звеньев" to chain(target).size))
        return arrive
    }

    /** Звонок — поверх всего; откуда ушли — запоминается, конец звонка возвращает туда (М10). */
    fun toCall() {
        if (window == Window.Call) return
        remember()
        window = Window.Call
        note("окно", listOf("как" to Via.Call.code, "куда" to Window.Call.name))
    }

    /** Звонок кончился — в точку, откуда в него ушли (М10); её нет — на «Телефон». */
    fun leaveCall() {
        if (window != Window.Call) return
        chains[Window.Call] = emptyList()
        val back = from.removeLastOrNull()?.takeIf { it.window != Window.Call }
        window = back?.window ?: Window.Phone
        back?.let { chains[it.window] = it.chain }
        note("окно", listOf("как" to "call.end", "куда" to window.name))
    }

    /**
     * «Назад» по слоям (М7): закрыть верхнее звено окна; окно пустое — в последнее «откуда»; его нет и окно не
     * «Телефон» — на «Телефон»; на «Телефоне» — `false`: выходить из приложения решает платформа.
     */
    fun back(): Boolean {
        val chain = chain()
        val done = when {
            chain.isNotEmpty() -> {
                chains[window] = chain.dropLast(1)
                "link"
            }
            from.isNotEmpty() -> {
                val back = from.removeAt(from.lastIndex)
                window = back.window
                chains[back.window] = back.chain
                "origin"
            }
            window != Window.Phone -> {
                window = Window.Phone
                "phone"
            }
            else -> null
        }
        note("назад", listOf("итог" to (done ?: "exit"), "окно" to window.name, "звеньев" to chain().size))
        return done != null
    }

    /** Звено «откуда» — текущая точка, без черновых подокон. */
    private fun remember() {
        from.add(Point(window, chain().filterNot { it.transient }))
        while (from.size > LIMIT) from.removeAt(0)
    }

    companion object {
        /** Звеньев в цепочке — не больше (М18). */
        const val LIMIT = 20
    }
}

/** Точка: окно и его цепочка подокон (МП М1). */
internal data class Point(val window: Window, val chain: List<Where>)

/** Куда приводит вход в окно: в точку окна или в начальное положение (МП М3). */
internal enum class Arrive(val code: String) { Point("point"), Start("start") }

/**
 * Способ перехода (МП М3, М15) — код латиницей для журнала. [arrive] — куда ведёт по умолчанию; [remembers] — пишет
 * ли переход в другое окно звено «откуда» (М7, М17).
 */
internal enum class Via(val code: String, val arrive: Arrive, val remembers: Boolean) {
    Open("open", Arrive.Point, false),
    Swipe("swipe", Arrive.Point, false),
    Rail("rail", Arrive.Point, false),
    Panel("panel", Arrive.Start, false),
    Switcher("switcher", Arrive.Start, false),
    HeaderNotice("header", Arrive.Start, true),
    SystemNotice("notice", Arrive.Start, true),
    Link("link", Arrive.Point, true),
    Pointer("pointer", Arrive.Start, false),
    Call("call", Arrive.Point, true),
}

/** Та же сущность — переписка той же, страница того же человека, любые настройки; остальное — по равенству. */
internal fun Where.sameAs(other: Where): Boolean = when (this) {
    is Where.Chat -> other is Where.Chat && other.chatId == chatId
    is Where.Person -> other is Where.Person && other.userId == userId
    is Where.Members -> other is Where.Members && other.groupId == groupId
    is Where.Access -> other is Where.Access && other.groupId == groupId
    is Where.Community -> other is Where.Community && other.communityId == communityId
    is Where.Showcase -> other is Where.Showcase && other.id == id && other.kind == kind
    is Where.Comments -> other is Where.Comments && other.channelId == channelId && other.postId == postId
    // Настройки — одно звено, что бы в них ни было открыто: пункт заменяет список и обратно, как до цепочек.
    is Where.Settings -> other is Where.Settings
    else -> this == other
}

/**
 * Черновое подокно — форма, а не место: новая переписка, новая группа, новый виртуальный аккаунт, привязка,
 * заверение, передача. В цепочке не держится (открытое поверх него его заменяет) и не хранится (МП М12).
 */
internal val Where.transient: Boolean
    get() = this == Where.New || this == Where.NewGroup || this == Where.NewVirtual ||
        this is Where.Link || this is Where.Certify || this is Where.Transfer

/** Что открыто в главной области. Вся навигация верхнего уровня — эти три случая. */
internal sealed interface Where {
    /** Ничего: на телефоне это список, на широком формате — пустая главная область. */
    data object Nothing : Where

    /** Подокно «новая переписка». */
    data object New : Where

    /**
     * Экран профиля: имя и ник (Д8).
     *
     * Отдельный экран, а не модалка: ник требует проверки занятости и показа ошибки,
     * аватар — загрузки картинки, и в модалке для этого тесно. Входа два и оба ведут
     * сюда: «Изменить» в переключении окон и «Профиль» в настройках.
     */
    data object Profile : Where

    /**
     * Своя страница «Я» (заказчик 2026-09-25): что известно о своём аккаунте, в том
     * числе что он временный. Правка профиля — кнопкой на ней, то есть [Profile].
     */
    data object SelfPage : Where

    /**
     * Подокно «новая группа».
     *
     * **Входа в него сейчас нет.** Чип «Группа» внизу списка переписок убран
     * 2026-09-03: в макете его нет, и появился он тогда, когда графического
     * интерфейса ещё не было. Подокно при этом собрано и работает; место входа —
     * каталог окна 2. Числится в `doc_mig/ИНТЕРФЕЙС/02-телефон/ФУНКЦИОНАЛ.md`,
     * раздел «нет входа», — иначе «сделано» неотличимо от «человек может этим
     * пользоваться».
     */
    data object NewGroup : Where

    /**
     * Подокно «участники группы».
     *
     * Открывается из окна переписки, а не из списка: состав — свойство открытой группы, и
     * попасть в него, не открыв её, значит спрашивать «чей состав».
     */
    data class Members(val groupId: String, val name: String?) : Where

    /**
     * Переписка. Имя хранится здесь, потому что строка в списке появится позже — потоком. [jumpTo] —
     * открыли, чтобы перейти к этому сообщению (буфер переходов, ПЛАН-(БП)).
     */
    data class Chat(val chatId: String, val name: String?, val jumpTo: Long? = null) : Where

    /** Доступ к закрытым записям: просьбы и выдача. Открывается из состава группы. */
    data class Access(val groupId: String, val name: String?) : Where

    /**
     * Личная страница человека — **заглушка** (заказчик 2026-09-19).
     *
     * Хранится только идентификатор: имя, ник и номер берутся из справочника и книги —
     * там же, где их берут списки. Второе имя здесь разошлось бы с первым.
     *
     * Входа два, и оба — «нажали на человека»: аватар в книге и аватар с именем в шапке
     * переписки. Так в макете `страница-гостя.html`: «у строки две цели».
     */
    data class Person(val userId: String) : Where

    /**
     * Страница сообщества: состав, описание, подписка (ПЛАН-(СО)-СООБЩЕСТВ С6).
     *
     * Хранится только идентификатор: название приезжает вместе со страницей, и держать
     * его здесь значило бы иметь два источника одного слова.
     */
    data class Community(val communityId: String) : Where

    /**
     * Окно «Описание» — сообщения уровня 0 группы, канала, сообщества (ПЛАН-(ЗО) ЗО4). Открывается
     * аватаром в разделах и текстом шапки; забирается при каждом открытии и нигде не хранится.
     */
    data class Showcase(val kind: String, val id: String, val title: String) : Where

    /**
     * Разговор под записью (ADR-0024).
     *
     * Адрес — канал и запись в нём: страница человека тоже канал, поэтому одного вида
     * достаточно и для ленты, и для канала.
     */
    data class Comments(val channelId: String, val postId: Long) : Where

    /**
     * Подтверждение привязки нового устройства.
     *
     * Приходит **снаружи**: человек навёл штатную камеру на код, та увидела `tima://link/…`
     * и открыла нас. Своего сканера у нас поэтому нет вовсе — и не нужно: чужой уже стоит
     * на каждом телефоне, а свой потребовал бы доступа к камере и объяснений, зачем он.
     */
    data class Link(val code: String) : Where

    /** Заверить устройство по отсканированному коду (Р32). */
    data class Certify(val code: String) : Where

    /**
     * Настройки: подокно с вкладками.
     *
     * Раньше «⚙» вело прямо в список устройств — тогда это было честно, потому что из
     * всех разделов существовал ровно один. Разделов стало три, и дверь снова одна.
     *
     * Вкладка хранится в состоянии, а не внутри подокна: человек, ушедший из настроек в
     * привязку устройства и вернувшийся назад, обязан вернуться на ту же вкладку, а не в
     * начало.
     */
    data class Settings(val item: SettingsItem? = null) : Where

    /**
     * Заведение виртуального аккаунта (ПЛАН-(Д)-КОНТАКТОВ.md, Д11).
     *
     * Открывается из переключения окон — оттуда же, где виден список аккаунтов. Это не
     * настройка: человек заводит второго себя, и место этому там, где он этих себя
     * выбирает.
     */
    data object NewVirtual : Where

    /**
     * Передача виртуального аккаунта (ПЛАН-(Д)-КОНТАКТОВ.md, Д12).
     *
     * @param virtualUserId кого передаём. `null` — мы принимающая сторона: принимающий
     *   не знает идентификатора до предъявления кода, и знать не должен.
     * @param brought код, принесённый снаружи: человек навёл штатную камеру на QR, та
     *   увидела `tima://transfer/…` и открыла нас. Пусто — пришли из настроек и код
     *   впишут руками.
     */
    data class Transfer(val virtualUserId: String?, val brought: String = "") : Where
}

/**
 * Код открытого подокна для журнала — латиницей, один на любом языке (заказчик
 * 2026-09-27). Что значит каждый — `doc_mig/ЖУРНАЛ-И-ОТЛАДКА/РЕЕСТР.md`, «Экраны».
 *
 * До 2026-09-27 здесь стояли русские названия: «настройки: Разрешения», «переписка».
 * Код не меняется от языка и от правки надписи, и отчёт находится поиском всегда.
 *
 * **Без идентификаторов.** `chat`, а не `chat:7f3a…`: читающему нужно знать, что человек
 * открыл переписку, а не какую, — идентификатор чужого разговора в хранилище отчётов не
 * нужен никому. Так же у страницы, группы, комментариев.
 */
internal fun whereCode(where: Where): String = when (where) {
    Where.Nothing -> "list"
    Where.New -> "chat.new"
    Where.Profile -> "profile"
    Where.SelfPage -> "page.self"
    Where.NewGroup -> "group.new"
    Where.NewVirtual -> "virtual.new"
    is Where.Members -> "group.members"
    is Where.Chat -> "chat"
    is Where.Person -> "page.person"
    is Where.Access -> "group.access"
    is Where.Comments -> "comments"
    is Where.Community -> "community"
    is Where.Showcase -> "showcase"
    is Where.Link -> "link.confirm"
    is Where.Certify -> "device.certify"
    is Where.Transfer -> if (where.virtualUserId == null) "account.take" else "account.give"
    // Пункт настроек — именем перечня: оно и есть ключ навигации, и латиницей.
    is Where.Settings -> "settings" + (where.item?.let { "." + it.name.lowercase() } ?: "")
}
