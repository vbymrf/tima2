package io.tima.core.call

/** Готовые цветовые наборы gpupixel (таблицы 512×512): нет, «светлый», «свой». */
enum class ColorLook { None, Light, Custom }

/**
 * Цвет помады (ФЗ14, заказчик 2026-11-12: «5 цветов поддерживаю»): картинка `mouth<код>.png` в ресурсах фильтров —
 * та же форма и тот же мягкий край, другой оттенок; накладывается умножением, как в gpupixel.
 */
enum class LipColor(val code: String) {
    Classic(""), Pink("_pink"), Coral("_coral"), Berry("_berry"), Cherry("_cherry");

    /** Имя картинки в `assets/filters` (телефон) и в ресурсах ПК. */
    val image: String get() = "mouth$code.png"
}

/**
 * Стиль румян (заказчик 2026-11-12: «Сделать яркий центрированный, аниме румянец, разные цвета», «Все четыре»):
 * «Мягкие» — картинка gpupixel по сетке лица, умножением; остальные — овал на каждой щеке поверх кадра (`CheekZones`,
 * шейдер `FS_CHEEK`), картинка — маски: красный канал — свечение, зелёный — штрихи, синий — блик.
 */
enum class BlushStyle(val image: String?) {
    Soft(null), Bright("blush_bright.png"), Anime("blush_anime.png"), AnimeStrokes("blush_strokes.png");

    companion object {
        /** По умолчанию — «Яркие» (заказчик 2026-11-12: «3. да»). */
        val DEFAULT = Bright
    }
}

/** Цвет румян (заказчик 2026-11-12: «5 да»): цвет свечения; штрихи — он же темнее, блик — белый. */
enum class BlushColor(val r: Float, val g: Float, val b: Float) {
    Pink(1f, 0.36f, 0.62f), Coral(1f, 0.45f, 0.36f), Red(0.93f, 0.16f, 0.22f), Peach(1f, 0.62f, 0.46f), Lilac(0.78f, 0.45f, 0.92f),
}

/** Разделы листа «Фильтры» (ФЗ9): ряд сверху, под ним — ползунки выбранного. */
enum class FilterSection(val key: String) {
    Beauty("beauty"), Color("color"), Face("face"), Wrinkles("wrinkles");

    /** «Лицо» и «Морщины» нужны точки лица — на задней камере не рисуются (ФЗ10). */
    val frontOnly: Boolean get() = this == Face || this == Wrinkles

