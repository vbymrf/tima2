package io.tima.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Словарь надписей (ПЛАН-ЯЗЫКА Я1, Я2).
 *
 * ── ПОЧЕМУ СЛОВАРЬ НА KOTLIN, А НЕ ФАЙЛ РЕСУРСОВ ────────────────────────────
 *
 * У нас **тексты сидят в перечислениях**: имя пункта настроек одновременно ключ навигации
 * и надпись. Файл ресурсов такой случай обслуживает плохо, а компилятор, который ловит
 * пропущенный перевод, стоит дороже удобства переводчика — переводчика у нас нет вовсе,
 * переводит агент (решение заказчика 2026-09-08).
 *
 * Пропущенная надпись здесь **не собирается**, а не обнаруживается на экране.
 *
 * ── ПОЧЕМУ ГРУППАМИ, А НЕ ОДНИМ СПИСКОМ ─────────────────────────────────────
 *
 * Надписей около девятисот. Плоский список из девятисот имён невозможно ни прочитать, ни
 * перевести партиями: переводчик (то есть агент) берёт группу целиком и видит её словарь
 * рядом, а не ищет по алфавиту среди чужих слов.
 *
 * Группа = экран или подсистема, а не «строки поменьше»: так перевод одной части не
 * трогает другую, и партия Я2 совпадает с группой.
 *
 * ── ЧТО СЮДА НЕ КЛАДЁТСЯ ────────────────────────────────────────────────────
 *
 * **Журнал и коды журнала, отчёт о проблеме** — они инструмент чинящего, и переведённый
 * журнал перестаёт находиться поиском по коду.
 *
 * **Сообщения `require` и `error`** — они адресованы тому, кто чинит код, а не человеку с
 * телефоном. Их кириллица остаётся и в счётчике не участвует.
 */
interface Words {

    /** Двухбуквенный тег языка: `ru`, `en`, `es`. Он же лежит в настройках. */
    val tag: String

    /** Название языка на нём самом: так его узнают в списке, не зная текущего. */
    val ownName: String

    val common: CommonWords
    val settings: SettingsWords
    val appearance: AppearanceWords
    val comments: CommentWords
    val communities: CommunityWords
    val tabs: TabWords
}

/**
 * Названия вкладок, фильтров и режимов окон.
 *
 * Ключ — [WindowTab], слово — здесь. До Я2 вкладка была строкой и служила и тем, и другим:
 * перевести её было нельзя, потому что перевод сменил бы ключ.
 */
interface TabWords {
    fun label(tab: WindowTab): String
}

/** Слова, встречающиеся всюду: кнопки, состояния, беды. */
interface CommonWords {
    val back: String
    val cancel: String
    val ready: String
    val send: String
    val hide: String
    val noConnection: String
    val nothingChosen: String
}

interface SettingsWords {
    val settings: String
    val language: String
    val languageAbout: String
    val appLanguage: String
    val country: String
    val countryAbout: String
    val countryHint: String
    val whatToShow: String
    val onlyMyCountry: String
    val onlyMyLanguages: String
    val filterNotForChats: String
    val on: String
    val off: String
    val chosen: String
    val soon: String
}

/**
 * Оформление: названия тем и цветовых мест.
 *
 * **Названия лежат здесь, а не в перечислении.** До Я2 `ThemeChoice` и `ColorSlot` носили
 * их в себе — это ровно тот случай, ради которого выбран словарь на Kotlin: перечисление
 * остаётся ключом, а слово приходит из словаря.
 */
interface AppearanceWords {
    fun theme(choice: ThemeChoice): String
    fun slot(slot: ColorSlot): String
    fun about(slot: ColorSlot): String

    /** Беда с набранным цветом — фраза собирается здесь, из вида беды. */
    fun colorTrouble(trouble: ColorTrouble): String
    val qrTooLong: String
    val qrTooLongAbout: String

    val theme: String
    val colors: String
    val palette: String
    val projectColors: String
    val customOnly: String
    val alphaHint: String
    val apply: String
    val takeFromLight: String
    val backToLight: String
    val backToDark: String

    /** Два цвета слились: экран перестал читаться, и «назад» подождёт. */
    val merged: String
    fun mergedAbout(front: String, back: String, ratio: String, where: String): String
}

/** Разговор под записью и ветка в группе (ADR-0024). */
interface CommentWords {
    val comments: String
    val thread: String
    val hint: String
    val reply: String
    val closed: String
    val closedButOldStay: String
    val nobodyWroteYet: String
    val postGone: String
    val postGoneAbout: String
    val loading: String
}

/** Сообщества (ПЛАН-СООБЩЕСТВ). */
interface CommunityWords {
    val community: String
    val communities: String
    val subscribe: String
    val unsubscribe: String
    val bring: String
    val takeOut: String
    val bringOwn: String
    val bringingKeepsEverything: String
    val noDescription: String
    val emptyInside: String
    val opening: String
    val channel: String
    val group: String
    val personalGroup: String
    val yourCommunity: String
    val youSubscribed: String
    val youOwner: String
    val youAdmin: String
    val youNotSubscribed: String
}

