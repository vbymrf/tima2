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
    val wizard: WizardWords
    val auth: AuthWords
}

/**
 * Вход, привязка устройства, виртуальные аккаунты и передача аккаунта.
 *
 * Здесь много длинных предупреждений, и они не украшение: экран входа объясняет цену
 * действия до кнопки, а не после. Переводить их придётся целиком — сокращать нельзя.
 *
 * Куски с подстановкой сделаны **функциями**, а не склейкой на экране: в другом языке
 * порядок слов иной, и «Код отправлен на » + номер перевести невозможно.
 */
interface AuthWords {
    /** Номер сборки внизу экрана входа: его спрашивает чинящий, а не человек. */
    fun build(version: String): String

    // Номер и код.
    val welcome: String
    val enterPhone: String
    val sending: String
    val getCode: String
    val alreadyHaveAccount: String
    val connectToAccount: String
    val confirmation: String
    fun codeSentTo(phone: String): String
    fun standSentCode(code: String): String
    val checking: String
    val confirm: String
    val changeNumber: String

    // Привязка второго устройства.
    val connectingDevice: String
    val connectingDeviceAbout: String
    val askingCode: String
    val oldChatsWontMove: String
    val newCode: String

    // Секретная фраза.
    val secretPhrase: String
    val secretPhraseAbout: String
    val wroteDown: String
    val phraseHint: String
    val phraseEntry: String
    fun accountExistsFor(phone: String): String
    val enter: String
    val otherNumber: String
    val noPhrase: String
    val startAnew: String

    // Виртуальный аккаунт.
    val virtualAccount: String
    val newNickname: String
    val newNicknameAbout: String
    val further: String
    val fiveAtMost: String
    val linkNotHiddenFromUs: String
    val yourSecretPhrase: String
    val yourSecretPhraseAbout: String
    val creating: String
    fun createAccount(nickname: String): String
    val wordsGoNowhere: String
    fun phraseOf(nickname: String): String
    val virtualPhraseSaved: String
    val virtualEntryFromYourNumber: String

    // Передача аккаунта.
    val giveAccount: String
    val takeAccount: String
    val whatHappens: String
    val transferTakesAll: String
    val transferCutsFuture: String
    val othersWontNotice: String
    val preparingCode: String
    val issueTransferCode: String
    val transferCode: String
    fun codeLives(minutes: Int): String
    val phraseSeparately: String
    val wrongPhraseCosts: String
    val transferCancelled: String
    val cancelTransfer: String
    val takeAccountAbout: String
    val accountPhrase: String
    val bringCodeHint: String
    val entryFromYourNumber: String
    val accountYours: String
    val accountYoursAbout: String
    val rotateGroupKeys: String
    val enterAccount: String

    // Подтверждение подключения на телефоне.
    val deviceConnected: String
    val deviceConnectedAbout: String
    val notConnectionCode: String
    val notConnectionCodeAbout: String
    val confirmConnection: String
    fun deviceNamed(name: String): String
    val deviceUnnamed: String
    val connectedDeviceCan: String
    val connecting: String
    val trust: String
    val reject: String

    // Список устройств.
    val watching: String
    val listNotCame: String
    val noDevices: String
    val reasonAbove: String
    val emptyListIsOurs: String
    val nameless: String
    val thisDevice: String
    val disconnect: String
    val disconnectDevice: String
    val disconnectAbout: String
    val keep: String

    // Виртуальные аккаунты списком.
    val virtualAboutShort: String
    val yourAccounts: String
    val noPhoneFoundByNickname: String
    val give: String
    val noVirtualsYet: String
    val actions: String
    val createVirtual: String
    val fiveAtMostShort: String
    val takeNeedsCodeAndPhrase: String
    val linkNotHiddenLong: String
}

/**
 * Мастер создания: разделы, шаги и их пояснения (ПЛАН-СООБЩЕСТВ С5).
 *
 * Названия разделов и способов вступления лежат здесь, а не в перечислениях `Section` и
 * `Joining`: то же решение, что у тем и цветовых мест — перечисление остаётся ключом.
 */
interface WizardWords {
    val create: String
    val creating: String
    val next: String
    val gotIt: String

    // Шаги.
    val whatCreate: String
    val whichGroup: String
    val howJoin: String
    val howFound: String
    val discussable: String
    val naming: String
    val bringing: String

    // Разделы.
    val sectionGroup: String
    val sectionGroupAbout: String
    val sectionChannel: String
    val sectionChannelAbout: String
    val sectionCommunity: String
    val sectionCommunityAbout: String
    val sectionVoice: String
    val sectionVoiceAbout: String

