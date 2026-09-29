package io.tima.core.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Пресет строкой и обратно.
 *
 * Сравнивать прогоны можно только тогда, когда известно, что именно сравнивалось. Пресет,
 * который не пережил закрытие приложения или вернулся на треть угаданным, делает
 * бессмысленным весь забег — поэтому здесь проверяется не «работает», а **что именно
 * теряется, когда не работает**.
 */
class PresetWireTest {

    @Test
    fun пресет_возвращается_таким_же_до_последнего_поля() {
        val был = PublishPreset(
            name = "VP9 SVC, запасной H.264",
            video = VideoPreset(
                codec = VideoCodec.VP9,
                layers = LayerMode.Svc,
                width = 1280,
                height = 720,
                fps = 30,
                bitrate = 1_500_000,
                maxBitrate = 2_000_000,
                backup = VideoCodec.H264,
                scalability = "L3T3",
                degradation = Degradation.MaintainFramerate,
                dynacast = false,
                adaptiveStream = false,
                align16 = true,
            ),
            audio = AudioPreset(red = false, dtx = false, bitrate = 32_000, stereo = true),
        )

        assertEquals(был, presetFromWire(был.toWire()))
    }

    @Test
    fun разделитель_в_имени_не_ломает_разбор() {
        // «H.264 | один слой» человек наберёт первым же делом: так он и читается. Имя
        // стоит последним и забирает хвост целиком — ровно ради этого случая.
        val был = PublishPreset(name = "H.264 | один слой | 640×480")

        val стал = presetFromWire(был.toWire())

        assertEquals("H.264 | один слой | 640×480", стал?.name)
        assertEquals(был, стал)
    }

    @Test
    fun запасного_кодека_может_не_быть() {
        // «Без запасного» — осмысленный выбор прогона: он проверяет, что будет, когда
        // второй телефон основной кодек не примет.
        val был = PublishPreset(name = "без запасного", video = VideoPreset(backup = null))

        val стал = presetFromWire(был.toWire())

        assertNull(стал?.video?.backup)
        assertEquals(был, стал)
    }

    @Test
    fun испорченная_строка_даёт_null_а_не_догадку() {
        // Пресет — испытательная настройка, и потерять его дешевле, чем показать человеку
        // набор, который на треть угадан: по такому прогону сделают вывод о кодеке.
        for (мусор in listOf("", "h264", "не пресет вовсе", "av1|Single|640|480|15|1|1|—|L3T3_KEY|Balanced|1|1|1|1|1|0|имя")) {
            assertNull(presetFromWire(мусор), "строка «$мусор» обязана быть отвергнута")
        }
    }

    @Test
    fun пресет_без_имени_отвергается() {
        // Безымянный пресет нельзя назвать в отчёте, а значит и сравнить с другим.
        assertNull(presetFromWire(PublishPreset(name = "  ").toWire()))
    }

    @Test
    fun набор_переживает_испорченную_строку_в_середине() {
        // Одна битая запись не должна уносить с собой весь набор: остальные пресеты в нём
        // целы, и терять их не за что.
        val набор = listOf(PublishPreset(name = "первый"), PublishPreset(name = "второй"))
        val строка = набор.toWire().replace("второй", "второй") // набор как есть
        val сломанный = строка + "\nмусор\n" + PublishPreset(name = "третий").toWire()

        val стал = presetsFromWire(сломанный)

        assertEquals(listOf("первый", "второй", "третий"), стал.map { it.name })
        assertTrue(стал.size == 3, "битая строка унесла соседей: ${стал.map { it.name }}")
    }

    @Test
    fun строка_прежнего_вида_читается_без_кратности() {
        // Так лежал выбранный набор до кратности 16. Не прочитайся он — прогон молча
        // пошёл бы с умолчанием, а назывался бы прежним именем.
        val прежняя = "h264|Single|640|480|15|800000|800000|—|L3T3_KEY|Balanced|1|1|1|1|24000|0|VP8 z"

        val стал = presetFromWire(прежняя)

        assertEquals("VP8 z", стал?.name)
        assertEquals(Degradation.Balanced, стал?.video?.degradation)
        assertEquals(false, стал?.video?.align16)
    }

    @Test
    fun метка_с_непонятной_кратностью_отвергается() {
        assertNull(presetFromWire("v2|да|" + PublishPreset(name = "x").toWire().substringAfter("v2|0|")))
        assertNull(presetFromWire("v2|1"))
        assertNull(presetFromWire("v2|1x|" + PublishPreset(name = "x").toWire().substringAfter("v2|0|")))
    }

    @Test
    fun способ_кратности_переживает_запись() {
        // Галочки под «Кратность» (заказчик 2026-09-29): потеряйся способ — прогон с
        // «Обрезка кодером» молча пошёл бы заявкой и мерил бы не то.
        for (способ in listOf(
            VideoPreset(align16 = true, alignCrop = true),
            VideoPreset(align16 = true, alignSingle = true),
            VideoPreset(align16 = true),
        )) {
            val был = PublishPreset(name = "h264 960 4 к", video = способ)
            assertEquals(был, presetFromWire(был.toWire()))
        }
    }

    @Test
    fun строка_с_кратностью_без_способа_это_заявка_на_все_слои() {
        // Так лежат наборы, запомненные до галочек.
        val прежняя = PublishPreset(name = "к", video = VideoPreset(align16 = true)).toWire()

        assertEquals(Alignment.RequestAllLayers, presetFromWire(прежняя)?.video?.alignment)
    }
}
