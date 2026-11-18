package io.tima.shared

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import io.tima.core.ui.Counter
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.Secondary
import io.tima.core.ui.SubwindowHeader
import io.tima.core.ui.Tab
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaColors
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.WindowHeader
import io.tima.testui.capture
import java.io.File
import kotlin.test.Test

/**
 * Макеты Социума (ПЛАН-(СЦ) СЦ1; заказчик 2026-11-18: «Нужно что бы там было три строки. Аватар под три строки.
 * Название, тип сущности, строка с последним сообщением… время и количество новых… Делаем два варианта», «Делай
 * шас макеты. Будем смотреть», «Значком» — тип справа значком).
 *
 * Не проверка, а генератор: строки окна «Социум» в двух вариантах (сообщение в две строки — тип значком справа;
 * в одну — тип и состояние словами второй строкой) в светлой, тёмной теме и «Оливе», трудные случаи, и варианты
 * страницы сущности и управления А, Б, В (§3 плана). Запускается только с `TIMA_MOCKUPS=1`. Снимки —
 * `build/снимки/макет-*.png`.
 */
class SocialMockupsTest {

    enum class Kind { Group, Channel, Community, Voice }
    enum class Reach { Personal, Public, Secret }

    data class Entity(
        val title: String,
        val kind: Kind,
        val reach: Reach,
        val typeWord: String,
        val state: String,
        val message: String,
        val time: String,
        val fresh: Int,
        val live: Boolean = false,
    )

    @Test
    fun макеты_социума() {
        if (System.getenv("TIMA_MOCKUPS") != "1") return println("пропуск: макеты Социума — по TIMA_MOCKUPS=1")
        val themes = listOf("светлая" to TimaColors.light, "тёмная" to TimaColors.dark, "олива" to TimaColors.olive)
        for ((name, colors) in themes) {
            capture("макет-строки-две-$name", W, H, dark = false, colors = colors) { catalogue(ENTITIES) { RowTwo(it) } }
            capture("макет-строки-одна-$name", W, H, dark = false, colors = colors) { catalogue(ENTITIES) { RowOne(it) } }
        }
        capture("макет-строки-трудные-две", W, H_HARD, dark = false, colors = TimaColors.light) { catalogue(HARD) { RowTwo(it) } }
        capture("макет-строки-трудные-одна", W, H_HARD, dark = false, colors = TimaColors.light) { catalogue(HARD) { RowOne(it) } }

        val pages = listOf(
            "А-читатель" to @Composable { PageA(owner = false) },
            "А-владелец" to @Composable { PageA(owner = true) },
            "Б-читатель" to @Composable { PageB(owner = false) },
            "Б-владелец" to @Composable { PageB(owner = true) },
            "Б-управление" to @Composable { ManageB() },
            "В-читатель" to @Composable { PageC(owner = false) },
            "В-владелец" to @Composable { PageC(owner = true) },
        )
        for ((name, page) in pages) capture("макет-страница-$name", W, H, dark = false, colors = TimaColors.light) { page() }
        File("build/снимки").mkdirs()
    }

    // ── строки ───────────────────────────────────────────────────────────────

