package io.tima.core.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Наборы файлом: их задают на ПК, а разбирает приложение.
 *
 * Проверяется то, ради чего формат и выбран: **набор из двух строк законен**, чужая
 * опечатка не уносит остальные наборы, и обратный путь ничего не теряет — иначе набор,
 * запомненный пальцем на телефоне, при следующем открытии окна вернулся бы другим.
 */
class PresetFileTest {

    @Test
    fun набора_из_двух_строк_достаточно() {
        // Иначе каждый набор стал бы стеной из семнадцати строк, и разница между ними
        // перестала бы читаться глазами — а в этом весь смысл файла.
        val файл = """{"presets":[{"name":"H.264 один слой","codec":"h264"}]}"""

        val наборы = presetsFromJson(файл)

        assertEquals(1, наборы.size)
        val один = наборы.first()
        assertEquals("H.264 один слой", один.name)
        assertEquals(VideoCodec.H264, один.video.codec)
        // Остальное — умолчания, те же, что у VideoPreset.
        assertEquals(640, один.video.width)
        assertEquals(LayerMode.Single, один.video.layers)
        assertEquals(VideoCodec.H264, один.video.backup)
        assertTrue(один.audio.red)
    }

    @Test
    fun разобранное_и_собранное_совпадают() {
        // Файл пишет и читает одно и то же приложение: запомнил набор пальцем — он лёг в
        // файл, открыл окно снова — прочитался оттуда же. Потеря хоть одного поля на этом
        // круге означала бы набор, который тихо меняется сам.
        val был = PublishPreset(
            name = "VP9 SVC, запасной H.264",
            video = VideoPreset(
                codec = VideoCodec.VP9,
                layers = LayerMode.Svc,
                width = 1280,
                height = 720,
                fps = 30,
                bitrate = 1_500_000,
                maxBitrate = 1_500_000,
                backup = VideoCodec.H264,
                scalability = "L3T3",
                degradation = Degradation.MaintainFramerate,
                dynacast = false,
                adaptiveStream = false,
            ),
            audio = AudioPreset(red = false, dtx = false, bitrate = 32_000, stereo = true),
        )

        assertEquals(listOf(был), presetsFromJson(presetsToJson(listOf(был))))
    }

    @Test
    fun без_запасного_кодека_это_тоже_выбор() {
        // «Без запасного» проверяет, что будет, когда второй телефон основной кодек не
        // примет. Прочитать это как «не указано» значило бы подставить H.264 и померить
        // не тот прогон.
        val наборы = presetsFromJson("""{"presets":[{"name":"без запасного","backup":"none"}]}""")

        assertNull(наборы.first().video.backup)
    }

    @Test
    fun опечатка_не_роняет_приложение() {
        // Файл пишет человек на ПК, а читает телефон посреди испытаний. Упасть из-за
        // запятой стенд не вправе: пустой список виден на экране словами.
        for (мусор in listOf("", "не json вовсе", "{}", """{"presets":[{"codec":"h264"}]}""")) {
            assertEquals(emptyList(), presetsFromJson(мусор), "вход «$мусор»")
        }
    }

    @Test
    fun незнакомое_поле_не_мешает() {
        // Файл переживёт версию приложения, которая про поле ещё не знает, — и наоборот.
        val наборы = presetsFromJson(
            """{"presets":[{"name":"из будущего","codec":"vp9","чего-то_новое":42}],"ещё":1}""",
        )

        assertEquals(1, наборы.size)
        assertEquals(VideoCodec.VP9, наборы.first().video.codec)
    }

    @Test
    fun неизвестный_кодек_не_молчит_а_становится_H264() {
        // Опечатка в названии кодека — самая вероятная в этом файле. Молча получить набор
        // с другим кодеком хуже, чем получить точку отсчёта: H.264 аппаратный везде, и в
        // отчёте это будет видно строкой «кодек на самом деле».
        val наборы = presetsFromJson("""{"presets":[{"name":"опечатка","codec":"h246"}]}""")

        assertEquals(VideoCodec.H264, наборы.first().video.codec)
    }
}
