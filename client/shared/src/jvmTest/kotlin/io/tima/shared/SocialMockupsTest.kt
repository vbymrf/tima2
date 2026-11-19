package io.tima.shared

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Caption
import io.tima.core.ui.CheckMark
import io.tima.core.ui.Counter
import io.tima.core.ui.ExpandMark
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.Secondary
import io.tima.core.ui.SubwindowHeader
import io.tima.core.ui.Tab
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaColors
import io.tima.core.ui.TimaType
import io.tima.core.ui.WindowHeader
import io.tima.testui.capture
import java.io.File
import kotlin.test.Test

/**
 * Макеты Социума по выбору заказчика (ПЛАН-(СЦ) СЦ1б; выбор — §1.2, «Макеты одобряю»; уточнения — §1.3–§1.9).
 *
 * Не проверка, а генератор. Один вид строки: сообщение в две строки, тип значком справа, число новых
 * оранжевое при включённых уведомлениях и серое при выключенных, название зелёным там, где я владелец, админ
 * или модератор; строка сообщества — название, элемент, сообщение. Страница — вариант В: вкладки
 * «О сущности · Участники · Управление», суперсообщение без надписи, пузыри подписки и уведомлений в трёх
 * состояниях, «Показывать уровень сообщений», «Написать». «Управление» по ролям, вкладка «Участники»,
 * «Общение → Ответы» и «Обращения» (переписка «сущность — человек», СЦ15). Прежние варианты (строка в одну
 * строку, страницы А и Б) убраны.
 *
 * Запускается только с `TIMA_MOCKUPS=1`. Снимки — `build/снимки/макет-*.png`.
 */
class SocialMockupsTest {

    enum class Kind { Group, Channel, Community, Voice }
    enum class Reach { Personal, Public, Secret }

    /** Моя роль в сущности: от неё — цвет названия (§1.2, 3). */
    enum class Role { None, Member, Moderator, Admin, Owner }

    /** Уведомления сущности у меня (§1.5, 7): от них — цвет числа и пузырь. */
    enum class Notify { On, Off, Blocked }

    data class Element(val kind: Kind, val title: String)

    data class Entity(
        val title: String,
        val kind: Kind,
        val reach: Reach,
        val message: String,
        val time: String,
        val fresh: Int,
        val role: Role = Role.Member,
        val notify: Notify = Notify.On,
        val live: Boolean = false,
        /** У сообщества — элемент, из которого последнее сообщение (§1.3, 1). */
        val element: Element? = null,
    )

    @Test
    fun макеты_социума() {
        if (System.getenv("TIMA_MOCKUPS") != "1") return println("пропуск: макеты Социума — по TIMA_MOCKUPS=1")
        val themes = listOf("светлая" to TimaColors.light, "тёмная" to TimaColors.dark, "олива" to TimaColors.olive)
        for ((name, colors) in themes) {
            capture("макет-строки-$name", W, H, dark = false, colors = colors) { Catalogue(ENTITIES) }
        }
        capture("макет-строки-трудные", W, H_HARD, dark = false, colors = TimaColors.light) { Catalogue(HARD) }

        val pages = listOf(
            "страница-читатель-не-подписан" to @Composable { AboutPage(Role.None, subscribed = false, notify = Notify.Off) },
            "страница-читатель-подписан" to @Composable { AboutPage(Role.Member, subscribed = true, notify = Notify.On) },
            "страница-читатель-заблокировано" to @Composable { AboutPage(Role.Member, subscribed = true, notify = Notify.Blocked) },
            "страница-владелец" to @Composable { AboutPage(Role.Owner, subscribed = true, notify = Notify.On) },
            "управление-владелец" to @Composable { ManagePage(Role.Owner) },
            "управление-админ" to @Composable { ManagePage(Role.Admin) },
            "управление-модератор-группы" to @Composable { ManagePage(Role.Moderator) },
            "участники" to @Composable { MembersPage() },
            "ответы" to @Composable { AnswersPage() },
            "обращения" to @Composable { AppealsPage() },
        )
        for ((name, page) in pages) capture("макет-$name", W, H, dark = false, colors = TimaColors.light) { page() }
        capture("макет-страница-владелец-тёмная", W, H, dark = false, colors = TimaColors.dark) {
            AboutPage(Role.Owner, subscribed = true, notify = Notify.On)
        }
        File("build/снимки").mkdirs()
    }

