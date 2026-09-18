package io.tima.feature.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.tima.core.ui.SectionGlyph
import io.tima.core.ui.hasSectionGlyph
import io.tima.domain.chat.SectionIcon
import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Набор значков разделов в домене и его рисунки в `core-ui` — один набор.**
 *
 * Перечень [SectionIcon] и рисунки `SectionGlyph` живут в разных модулях: `core-ui` от
 * домена не зависит и рисует по индексу. Два списка на одну правду разошлись бы молча —
 * добавили значок в перечень, забыли рисунок, и раздел с ним показывает пустоту. Этот
 * тест стоит там, где видны оба.
 */
class SectionIconTest {

    @Test
    fun у_каждого_значка_перечня_есть_рисунок() {
        val missing = SectionIcon.choices.filterNot { hasSectionGlyph(it.index) }
        assertTrue(missing.isEmpty(), "в перечне есть, а рисунка нет: $missing")
    }

    @Test
    fun индексы_перечня_не_повторяются_и_идут_подряд() {
        // Индекс уезжает в базу и на другие устройства: дыра или повтор — это подмена
        // значков у чужих разделов. Подряд — потому что так виден сдвиг при правке.
        val indices = SectionIcon.entries.map { it.index }
        assertEquals(indices.toSet().size, indices.size, "индексы повторяются: $indices")
        assertEquals((0 until SectionIcon.entries.size).toList(), indices.sorted(), "индексы не подряд")
    }

    @Test
    fun каждый_значок_что_то_рисует() {
        // Пустой холст у значка — тот же провал, что отсутствие рисунка, только тише.
        for (icon in SectionIcon.choices) {
            val shot = capture("значок-${icon.name.lowercase()}", 40, 40, dark = false) {
                SectionGlyph(index = icon.index, modifier = Modifier.padding(8.dp))
            }
            val background = shot.color(0, 0)
            val inked = shot.pixels().count { it != background }
            assertTrue(inked > 20, "значок ${icon.title} (${icon.index}) не оставил следа на холсте")
        }
    }

    @Test
    fun снимок_набора_целиком() {
        // Не проверка, а картинка для глаз: build/снимки/значки-разделов-light.png.
        capture("значки-разделов", 12 * 40 + 16, 56, dark = false) {
            Row(Modifier.padding(8.dp)) {
                for (icon in SectionIcon.choices) {
                    SectionGlyph(index = icon.index, modifier = Modifier.padding(8.dp))
                }
            }
        }
    }
}