    /** Подпись у раздела, которого ещё нет. Не «скоро»: это решение, а не очередь. */
    val waitsImplementation: String

    // Вид группы.
    val personal: String
    val personalAbout: String
    val personalExplain: String
    val public: String
    val publicAbout: String
    val publicExplain: String
    val kindIsFinal: String

    // Вступление.
    val openJoining: String
    val openJoiningAbout: String
    val closedJoining: String
    val closedJoiningAbout: String
    val noneForPersonal: String
    val openExplain: String
    val closedExplain: String
    val personalAlwaysClosed: String

    // Канал.
    val inCatalogue: String
    val inCatalogueAbout: String
    val inCatalogueExplain: String
    val byLink: String
    val byLinkAbout: String
    val byLinkExplain: String
    val commentsAllowed: String
    val commentsAllowedAbout: String
    val commentsAllowedExplain: String
    val commentsForbidden: String
    val commentsForbiddenAbout: String
    val commentsForbiddenExplain: String

    // Название и приглашения.
    val groupName: String
    val descriptionAbout: String
    val whomInvite: String
    val add: String
    val remove: String
    val numberAlreadyListed: String
    val groupCreatedNotInvited: String
    val communityCreatedNotLinked: String
    val nothingFreeToBring: String
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

    override val auth = object : AuthWords {
        override fun build(version: String) = "сборка $version"

        override val welcome = "Добро пожаловать"
        override val enterPhone = "Введите номер телефона — пришлём код"
        override val sending = "Отправляем…"
        override val getCode = "Получить код"
        override val alreadyHaveAccount =
            "Аккаунт уже есть на телефоне? Это устройство можно подключить к нему — " +
                "код подтвердите телефоном."
        override val connectToAccount = "Подключить к аккаунту"
        override val confirmation = "Подтверждение"
        override fun codeSentTo(phone: String) = "Код отправлен на $phone"
        override fun standSentCode(code: String) = "Стенд прислал код в ответе: $code"
        override val checking = "Проверяем…"
        override val confirm = "Подтвердить"
        override val changeNumber = "Изменить номер"

        override val connectingDevice = "Подключение устройства"
        override val connectingDeviceAbout =
            "Откройте камеру на телефоне, где вы уже вошли, и наведите её на этот код. " +
                "Телефон спросит подтверждение — код действует пять минут."
        override val askingCode = "Просим код у сервера…"
        override val oldChatsWontMove =
            "Прежняя переписка на это устройство не переедет: ключи старых сообщений " +
                "оборачивались на другие устройства. Новые письма будут приходить на оба."
        override val newCode = "Новый код"

        override val secretPhrase = "Секретная фраза"
        override val secretPhraseAbout =
            "Двенадцать слов — единственный способ вернуться в аккаунт, если телефон " +
                "потерян. Запишите их по порядку и держите отдельно от телефона."
        override val wroteDown = "Записал"
        override val phraseHint = "слово слово слово…"
        override val phraseEntry = "Вход по фразе"
        override fun accountExistsFor(phone: String) =
            "У номера $phone уже есть аккаунт. Введите его секретную фразу — " +
                "двенадцать слов через пробел."
        override val enter = "Войти"
        override val otherNumber = "Другой номер"
        override val noPhrase =
            "Фразы нет? Можно начать заново: прежняя переписка не вернётся, а собеседники " +
                "увидят предупреждение о смене личности."
        override val startAnew = "Начать заново"

        override val virtualAccount = "Виртуальный аккаунт"
        override val newNickname = "Ник нового аккаунта"
        override val newNicknameAbout =
            "Это отдельный пользователь: своя переписка, свои ключи, своя секретная фраза. " +
                "Телефона у него нет — находят его только по нику, поэтому ник обязателен."
        override val further = "Дальше"
        override val fiveAtMost =
            "Виртуальных аккаунтов на один номер — не больше пяти. Занятый ник не " +
                "освобождается никогда."
        override val linkNotHiddenFromUs =
            "Собеседники не увидят связи с вашим основным аккаунтом. От нас она не скрыта: " +
                "привязка хранится на сервере."
        override val yourSecretPhrase = "Ваша секретная фраза"
        override val yourSecretPhraseAbout =
            "Новый аккаунт заводится вашей подписью: телефона у него нет, и код на него не " +
                "придёт. Введите двенадцать слов вашего основного аккаунта — через пробел."
        override val creating = "Заводим…"
        override fun createAccount(nickname: String) = "Завести аккаунт «$nickname»"
        override val wordsGoNowhere =
            "Слова никуда не отправляются: из них считается подпись, и на этом они забываются."
        override fun phraseOf(nickname: String) = "Фраза аккаунта «$nickname»"
        override val virtualPhraseSaved =
            "Аккаунт заведён. Эти двенадцать слов — единственный способ вернуться в него. " +
                "Запишите их по порядку: показать второй раз будет нечем."
        override val virtualEntryFromYourNumber =
            "Войти в этот аккаунт заново можно только с вашего номера: своего телефона у " +
                "него нет, и код придёт вам."

        override val giveAccount = "Передать аккаунт"
        override val takeAccount = "Принять аккаунт"
        override val whatHappens = "Что произойдёт"
        override val transferTakesAll =
            "Аккаунт уйдёт целиком: переписка, группы, каналы, роли и владение. Ваши " +
                "устройства в нём будут отключены, и войти в него вы больше не сможете — " +
                "код на вход приходит владельцу, а владельцем станет другой человек."
        override val transferCutsFuture =
            "Передача отрезает будущее, а не прошлое: всё, что вы уже прочитали, осталось " +
                "на вашем телефоне, и передача этого не стирает."
        override val othersWontNotice =
            "Собеседники ничего не заметят: у аккаунта нет телефона, и они с самого начала " +
                "разговаривают с ником, а не с номером."
        override val preparingCode = "Готовим код…"
        override val issueTransferCode = "Выдать код передачи"
        override val transferCode = "Код передачи"
        override fun codeLives(minutes: Int) =
            "Покажите его тому, кому передаёте: он наведёт камеру. Код живёт " +
                "$minutes минут и годится один раз."
        override val phraseSeparately =
            "Фразу аккаунта передайте ОТДЕЛЬНО и другим путём — не тем сообщением, что код. " +
                "Вместе они и есть аккаунт: перехвативший одну переписку получит оба."
        override val wrongPhraseCosts =
            "Неверная фраза тратит попытку: после третьей код сгорит, и придётся выдать новый."
        override val transferCancelled = "Передача отменена — код больше не действует"
        override val cancelTransfer = "Отменить передачу"
        override val takeAccountAbout =
            "Нужны две вещи, и обе от того, кто передаёт: код и секретная фраза аккаунта. " +
                "Одного кода мало — он ничего не открывает без фразы."
        override val accountPhrase = "Секретная фраза аккаунта"
        override val bringCodeHint = "наведите камеру или вставьте код"
        override val entryFromYourNumber =
            "Дальше входить в этот аккаунт вы будете со своего номера: своего телефона у " +
                "него нет, и код придёт вам."
        override val accountYours = "Аккаунт ваш"
        override val accountYoursAbout =
            "Устройства прежнего владельца отключены, и вход в аккаунт теперь ваш. " +
                "Фразу смените: прежнюю знает тот, кто вам её дал."
        override val rotateGroupKeys =
            "В группах этого аккаунта нужно сменить ключ: до этого прежний владелец " +
                "продолжит читать в них новое. Откройте состав группы и смените ключ."
        override val enterAccount = "Войти в аккаунт"

        override val deviceConnected = "Устройство подключено"
        override val deviceConnectedAbout =
            "Новые сообщения будут приходить и на него. Прежняя переписка туда не " +
                "переедет: ключи старых сообщений оборачивались на другие устройства."
        override val notConnectionCode = "Это не код подключения"
        override val notConnectionCodeAbout =
            "Отсканирован другой код. Откройте на компьютере «Подключить к аккаунту» " +
                "и наведите камеру на код оттуда."
        override val confirmConnection = "Подтвердить подключение?"
        override fun deviceNamed(name: String) = "Устройство: $name"
        override val deviceUnnamed = "Устройство себя не назвало"
        override val connectedDeviceCan =
            "Подключённое устройство сможет читать новые сообщения этого аккаунта и писать " +
                "от вашего имени. Отключить его можно в списке устройств."
        override val connecting = "Подключаем…"
        override val trust = "Доверить"
        override val reject = "Отклонить"

        override val watching = "Смотрим…"
        override val listNotCame = "Список не пришёл"
        override val noDevices = "Устройств нет"
        override val reasonAbove = "Причина выше. Это не значит, что устройств нет"
        override val emptyListIsOurs =
            "Сервер пустой список не отдаёт — значит дело на этой стороне"
        override val nameless = "Без имени"
        override val thisDevice = "это устройство"
        override val disconnect = "Отключить"
        override val disconnectDevice = "Отключить устройство?"
        override val disconnectAbout =
            "Оно перестанет получать сообщения и потеряет доступ к аккаунту. Вернуть его " +
                "нельзя — на нём придётся подключаться заново."
        override val keep = "Оставить"

        override val virtualAboutShort =
            "Виртуальный аккаунт — отдельный пользователь: своя переписка, свои ключи, " +
                "своя секретная фраза. Телефона у него нет, находят его по нику."
        override val yourAccounts = "Ваши аккаунты"
        override val noPhoneFoundByNickname = "нет телефона · находят по нику"
        override val give = "Передать"
        override val noVirtualsYet = "Виртуальных аккаунтов пока нет."
        override val actions = "Действия"
        override val createVirtual = "Завести виртуальный аккаунт"
        override val fiveAtMostShort = "не больше пяти на номер"
        override val takeNeedsCodeAndPhrase = "нужны код и фраза от того, кто передаёт"
        override val linkNotHiddenLong =
            "Связь с вашим основным аккаунтом не видна собеседникам. От нас она не " +
                "скрыта: привязка хранится на сервере — иначе её нечем было бы проверять."
    }