/**
 * Русский словарь.
 *
 * Он же образец для остальных: перевод — тот же список имён с другими значениями, и ни
 * одно имя не пропадает. Пропадёт — не соберётся.
 */
object RussianWords : Words {
    override val tag = "ru"
    override val ownName = "Русский"

    override val common = object : CommonWords {
        override val back = "Назад"
        override val cancel = "Отмена"
        override val ready = "Готово"
        override val send = "Отправить"
        override val hide = "Скрыть"
        override val noConnection = "Нет связи с сервером"
        override val nothingChosen = "Ничего не выбрано"
    }

    override val settings = object : SettingsWords {
        override val settings = "Настройки"
        override val language = "Язык"
        override val languageAbout = "Язык приложения. Сообщения не переводятся"
        override val appLanguage = "Язык приложения"
        override val country = "Страна"
        override val countryAbout = "Ею сервер отбирает выдачу: своё, а не весь мир. Пусто — показывать всё"
        override val countryHint = "RU"
        override val whatToShow = "Что показывать"
        override val onlyMyCountry = "Только моя страна"
        override val onlyMyLanguages = "Только мои языки"
        override val filterNotForChats = "Переписки и ленты друзей это не касается"
        override val on = "включено"
        override val off = "выключено"
        override val chosen = "выбран"
        override val soon = "скоро"
    }

    override val appearance = object : AppearanceWords {
        override fun theme(choice: ThemeChoice) = when (choice) {
            ThemeChoice.Light -> "Светлая"
            ThemeChoice.Dark -> "Тёмная"
            ThemeChoice.Custom -> "Пользовательская"
        }

        override fun slot(slot: ColorSlot) = when (slot) {
            ColorSlot.NAVIGATION -> "Навигация и действие"
            ColorSlot.ACTIVITY -> "Активность"
            ColorSlot.CONFIRMED -> "Подтверждено"
            ColorSlot.SURFACE -> "Фон содержимого"
            ColorSlot.FUNCTIONAL -> "Фон панелей"
            ColorSlot.TEXT -> "Текст"
            ColorSlot.TEXT_2 -> "Текст потише"
            ColorSlot.TEXT_3 -> "Текст ещё тише"
            ColorSlot.MY -> "Фон моих сообщений"
            ColorSlot.AUTHOR -> "Фон чужих сообщений"
            ColorSlot.BORDER -> "Рамка сообщения"
            ColorSlot.LINE -> "Линия списка"
            ColorSlot.ON_ACCENT -> "Текст на зелёном"
            ColorSlot.ON_AMBER -> "Текст на янтаре"
            ColorSlot.IN_PLATE -> "Внутри плашки"
            ColorSlot.SOFT_ACCENT -> "Тихая подложка"
            ColorSlot.QUIET -> "Нейтральная подложка"
        }

        override fun about(slot: ColorSlot) = when (slot) {
            ColorSlot.NAVIGATION -> "логотип, текущее окно, «назад», «отправить»"
            ColorSlot.ACTIVITY -> "счётчик непрочитанного"
            ColorSlot.CONFIRMED -> "доставлено, прочитано, метка E2E"
            ColorSlot.SURFACE -> "лента и переписка"
            ColorSlot.FUNCTIONAL -> "шапка, вкладки, строка ввода"
            ColorSlot.TEXT -> "основной"
            ColorSlot.TEXT_2 -> "подписи, время"
            ColorSlot.TEXT_3 -> "третий уровень"
            ColorSlot.LINE -> "между записями"
            ColorSlot.ON_ACCENT -> "на кнопках, вкладках, плашке шапки"
            ColorSlot.ON_AMBER -> "на счётчике непрочитанного"
            ColorSlot.IN_PLATE -> "логотип и кнопки на салатовом"
            ColorSlot.SOFT_ACCENT -> "невыбранная вкладка, поле ввода"
            ColorSlot.QUIET -> "невыбранная подвкладка, капсула переключателя"
            // У трёх мест пояснения нет: название говорит само, и подпись под ним была бы
            // повтором. Пустая строка здесь — решение, а не пропуск.
            ColorSlot.MY, ColorSlot.AUTHOR, ColorSlot.BORDER -> ""
        }

        override fun colorTrouble(trouble: ColorTrouble) = when (trouble) {
            ColorTrouble.Empty -> "Пусто. Наберите цвет: шесть знаков или восемь"
            is ColorTrouble.NotHex -> "Не шестнадцатеричные знаки: ${trouble.listed}. Допустимы 0–9 и A–F"
            is ColorTrouble.WrongLength ->
                "Знаков ${trouble.length}, а нужно 6 (цвет) или 8 (с непрозрачностью)"
        }
        override val qrTooLong = "Код не показать"
        override val qrTooLongAbout = "Он слишком длинный для QR"

        override val theme = "Тема"
        override val colors = "Цвета"
        override val palette = "Палитра"
        override val projectColors = "Цвета проекта"
        override val customOnly =
            "Свои цвета показываются, когда выбрана «Пользовательская». " +
                "Правки в ней сохраняются и при переключении на светлую или тёмную."
        override val alphaHint = "Первые два знака — непрозрачность: FF непрозрачный, 00 невидимый"
        override val apply = "Применить"
        override val takeFromLight = "Взять из светлой"
        override val backToLight = "Вернуть светлую"
        override val backToDark = "Вернуть тёмную"

        override val merged = "Так отсюда не выйти"

        // Фраза собирается целиком, а не склеивается из кусков на экране: на другом языке
        // порядок слов другой, и склейка разваливается первой (ПЛАН-ЯЗЫКА §3).
        override fun mergedAbout(front: String, back: String, ratio: String, where: String) =
            "«$front» и «$back» слились: $ratio : 1. Этим нарисовано $where — " +
                "без них до оформления уже не дойти, поэтому «назад» подождёт." 
    }

