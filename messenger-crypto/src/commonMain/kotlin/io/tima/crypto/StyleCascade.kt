package io.tima.crypto

import kotlin.math.pow

/**
 * Каскад стилей (ADR-0011 §9). Порядок слоёв фиксирован и не настраивается —
 * настраиваемый каскад был бы местом, где пользователь видит результат и не может
 * объяснить, почему он такой:
 *
 *     инлайн → тип → чат → сообщество → приложение
 *
 * Режим выбирает читатель и решает, какие слои вообще участвуют, а не порядок
 * внутри них ([ReaderMode]):
 *
 * - **AS_AUTHOR** («как оформил автор») — полный каскад, замысел отправителя.
 * - **READER** («моё оформление») — только слой приложения. Он уже несёт
 *   персональные настройки читателя (масштаб шрифта и т.п. — doc_UI/25); от
 *   документа остаётся структура (тип блока), которую рендерер применяет отдельно
 *   от этой функции, а не свойства из inline/byType/chat/community.
 * - **DEFAULT** («по умолчанию») — ни один слой не участвует, только встроенные
 *   значения рендерера. Отличие от READER ровно в этом: там читаются личные
 *   настройки читателя, здесь — нет, даже они.
 *
 * Защитный пол (ADR-0011 §9) применяется ПОСЛЕДНИМ, поверх любого слоя и любого
 * режима — включая AS_AUTHOR: минимальный кегль, обязательный контраст с фоном,
 * запрет полноэкранной заливки. Иначе он не был бы полом, а был бы ещё одним
 * слоем, который можно перебить.
 */
enum class ReaderMode { AS_AUTHOR, READER, DEFAULT }

/** Один слой каскада: имя свойства → значение (те же строковые ключи, что в MarkupStyles/MarkupBlock.style). */
typealias StyleLayer = Map<String, String>

object StyleCascade {

    const val KEY_FONT_SIZE = "font-size"
    const val KEY_COLOR = "color"
    const val KEY_BACKGROUND = "background"

    /** Ниже этого кегля (sp) текст быть не может ни при каком слое и ни в каком режиме. */
    const val MIN_FONT_SIZE_SP = 12

    /** Минимальный контраст текста с фоном — WCAG AA для обычного текста. */
    const val MIN_CONTRAST = 4.5

    /**
     * @param inline `MarkupBlock.style` этого конкретного блока
     * @param byType `MarkupStyles.byType[тип блока]`, если есть
     * @param chat стили чата — настройка пространства, не документа
     * @param community стили сообщества
     * @param app текущие настройки/тема читателя в приложении
     * @param backgroundHex фон, на котором рисуется текст — нужен для проверки контраста
     */
    fun resolve(
        mode: ReaderMode,
        inline: StyleLayer = emptyMap(),
        byType: StyleLayer = emptyMap(),
        chat: StyleLayer = emptyMap(),
        community: StyleLayer = emptyMap(),
        app: StyleLayer = emptyMap(),
        backgroundHex: String = "#FFFFFF",
    ): Map<String, String> {
        // Полноэкранная заливка — не полномочие документа или пространства, ни в
        // каком режиме: угроза («текст 4pt белым по белому») исходит от автора и
        // настроек чата/сообщества, а не от приложения самого читателя. Поэтому
        // background вырезается из этих слоёв ДО каскада, а не проверяется постфактум.
        val layers = when (mode) {
            ReaderMode.AS_AUTHOR -> listOf(
                stripBackground(inline), stripBackground(byType),
                stripBackground(chat), stripBackground(community), app,
            )
            ReaderMode.READER -> listOf(app)
            ReaderMode.DEFAULT -> emptyList()
        }
        val resolved = mutableMapOf<String, String>()
        // Первый слой, задавший свойство, побеждает — порядок списка выше уже несёт
        // приоритет (инлайн раньше приложения), здесь он просто применяется.
        for (layer in layers) {
            for ((key, value) in layer) {
                // getOrPut, а не putIfAbsent: последний — метод java.util.Map, и в
                // общий код он попадал без импорта. Смысл тот же: первый слой,
                // задавший свойство, побеждает.
                resolved.getOrPut(key) { value }
            }
        }
        return applyFloor(resolved, backgroundHex)
    }

    private fun stripBackground(layer: StyleLayer): StyleLayer =
        if (KEY_BACKGROUND in layer) layer - KEY_BACKGROUND else layer

    /** Защитный пол: минимальный кегль и обязательный контраст, поверх результата каскада. */
    private fun applyFloor(resolved: Map<String, String>, backgroundHex: String): Map<String, String> {
        val out = resolved.toMutableMap()
        out[KEY_FONT_SIZE] = clampFontSize(out[KEY_FONT_SIZE]).toString()
        val color = out[KEY_COLOR]
        if (color != null) {
            val ratio = contrastRatio(color, backgroundHex)
            // Не удалось разобрать цвет — контраст не проверить, а непроверенное
            // не считается прошедшим: тот же fail-closed принцип, что у остальной
            // защиты в проекте (escrow, подпись конфига).
            if (ratio == null || ratio < MIN_CONTRAST) out.remove(KEY_COLOR)
        }
        return out
    }

    private fun clampFontSize(raw: String?): Int {
        val sp = raw?.toIntOrNull() ?: MIN_FONT_SIZE_SP
        return maxOf(sp, MIN_FONT_SIZE_SP)
    }

    /** Контраст по WCAG: (L1+0.05)/(L2+0.05), L — относительная яркость; null — цвет не разобран. */
    fun contrastRatio(hexA: String, hexB: String): Double? {
        val la = relativeLuminance(hexA) ?: return null
        val lb = relativeLuminance(hexB) ?: return null
        val (l1, l2) = if (la > lb) la to lb else lb to la
        return (l1 + 0.05) / (l2 + 0.05)
    }

    private fun relativeLuminance(hex: String): Double? {
        val (r8, g8, b8) = parseHex(hex) ?: return null
        fun channel(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(r8) + 0.7152 * channel(g8) + 0.0722 * channel(b8)
    }

    private fun parseHex(hex: String): Triple<Int, Int, Int>? {
        val h = hex.removePrefix("#")
        if (h.length != 6) return null
        return try {
            Triple(h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16))
        } catch (_: NumberFormatException) {
            null
        }
    }
}
