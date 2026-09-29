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
 *
 * ── НОВЫЕ ПОЛЯ — ВПЕРЕДИ, ЗА МЕТКОЙ ─────────────────────────────────────────
 *
 * Имя забирает хвост, поэтому новое поле в конец не встанет. Встань оно в середину —
 * строка прежнего вида (17 полей) перестала бы разбираться, и выбранный набор молча
 * сменился бы на умолчание. Поэтому новые поля идут перед прежними, за меткой `v2`:
 * строка с меткой — новая, без неё — прежняя, и её поля получают умолчания. Первым
 * полем прежней строки стоит кодек, а кодека `v2` не бывает — спутать нельзя.
 */

private const val SEPARATOR = "|"

/** Сколько полей до имени. Имя — последнее и забирает хвост целиком. */
private const val FIELDS = 17

/** Метка строки, у которой перед прежними полями стоят новые. */
private const val V2 = "v2"

/**
 * Новые поля: пока одно — кратность 16 ([VideoPreset.align16]). `0` или `1`, за ним —
 * буквы способа: `c` — обрезка ([VideoPreset.alignCrop]), `s` — только один слой
 * ([VideoPreset.alignSingle]). Строка с одной цифрой — прежняя, способ у неё — заявка на
 * все слои.
 */
fun PublishPreset.toWire(): String {
    val align = (if (video.align16) "1" else "0") +
        (if (video.alignCrop) CROP else "") +
        (if (video.alignSingle) SINGLE else "")
    return listOf(V2, align, legacyWire()).joinToString(SEPARATOR)
}

private const val CROP = "c"
private const val SINGLE = "s"

fun presetFromWire(wire: String): PublishPreset? {
    val marked = V2 + SEPARATOR
    if (!wire.startsWith(marked)) return legacyFromWire(wire)
    val rest = wire.removePrefix(marked)
    val align = rest.substringBefore(SEPARATOR, missingDelimiterValue = "")
    val on = align.firstOrNull()
    val way = align.drop(1)
    if (on != '0' && on != '1') return null
    if (way.any { it.toString() != CROP && it.toString() != SINGLE }) return null
    val preset = legacyFromWire(rest.substringAfter(SEPARATOR)) ?: return null
    return preset.copy(
        video = preset.video.copy(
            align16 = on == '1',
            alignCrop = CROP in way,
            alignSingle = SINGLE in way,
        ),
    )
}

/** Прежние 17 полей и имя. */
private fun PublishPreset.legacyWire(): String = listOf(
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

private fun legacyFromWire(wire: String): PublishPreset? {
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