    override val comments = object : CommentWords {
        override val comments = "Комментарии"
        override val thread = "Ветка"
        override val hint = "Написать комментарий…"
        override val reply = "Ответить"
        override val closed = "Обсуждение закрыто"
        override val closedButOldStay = "Обсуждение закрыто. Написанное раньше осталось"
        override val nobodyWroteYet = "Здесь ещё никто не написал"
        override val postGone = "Записи больше нет"
        override val postGoneAbout = "Разговор ушёл вместе с ней"
        override val loading = "Загружаем разговор…"
    }

    override val tabs = object : TabWords {
        override fun label(tab: WindowTab) = when (tab) {
            WindowTab.Chats -> "Чаты"
            WindowTab.Contacts -> "Контакты"
            WindowTab.Calls -> "Звонки"
            WindowTab.View -> "Вид"

            WindowTab.All -> "Все"
            WindowTab.FromBook -> "Контактов"
            WindowTab.Unknown -> "Неизвестные"
            WindowTab.Missed -> "Пропущенные"

            WindowTab.Common -> "Общая"
            WindowTab.Friends -> "Друзья"
            WindowTab.Catalogue -> "Каталог"

            WindowTab.Feed -> "Лента"
            WindowTab.Slides -> "Слайды"

            WindowTab.Answers -> "Ответы"
            WindowTab.Reactions -> "Реакции"
            WindowTab.Collections -> "Коллекции"

            WindowTab.Comments -> "Комментарии"
            WindowTab.Marks -> "Оценки"

            WindowTab.Subscribed -> "Подписан"
            WindowTab.Groups -> "Группы"

            WindowTab.Media -> "Медиа"
            WindowTab.Messages -> "Сообщения"
            WindowTab.Open -> "Открытое"
            WindowTab.Personal -> "Личное"
        }
    }

    override val communities = object : CommunityWords {
        override val community = "Сообщество"
        override val communities = "Сообщества"
        override val subscribe = "Подписаться"
        override val unsubscribe = "Отписаться"
        override val bring = "Внести"
        override val takeOut = "Вынуть"
        override val bringOwn = "Внести своё"
        override val bringingKeepsEverything =
            "Переписка, участники и ключи не меняются — меняется одна ссылка"
        override val noDescription = "Описания пока нет"
        override val emptyInside = "В сообществе пока ничего нет"
        override val opening = "Открываем сообщество…"
        override val channel = "канал"
        override val group = "группа"
        override val personalGroup = "личная группа"
        override val yourCommunity = "ваше сообщество"
        override val youSubscribed = "вы подписаны"
        override val youOwner = "вы владелец"
        override val youAdmin = "вы админ"
        override val youNotSubscribed = "вы не подписаны"
    }
}

/**
 * Языки приложения.
 *
 * Три: русский, английский, испанский — решение заказчика 2026-09-08. Английский и
 * испанский заводятся вместе со своими словарями (Я9, Я10); до тех пор список знает о них,
 * но выбрать можно лишь то, у чего словарь есть — [available].
 */
enum class Language(val tag: String, val ownName: String, val words: Words?) {
    Russian("ru", "Русский", RussianWords),
    English("en", "English", null),
    Spanish("es", "Español", null);

    /** Есть ли словарь. Выбирать язык без словаря — обещать надписи, которых нет. */
    val available: Boolean get() = words != null

    companion object {
        /** По тегу из настроек. Незнакомый тег — русский: приложение обязано открыться. */
        fun of(tag: String): Language = entries.firstOrNull { it.tag == tag } ?: Russian
    }
}

/**
 * Раздача словаря. Умолчание — русский: приложение обязано говорить даже там, где язык
 * ещё не выбран.
 */
val LocalWords: ProvidableCompositionLocal<Words> = staticCompositionLocalOf { RussianWords }

/** Короткий доступ: `Tima.words.comments.thread`. */
val Tima.words: Words
    @Composable get() = LocalWords.current
