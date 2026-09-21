package io.tima.feature.call

import androidx.compose.runtime.Composable
import io.tima.core.call.BenchSample
import io.tima.core.call.BenchSummary
import io.tima.core.call.CallStats
import io.tima.core.call.LayerMode
import io.tima.core.call.PhoneLoad
import io.tima.core.call.PhoneTraffic
import io.tima.core.call.PublishPreset
import io.tima.core.call.VideoCodec
import io.tima.core.call.VideoPreset
import io.tima.core.ui.Stage
import io.tima.testui.FOREIGN_BACKGROUND
import io.tima.testui.bothThemes
import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Экран стенда в снимках (С3, С4, С5).
 *
 * Проверяются **решения**, а не красота: числа, которых нет, не выглядят нулями; режим
 * SVC не предлагается кодеку, который его не умеет; прогон видно по экрану, а не по
 * догадке. Каждое из них легко потерять при следующей правке, и потеря будет тихой —
 * экран останется нарисованным.
 */
class BenchScreenTest {

    @Test
    fun экран_заливает_свой_фон() {
        val shots = bothThemes("стенд-фон", WIDTH, HEIGHT, backdrop = FOREIGN_BACKGROUND) {
            screen()
        }
        for ((name, shot) in shots) {
            assertTrue(!shot.has(FOREIGN_BACKGROUND), "$name: сквозь экран видна подложка")
        }
    }

    @Test
    fun режим_svc_предлагается_только_умеющему_кодеку() {
        // SVC в WebRTC есть только у VP9. Показать этот ряд рядом с H.264 значило бы
        // предложить набор, которого SDK не сделает, — и получить в отчёте прогон
        // «H.264 SVC», которого не было.
        val vp9 = capture("стенд-vp9-svc", WIDTH, HEIGHT, dark = false) {
            screen(preset(VideoCodec.VP9, LayerMode.Svc))
        }
        val h264 = capture("стенд-h264", WIDTH, HEIGHT, dark = false) {
            screen(preset(VideoCodec.H264, LayerMode.Single))
        }
        assertTrue(vp9.difference(h264) > 0.0, "выбор кодека ничего не меняет на экране")
    }

    @Test
    fun несобранные_числа_не_выглядят_нулями() {
        // «Мерить было нечем» и «ноль» — разные утверждения: на части прошивок нет
        // датчика температуры, и ноль градусов в отчёте прочитали бы как измерение.
        val empty = capture("стенд-без-чисел", WIDTH, HEIGHT, dark = false) {
            screen(samples = emptyList())
        }
        val full = capture("стенд-с-числами", WIDTH, HEIGHT, dark = false) {
            screen(samples = listOf(sample()))
        }
        assertTrue(empty.difference(full) > 0.0, "числа не показаны: снимки совпали")
    }

    @Test
    fun идущий_прогон_виден_на_экране() {
        // Без этого «Начать прогон» нажимают дважды и получают два прогона там, где
        // думали, что идёт один.
        val idle = capture("стенд-покой", WIDTH, HEIGHT, dark = false) { screen(running = false) }
        val going = capture("стенд-прогон", WIDTH, HEIGHT, dark = false) { screen(running = true) }
        assertTrue(idle.difference(going) > 0.0, "идущий прогон неотличим от покоя")
    }

    @Test
    fun применить_предлагается_только_в_разговоре() {
        // Вне разговора применять нечего: набор и так возьмётся при следующем входе в
        // комнату. Кнопка, которая в половине случаев ничего не делает, читается как
        // поломка — и по ней жмут второй раз.
        val idle = capture("стенд-вне-звонка", WIDTH, HEIGHT, dark = false) { screen(inCall = false) }
        val talking = capture("стенд-в-звонке", WIDTH, HEIGHT, dark = false) { screen(inCall = true) }
        assertTrue(idle.difference(talking) > 0.0, "кнопка «Применить» показана вне разговора или не показана в нём")
    }

    @Test
    fun путь_к_файлу_показывается_после_прогона() {
        // По нему отчёт забирают с телефона. Не показать его — значит заставить искать.
        val without = capture("стенд-без-файла", WIDTH, HEIGHT, dark = false) { screen(lastFile = null) }
        val with = capture("стенд-с-файлом", WIDTH, HEIGHT, dark = false) {
            screen(lastFile = "/data/data/io.tima.app.v2/files/test/2026-09-21-1530-RMX3269.md")
        }
        assertTrue(without.difference(with) > 0.0, "путь к отчёту не показан")
    }

    @Test
    fun прошлые_прогоны_показываются_под_числами() {
        val none = capture("стенд-без-прогонов", WIDTH, HEIGHT, dark = false) { screen(runs = emptyList()) }
        val some = capture("стенд-с-прогонами", WIDTH, HEIGHT, dark = false) {
            screen(runs = listOf(BenchSummary(preset = "H.264 один слой", seconds = 60, samples = 60)))
        }
        assertTrue(none.difference(some) > 0.0, "прошлые прогоны не показаны")
    }

    private companion object {
        const val WIDTH = 380

        /**
         * Высота снимка — **во весь экран стенда, а не в телефон**.
         *
         * Экран прокручиваемый: настройки публикации занимают больше страницы, а числа и
         * прогоны идут под ними. В телефонной высоте снимок показал бы одни настройки, и
         * проверки чисел сравнивали бы две одинаковые картинки, ничего не проверяя.
         */
        const val HEIGHT = 2600

        fun preset(codec: VideoCodec, layers: LayerMode) = PublishPreset(
            name = "проба",
            video = VideoPreset(codec = codec, layers = layers),
        )

        fun sample() = BenchSample(
            atSecond = 12,
            stats = CallStats(
                upBitrate = 780_000,
                downBitrate = 640_000,
                rttMs = 42,
                packetsLost = 3,
                videoCodec = "H264",
                hardwareEncoder = true,
            ),
            load = PhoneLoad(cpuPercent = 34.5, memoryMb = 180, temperatureC = 41.2, batteryPercent = 68),
            traffic = PhoneTraffic(sentBytes = 6_500_000, receivedBytes = 5_200_000),
        )

        @Composable
        fun screen(
            preset: PublishPreset = preset(VideoCodec.H264, LayerMode.Single),
            presets: List<PublishPreset> = emptyList(),
            running: Boolean = false,
            samples: List<BenchSample> = emptyList(),
            runs: List<BenchSummary> = emptyList(),
            inCall: Boolean = false,
            lastFile: String? = null,
            skip: Int = 5,
        ) = Stage(
            column = {
                BenchScreen(
                    preset = preset,
                    presets = presets,
                    running = running,
                    samples = samples,
                    runs = runs,
                    inCall = inCall,
                    lastFile = lastFile,
                    skip = skip,
                    onChange = {},
                    onSave = {},
                    onForget = {},
                    onApply = {},
                    onSkip = {},
                    onStop = {},
                )
            },
        )
    }
}
