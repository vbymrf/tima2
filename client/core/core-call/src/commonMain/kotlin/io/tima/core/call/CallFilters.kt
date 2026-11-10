package io.tima.core.call

/** Откуда берутся точки лица для лицевых фильтров (ПЛАН-(ФЗ) решение 2: оба, выбор — сравнивать и отлаживать). */
enum class FaceSource { MediaPipe, Vnn }

/** Цвет помады (заказчик 2026-11-10): красный, розовый, коралловый, вишнёвый. Цвет — 0xRRGGBB. */
enum class LipColor(val rgb: Int) { Red(0xC8102E), Pink(0xE75480), Coral(0xFF6F61), Cherry(0x7B1E3A) }

/** Цвет румян (заказчик 2026-11-10: «румяна тоже с выбором цветов»): розовый, персиковый, коралловый, ягодный. */
enum class BlushColor(val rgb: Int) { Pink(0xF4A6B7), Peach(0xFFB38A), Coral(0xFF7F6E), Berry(0xC0506E) }

/** Готовые цветовые наборы gpupixel (таблицы 512×512): нет, «светлый», «свой». */
enum class ColorLook { None, Light, Custom }

/**
 * Фильтры своего видео (ПЛАН-(ФЗ)). Кадр собирается **до отправки** — собеседник видит то же, что своё окошко
 * (решение 5). Все силы — 0…100, кроме яркости, контраста, насыщенности и тепла: −100…100, 0 — как есть.
 */
data class CallFilters(
    /** Общий выключатель: выключено — кадр уходит как снят, ползунки помнятся. */
    val on: Boolean = false,
    // ── «Красота» (ФЗ2) ──
    val smooth: Int = 0,
    val whiten: Int = 0,
    val sharpen: Int = 0,
    // ── Цвет (ФЗ3) ──
    val brightness: Int = 0,
    val contrast: Int = 0,
    val saturation: Int = 0,
    val warmth: Int = 0,
    val look: ColorLook = ColorLook.None,
    val lookStrength: Int = 100,
    // ── Лицо (ФЗ5) ──
    val thin: Int = 0,
    val eyes: Int = 0,
    val lipstick: Int = 0,
    val blush: Int = 0,
    val lipColor: LipColor = LipColor.Red,
    val blushColor: BlushColor = BlushColor.Pink,
    // ── Морщины по областям (ФЗ6, решение 4) ──
    val forehead: Int = 0,
    val brows: Int = 0,
    val underEyes: Int = 0,
    val eyeCorners: Int = 0,
    val nasolabial: Int = 0,
    // ── Точки лица (ФЗ4) ──
    val source: FaceSource = FaceSource.Vnn,
) {
    val beauty: Boolean get() = smooth > 0 || whiten > 0 || sharpen > 0 || wrinkles
    val colour: Boolean get() = brightness != 0 || contrast != 0 || saturation != 0 || warmth != 0 || (look != ColorLook.None && lookStrength > 0)
    val wrinkles: Boolean get() = forehead > 0 || brows > 0 || underEyes > 0 || eyeCorners > 0 || nasolabial > 0
    val reshape: Boolean get() = thin > 0 || eyes > 0
    val makeup: Boolean get() = lipstick > 0 || blush > 0

    /** Нужны ли точки лица: лицевые фильтры и морщины по областям. */
    val needsFace: Boolean get() = on && (reshape || makeup || wrinkles)

    /** Есть ли что делать с кадром. */
    val active: Boolean get() = on && (beauty || colour || reshape || makeup)

    /** Для журнала: только ненулевое. */
    fun describe(): String = if (!on) {
        "выкл"
    } else {
        listOf(
            "сглаживание" to smooth, "осветление" to whiten, "резкость" to sharpen,
            "яркость" to brightness, "контраст" to contrast, "насыщенность" to saturation, "тепло" to warmth,
            "лицо" to thin, "глаза" to eyes, "помада" to lipstick, "румяна" to blush,
            "лоб" to forehead, "между бровями" to brows, "под глазами" to underEyes, "уголки глаз" to eyeCorners,
            "носогубные" to nasolabial,
        ).filter { it.second != 0 }.joinToString(" ") { "${it.first}=${it.second}" }
            .let { if (look != ColorLook.None) "$it набор=${look.name}:$lookStrength" else it }
            .let { if (lipstick > 0) "$it цвет помады=${lipColor.name}" else it }
            .let { if (blush > 0) "$it цвет румян=${blushColor.name}" else it }
            .let { if (needsFace) "$it точки=${source.name}" else it }
            .ifEmpty { "вкл, всё по нулям" }
    }

    /**
     * «Тоньше лицо» — доля пути точек контура к носу. Первая половина ползунка — как у gpupixel (до 0,025), вторая —
     * быстрее до 0,12 (заказчик 2026-11-10: «предел 0,12»; было 0,05 на всём ходе).
     */
    val thinDelta: Float
        get() {
            val s = thin / 100f
            return if (s <= 0.5f) s * 0.05f else 0.025f + (s - 0.5f) / 0.5f * (THIN_MAX - 0.025f)
        }

    companion object {
        val OFF = CallFilters()

        const val THIN_MAX = 0.12f

        /** Морщины: ползунок 100 — сила зоны 1,6 (заказчик 2026-11-10: «поднять предел»; было 1). */
        const val WRINKLE_MAX = 1.6f
    }
}

