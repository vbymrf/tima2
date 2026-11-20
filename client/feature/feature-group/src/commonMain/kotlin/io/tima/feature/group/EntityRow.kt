package io.tima.feature.group

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Avatar
import io.tima.core.ui.Caption
import io.tima.core.ui.Counter
import io.tima.core.ui.ListLine
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaType
import io.tima.core.ui.words
import io.tima.domain.chat.EntityKind
import io.tima.domain.chat.EntityReach
import io.tima.domain.chat.EntityType
import io.tima.domain.chat.GroupRole

/**
 * Что строка Социума показывает о сущности сверх названия (ПЛАН-(СЦ) СЦ3): последнее сообщение, его время, число
 * новых и включены ли уведомления; у сообщества — элемент, откуда последнее.
 */
data class RowLook(
    val preview: String? = null,
    val atMs: Long? = null,
    /** Время последнего словами — готовым: сегодня часы, иначе число и месяц (форматирует сборка). */
    val time: String = "",
    val fresh: Int = 0,
    val muted: Boolean = false,
    /** Сообщество (§1.3, 1): вид и название элемента, откуда последнее сообщение. */
    val element: Pair<EntityKind, String>? = null,
    /** Звуковой чат: идёт разговор — зелёная точка на аватаре. */
    val live: Boolean = false,
    /** Аватар сущности — `tima-media://<номер>` (СЦ5); `null` — буквы. */
    val picture: String? = null,
)

/** Метка строки Социума — для сценариев; нужная строка — по названию внутри. */
const val SOCIAL_ROW_TAG = "social:row"

/**
 * Строка Социума по выбранному макету (СЦ1б, одобрено 2026-11-19): аватар 56; название — зелёным, где я владелец,
 * админ или модератор (цвет — настройкой «Вид → Социум»); сообщение в две строки, у сообщества — элемент и
 * сообщение одной строкой; справа — тип значком, время, число новых: оранжевое при включённых уведомлениях,
 * серое — при выключенных.
 */
@Composable
fun EntityRow(
    title: String,
    type: EntityType,
    role: GroupRole?,
    look: RowLook,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    avatar: @Composable () -> Unit = { Avatar(letters = title.take(2).uppercase(), size = io.tima.core.ui.AvatarSize.Row) },
    /** Цвет названия у своей роли; `null` — цвет темы `confirmed` (зелёный, по умолчанию). */
    ownColor: Color? = null,
) {
    val colors = Tima.colors
    val words = Tima.words.socium
    ListLine(
        modifier = modifier.testTag(SOCIAL_ROW_TAG).semantics { contentDescription = "$title, ${type.words(words)}" },
        onClick = onClick,
        left = {
            Box {
                avatar()
                if (look.live) {
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(3.dp).size(12.dp)
                            .background(colors.confirmed, CircleShape).border(2.dp, colors.surface, CircleShape),
                    )
                }
            }
        },
        // Две строки (заказчик 2026-11-20): название; значок вида с меткой рядом, положение и 🔕. Справа — сверху
        // время или дата, снизу число новых. Текста последнего сообщения в строке нет.
        right = {
            Tertiary(look.time)
            if (look.fresh > 0) Fresh(look.fresh, on = !look.muted) else Spacer(Modifier.height(22.dp))
        },
        middle = {
            val own = role == GroupRole.Owner || role == GroupRole.Admin || role == GroupRole.Moderator
            Caption(title, fontSize = TimaType.sz4, weight = FontWeight.Bold, color = if (own) ownColor ?: colors.confirmed else colors.text, lineOne = true)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TypeIcon(type, 18.dp, markBeside = true)
                Caption(standing(type, role), fontSize = TimaType.sz5, color = if (look.live) colors.confirmed else colors.text2, lineOne = true)
                if (look.muted) Caption("🔕", fontSize = TimaType.sz5, lineOne = true)
            }
        },
    )
}

/**
 * Положение в сущности одним словом: роль — «владелец», «админ», «модератор»; без роли — в группе «участник», в
 * канале и сообществе «подписан». В каталоге только своё, поэтому «не участник» и «не подписан» здесь не бывает.
 */