    override val wizard = object : WizardWords {
        override val create = "Создать"
        override val creating = "Создаём…"
        override val next = "Далее"
        override val gotIt = "Понятно"

        override val whatCreate = "Что создаём?"
        override val whichGroup = "Какая группа?"
        override val howJoin = "Как вступают?"
        override val howFound = "Как находят канал?"
        override val discussable = "Записи можно обсуждать?"
        override val naming = "Название и описание"
        override val bringing = "Что вносим?"

        override val sectionGroup = "Группа"
        override val sectionGroupAbout = "Общение нескольких участников. Личная или публичная"
        override val sectionChannel = "Канал"
        override val sectionChannelAbout = "Публикации для подписчиков"
        override val sectionCommunity = "Сообщество"
        override val sectionCommunityAbout =
            "Контейнер: группы и каналы. Связывает готовое, а не создаёт новое"
        override val sectionVoice = "Звуковой чат"
        override val sectionVoiceAbout = "Голосовая комната. Ждёт реализации"
        override val waitsImplementation = "ждёт реализации"

        override val personal = "Личная"
        override val personalAbout = "Сообщения зашифрованы. Поиском не находится — зовут по знакомству"
        override val personalExplain =
            "Личная группа: сквозное шифрование, сервер переписки не видит. " +
                "Поиском не находится — о ней узнают по цепочке знакомств."
        override val public = "Публичная"
        override val publicAbout = "Находится поиском. Открытый и закрытый доступ участников"
        override val publicExplain =
            "Публичная группа: открытое общение, находится поиском и каталогом. " +
                "Шифрования переписки нет."
        override val kindIsFinal = "Вид не меняется после создания: от него зависит шифрование"

        override val openJoining = "Открытая"
        override val openJoiningAbout = "Нашёл и вступил сам"
        override val closedJoining = "Закрытая"
        override val closedJoiningAbout = "Подал заявку, админ разрешил"
        override val noneForPersonal = "у личной нет"
        override val openExplain = "Открытая: человек находит группу и вступает сам."
        override val closedExplain = "Закрытая: человек подаёт заявку, админ разрешает."
        override val personalAlwaysClosed =
            "Личная группа всегда закрытая: её не находят поиском, и вступить самому некуда"

        override val inCatalogue = "Открытый"
        override val inCatalogueAbout = "Виден в каталоге, подписаться может любой"
        override val inCatalogueExplain = "Открытый канал. Виден в каталоге, подписаться может любой"
        override val byLink = "По подписке"
        override val byLinkAbout = "В каталоге не показывается — находят по ссылке"
        override val byLinkExplain = "По подписке. Канала нет в каталоге, его находят по ссылке"
        override val commentsAllowed = "Можно"
        override val commentsAllowedAbout =
            "Под записью открывается разговор. Комментирует тот, кто видит запись"
        override val commentsAllowedExplain =
            "Комментирует тот, кто видит запись: отдельного права нет"
        override val commentsForbidden = "Нельзя"
        override val commentsForbiddenAbout = "Канал без обсуждений. Это можно поменять потом"
        override val commentsForbiddenExplain =
            "Выключено значит «новых не принимаем»: написанное раньше остаётся"

        override val groupName = "Название группы"
        override val descriptionAbout = "Описание — его видят все, кому открыта карточка"
        override val whomInvite = "Кого позвать"
        override val add = "Добавить"
        override val remove = "убрать"
        override val numberAlreadyListed = "Этот номер уже в списке"
        override val groupCreatedNotInvited =
            "Группа создана. Этих номеров в TIMA нет — позовите людей:"
        override val communityCreatedNotLinked =
            "Сообщество создано. Это внести не удалось — они уже в другом:"
        override val nothingFreeToBring =
            "Своих групп и каналов, свободных для внесения, нет. Сообщество можно создать пустым"
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
