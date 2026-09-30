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
                noCrop = true,
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
        assertEquals(false, стал?.video?.noCrop)
    }

    @Test
    fun метка_с_непонятной_кратностью_отвергается() {
        assertNull(presetFromWire("v2|да|" + PublishPreset(name = "x").toWire().substringAfter("v2|0|")))
        assertNull(presetFromWire("v2|1"))
        assertNull(presetFromWire("v2|1x|" + PublishPreset(name = "x").toWire().substringAfter("v2|0|")))
    }

    @Test
    fun без_обрезки_переживает_запись() {
        // Снятая галочка «Обрезка до кратного 16» (ПЛАН-ВИДЕО.md В2.3): потеряйся она — прогон, который
        // воспроизводит прежнюю беду, молча пошёл бы с обрезкой и мерил бы не то.
        for (галочка in listOf(true, false)) {
            val был = PublishPreset(name = "h264 960 4 к", video = VideoPreset(noCrop = галочка))
            assertEquals(был, presetFromWire(был.toWire()))
        }
    }

    @Test
    fun прежние_способы_кратности_читаются_и_пропускаются() {
        // Так лежат наборы, запомненные с галочками 2026-09-29: «Кратность 16», «Обрезка
        // кодером», «Только один слой». Обрезка теперь всегда — поля пропускаются, набор
        // читается (заказчик 2026-09-30, 1а).
        val хвост = PublishPreset(name = "к").toWire().substringAfter("v2|0|")
        for (поле in listOf("1", "1c", "1s", "0")) {
            val стал = presetFromWire("v2|" + поле + "|" + хвост)
            assertEquals("к", стал?.name, "поле «$поле»")
            assertEquals(false, стал?.video?.noCrop, "поле «$поле»")
        }
    }

    @Test
    fun выбор_кодера_и_раскодировщика_переживает_запись() {
        // Заказчик 2026-09-30, 1а: потеряйся выбор — прогон «программный кодер» молча пошёл
        // бы аппаратным и мерил бы не то.
        for (кодер in CoderChoice.entries) for (раскодировщик in CoderChoice.entries) {
            val был = PublishPreset(name = "vp8 | $кодер", video = VideoPreset(noCrop = true, encoder = кодер, decoder = раскодировщик))
            assertEquals(был, presetFromWire(был.toWire()), "$кодер / $раскодировщик")
        }
    }

    @Test
    fun прежний_набор_берёт_кодер_как_в_настройках() {
        val стал = presetFromWire("v2|0n|" + PublishPreset(name = "к").toWire().substringAfter("v2|0|"))
        assertEquals(CoderChoice.Settings, стал?.video?.encoder)
        assertEquals(CoderChoice.Settings, стал?.video?.decoder)
    }
}