@Composable
private fun standing(type: EntityType, role: GroupRole?): String {
    val w = Tima.words.social
    return when (role) {
        GroupRole.Owner -> w.owner
        GroupRole.Admin -> w.admin
        GroupRole.Moderator -> w.moderator
        else -> if (type.kind == EntityKind.Group) w.member else Tima.words.socium.rowSubscribed
    }
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

/**
 * Значок типа (§1.1, 3): две головы — группа, рупор — канал, дом — сообщество, микрофон — звуковой чат; сверху
 * справа — глобус у публичной, замок у секретной, у личной метки нет.
 */
@Composable
fun TypeIcon(
    type: EntityType,
    side: Dp,
    modifier: Modifier = Modifier,
    /** Метку (глобус, замок) — справа от значка, а не поверх него (заказчик 2026-11-20); холст шире в полтора раза. */
    markBeside: Boolean = false,
) {
    val ink = Tima.colors.text2
    val mark = Tima.colors.text
    val hasMark = type.secret || type.reach == EntityReach.Public
    Canvas(modifier.size(width = if (markBeside && hasMark) side * 1.5f else side, height = side)) {
        val u = size.width / 16f
        val stroke = Stroke(width = 1.5f * u)
        when (type.kind) {
            EntityKind.Group -> {
                drawCircle(ink, 2.2f * u, Offset(5f * u, 5f * u), style = stroke)
                drawCircle(ink, 2.2f * u, Offset(10.5f * u, 5f * u), style = stroke)
                drawArc(ink, 180f, 180f, false, Offset(1f * u, 9f * u), Size(8f * u, 8f * u), style = stroke)
                drawArc(ink, 180f, 180f, false, Offset(6.5f * u, 9f * u), Size(8f * u, 8f * u), style = stroke)
            }
            EntityKind.Channel -> {
                val p = Path().apply {
                    moveTo(2f * u, 6f * u); lineTo(6f * u, 6f * u); lineTo(12.5f * u, 2.5f * u)
                    lineTo(12.5f * u, 13.5f * u); lineTo(6f * u, 10f * u); lineTo(2f * u, 10f * u); close()
                }
                drawPath(p, ink, style = stroke)
                drawLine(ink, Offset(5f * u, 10f * u), Offset(6.5f * u, 14f * u), strokeWidth = 1.5f * u)
            }
            EntityKind.Community -> {
                val roof = Path().apply { moveTo(1.5f * u, 8f * u); lineTo(8f * u, 2f * u); lineTo(14.5f * u, 8f * u) }
                drawPath(roof, ink, style = stroke)
                drawRect(ink, Offset(3.5f * u, 7.5f * u), Size(9f * u, 7f * u), style = stroke)
                drawRect(ink, Offset(6.8f * u, 10.5f * u), Size(2.4f * u, 4f * u), style = stroke)
            }
            EntityKind.Voice -> {
                drawRoundRect(ink, Offset(5.5f * u, 1.5f * u), Size(5f * u, 8f * u), CornerRadius(2.5f * u), style = stroke)
                drawArc(ink, 0f, 180f, false, Offset(3.5f * u, 4.5f * u), Size(9f * u, 8f * u), style = stroke)
                drawLine(ink, Offset(8f * u, 12.5f * u), Offset(8f * u, 15f * u), strokeWidth = 1.5f * u)
            }
        }
        // Метка рисуется в правом верхнем углу значка; «рядом» — та же фигура, сдвинутая за правый край значка.
        val shift = if (markBeside) 7.8f * u else 0f
        translate(left = shift) {
            when {
                type.secret -> lock(mark, u)
                type.reach == EntityReach.Public -> globe(mark, u)
                else -> Unit
            }
        }
    }
}

private fun DrawScope.globe(c: Color, u: Float) {
    drawCircle(c, 3.4f * u, Offset(12.6f * u, 3.6f * u), style = Stroke(1.3f * u))
    drawLine(c, Offset(9.2f * u, 3.6f * u), Offset(16f * u, 3.6f * u), strokeWidth = 1.1f * u)
    drawOval(c, Offset(11.1f * u, 0.2f * u), Size(3f * u, 6.8f * u), style = Stroke(1.1f * u))
}

private fun DrawScope.lock(c: Color, u: Float) {
    drawArc(c, 180f, 180f, false, Offset(10.4f * u, 0f), Size(4.8f * u, 5.2f * u), style = Stroke(1.5f * u))
    drawRoundRect(c, Offset(9.4f * u, 2.8f * u), Size(6.8f * u, 5f * u), CornerRadius(1f * u))
}