    // ── строки ───────────────────────────────────────────────────────────────

    @Composable
    private fun Catalogue(list: List<Entity>) {
        Column(Modifier.fillMaxSize().background(Tima.colors.surface)) {
            WindowHeader(title = "Социум", logo = "T")
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tab("Каталог", current = true, onClick = {}, count = 4)
                Tab("Общая", current = false, onClick = {})
                Tab("Друзья", current = false, onClick = {})
            }
            for (e in list) EntityRow(e)
        }
    }

    /**
     * Строка Социума (§1.2, 1; СЦ3): аватар 56 под три строки; название (зелёное у своей роли); сообщение в две
     * строки, у сообщества — элемент и сообщение одной строкой; справа — значок типа, время, число новых.
     */
    @Composable
    private fun EntityRow(e: Entity) {
        ListLine(
            onClick = {},
            left = { BigAvatar(e) },
            right = {
                TypeIcon(e.kind, e.reach, 20.dp)
                Tertiary(e.time)
                if (e.fresh > 0) Fresh(e.fresh, e.notify == Notify.On) else Spacer(Modifier.height(22.dp))
            },
            middle = {
                Title(e.title, e.role)
                val element = e.element
                if (element != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        TypeIcon(element.kind, Reach.Personal, 14.dp)
                        Caption(element.title, fontSize = TimaType.sz5, weight = FontWeight.SemiBold, color = Tima.colors.text, lineOne = true)
                    }
                    Message(e, lines = 1)
                } else {
                    Message(e, lines = 2)
                }
            },
        )
    }

    /** Название: владелец, админ, модератор — зелёным (по умолчанию; цвет — в «Настройки → Вид → Социум»). */
    @Composable
    private fun Title(text: String, role: Role) {
        val own = role == Role.Owner || role == Role.Admin || role == Role.Moderator
        Caption(text, fontSize = TimaType.sz4, weight = FontWeight.Bold, color = if (own) Tima.colors.confirmed else Tima.colors.text, lineOne = true)
    }

    /** Число новых: оранжевое — уведомления включены, серое — выключены (§1.2, 1–2). */
    @Composable
    private fun Fresh(howMany: Int, on: Boolean) {
        if (on) return Counter(howMany)
        val colors = Tima.colors
        Box(
            Modifier.defaultMinSize(minWidth = 22.dp, minHeight = 22.dp).background(colors.quiet, CircleShape).padding(horizontal = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Caption(if (howMany > 99) "99+" else howMany.toString(), fontSize = TimaType.sz6, weight = FontWeight.ExtraBold, color = colors.text2)
        }
    }

    @Composable
    private fun Message(e: Entity, lines: Int) {
        val colors = Tima.colors
        Caption(
            text = AnnotatedString(e.message),
            fontSize = TimaType.sz5,
            color = if (e.live) colors.confirmed else colors.text2,
            weight = if (e.live) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = lines,
        )
    }

    @Composable
    private fun BigAvatar(e: Entity, side: Dp = AVATAR) {
        val colors = Tima.colors
        Box(
            Modifier.size(side).background(colors.softAccent, RoundedCornerShape(side * 0.24f)),
            contentAlignment = Alignment.Center,
        ) {
            Caption(e.title.take(2).uppercase(), fontSize = if (side > 60.dp) TimaType.sz2 else TimaType.sz4, weight = FontWeight.Bold, color = colors.text)
            if (e.live) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(3.dp).size(12.dp)
                        .background(colors.confirmed, CircleShape).border(2.dp, colors.surface, CircleShape),
                )
            }
        }
    }

    // ── значки типов ─────────────────────────────────────────────────────────

    /** Значок типа: вид сущности линиями, сверху справа — глобус (публичная) или замок (секретная). */
    @Composable
    private fun TypeIcon(kind: Kind, reach: Reach, side: Dp) {
        val ink = Tima.colors.text2
        val mark = Tima.colors.text
        Canvas(Modifier.size(side)) {
            val u = size.width / 16f
            val stroke = Stroke(width = 1.5f * u)
            when (kind) {
                Kind.Group -> {
                    drawCircle(ink, 2.2f * u, Offset(5f * u, 5f * u), style = stroke)
                    drawCircle(ink, 2.2f * u, Offset(10.5f * u, 5f * u), style = stroke)
                    drawArc(ink, 180f, 180f, false, Offset(1f * u, 9f * u), Size(8f * u, 8f * u), style = stroke)
                    drawArc(ink, 180f, 180f, false, Offset(6.5f * u, 9f * u), Size(8f * u, 8f * u), style = stroke)
                }
                Kind.Channel -> {
                    val p = Path().apply {
                        moveTo(2f * u, 6f * u); lineTo(6f * u, 6f * u); lineTo(12.5f * u, 2.5f * u)
                        lineTo(12.5f * u, 13.5f * u); lineTo(6f * u, 10f * u); lineTo(2f * u, 10f * u); close()
                    }
                    drawPath(p, ink, style = stroke)
                    drawLine(ink, Offset(5f * u, 10f * u), Offset(6.5f * u, 14f * u), strokeWidth = 1.5f * u)
                }
                Kind.Community -> {
                    val roof = Path().apply { moveTo(1.5f * u, 8f * u); lineTo(8f * u, 2f * u); lineTo(14.5f * u, 8f * u) }
                    drawPath(roof, ink, style = stroke)
                    drawRect(ink, Offset(3.5f * u, 7.5f * u), Size(9f * u, 7f * u), style = stroke)
                    drawRect(ink, Offset(6.8f * u, 10.5f * u), Size(2.4f * u, 4f * u), style = stroke)
                }
                Kind.Voice -> {
                    drawRoundRect(ink, Offset(5.5f * u, 1.5f * u), Size(5f * u, 8f * u), CornerRadius(2.5f * u), style = stroke)
                    drawArc(ink, 0f, 180f, false, Offset(3.5f * u, 4.5f * u), Size(9f * u, 8f * u), style = stroke)
                    drawLine(ink, Offset(8f * u, 12.5f * u), Offset(8f * u, 15f * u), strokeWidth = 1.5f * u)
                }
            }
            when (reach) {
                Reach.Public -> globe(mark, u)
                Reach.Secret -> lock(mark, u)
                Reach.Personal -> Unit
            }
        }
    }

    private fun DrawScope.globe(c: Color, u: Float) {
        val center = Offset(12.6f * u, 3.6f * u)
        drawCircle(c, 3.4f * u, center, style = Stroke(1.3f * u))
        drawLine(c, Offset(9.2f * u, 3.6f * u), Offset(16f * u, 3.6f * u), strokeWidth = 1.1f * u)
        drawOval(c, Offset(11.1f * u, 0.2f * u), Size(3f * u, 6.8f * u), style = Stroke(1.1f * u))
    }

    private fun DrawScope.lock(c: Color, u: Float) {
        drawArc(c, 180f, 180f, false, Offset(10.4f * u, 0f), Size(4.8f * u, 5.2f * u), style = Stroke(1.5f * u))
        drawRoundRect(c, Offset(9.4f * u, 2.8f * u), Size(6.8f * u, 5f * u), CornerRadius(1f * u))
    }

    // ── страница сущности: вариант В ─────────────────────────────────────────

    /** Вкладки по правам (§3): читатель видит одну «О сущности» — полосы вкладок у него нет. */
    private fun tabsFor(role: Role): List<String> = when (role) {
        Role.None, Role.Member -> emptyList()
        else -> listOf("О сообществе", "Участники", "Управление")
    }

    @Composable
    private fun PageTop(e: Entity, typeWord: String, counts: String, role: Role, current: Int) {
        val colors = Tima.colors
        SubwindowHeader(title = e.title, onBack = {}, caption = typeWord, avatar = e.title.take(2).uppercase())
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BigAvatar(e, 84.dp)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Title(e.title, role)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    TypeIcon(e.kind, e.reach, 16.dp)
                    Secondary(typeWord)
                }
                Tertiary(counts)
            }
        }
        val tabs = tabsFor(role)
        if (tabs.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tabs.forEachIndexed { i, t -> Tab(t, current = i == current, onClick = {}, count = if (t == "Управление") 4 else 0) }
            }
            Spacer(Modifier.height(6.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
    }

    /** Пузырь состояния (§1.2, 6): зелёный — да, серый — нет, красный — заблокировано. */
    @Composable
    private fun Pill(text: String, tone: Notify) {
        val colors = Tima.colors
        val fill = when (tone) {
            Notify.On -> colors.confirmed
            Notify.Off -> colors.quiet
            Notify.Blocked -> colors.alarm
        }
        Caption(
            text,
            Modifier.background(fill, CircleShape).padding(horizontal = 12.dp, vertical = 4.dp),
            fontSize = TimaType.sz6,
            weight = FontWeight.Bold,
            color = if (tone == Notify.Off) colors.text2 else colors.onAccent,
        )
    }

    /** «О сущности» (СЦ4). */
    @Composable
    private fun AboutPage(role: Role, subscribed: Boolean, notify: Notify) {
        val colors = Tima.colors
        Column(Modifier.fillMaxSize().background(colors.surface)) {
            PageTop(CLUB.copy(role = role), "публичное сообщество", "1 240 подписчиков · 3 группы · 1 канал · 1 звуковой чат", role, current = 0)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(if (subscribed) "Подписан" else "Не подписан", if (subscribed) Notify.On else Notify.Off)
                    Pill(
                        when (notify) {
                            Notify.On -> "Уведомления включены"
                            Notify.Off -> "Уведомления выключены"
                            Notify.Blocked -> "Уведомления заблокированы"
                        },
                        notify,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (subscribed) {
                        Button("Написать", onClick = {})
                        Button("Уведомления", onClick = {}, kind = ButtonKind.Quiet)
                        if (role != Role.Owner) Button("Выйти", onClick = {}, kind = ButtonKind.Quiet)
                    } else {
                        Button("Подписаться", onClick = {})
                        Button("Написать", onClick = {}, kind = ButtonKind.Quiet)
                    }
                }
                if (subscribed) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CheckMark(on = role == Role.Owner)
                        Secondary("Показывать уровень сообщений")
                    }
                }
            }
            SuperMessage()
            Inside()
        }
    }

    /** Суперсообщение — первым, без надписи над ним (§1.2, 5). */
    @Composable
    private fun SuperMessage() {
        val colors = Tima.colors
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()
                .border(1.dp, colors.border, RoundedCornerShape(12.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Secondary("Всё о саде и огороде: рассада, обрезка, обмен семенами. Весенний обмен — 22 марта, 11:00, оранжерея на Садовой.")
        }
    }

    /** Элементы сообщества — закреплённый список, от сообщений не двигается (§1.6, 1). */
    @Composable
    private fun Inside() {
        Column(Modifier.padding(top = 6.dp)) {
            Tertiary("ВНУТРИ", Modifier.padding(horizontal = 16.dp))
            EntityRow(Entity("Рассада и семена", Kind.Group, Reach.Public, "Ольга: у кого есть лишние томаты черри? Меняю на перец", "12:40", 3))
            EntityRow(Entity("Новости клуба", Kind.Channel, Reach.Public, "Субботняя встреча переносится на 10:00", "вчера", 1, notify = Notify.Off))
            EntityRow(Entity("Вечерний разговор", Kind.Voice, Reach.Public, "Идёт сейчас: 7 человек внутри", "сейчас", 0, live = true))
        }
    }

    // ── «Управление» по ролям (§3) ───────────────────────────────────────────

    @Composable
    private fun Section(title: String, hint: String, count: Int = 0, danger: Boolean = false) {
        ListLine(
            onClick = {},
            right = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (count > 0) Counter(count)
                    ExpandMark()
                }
            },
            middle = {
                Caption(title, fontSize = TimaType.sz4, weight = FontWeight.Bold, color = if (danger) Tima.colors.alarm else Tima.colors.text, lineOne = true)
                Secondary(hint, lineOne = true)
            },
        )
    }

    @Composable
    private fun ManagePage(role: Role) {
        Column(Modifier.fillMaxSize().background(Tima.colors.surface)) {
            if (role == Role.Moderator) {
                // У сообщества модераторов нет (§1.8, 2) — модератор показан на отдельной группе.
                val run = Entity("Бег по утрам", Kind.Group, Reach.Public, "", "", 0, role = role)
                PageTop(run, "публичная группа", "86 участников", role, current = 2)
                Section("Сообщения", "закреплённые")
                Section("Обращения", "ленты с людьми", count = 2)
                return@Column
            }
            PageTop(CLUB.copy(role = role), "публичное сообщество", "1 240 подписчиков · 3 группы · 1 канал · 1 звуковой чат", role, current = 2)
            Section("Основное", "аватар, название, описание, «секретное»")
            Section("Роли", if (role == Role.Owner) "владелец, 2 администратора" else "администраторы — у владельца")
            Section("Заявки", "просят вступить", count = 3)
            Section("Доступ", "уровень 3 и сроки")
            Section("Сообщения", if (role == Role.Owner) "суперсообщение, закреплённые, медленный режим" else "закреплённые, медленный режим")
            Section("Звуковые чаты", "создать, настройки, список · 1 идёт")
            Section("Обращения", "ленты с людьми, «Принимать сообщения», блокировка", count = 1)
            if (role == Role.Owner) Section("Опасное", "передать владение, удалить", danger = true)
        }
    }

    // ── «Участники» (СЦ6) ────────────────────────────────────────────────────

    @Composable
    private fun MembersPage() {
        Column(Modifier.fillMaxSize().background(Tima.colors.surface)) {
            PageTop(CLUB.copy(role = Role.Owner), "публичное сообщество", "1 240 подписчиков · 3 группы · 1 канал · 1 звуковой чат", Role.Owner, current = 1)
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("Пригласить", onClick = {})
                Button("Поделиться", onClick = {}, kind = ButtonKind.Quiet)
            }
            for ((name, what) in MEMBERS) {
                ListLine(
                    onClick = {},
                    left = { BigAvatar(Entity(name, Kind.Group, Reach.Personal, "", "", 0), 44.dp) },
                    middle = { Name(name); Secondary(what, lineOne = true) },
                    right = { ExpandMark() },
                )
            }
        }
    }

    // ── переписка «сущность — человек» (СЦ15, §1.9) ──────────────────────────

    /** «Общение → Ответы»: первая строка — название сущности, две — сообщение; строки «от кого» нет (§1.9, 5). */
    @Composable
    private fun AnswersPage() {
        Column(Modifier.fillMaxSize().background(Tima.colors.surface)) {
            WindowHeader(title = "Общение", logo = "T")
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tab("Чаты", current = false, onClick = {})
                Tab("Ответы", current = true, onClick = {}, count = 3)
            }
            for (e in ANSWERS) {
                ListLine(
                    onClick = {},
                    left = { BigAvatar(e) },
                    right = {
                        TypeIcon(e.kind, e.reach, 20.dp)
                        Tertiary(e.time)
                        if (e.fresh > 0) Fresh(e.fresh, e.notify == Notify.On) else Spacer(Modifier.height(22.dp))
                    },
                    middle = {
                        Title(e.title, Role.Member)
                        Message(e, lines = 2)
                    },
                )
            }
        }
    }

    /** «Управление → Обращения»: ленты сущности с людьми; прочитано — общее для всей команды (§1.9, 6). */
    @Composable
    private fun AppealsPage() {
        Column(Modifier.fillMaxSize().background(Tima.colors.surface)) {
            SubwindowHeader(title = "Обращения", onBack = {}, caption = CLUB.title, avatar = "КЛ")
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CheckMark(on = true)
                Secondary("Принимать сообщения")
            }
            Tertiary("Сегодня написали: 2 из 5", Modifier.padding(horizontal = 16.dp))
            for ((name, text, state) in APPEALS) {
                ListLine(
                    onClick = {},
                    left = { BigAvatar(Entity(name, Kind.Group, Reach.Personal, "", "", 0), 44.dp) },
                    middle = {
                        Name(name)
                        Caption(text, fontSize = TimaType.sz5, color = Tima.colors.text2, maxLines = 2)
                    },
                    right = {
                        Tertiary(state.first)
                        if (state.second > 0) Counter(state.second) else Spacer(Modifier.height(22.dp))
                    },
                )
            }
        }
    }

    private companion object {
        const val W = 380
        const val H = 800
        const val H_HARD = 520
        val AVATAR = 56.dp

        val CLUB = Entity("Клуб садоводов", Kind.Community, Reach.Public, "", "", 0)

        val ENTITIES = listOf(
            Entity("Семья", Kind.Group, Reach.Secret, "Мама: Ужин в семь, не опаздывайте. Купите по дороге хлеб и молоко", "14:05", 2, role = Role.Owner),
            Entity(
                "Клуб садоводов", Kind.Community, Reach.Public, "Ольга: у кого есть лишние томаты черри?", "12:40", 4,
                element = Element(Kind.Group, "Рассада и семена"),
            ),
            Entity("Дача 2026", Kind.Group, Reach.Personal, "Пётр: Кто везёт мангал? Могу взять уголь и шампуры", "12:10", 0),
            Entity("Бег по утрам", Kind.Group, Reach.Public, "Анна: Завтра в 6:30 у фонтана, берите воду", "11:02", 15, role = Role.Moderator, notify = Notify.Off),
            Entity("Новости района", Kind.Channel, Reach.Public, "Улицу Ленина перекрывают до пятницы: ремонт трубы у дома 12", "09:12", 128, notify = Notify.Off),
            Entity("Отдел продаж", Kind.Channel, Reach.Secret, "План на неделю: три встречи и отчёт к пятнице", "пн", 3, role = Role.Admin),
            Entity(
                "Выпуск-2010", Kind.Community, Reach.Secret, "Встреча выпускников 20 декабря, кафе «Сад»", "14.11", 1,
                role = Role.Owner, element = Element(Kind.Channel, "Объявления"),
            ),
        )

        val HARD = listOf(
            Entity("Очень длинное название группы, которое не помещается в строку целиком", Kind.Group, Reach.Public, "Сообщений пока нет", "—", 0, role = Role.None),
            Entity("Чат дома 7", Kind.Group, Reach.Personal, "Сосед: Кто оставил велосипед у лифта? Он мешает проходу с колясками, уберите, пожалуйста, до вечера", "23.10.2025", 9, notify = Notify.Off),
            Entity("Объявления", Kind.Channel, Reach.Public, "Фото", "10:01", 1000),
            Entity(
                "Сообщество с очень длинным названием про всё на свете", Kind.Community, Reach.Public,
                "Идёт сейчас: 7 человек внутри", "сейчас", 0, live = true,
                element = Element(Kind.Voice, "Вечерний разговор с очень длинным названием"),
            ),
            Entity("Мой клуб", Kind.Community, Reach.Personal, "Пока пусто", "—", 0, role = Role.Owner, element = Element(Kind.Group, "Общая")),
        )

        val MEMBERS = listOf(
            "Евгений" to "владелец",
            "Мария" to "администратор",
            "Олег" to "администратор",
            "Ольга" to "участник · Рассада и семена",
            "Пётр" to "участник",
            "Анна" to "заблокирован",
        )

        val ANSWERS = listOf(
            Entity("Клуб садоводов", Kind.Community, Reach.Public, "Ваша заявка на обмен принята. Приходите в субботу к 11:00, стол №4. — Мария", "15:20", 1),
            Entity("Новости района", Kind.Channel, Reach.Public, "Спасибо за сообщение о яме у дома 12, передали в управу", "13:02", 2),
            Entity("Бег по утрам", Kind.Group, Reach.Public, "Вы: Можно ли прийти с собакой?", "вчера", 0),
            Entity("Отдел продаж", Kind.Channel, Reach.Secret, "Ответ в комментариях: «Согласен, перенесём на четверг»", "пн", 0, notify = Notify.Off),
        )

        val APPEALS = listOf(
            Triple("Ольга", "Можно ли поменять рассаду томатов на перец? У меня десять кустов", "15:31" to 1),
            Triple("Пётр", "Клуб: Ваша заявка на обмен принята. Приходите в субботу к 11:00", "15:20" to 0),
            Triple("Анна", "Где посмотреть расписание субботних встреч?", "вчера" to 0),
        )
    }
}