/** Фильтры в настройках устройства (решение 6: выбор помнится). Ключ на ползунок: `call.filter.<имя>`. */
object CallFilterKeys {
    const val PREFIX = "call.filter."

    fun read(all: Map<String, String>): CallFilters {
        fun int(name: String, min: Int = 0) = all[PREFIX + name]?.toIntOrNull()?.coerceIn(min, 100) ?: 0
        return CallFilters(
            on = all[PREFIX + "on"] == "1",
            smooth = int("smooth"), whiten = int("whiten"), sharpen = int("sharpen"),
            brightness = int("brightness", -100), contrast = int("contrast", -100),
            saturation = int("saturation", -100), warmth = int("warmth", -100),
            look = ColorLook.entries.firstOrNull { it.name == all[PREFIX + "look"] } ?: ColorLook.None,
            lookStrength = all[PREFIX + "lookStrength"]?.toIntOrNull()?.coerceIn(0, 100) ?: 100,
            thin = int("thin"), eyes = int("eyes"), lipstick = int("lipstick"), blush = int("blush"),
            lipColor = LipColor.entries.firstOrNull { it.name == all[PREFIX + "lipColor"] } ?: LipColor.Red,
            blushColor = BlushColor.entries.firstOrNull { it.name == all[PREFIX + "blushColor"] } ?: BlushColor.Pink,
            forehead = int("forehead"), brows = int("brows"), underEyes = int("underEyes"),
            eyeCorners = int("eyeCorners"), nasolabial = int("nasolabial"),
            source = FaceSource.entries.firstOrNull { it.name == all[PREFIX + "source"] } ?: FaceSource.Vnn,
        )
    }

    fun write(f: CallFilters): Map<String, String> = mapOf(
        "on" to if (f.on) "1" else "0",
        "smooth" to f.smooth, "whiten" to f.whiten, "sharpen" to f.sharpen,
        "brightness" to f.brightness, "contrast" to f.contrast, "saturation" to f.saturation, "warmth" to f.warmth,
        "look" to f.look.name, "lookStrength" to f.lookStrength,
        "thin" to f.thin, "eyes" to f.eyes, "lipstick" to f.lipstick, "blush" to f.blush,
        "lipColor" to f.lipColor.name, "blushColor" to f.blushColor.name,
        "forehead" to f.forehead, "brows" to f.brows, "underEyes" to f.underEyes,
        "eyeCorners" to f.eyeCorners, "nasolabial" to f.nasolabial,
        "source" to f.source.name,
    ).mapKeys { PREFIX + it.key }.mapValues { it.value.toString() }
}