    @Composable
    private fun catalogue(list: List<Entity>, row: @Composable (Entity) -> Unit) {
        Column(Modifier.fillMaxSize().background(Tima.colors.surface)) {
            WindowHeader(title = "Социум", logo = "T")
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tab("Каталог", current = true, onClick = {}, count = 4)
                Tab("Общая", current = false, onClick = {})
                Tab("Друзья", current = false, onClick = {})
            }
            for (e in list) row(e)
        }
    }

    /** Вариант 1: сообщение в две строки; справа — тип значком, время, число новых. */
    @Composable
    private fun RowTwo(e: Entity) {
        ListLine(
            onClick = {},
            left = { BigAvatar(e) },
            right = {
                TypeIcon(e.kind, e.reach, 20.dp)
                Tertiary(e.time)
                if (e.fresh > 0) Counter(e.fresh) else Spacer(Modifier.height(18.dp))
            },
            middle = {
                Name(e.title)
                Message(e, lines = 2)
            },
        )
    }

    /** Вариант 2: сообщение в одну строку; второй строкой — тип и состояние словами, справа — время и число новых. */
    @Composable
    private fun RowOne(e: Entity) {
        ListLine(
            onClick = {},
            left = { BigAvatar(e) },
            right = {
                Tertiary(e.time)
                if (e.fresh > 0) Counter(e.fresh) else Spacer(Modifier.height(18.dp))
            },
            middle = {
                Name(e.title)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    TypeIcon(e.kind, e.reach, 14.dp)
                    Tertiary("${e.typeWord}, ${e.state}", lineOne = true)
                }
                Message(e, lines = 1)
            },
        )
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

    // ── страница сущности и управление: варианты А, Б, В ─────────────────────

    @Composable
    private fun PageTop(owner: Boolean, tabs: List<String>? = null, current: Int = 0) {
        val colors = Tima.colors
        SubwindowHeader(title = CLUB.title, onBack = {}, caption = "сообщество", avatar = "КС")
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BigAvatar(CLUB, 84.dp)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Caption(CLUB.title, fontSize = TimaType.sz3, weight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        TypeIcon(Kind.Community, Reach.Public, 16.dp)
                        Secondary("публичное сообщество")
                    }
                    Tertiary("1 240 подписчиков · 4 группы · 1 канал")
                    Tertiary(if (owner) "вы владелец" else "вы подписаны")
                }
            }
            Secondary("Всё о саде и огороде: рассада, обрезка, обмен семенами. Встречи по субботам.")
        }
        if (tabs != null) {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tabs.forEachIndexed { i, t -> Tab(t, current = i == current, onClick = {}) }
            }
            Spacer(Modifier.height(6.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
    }

    @Composable
    private fun ReaderButtons(owner: Boolean, manage: Boolean) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (owner && manage) Button("Управление", onClick = {}) else Button(if (owner) "Написать всем" else "Подписаны", onClick = {}, kind = ButtonKind.Quiet)
            Button("Уведомления", onClick = {}, kind = ButtonKind.Quiet)
        }
    }

    @Composable
    private fun LevelZero() {
        val colors = Tima.colors
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()
            .border(1.dp, colors.border, RoundedCornerShape(12.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Tertiary("Суперсообщение · для всех")
            Secondary("Весенний обмен семенами — 22 марта, 11:00, оранжерея на Садовой. Приносите подписанные пакетики.")
        }
    }

    @Composable
    private fun Inside() {
        Column(Modifier.padding(top = 6.dp)) {
            Tertiary("  ВНУТРИ", Modifier.padding(horizontal = 12.dp))
            RowOne(Entity("Рассада и семена", Kind.Group, Reach.Public, "публичная группа", "вы участник", "Ольга: у кого есть лишние томаты черри?", "12:40", 3))
            RowOne(Entity("Вечерний разговор", Kind.Voice, Reach.Public, "звуковой чат", "идёт сейчас", "Говорят 5, слушают 23", "сейчас", 0, live = true))
            RowOne(Entity("Новости клуба", Kind.Channel, Reach.Public, "публичный канал", "уведомления вкл", "Субботняя встреча переносится на 10:00", "вчера", 1))
        }
    }

    @Composable
    private fun EditLine(title: String, hint: String, count: Int = 0) {
        ListLine(
            onClick = {},
            right = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (count > 0) Counter(count)
                    Caption("›", fontSize = TimaType.sz3, color = Tima.colors.text3)
                }
            },
            middle = { Name(title); Secondary(hint, lineOne = true) },
        )
    }

    /** А: одна страница, управление — на ней же по роли. */
    @Composable
    private fun PageA(owner: Boolean) {
        Column(Modifier.fillMaxSize().background(Tima.colors.surface)) {
            PageTop(owner)
            ReaderButtons(owner, manage = false)
            if (owner) {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button("Аватар", onClick = {}, kind = ButtonKind.Quiet)
                    Button("Название", onClick = {}, kind = ButtonKind.Quiet)
                    Button("Описание", onClick = {}, kind = ButtonKind.Quiet)
                }
                EditLine("Участники", "1 240 · роли, бан, исключить")
                EditLine("Заявки", "просят вступить", 3)
                EditLine("Сообщения", "суперсообщение, уровень 0, закреплённые")
            }
            LevelZero()
            Inside()
        }
    }

    /** Б: страница для всех; у владельца — кнопка «Управление», дальше — подокно разделов. */
    @Composable
    private fun PageB(owner: Boolean) {
        Column(Modifier.fillMaxSize().background(Tima.colors.surface)) {
            PageTop(owner)
            ReaderButtons(owner, manage = true)
            LevelZero()
            Inside()
        }
    }

    @Composable
    private fun ManageB() {
        Column(Modifier.fillMaxSize().background(Tima.colors.surface)) {
            SubwindowHeader(title = "Управление", onBack = {}, caption = CLUB.title, avatar = "КС")
            EditLine("Основное", "аватар, название, описание, тип, «секретное»")
            EditLine("Участники", "1 240 · поиск, добавить, исключить, бан")
            EditLine("Роли", "владелец, 2 администратора, 3 модератора")
            EditLine("Заявки", "просят вступить", 3)
            EditLine("Доступ", "уровень 3 и сроки")
            EditLine("Сообщения", "суперсообщение, уровень 0, закреплённые")
            EditLine("Звуковые чаты", "создать, список · 1 идёт")
            EditLine("Опасное", "передать владение, удалить")
        }
    }

    /** В: вкладки на странице. */
    @Composable
    private fun PageC(owner: Boolean) {
        val tabs = if (owner) listOf("О сообществе", "Участники", "Управление") else listOf("О сообществе", "Участники")
        Column(Modifier.fillMaxSize().background(Tima.colors.surface)) {
            PageTop(owner, tabs, current = if (owner) 2 else 0)
            if (owner) {
                EditLine("Основное", "аватар, название, описание, тип")
                EditLine("Роли", "владелец, 2 администратора, 3 модератора")
                EditLine("Заявки", "просят вступить", 3)
                EditLine("Сообщения", "суперсообщение, уровень 0")
                EditLine("Звуковые чаты", "создать, список")
                EditLine("Опасное", "передать владение, удалить")
            } else {
                ReaderButtons(owner, manage = false)
                LevelZero()
                Inside()
            }
        }
    }

    private companion object {
        const val W = 380
        const val H = 800
        const val H_HARD = 480
        val AVATAR = 56.dp

        val CLUB = Entity("Клуб садоводов", Kind.Community, Reach.Public, "сообщество", "подписаны", "", "", 0)

        val ENTITIES = listOf(
            Entity("Семья", Kind.Group, Reach.Secret, "секретная группа", "уведомления вкл", "Мама: Ужин в семь, не опаздывайте. Купите по дороге хлеб и молоко", "14:05", 2),
            Entity("Дача 2026", Kind.Group, Reach.Personal, "личная группа", "вы участник", "Пётр: Кто везёт мангал? Могу взять уголь и шампуры", "12:40", 0),
            Entity("Бег по утрам", Kind.Group, Reach.Public, "публичная группа", "уведомления выкл", "Анна: Завтра в 6:30 у фонтана, берите воду", "вчера", 15),
            Entity("Новости района", Kind.Channel, Reach.Public, "публичный канал", "уведомления вкл", "Улицу Ленина перекрывают до пятницы: ремонт трубы у дома 12", "09:12", 128),
            Entity("Отдел продаж", Kind.Channel, Reach.Secret, "секретный канал", "вы автор", "План на неделю: три встречи и отчёт к пятнице", "пн", 3),
            Entity("Клуб садоводов", Kind.Community, Reach.Public, "сообщество", "подписаны", "Новая группа в сообществе: «Рассада и семена»", "16.11", 0),
            Entity("Выпуск-2010", Kind.Community, Reach.Secret, "секретное сообщество", "вы владелец", "Встреча выпускников 20 декабря, кафе «Сад»", "14.11", 1),
            Entity("Вечерний разговор", Kind.Voice, Reach.Public, "звуковой чат", "Клуб садоводов", "Идёт сейчас: говорят 5, слушают 23", "сейчас", 0, live = true),
        )

        val HARD = listOf(
            Entity("Очень длинное название группы, которое не помещается в строку целиком", Kind.Group, Reach.Public, "публичная группа", "не подписаны", "Сообщений пока нет", "—", 0),
            Entity("Чат дома 7", Kind.Group, Reach.Personal, "личная группа", "уведомления выкл", "Сосед: Кто оставил велосипед у лифта? Он мешает проходу с колясками, уберите, пожалуйста, до вечера", "23.10.2025", 9),
            Entity("Объявления", Kind.Channel, Reach.Public, "публичный канал", "не подписаны", "Фото", "10:01", 1000),
            Entity("Тихий вечер", Kind.Voice, Reach.Secret, "звуковой чат", "закончился", "Был вчера, 40 минут", "вчера", 0),
        )
    }
}
