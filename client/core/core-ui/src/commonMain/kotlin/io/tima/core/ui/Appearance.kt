package io.tima.core.ui

import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

/**
 * Оформление: какая тема выбрана и из чего состоит своя.
 *
 * ── ПОЧЕМУ ТЕМ ТРИ, А НЕ ДВЕ И НЕ ЧЕТЫРЕ ────────────────────────────────────
 *
 * До 2026-09-02 тему решала операционная система: `TimaTheme(dark = isSystemInDarkTheme())`.
 * Тёмная в токенах была, в макете была, а выбрать её было нельзя — только сменить тему
 * системы. Заказчик назвал три: **Светлая, Тёмная, Пользовательская**.
 *
 * «Как в системе» четвёртым пунктом не заведено намеренно: его не просили, а пункт,
 * которого не просили, потом объясняют. Система при этом не забыта — она решает, **какая
 * тема стоит при первом запуске**, и на этом её роль заканчивается.
 */
enum class ThemeChoice(val title: String) {
    Light("Светлая"),
    Dark("Тёмная"),

    /**
     * Своя тема. Начинается копией светлой или тёмной — той, что стояла в момент
     * первого захода: пустая палитра из чёрного по чёрному никому не нужна.
     */
    Custom("Пользовательская"),
}

/**
 * Цвет темы, который человеку разрешено менять.
 *
 * ── ПОЧЕМУ СЕМНАДЦАТЬ, А НЕ ДВАДЦАТЬ ────────────────────────────────────────
 *
 * В теме двадцать цветов. Три из них меняют **не вид, а работу**, и потому сюда не
 * попали:
 *
 * - **два цвета QR-кода.** Код читает чужая программа, а не человек. Часть сканеров не
 *   берёт светлые модули на тёмном фоне, и «код не сканируется» человек прочтёт как
 *   поломку привязки устройства, а не как выбранную им тему;
 * - **цвет эмоций.** Он обязан быть серым: цвет в этом интерфейсе занят кодом —
 *   салатовый значит навигацию, янтарь активность, зелёный подтверждение. Раскрашенные
 *   эмоции отдали бы девяти значкам смысл, которого у них нет, рядом с тремя значащими.
 *
 * Остальные семнадцать открыты целиком — решение заказчика 2026-09-02. Собрать ими
 * нечитаемую тему можно: белый текст на белом фоне здесь никто не запрещает. Это принято
 * осознанно, и потому у своей темы есть кнопка «Вернуть как было» — выход из положения,
 * когда экран перестал читаться, обязан существовать.
 *
 * Порядок объявления — порядок на экране: сначала то, что видно всегда и всем.
 */
enum class ColorSlot(val title: String, val about: String) {
    NAVIGATION("Навигация и действие", "логотип, текущее окно, «назад», «отправить»"),
    ACTIVITY("Активность", "счётчик непрочитанного"),
    CONFIRMED("Подтверждено", "доставлено, прочитано, метка E2E"),

    SURFACE("Фон содержимого", "лента и переписка"),
    FUNCTIONAL("Фон панелей", "шапка, вкладки, строка ввода"),
    TEXT("Текст", "основной"),
    TEXT_2("Текст потише", "подписи, время"),
    TEXT_3("Текст ещё тише", "третий уровень"),

    MY("Фон моих сообщений", ""),
    AUTHOR("Фон чужих сообщений", ""),
    BORDER("Рамка сообщения", ""),
    LINE("Линия списка", "между записями"),

    ON_ACCENT("Текст на зелёном", "на кнопках, вкладках, плашке шапки"),
    ON_AMBER("Текст на янтаре", "на счётчике непрочитанного"),
    IN_PLATE("Внутри плашки", "логотип и кнопки на салатовом"),
    SOFT_ACCENT("Тихая подложка", "невыбранная вкладка, поле ввода"),
    QUIET("Нейтральная подложка", "невыбранная подвкладка, капсула переключателя"),
}

/** Значение цвета из набора. */
fun TimaColors.slot(slot: ColorSlot): Color = when (slot) {
    ColorSlot.NAVIGATION -> navigation
    ColorSlot.ACTIVITY -> activity
    ColorSlot.CONFIRMED -> confirmed
    ColorSlot.SURFACE -> surface
    ColorSlot.FUNCTIONAL -> functional
    ColorSlot.TEXT -> text
    ColorSlot.TEXT_2 -> text2
    ColorSlot.TEXT_3 -> text3
    ColorSlot.MY -> my
    ColorSlot.AUTHOR -> author
    ColorSlot.BORDER -> border
    ColorSlot.LINE -> line
    ColorSlot.ON_ACCENT -> onAccent
    ColorSlot.ON_AMBER -> onAmber
    ColorSlot.IN_PLATE -> inPlate
    ColorSlot.SOFT_ACCENT -> softAccent
    ColorSlot.QUIET -> quiet
}

/**
 * Тот же набор с одним заменённым цветом.
 *
 * `when` по перечню, а не отражение: перечень и `data class` разойтись не могут молча —
 * новый цвет в наборе уронит компиляцию здесь и в [slot], и его придётся либо открыть
 * человеку, либо явно закрыть.
 */
