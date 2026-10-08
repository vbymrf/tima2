package io.tima.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import io.tima.core.ui.Caption
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words
import io.tima.domain.chat.ChatPerson
import io.tima.domain.chat.PersonField
import io.tima.domain.chat.PersonLook
import io.tima.domain.chat.StrangerLook
import io.tima.domain.chat.line

/** Кто человек строки для меня: от этого зависят слово и цвет имени (заказчик 2026-10-08). */
enum class PersonKind {
    /** В книге. */
    Known,

    /** Нет в книге — по «Незнакомых показывать как». */
    Stranger,

    /** Заблокирован — имя единым красным. */
    Blocked,
}

/**
 * Имя в строке списка — «Чаты», «Звонки» — по тому же правилу, что в окне звонка (заказчик
 * 2026-10-08).
 *
 * - **Заблокированный** — имя, «#имя» или «@ник» красным.
 * - **Незнакомый** — по «Вид» → «Незнакомых показывать как»: словом «Незнакомый» (за ним только
 *   имя, которым назвался, и ник), именем светло-оранжевым или как обычного.
 *
 * @param fontSize кегль; `null` — кегль имени строки.
 */
@Composable
internal fun PersonName(who: ChatPerson?, look: PersonLook, kind: PersonKind, fallback: String, fontSize: TextUnit? = null) {
    val colors = Tima.colors
    val size = fontSize ?: TimaType.sz4
    val weight = if (fontSize == null) FontWeight.Bold else FontWeight.Normal
    val usual = if (fontSize == null) colors.text else colors.text2
    @Composable
    fun Line(text: String, color: Color) = Caption(text, fontSize = size, weight = weight, color = color, lineOne = true)

    val name = who?.line(look, PERSON_FIRST_LINE) ?: fallback
    when {
        kind == PersonKind.Blocked -> Line(name, colors.alarm)
        kind == PersonKind.Stranger && look.stranger == StrangerLook.Word ->
            Row(horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about1), verticalAlignment = Alignment.CenterVertically) {
                Caption(Tima.words.call.stranger, fontSize = size, weight = FontWeight.Bold, color = colors.activity, lineOne = true)
                who?.line(look, SELF_NAMED)?.let { Line(it, usual) }
            }
        kind == PersonKind.Stranger && look.stranger == StrangerLook.Tinted -> Line(name, colors.activity)
        else -> Line(name, usual)
    }
}

/** У незнакомого словом — только то, как он назвал себя сам, и ник. */
private val SELF_NAMED = setOf(PersonField.UserName, PersonField.Nick)
