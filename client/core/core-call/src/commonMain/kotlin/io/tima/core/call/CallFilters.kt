package io.tima.core.call

/** Откуда берутся точки лица для лицевых фильтров (ПЛАН-(ФЗ) решение 2: оба, выбор — сравнивать и отлаживать). */
enum class FaceSource { MediaPipe, Vnn }

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
            .let { if (needsFace) "$it точки=${source.name}" else it }
            .ifEmpty { "вкл, всё по нулям" }
    }

    companion object {
        val OFF = CallFilters()
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
        "forehead" to f.forehead, "brows" to f.brows, "underEyes" to f.underEyes,
        "eyeCorners" to f.eyeCorners, "nasolabial" to f.nasolabial,
        "source" to f.source.name,
    ).mapKeys { PREFIX + it.key }.mapValues { it.value.toString() }
}
