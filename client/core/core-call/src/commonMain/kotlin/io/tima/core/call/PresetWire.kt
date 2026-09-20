package io.tima.core.call

/**
 * Пресет строкой — чтобы он пережил закрытие приложения.
 *
 * ── ПОЧЕМУ СВОЙ ФОРМАТ, А НЕ JSON ───────────────────────────────────────────
 *
 * Пресет лежит в таблице настроек: имя — строка, значение — строка ([io.tima.domain.chat.Settings]).
 * Тянуть ради семнадцати полей разбор JSON в `core-call` значило бы дать модулю звонков
 * зависимость, которой у него нет, — а формат здесь читает и пишет один и тот же файл.
 *
 * ── ПОЧЕМУ ИМЯ СТОИТ ПОСЛЕДНИМ ──────────────────────────────────────────────
 *
 * Имя пишет человек, и разделитель в нём — вопрос времени: «H.264 | один слой» набрать
 * проще, чем не набрать. Стоя последним, оно забирает весь хвост строки целиком, и ломать
 * разбор ему нечем. Все остальные поля — числа и перечни, в них разделителя не бывает.
 *
 * ── ЧТО БУДЕТ С ЧУЖОЙ СТРОКОЙ ───────────────────────────────────────────────
 *
 * [presetFromWire] возвращает `null` на всём, что не разобралось: испорченная запись,
 * запись прошлой версии, чужой мусор. Пресет — испытательная настройка, и потерять её
 * дешевле, чем показать человеку набор, который на треть угадан.
 */

private const val SEPARATOR = "|"

/** Сколько полей до имени. Имя — последнее и забирает хвост целиком. */
private const val FIELDS = 17

fun PublishPreset.toWire(): String = listOf(
    video.codec.wire,
    video.layers.name,
    video.width.toString(),
    video.height.toString(),
    video.fps.toString(),
    video.bitrate.toString(),
    video.maxBitrate.toString(),
    video.backup?.wire ?: "—",
    video.scalability,
    video.degradation.name,
    if (video.dynacast) "1" else "0",
    if (video.adaptiveStream) "1" else "0",
    if (audio.red) "1" else "0",
    if (audio.dtx) "1" else "0",
    audio.bitrate.toString(),
    if (audio.stereo) "1" else "0",
    name,
).joinToString(SEPARATOR)

fun presetFromWire(wire: String): PublishPreset? {
    val parts = wire.split(SEPARATOR, limit = FIELDS)
    if (parts.size < FIELDS) return null
    val codec = VideoCodec.entries.firstOrNull { it.wire == parts[0] } ?: return null
    val layers = LayerMode.entries.firstOrNull { it.name == parts[1] } ?: return null
    val degradation = Degradation.entries.firstOrNull { it.name == parts[9] } ?: return null
    val name = parts[16]
    if (name.isBlank()) return null
    return PublishPreset(
        name = name,
        video = VideoPreset(
            codec = codec,
            layers = layers,
            width = parts[2].toIntOrNull() ?: return null,
            height = parts[3].toIntOrNull() ?: return null,
            fps = parts[4].toIntOrNull() ?: return null,
            bitrate = parts[5].toIntOrNull() ?: return null,
            maxBitrate = parts[6].toIntOrNull() ?: return null,
            backup = VideoCodec.entries.firstOrNull { it.wire == parts[7] },
            scalability = parts[8],
            degradation = degradation,
            dynacast = parts[10] == "1",
            adaptiveStream = parts[11] == "1",
        ),
        audio = AudioPreset(
            red = parts[12] == "1",
            dtx = parts[13] == "1",
            bitrate = parts[14].toIntOrNull() ?: return null,
            stereo = parts[15] == "1",
        ),
    )
}

/** Набор пресетов одной строкой. Разделитель — перевод строки: в поля он не попадает. */
fun List<PublishPreset>.toWire(): String = joinToString("\n") { it.toWire() }

fun presetsFromWire(wire: String): List<PublishPreset> =
    wire.lineSequence().mapNotNull { presetFromWire(it) }.toList()
