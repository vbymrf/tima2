package io.tima.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import io.tima.core.ui.Caption
import io.tima.core.ui.Name
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words
import io.tima.domain.chat.ChatPerson
import io.tima.domain.chat.PersonField
import io.tima.domain.chat.PersonLook
import io.tima.domain.chat.line

/**
 * Имя в строке списка — «Чаты», «Звонки» — по тому же правилу, что в окне звонка (заказчик
 * 2026-10-08): человека нет в книге — впереди «Незнакомый» светло-оранжевым, а за ним только
 * то, как он назвал себя сам или его ник, по «Виду». Имени, данного мной, у незнакомого нет,
 * и «Без имени» там врало бы: его не не назвали, его не записали.
 *
 * @param fontSize кегль; `null` — кегль имени строки.
 */
@Composable
internal fun PersonName(who: ChatPerson?, look: PersonLook, stranger: Boolean, fallback: String, fontSize: TextUnit? = null) {
    if (!stranger) {
        val text = who?.line(look, PERSON_FIRST_LINE) ?: fallback
        if (fontSize == null) Name(text) else Caption(text, fontSize = fontSize, color = Tima.colors.text2, lineOne = true)
        return
    }
    val size = fontSize ?: TimaType.sz4
    Row(horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about1), verticalAlignment = Alignment.CenterVertically) {
        Caption(Tima.words.call.stranger, fontSize = size, weight = FontWeight.Bold, color = Tima.colors.activity, lineOne = true)
        who?.line(look, SELF_NAMED)?.let { name ->
            if (fontSize == null) Name(name) else Caption(name, fontSize = size, color = Tima.colors.text2, lineOne = true)
        }
    }
}

/** У незнакомого — только то, как он назвал себя сам, и ник. */
private val SELF_NAMED = setOf(PersonField.UserName, PersonField.Nick)