fun TimaColors.with(slot: ColorSlot, color: Color): TimaColors = when (slot) {
    ColorSlot.NAVIGATION -> copy(navigation = color)
    ColorSlot.ACTIVITY -> copy(activity = color)
    ColorSlot.CONFIRMED -> copy(confirmed = color)
    ColorSlot.SURFACE -> copy(surface = color)
    ColorSlot.FUNCTIONAL -> copy(functional = color)
    ColorSlot.TEXT -> copy(text = color)
    ColorSlot.TEXT_2 -> copy(text2 = color)
    ColorSlot.TEXT_3 -> copy(text3 = color)
    ColorSlot.MY -> copy(my = color)
    ColorSlot.AUTHOR -> copy(author = color)
    ColorSlot.BORDER -> copy(border = color)
    ColorSlot.LINE -> copy(line = color)
    ColorSlot.ON_ACCENT -> copy(onAccent = color)
    ColorSlot.ON_AMBER -> copy(onAmber = color)
    ColorSlot.IN_PLATE -> copy(inPlate = color)
    ColorSlot.SOFT_ACCENT -> copy(softAccent = color)
    ColorSlot.QUIET -> copy(quiet = color)
}

/**
 * Выбранная тема и своя палитра.
 *
 * Своя палитра хранится **всегда**, даже когда выбрана светлая: иначе переключение
 * туда-обратно стирало бы работу человека, а «я же настраивал» — худший вид потери.
 */
data class Appearance(
    val choice: ThemeChoice,
    val custom: TimaColors,
) {
    /** Цвета, которыми рисовать. */
    val colors: TimaColors
        get() = when (choice) {
            ThemeChoice.Light -> TimaColors.light
            ThemeChoice.Dark -> TimaColors.dark
            ThemeChoice.Custom -> custom
        }

    /**
     * Запись для хранилища: строки `ключ=значение`, по одной на цвет.
     *
     * Формат нарочно простой и человекочитаемый. Его читает и пишет только приложение,
     * но чинить сломанное оформление однажды придётся руками — а руками правят то, что
     * видно глазом.
     */
    fun write(): String = buildString {
        append(KEY_CHOICE).append('=').append(choice.name).append('\n')
        for (slot in ColorSlot.entries) {
            append(slot.name).append('=').append(custom.slot(slot).hex()).append('\n')
        }
    }

    companion object {
        private const val KEY_CHOICE = "choice"

        /**
         * Что показать, когда сохранённого нет или оно испорчено.
         *
         * Тему берём у системы — ровно то поведение, что было до появления выбора. Это
         * единственное место, где система на что-то влияет.
         */
        fun byDefault(systemDark: Boolean): Appearance = Appearance(
            choice = if (systemDark) ThemeChoice.Dark else ThemeChoice.Light,
            custom = if (systemDark) TimaColors.dark else TimaColors.light,
        )

        /**
         * Прочитать сохранённое.
         *
         * **Испорченная строка не роняет приложение и не обнуляет остальные цвета.**
         * Непонятное значение пропускается, и на его месте остаётся значение по
         * умолчанию: оформление — не то, ради чего стоит не пускать человека в переписку.
         */
        fun read(stored: String?, systemDark: Boolean): Appearance {
            if (stored.isNullOrBlank()) return byDefault(systemDark)
            val pairs = stored.lineSequence()
                .mapNotNull { line ->
                    val at = line.indexOf('=')
                    if (at <= 0) null else line.substring(0, at).trim() to line.substring(at + 1).trim()
                }
                .toMap()

            val choice = ThemeChoice.entries.firstOrNull { it.name == pairs[KEY_CHOICE] }
                ?: byDefault(systemDark).choice
            var custom = byDefault(systemDark).custom
            for (slot in ColorSlot.entries) {
                pairs[slot.name]?.let { hex -> colorOf(hex)?.let { custom = custom.with(slot, it) } }
            }
            return Appearance(choice, custom)
        }
    }
}

/**
 * Цвет строкой: `AARRGGBB`, восемь шестнадцатеричных знаков.
 *
 * **Прозрачность в записи есть всегда**, хотя у большинства цветов она полная. Половина
 * токенов полупрозрачна — линия, рамка, тихие подложки, — и формат из шести знаков
 * потерял бы их молча, превратив линию в сплошную полосу.
 */
fun Color.hex(): String {
    fun part(value: Float): String = (value * 255).roundToInt().coerceIn(0, 255)
        .toString(16).uppercase().padStart(2, '0')
    return part(alpha) + part(red) + part(green) + part(blue)
}

/**
 * Разбор цвета из строки.
 *
 * Принимает `RRGGBB` и `AARRGGBB`, с решёткой и без: человек, набирающий цвет руками,
 * пишет так, как привык в любом другом месте. Шесть знаков означают непрозрачный.
 * Непонятное — `null`, а не чёрный: подставленный вместо ошибки цвет выглядит как
 * принятый ввод.
 */
fun colorOf(text: String): Color? {
    val clean = text.trim().removePrefix("#").uppercase()
    if (clean.length != 6 && clean.length != 8) return null
    if (clean.any { it !in "0123456789ABCDEF" }) return null
    val full = if (clean.length == 6) "FF$clean" else clean
    fun part(at: Int): Int = full.substring(at, at + 2).toInt(16)
    return Color(red = part(2), green = part(4), blue = part(6), alpha = part(0))
}
