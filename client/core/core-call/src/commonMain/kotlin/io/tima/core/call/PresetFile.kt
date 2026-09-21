package io.tima.core.call

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Наборы прогонов файлом — решение заказчика 2026-09-21.
 *
 * ── ОДИН ФАЙЛ, И ОН ЖЕ ИСТОЧНИК ПРАВДЫ ─────────────────────────────────────
 *
 * Список наборов живёт **в файле на телефоне**, а не в настройках приложения. Отсюда и
 * ответ на вопрос «передать на телефон — это заменить файл?»: да, ровно это. Скрипт с ПК
 * кладёт новый файл поверх старого, приложение при следующем открытии окна стенда читает
 * его — и список стал тот, что задали на ПК.
 *
 * Если бы список лежал в настройках, а файл только «ввозился» в них, источников стало бы
 * два, и первый же вопрос «почему на телефоне не тот набор» пришлось бы решать сверкой
 * двух хранилищ. Один файл такого вопроса не порождает.
 *
 * **Что осталось в настройках — текущий набор**, то есть на каком месте кольца мы сейчас.
 * Он меняется каждым звонком, и держать его в файле значило бы переписывать файл после
 * каждого разговора.
 *
 * ── ПОЧЕМУ JSON, А НЕ НАШ ВНУТРЕННИЙ ФОРМАТ ────────────────────────────────
 *
 * Внутренний формат ([toWire]) — строка с разделителями, её пишет и читает один и тот же
 * файл. Заставить писать её скрипт на ПК значило бы описать формат **в двух местах**, на
 * Kotlin и на PowerShell, — и однажды они разойдутся молча. JSON разбирает приложение,
 * скрипт же только переносит байты и ничего про формат не знает.
 *
 * ── ЧЕГО В ФАЙЛЕ МОЖНО НЕ ПИСАТЬ ───────────────────────────────────────────
 *
 * Почти всего: у каждого поля есть умолчание, совпадающее с [VideoPreset] и [AudioPreset].
 * Набор из двух строк — «имя и кодек» — законен. Иначе каждый набор превратился бы в стену
 * из семнадцати строк, и разница между ними перестала бы читаться глазами, а в этом весь
 * смысл файла.
 */

private val json = Json {
    ignoreUnknownKeys = true // поле из будущей версии не должно ронять весь список
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = true
}

@Serializable
private data class PresetsFile(val presets: List<PresetJson> = emptyList())

@Serializable
private data class PresetJson(
    val name: String = "",
    val codec: String = "h264",
    /** `none` — без запасного. Это осмысленный выбор прогона, а не пропуск. */
    val backup: String = "h264",
    val layers: String = "single",
    val scalability: String = "L3T3_KEY",
    val width: Int = 640,
    val height: Int = 480,
    val fps: Int = 15,
    val bitrate: Int = 800_000,
    /** `resolution` · `framerate` · `balanced` — чем жертвовать при нехватке полосы. */
    val degradation: String = "resolution",
    val dynacast: Boolean = true,
    val adaptive: Boolean = true,
    val red: Boolean = true,
    val dtx: Boolean = true,
    val stereo: Boolean = false,
    @SerialName("audioBitrate") val audioBitrate: Int = 24_000,
)

/**
 * Разобрать файл.
 *
 * Испорченный файл даёт пустой список, а не исключение: стенд — испытательная вещь, и
 * уронить приложение из-за опечатки в чужом файле он не вправе. Пустой список виден на
 * экране словами «наборов пока нет».
 */
fun presetsFromJson(text: String): List<PublishPreset> = runCatching {
    json.decodeFromString<PresetsFile>(text).presets
        .filter { it.name.isNotBlank() }
        .map { row ->
            PublishPreset(
                name = row.name,
                video = VideoPreset(
                    codec = codecOf(row.codec) ?: VideoCodec.H264,
                    layers = layersOf(row.layers),
                    width = row.width,
                    height = row.height,
                    fps = row.fps,
                    bitrate = row.bitrate,
                    maxBitrate = row.bitrate,
                    backup = codecOf(row.backup),
                    scalability = row.scalability,
                    degradation = degradationOf(row.degradation),
                    dynacast = row.dynacast,
                    adaptiveStream = row.adaptive,
                ),
                audio = AudioPreset(
                    red = row.red,
                    dtx = row.dtx,
                    bitrate = row.audioBitrate,
                    stereo = row.stereo,
                ),
            )
        }
}.getOrElse { emptyList() }

/** Собрать файл. Пишется тем же приложением, когда набор запомнили пальцем на телефоне. */
fun presetsToJson(presets: List<PublishPreset>): String = json.encodeToString(
    PresetsFile.serializer(),
    PresetsFile(
        presets.map { preset ->
            PresetJson(
                name = preset.name,
                codec = preset.video.codec.wire,
                backup = preset.video.backup?.wire ?: NONE,
                layers = preset.video.layers.name.lowercase(),
                scalability = preset.video.scalability,
                width = preset.video.width,
                height = preset.video.height,
                fps = preset.video.fps,
                bitrate = preset.video.bitrate,
                degradation = when (preset.video.degradation) {
                    Degradation.MaintainResolution -> "resolution"
                    Degradation.MaintainFramerate -> "framerate"
                    Degradation.Balanced -> "balanced"
                },
                dynacast = preset.video.dynacast,
                adaptive = preset.video.adaptiveStream,
                red = preset.audio.red,
                dtx = preset.audio.dtx,
                stereo = preset.audio.stereo,
                audioBitrate = preset.audio.bitrate,
            )
        },
    ),
)

private const val NONE = "none"

private fun codecOf(wire: String): VideoCodec? =
    VideoCodec.entries.firstOrNull { it.wire.equals(wire, ignoreCase = true) }

private fun layersOf(text: String): LayerMode = when (text.lowercase()) {
    "simulcast" -> LayerMode.Simulcast
    "svc" -> LayerMode.Svc
    else -> LayerMode.Single
}

private fun degradationOf(text: String): Degradation = when (text.lowercase()) {
    "framerate" -> Degradation.MaintainFramerate
    "balanced" -> Degradation.Balanced
    else -> Degradation.MaintainResolution
}

/**
 * Прочитать файл наборов с диска. `null` — файла нет или платформа их не держит.
 *
 * Имя у него одно на все телефоны — `presets.json` в том же `files/test/`, откуда
 * забираются отчёты. Одно имя затем, чтобы скрипт с ПК не спрашивал, куда класть.
 */
expect fun readPresetsFile(): String?

/** Записать файл наборов. Возвращает путь — его показывает экран. */
expect fun writePresetsFile(text: String): String?
