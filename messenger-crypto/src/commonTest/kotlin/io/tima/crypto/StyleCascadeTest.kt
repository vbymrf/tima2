package io.tima.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Каскад стилей (ADR-0011 §9): порядок слоёв, режимы читателя, защитный пол. */
class StyleCascadeTest {

    @Test
    fun `фиксированный порядок - инлайн раньше типа раньше чата раньше сообщества раньше приложения`() {
        val resolved = StyleCascade.resolve(
            mode = ReaderMode.AS_AUTHOR,
            inline = mapOf("weight" to "inline"),
            byType = mapOf("weight" to "type", "align" to "type"),
            chat = mapOf("weight" to "chat", "align" to "chat", "gap" to "chat"),
            community = mapOf("weight" to "community", "align" to "community", "gap" to "community", "radius" to "community"),
            app = mapOf("weight" to "app", "align" to "app", "gap" to "app", "radius" to "app"),
        )
        assertEquals("inline", resolved["weight"]) // задан везде — побеждает самый ранний слой
        assertEquals("type", resolved["align"])    // не задан в inline — берём из type
        assertEquals("chat", resolved["gap"])      // не задан в inline/type — берём из chat
        assertEquals("community", resolved["radius"]) // не задан до сообщества
    }

    @Test
    fun `AS_AUTHOR - свойство только в приложении всё равно доходит`() {
        val resolved = StyleCascade.resolve(mode = ReaderMode.AS_AUTHOR, app = mapOf("font-size" to "18"))
        assertEquals("18", resolved["font-size"])
    }

    @Test
    fun `READER - документ и пространство игнорируются побеждает только приложение`() {
        val resolved = StyleCascade.resolve(
            mode = ReaderMode.READER,
            inline = mapOf("font-size" to "40"),
            byType = mapOf("font-size" to "30"),
            chat = mapOf("font-size" to "26"),
            community = mapOf("font-size" to "22"),
            app = mapOf("font-size" to "16"),
        )
        assertEquals("16", resolved["font-size"], "«моё оформление» — побеждают личные настройки читателя, не автора")
    }

    @Test
    fun `DEFAULT - ни один слой не участвует даже приложение`() {
        val resolved = StyleCascade.resolve(
            mode = ReaderMode.DEFAULT,
            inline = mapOf("font-size" to "40"),
            app = mapOf("font-size" to "16"),
        )
        // Свойства нет вовсе — только защитный пол выставит font-size в минимум.
        assertEquals(StyleCascade.MIN_FONT_SIZE_SP.toString(), resolved["font-size"])
    }

    @Test
    fun `защитный пол - полноэкранная заливка запрещена документу и пространству даже в AS_AUTHOR`() {
        val resolved = StyleCascade.resolve(
            mode = ReaderMode.AS_AUTHOR,
            inline = mapOf(StyleCascade.KEY_BACKGROUND to "#000000"),
            byType = mapOf(StyleCascade.KEY_BACKGROUND to "#000000"),
            chat = mapOf(StyleCascade.KEY_BACKGROUND to "#000000"),
            community = mapOf(StyleCascade.KEY_BACKGROUND to "#000000"),
        )
        assertNull(resolved[StyleCascade.KEY_BACKGROUND], "автор и пространство не могут залить экран")
    }

    @Test
    fun `защитный пол - приложение фон задавать может`() {
        val resolved = StyleCascade.resolve(mode = ReaderMode.AS_AUTHOR, app = mapOf(StyleCascade.KEY_BACKGROUND to "#101010"))
        assertEquals("#101010", resolved[StyleCascade.KEY_BACKGROUND], "тема приложения — не угроза, которую описывает ADR")
    }

    @Test
    fun `защитный пол - кегль ниже минимума поднимается выше минимума не трогается`() {
        val tooSmall = StyleCascade.resolve(mode = ReaderMode.AS_AUTHOR, inline = mapOf(StyleCascade.KEY_FONT_SIZE to "4"))
        assertEquals(StyleCascade.MIN_FONT_SIZE_SP.toString(), tooSmall[StyleCascade.KEY_FONT_SIZE])

        val fine = StyleCascade.resolve(mode = ReaderMode.AS_AUTHOR, inline = mapOf(StyleCascade.KEY_FONT_SIZE to "20"))
        assertEquals("20", fine[StyleCascade.KEY_FONT_SIZE])
    }

    @Test
    fun `защитный пол - недостаточный контраст сбрасывает цвет`() {
        // Белым по белому — ровно пример из ADR-0011 §9.
        val resolved = StyleCascade.resolve(
            mode = ReaderMode.AS_AUTHOR,
            inline = mapOf(StyleCascade.KEY_COLOR to "#FFFFFF"),
            backgroundHex = "#FFFFFF",
        )
        assertNull(resolved[StyleCascade.KEY_COLOR], "белым по белому не должно пройти защитный пол")
    }

    @Test
    fun `защитный пол - достаточный контраст цвет сохраняет`() {
        val resolved = StyleCascade.resolve(
            mode = ReaderMode.AS_AUTHOR,
            inline = mapOf(StyleCascade.KEY_COLOR to "#000000"),
            backgroundHex = "#FFFFFF",
        )
        assertEquals("#000000", resolved[StyleCascade.KEY_COLOR])
    }

    @Test
    fun `защитный пол - неразбираемый цвет считается не прошедшим проверку`() {
        val resolved = StyleCascade.resolve(
            mode = ReaderMode.AS_AUTHOR,
            inline = mapOf(StyleCascade.KEY_COLOR to "not-a-color"),
        )
        assertNull(resolved[StyleCascade.KEY_COLOR], "непроверяемое не считается прошедшим — fail-closed")
    }

    @Test
    fun `contrastRatio - крайние случаи`() {
        val blackWhite = StyleCascade.contrastRatio("#000000", "#FFFFFF")
        assertTrue(blackWhite != null && blackWhite > 20.0, "чёрный на белом — максимальный контраст, ~21:1")

        val whiteWhite = StyleCascade.contrastRatio("#FFFFFF", "#FFFFFF")
        assertTrue(whiteWhite != null && whiteWhite < StyleCascade.MIN_CONTRAST)

        assertNull(StyleCascade.contrastRatio("nope", "#FFFFFF"))
    }
}