    companion object {
        fun of(key: String?): FilterSection = entries.firstOrNull { it.key == key } ?: Beauty
    }
}

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
    val blushStyle: BlushStyle = BlushStyle.DEFAULT,
    val blushColor: BlushColor = BlushColor.Pink,
    val lipColor: LipColor = LipColor.Classic,
    // ── Морщины по областям (ФЗ6, решение 4) ──
    val forehead: Int = 0,
    val brows: Int = 0,
    val underEyes: Int = 0,
    val eyeCorners: Int = 0,
    val nasolabial: Int = 0,
) {
    val beauty: Boolean get() = smooth > 0 || whiten > 0 || sharpen > 0 || wrinkles
    val colour: Boolean get() = brightness != 0 || contrast != 0 || saturation != 0 || warmth != 0 || (look != ColorLook.None && lookStrength > 0)
    val wrinkles: Boolean get() = forehead > 0 || brows > 0 || underEyes > 0 || eyeCorners > 0 || nasolabial > 0
    val reshape: Boolean get() = thin > 0 || eyes > 0
    val makeup: Boolean get() = lipstick > 0 || blush > 0

    /** Проход по сетке лица: помада и «Мягкие» румяна (картинки gpupixel). */
    val meshMakeup: Boolean get() = lipstick > 0 || (blush > 0 && blushStyle == BlushStyle.Soft)

    /** Проход щёк: румяна новых стилей (`CheekZones`). */
    val cheeks: Boolean get() = blush > 0 && blushStyle != BlushStyle.Soft

    /** Нужны ли точки лица: лицевые фильтры и морщины по областям. */
    val needsFace: Boolean get() = on && (reshape || makeup || wrinkles)

    /**
     * Сколько изменено от стандарта в разделе (ФЗ9, заказчик 2026-11-12: «добавлено Морщины Лоб, Уголки глаз,
     * Носогубные = Морщины 3»): ползунки не на нуле; образ в «Цвете» не «нет» — одно, его сила отдельно не
     * считается; цвет помады — часть помады, отдельно не считается. Выключатель на счёт не влияет — это настройки.
     */
    fun changed(section: FilterSection): Int = when (section) {
        FilterSection.Beauty -> listOf(smooth, whiten, sharpen).count { it != 0 }
        FilterSection.Color -> listOf(brightness, contrast, saturation, warmth).count { it != 0 } + if (look != ColorLook.None) 1 else 0
        FilterSection.Face -> listOf(thin, eyes, lipstick, blush).count { it != 0 }
        FilterSection.Wrinkles -> listOf(forehead, brows, underEyes, eyeCorners, nasolabial).count { it != 0 }
    }

    /** Сумма по разделам — в строке «Фильтры» листа звонка. */
    val changedTotal: Int get() = FilterSection.entries.sumOf { changed(it) }

    /** Задняя камера (ФЗ10): «Лицо» и «Морщины» не рисуются, сами настройки не меняются. */
    fun forCamera(front: Boolean): CallFilters = if (front) {
        this
    } else {
        copy(thin = 0, eyes = 0, lipstick = 0, blush = 0, forehead = 0, brows = 0, underEyes = 0, eyeCorners = 0, nasolabial = 0)
    }

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
            .let { if (lipstick != 0 && lipColor != LipColor.Classic) "$it цвет_помады=${lipColor.name}" else it }
            .let { if (blush != 0) "$it румяна_стиль=${blushStyle.name} румяна_цвет=${blushColor.name}" else it }
            .ifEmpty { "вкл, всё по нулям" }
    }

    /**
     * «Тоньше лицо» — доля пути точек контура к носу, ровно по ползунку до 0,12 (заказчик 2026-11-10: «предел 0,12»,
     * затем «равномерное изменение силы»; было: до середины — как у gpupixel, 0,025, после — быстрее).
     */
    val thinDelta: Float get() = thin / 100f * THIN_MAX

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

    /** Последний открытый раздел листа «Фильтры» (ФЗ9): открывается он. */
    const val SECTION = "call.filters.section"

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
            lipColor = LipColor.entries.firstOrNull { it.name == all[PREFIX + "lipColor"] } ?: LipColor.Classic,
            blushStyle = BlushStyle.entries.firstOrNull { it.name == all[PREFIX + "blushStyle"] } ?: BlushStyle.DEFAULT,
            blushColor = BlushColor.entries.firstOrNull { it.name == all[PREFIX + "blushColor"] } ?: BlushColor.Pink,
            forehead = int("forehead"), brows = int("brows"), underEyes = int("underEyes"),
            eyeCorners = int("eyeCorners"), nasolabial = int("nasolabial"),
        )
    }

    fun write(f: CallFilters): Map<String, String> = mapOf(
        "on" to if (f.on) "1" else "0",
        "smooth" to f.smooth, "whiten" to f.whiten, "sharpen" to f.sharpen,
        "brightness" to f.brightness, "contrast" to f.contrast, "saturation" to f.saturation, "warmth" to f.warmth,
        "look" to f.look.name, "lookStrength" to f.lookStrength,
        "thin" to f.thin, "eyes" to f.eyes, "lipstick" to f.lipstick, "blush" to f.blush, "lipColor" to f.lipColor.name,
        "blushStyle" to f.blushStyle.name, "blushColor" to f.blushColor.name,
        "forehead" to f.forehead, "brows" to f.brows, "underEyes" to f.underEyes,
        "eyeCorners" to f.eyeCorners, "nasolabial" to f.nasolabial,
    ).mapKeys { PREFIX + it.key }.mapValues { it.value.toString() }
}

/**
 * Стандарт звонка при обновлении (ФЗ13, заказчик 2026-11-12: «По умолчанию для обновления выстави Увеличение 1.3х
 * Обеими», «б», «Делаем по умолчанию с обновлением включить Красота, Сглаживание = 50»). Вариант «б»: новая версия при
 * первом запуске **один раз** пишет стандарт — и тем, кто выбирал раньше; дальше выбор человека не трогается. На ПК —
 * только фильтры: увеличение там своё и остаётся 1× (решение 10); сглаживание включится само, когда на ПК появятся
 * фильтры (ФЗ15, «2. да»).
 */
object CallDefaults {
    /** Метка «стандарт уже записан». */
    const val DONE = "call.defaults.v1"

    /** Что записать; пусто — уже записано. */
    fun writes(all: Map<String, String>, phone: Boolean): Map<String, String> {
        if (all[DONE] == "1") return emptyMap()
        val out = linkedMapOf<String, String>()
        if (phone) {
            out[CallLookKeys.ZOOM] = "1.3"
            out[CallLookKeys.HAND] = CallLookKeys.code(CallHand.Both)
        }
        out[CallFilterKeys.PREFIX + "on"] = "1"
        out[CallFilterKeys.PREFIX + "smooth"] = "50"
        out[DONE] = "1"
        return out
    }
}
