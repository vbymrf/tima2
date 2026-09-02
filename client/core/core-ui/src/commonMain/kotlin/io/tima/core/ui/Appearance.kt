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
 * Пары цветов, без которых из «Оформления» не выбраться.
 *
 * ── ЗАЩИТА ОТ ДУРАКА: ЧТО ИМЕННО ЗАЩИЩАЕТСЯ ─────────────────────────────────
 *
 * Семнадцать цветов открыты целиком, и это решение остаётся. Но два сочетания из
 * семнадцати — не про красоту, а про **дорогу назад**. Заказчик описал её 2026-09-03
 * по шагам: «нажать на шапку, потом на настройки и потом зайти в оформление».
 *
 * Пройдём её и посмотрим, чем она нарисована:
 *
 * 1. **шапка** — плашка [ColorSlot.NAVIGATION] с именем окна цветом
 *    [ColorSlot.ON_ACCENT]. Тем же сочетанием нарисована стрелка «назад» в каждом
 *    подокне: круг навигации, стрелка на нём;
 * 2. **переключение окон и список настроек** — строки цветом [ColorSlot.TEXT] по
 *    [ColorSlot.SURFACE];
 * 3. **сам экран оформления** уже вне темы — чёрным по белому ([TimaFixed]).
 *
 * Третий шаг защищён с 2026-09-03, первые два — нет. Слились цвета на первом или втором
 * — и человек стоит перед экраном, на котором ничего не написано, без понятия, что
 * делать. Отсюда список ровно из двух пар: он покрывает названную дорогу и не трогает
 * остальные пятнадцать цветов.
 *
 * **Чего в списке НЕТ и почему это стоит знать.** Кнопка «⚙» в шапке — белый круг
 * ([ColorSlot.IN_PLATE]) на плашке, и если сделать их одним цветом, круг пропадёт. Дорога
 * при этом останется: вся плашка целиком — область нажатия, и она ведёт в переключение
 * окон, откуда настройки открываются строкой. То есть названная заказчиком дорога живёт,
 * а короткий путь через «⚙» — нет. Пара не добавлена: «защищаем **только**» сказано
 * прямо, а решение расширить список — за заказчиком.
 */
enum class VitalPair(
    val front: ColorSlot,
    val back: ColorSlot,
    /** Где это видно человеку — словами, которые он прочтёт в предупреждении. */
    val where: String,
) {
    PLATE(ColorSlot.ON_ACCENT, ColorSlot.NAVIGATION, "имя окна в шапке и стрелка «назад»"),
    CONTENT(ColorSlot.TEXT, ColorSlot.SURFACE, "переключение окон и список настроек"),
}

/**
 * Ниже какого контраста цвета считаются слившимися.
 *
 * **Это не порог читаемости, и путать их нельзя.** Порог читаемости — 4,5
 * ([TimaContrast.TEXT_THRESHOLD]), и светлая тема его для плашки не берёт: белым по
 * салатовому 2,08 : 1, решение принято осознанно 2026-09-02. Требовать здесь 4,5 значило
 * бы объявить незаконной свою же светлую тему — а её ставит кнопка «Вернуть светлую».
 *
 * Поэтому порог отвечает на другой вопрос — **«слилось или нет»**, а не «читается ли
 * хорошо». 1,8 : 1 пропускает светлую тему с запасом в 0,28 и отсекает белое на всём,
 * что светлее примерно `#C9C9C9`. Запас тонкий, и он под присмотром теста: правка
 * палитры, из-за которой готовая тема перестанет проходить собственную проверку,
 * покраснеет, а не проедет молча.
 */
const val MERGE_LIMIT: Double = 1.8

/** Контраст пары — с учётом прозрачности: полупрозрачный цвет сперва кладётся на фон. */
fun TimaColors.contrastOf(pair: VitalPair): Double {
    val back = solid(slot(pair.back), TimaFixed.paper)
    return TimaContrast.ratio(solid(slot(pair.front), back), back)
}

/**
 * Какие из жизненно важных пар слились. Пусто — дорога назад видна.
 *
 * Считается по **выбранной теме целиком**, а не по своей палитре: у готовых тем слиться
 * нечему, и проверка на них не срабатывает никогда.
 */
fun TimaColors.merged(): List<VitalPair> =
    VitalPair.entries.filter { contrastOf(it) < MERGE_LIMIT }

private fun solid(color: Color, under: Color): Color =
    if (color.alpha < 1f) TimaContrast.overlay(color, under) else color

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
    if (clean.any { it !in HEX }) return null
    val full = if (clean.length == 6) "FF$clean" else clean
    fun part(at: Int): Int = full.substring(at, at + 2).toInt(16)
    return Color(red = part(2), green = part(4), blue = part(6), alpha = part(0))
}

private const val HEX = "0123456789ABCDEF"

/**
 * Что именно не так с набранным цветом. `null` — всё так.
 *
 * ── ПОЧЕМУ ЭТО ОТДЕЛЬНАЯ ФУНКЦИЯ, А НЕ `colorOf() == null` ───────────────────
 *
 * «Не разобрано» — это не сообщение, а отсутствие сообщения. Человек видит, что цвет не
 * применился, и не знает, дело в лишнем знаке, в нехватке знака или в том, что он набрал
 * русскую «С» вместо латинской «C» — а последнее на глаз неотличимо вовсе.
 *
 * Решение заказчика 2026-09-03: «вместо того, чтобы не дать сохранить неправильный, с
 * указанием, что не так». Проверки идут от частного к общему: сначала называется чужой
 * знак — он и есть причина, — и только потом длина.
 */
fun colorProblem(text: String): String? {
    val clean = text.trim().removePrefix("#").uppercase()
    if (clean.isEmpty()) return "Пусто. Наберите цвет: шесть знаков или восемь"

    val strangers = clean.filter { it !in HEX }.toSet()
    if (strangers.isNotEmpty()) {
        val listed = strangers.joinToString("», «", prefix = "«", postfix = "»")
        return "Не шестнадцатеричные знаки: $listed. Допустимы 0–9 и A–F"
    }
    if (clean.length != 6 && clean.length != 8) {
        return "Знаков ${clean.length}, а нужно 6 (цвет) или 8 (с непрозрачностью)"
    }
    return null
}

/**
 * Готовые цвета для выбора: сначала подходящие этому месту, потом вся палитра.
 *
 * ── ОТКУДА БЕРУТСЯ ЦВЕТА ────────────────────────────────────────────────────
 *
 * **Не выписаны руками.** Набор — это все значения обеих готовых тем, и ничего больше:
 * палитра проекта и есть то, из чего собраны светлая с тёмной. Выписанный от руки
 * список разошёлся бы с темами при первой же правке — и предлагал бы человеку цвета,
 * которых в проекте уже нет.
 *
 * Первыми идут два значения **этого самого места**: каким оно было бы в светлой теме и
 * каким в тёмной. Чаще всего человек хочет именно их — «как в тёмной, но фон свой».
 */
fun paletteFor(slot: ColorSlot): List<Color> {
    val own = listOf(TimaColors.light.slot(slot), TimaColors.dark.slot(slot))
    val all = ColorSlot.entries.flatMap { listOf(TimaColors.light.slot(it), TimaColors.dark.slot(it)) }
    return (own + all).distinct()
}
