package io.tima.core.words

import kotlin.concurrent.Volatile

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
    val chat: ChatWords
    val call: CallWords
    val book: BookWords
    val page: PageWords
    val social: SocialWords
    val update: UpdateWords
    val storage: StorageWords
    val windows: WindowWords
    val settings2: SettingsListWords
    val problem: ProblemWords
    val switching: SwitchingWords
    val trouble: TroubleWords
}

/**
 * Беды, общие всему приложению (ПЛАН-ЯЗЫКА, Я2-беды).
 *
 * «Нет связи» и «сервер отказал» приходят из десятка разных мест и обязаны звучать
 * одинаково: разные слова об одном и том же человек читает как разные поломки.
 *
 * `refused` подставляет причину **как она пришла с сервера** — она не переводится и
 * переводиться не может: это его слова, а не наши.
 */
interface TroubleWords {
    val offline: String
    fun refused(reason: String): String
    val didNotReach: String
    fun retryIn(seconds: Int): String
    val noConnection: String
}

/**
 * Экран «Сообщить о проблеме» — то, что читает жалующийся.
 *
 * **Тело отчёта сюда не входит и не войдёт.** Факты об устройстве, снимок состояния и
 * журнал остаются по-русски всегда: их читает чинящий, а не тот, кто прислал отчёт
 * (ПЛАН-ЯЗЫКА §4). Переведённый отчёт пришлось бы переводить обратно.
 */
interface ProblemWords {
    val whatHappened: String
    val describeHint: String
    val onlyYouKnow: String
    val whenBegan: String
    val today: String
    val thisWeek: String
    val earlier: String
    val whatAbout: String
    val kindMessages: String
    val kindCalls: String
    val kindLooks: String
    val kindOther: String
    val whatGoes: String
    val whatGoesAbout: String
    val crashesSentThemselves: String
    val watch: String
    val stateNow: String
    val whatHappenedLog: String
    val emptyDiary: String
    val reportSent: String
    val sending: String
    val send: String
    val writeAgainHow: String
    val reportNumber: String
    val nameItToSupport: String
    val willSendWhenOnline: String
    val willSendWhenOnlineAbout: String
    val couldNotSend: String
    val writeWhatHappened: String
}

/** Переключатель окон: аккаунты, настройки и неотправленное. */
interface SwitchingWords {
    val accounts: String
    val notSent: String
    val virtualAccount: String
    val settingsHelpBugs: String
    val notSentSection: String
    fun waiting(howMany: Int): String
    val waitingAbout: String
    val waitForSending: String
    val leaveNow: String
}

/**
 * Имена окон: длинное для переключателя и рейки, короткое для собственной шапки.
 *
 * Два имени одного окна — решение макета (`интерфейс.md §1`), и разводить их по разным
 * местам нельзя: разошедшись, они дают окно, называющееся по-разному в двух местах
 * одного экрана.
 */
interface WindowWords {
    val phone: WindowName
    val social: WindowName
    val media: WindowName
    val activity: WindowName
    val page: WindowName
    /** Пометка у окна, в котором человек сейчас: цвет здесь занят навигацией. */
    fun youAreHere(about: String): String
    fun cameFrom(window: String): String
    fun cameFromTab(window: String, tab: String): String
}

/**
 * Три имени одного окна — вместе, а не тремя списками.
 *
 * Держать их порознь значит однажды получить окно, которое в переключателе называется
 * одним словом, а в собственной шапке другим. Здесь они физически рядом, и разойтись им
 * негде.
 */
data class WindowName(
    /** Длинное: переключатель окон и рейка. */
    val full: String,
    /** Короткое: собственная шапка окна. «Социум» вместо «Социальная лента». */
    val short: String,
    /** Что внутри — строкой под названием. */
    val about: String,
)

/** Список настроек: четыре группы и их пункты. */
interface SettingsListWords {
    val settings: String
    val groupAccount: String
    val groupApplication: String
    val groupBlogger: String
    val groupHelp: String

    val itemProfile: String
    val itemDevices: String
    val itemNotifications: String
    val itemVirtuals: String
    val itemAppearance: String
    /** «Шрифты и размеры» — второй пункт вида (ПЛАН-ШРИФТОВ Ш4, 2026-09-16). */
    val itemText: String
    val itemLanguage: String
    val itemPrivacy: String
    val itemStorage: String
    val itemBlogger: String
    val itemQuestions: String
    val itemProblem: String
    val itemUpdate: String
    val itemAbout: String
}

/**
 * Обновление: событие о версии, вкладка настроек и заслон «нужно обновиться».
 *
 * Здесь три текста красным (`Alarm`) — решение заказчика 2026-09-06. Красным то, что
 * человек обязан сделать сам; серым то, что просто происходит. При переводе это различие
 * важнее слов: серая просьба подтвердить установку не выполняется.
 */
interface UpdateWords {
    // Событие о версии.
    val installed: String
    fun runningVersion(version: String): String
    fun whatChanged(notes: String): String
    val broken: String
    fun brokenText(wanted: String, current: String): String
    val version: String
    val chatsUntouched: String
    val importantOut: String
    fun availableVersion(version: String): String
    val oldMayMisbehave: String

    // Вкладка настроек.
    fun installedVersion(version: String): String
    val streamNotDeclared: String
    val askingServer: String
    val notConfigured: String
    fun download(megabytes: String): String
    val install: String
    val notSelfUpdating: String
    fun alienStream(version: String): String
    val latestInstalled: String
    val checkAgain: String

    // Заслон.
    val mustUpdate: String
    val mustUpdateAbout: String
    val notSelfUpdatingLong: String

    // Скачивание, вопрос, исход.
    fun downloading(percent: Int): String
    val dontCloseApp: String
    fun installVersion(version: String): String
    val installNow: String
    val appWillClose: String
    val confirmSystemAsk: String
    val comeBackAfter: String
    val dataStays: String
    val notNow: String
    val installerStarted: String
    val confirmInSystem: String
    val pressAgain: String
    val notDownloaded: String
    val notDownloadedAbout: String
    val badPackage: String
    val badPackageAbout: String
    val noHash: String
    val noHashAbout: String
    val installNotStarted: String
    val tryAgain: String
    val installerDidNotStart: String
    val cannotAskServer: String
}

/**
 * Память и журнал.
 *
 * `keepFor` — **склонение срока**: «1 месяц», «3 недели», «5 недель». Формы живут здесь
 * же, где слова: у английского их две, и хранить тройку в перечислении значило бы
 * записать русскую грамматику в код.
 */
interface StorageWords {
    val weeks: String
    val months: String
    fun keepFor(count: Int, weeks: Boolean): String
    val mediaAndFiles: String
    val mediaAndFilesAbout: String
    val messages: String
    val messagesAbout: String
    val diary: String
    val diaryAbout: String
    val occupies: String
    val keep: String
    val butNoMore: String
    fun olderThan(term: String): String
    val whicheverFirst: String
    val clearDiaryNow: String
    val clearDiaryAbout: String
    fun megabytes(value: Int): String
    fun kilobytesOccupied(value: String): String
    fun megabytesOccupied(value: String): String
}

/**
 * Группы, каналы и сообщества: списки, состав и доступ к закрытым записям.
 *
 * Роль названа **от лица человека** — «вы владелец», а не «владелец»: строка стоит под
 * названием группы и отвечает на вопрос «кто я здесь», а не «кто такой владелец».
 */
interface SocialWords {
    // Списки.
    val noGroupsYet: String
    val lookingForGroups: String
    val createFirst: String
    val ifListNeverComes: String
    val create: String
    val noCardsYet: String
    val lookingWhatFriendsOpened: String
    val cardsAbout: String
    val askSent: String
    val asking: String
    val askToJoin: String
    val personalGroup: String
    val publicGroup: String
    val youOwner: String
    val youAdmin: String
    val youModerator: String
    val youMember: String

    // Состав.
    val members: String
    val access: String
    val invite: String
    val readingMembers: String
    val nobodyHereYet: String
    val inviteByPhone: String
    /** Позвать по нику: подпись поля и кнопка списка контактов (заказчик 2026-09-18). */
    val nickField: String
    val fromContacts: String
    val noSuchNickname: String
    val alreadyMember: String
    val exclude: String
    fun bannedUntil(until: String): String
    val owner: String
    val admin: String
    val moderator: String
    val member: String
    val roleUnknown: String

    // Доступ к закрытым записям.
    val closedAccess: String
    val nobodyAsksAccess: String
    val loading: String
    val accessOpen: String
    val accessOpenAbout: String
    val askSentTitle: String
    val askSentAbout: String
    val declined: String
    val declinedAbout: String
    val askAgain: String
    val noAccess: String
    val noAccessAbout: String
    val ask: String
    val asksAccess: String
    val openForever: String
    fun openUntil(epoch: String): String
    val declinedShort: String
    val noAccessShort: String
    val forever: String
    val decline: String
    val deciding: String

    // Сроки доступа: три кнопки и «бессрочно» рядом с ними.
    val month: String
    val threeMonths: String

    // Беды социума.
    val badTerm: String
    val adminOpensAccess: String
    val communityDidNotOpen: String
    val subscriptionNotChanged: String
    fun alreadyInAnother(title: String): String
    val ownerLinks: String
    val couldNotLink: String
    val ownerUnlinks: String
    val couldNotUnlink: String
    fun couldNotAsk(reason: String): String
    val groupsMayBeIncomplete: String
    val cardsMayBeIncomplete: String
    val numberAlreadyListed: String
    val membersMayBeStale: String
    val noSuchNumber: String
    val ownerOrAdminChangesMembers: String
}

/**
 * Книга контактов и подокно «Вид».
 *
 * Пустых состояний здесь четыре, и они разные: нет разрешения, нет книги у платформы,
 * поиск ничего не нашёл, книга прочитана и пуста. Одно слово на все четыре — это
 * «ничего нет» вместо ответа, что делать дальше.
 */
interface BookWords {
    /** Подсказка поиска по книге. Короткая: строка поиска однострочная (2026-09-19). */
    val search: String

    /** Подсказка поиска по перепискам: ищется имя в шапке и первая строка последнего. */
    val searchChats: String
    val notRead: String
    val notReadAbout: String
    val addByHand: String
    val noBookHere: String
    val nobodyFound: String
    fun nothingMatches(search: String): String
    val bookEmpty: String
    val bookEmptyAbout: String
    val nameless: String
    val everyone: String
    val commonSection: String
    val phoneSection: String

    // Подокно «Вид».
    val view: String
    /** Пункт «Вид разделов» — вход в подокно с образцом (заказчик 2026-09-18). */
    val sectionsLook: String
    /** Заголовок над образцом в подокнах «Вида»: как будет выглядеть. */
    val lookSample: String
    val subsections: String
    val folders: String
    val foldersAbout: String
    val menu: String
    val menuAbout: String

    // Второй тумблер «Вида»: ярлычки или имена (разделы.md, четыре исполнения).
    val labelsTitle: String
    val labelsIcons: String
    val labelsIconsAbout: String
    val labelsNames: String
    val labelsNamesAbout: String

    // Размер ярлычков в плитке.
    val tileSizeTitle: String
    val tileSmall: String
    val tileNormal: String
    val tileLarge: String

    // Пункт «Разделы» и экран управления набором.
    /** Пояснение к «Общему» в управлении разделами (заказчик 2026-09-19). */
    val commonSectionAbout: String
    val sectionsItem: String
    val sectionsItemAbout: String
    val sectionsScreen: String
    val addSection: String
    val newSectionName: String
    val sectionIcon: String
    val noIcon: String
    val removeSection: String
    val removeSectionAbout: String
    val sectionsEmpty: String
    val sectionsEmptyAbout: String
    fun peopleInSection(count: Int): String

    // Тот же экран управления разделами, но для набора СООБЩЕСТВ: там на полках не люди,
    // а группы и каналы, и называть их «человеками» — врать в самом видном месте
    // (заказчик 2026-09-19: механизм один, слова по сущности).
    val sectionsScreenCommunity: String
    val sectionsEmptyAboutCommunity: String
    val removeSectionAboutCommunity: String
    fun groupsInSection(count: Int): String
    val moveUp: String
    val moveDown: String
    val save: String
    val showPersonAs: String
    val name: String
    val nameAbout: String
    val userName: String
    val userNameAbout: String
    val nickname: String
    val nicknameAbout: String
    val phone: String
    val phoneAbout: String
    val whatToShow: String
    val showOutsiders: String
    val showOutsidersAbout: String
}

/** Страница человека: принесённые записи и обсуждения под ними. */
interface PageWords {
    val commentsOn: String
    val commentsOff: String
    val turnCommentsOff: String
    val turnCommentsOn: String
    val emptyHere: String
    val emptyMine: String
    val emptyTheirs: String
    val loading: String
    val yourEntry: String
    val carriedByYou: String
    val entryUnavailable: String
    val openDiscussion: String
    val closeDiscussion: String
    val remove: String

    // Личная страница человека — заглушка (заказчик 2026-09-19).
    val guestPage: String
    val whatWeKnow: String
    val subscribe: String
    val unsubscribe: String
    /** Почему «Подписаться» не работает: у ленты человека нет читательской подписки. */
    val subscribeNotYet: String
    val alreadyInContacts: String
    val contactMeansFriend: String
    val nothingKnown: String

    // Дружба: добавил ли ОН меня к себе (заказчик 2026-09-19).
    val theyAddedYou: String
    val theyDidNotAddYou: String
    val friendshipUnknown: String
    val subscribeAsks: String

    // Беды страницы.
    val cannotCarry: String
    val entryGone: String
    val couldNotRemove: String
    val ownerSwitchesPage: String
    val pageGone: String
    val ownerOrModeratorCloses: String
}

/**
 * Переписка: шапка, пузыри, полосы сообщений и подокна вокруг неё.
 *
 * `replies` — **склонение живёт здесь**, а не на экране (ПЛАН-ЯЗЫКА Я3). Правило числа у
 * каждого языка своё: у русского три формы, у английского две, у испанского две с другой
 * границей. Экран знает число, словарь — слово.
 */
/**
 * Звонок — окно 0 (макет `doc/doc_UI/21-call.md`).
 *
 * Слов немного и почти все — подписи кнопок. Отдельный словарь, а не раздел переписки:
 * звонок живёт своим окном, и его надписи не должны разъезжаться с чужими при переводе.
 */
interface CallWords {
    val incoming: String
    val outgoing: String
    val calling: String
    val accept: String
    val decline: String
    val cancel: String
    val hangUp: String
    val microphoneOn: String
    val microphoneOff: String
    val cameraOn: String
    val cameraOff: String
    val ended: String
    val callAgain: String
    val close: String
    val connecting: String
    val reconnecting: String

    /** Видео остановила нехватка полосы. Человеку это надо сказать: иначе — поломка. */
    val videoPaused: String

    /** Оценка связи от SFU. Своей не считаем. */
    fun quality(level: String): String

    /** Сколько идёт разговор: `4:32`. Часы появляются только когда они есть. */
    fun duration(seconds: Int): String
}

interface ChatWords {
    // Шапка и лента.
    val access: String
    val members: String
    val someone: String
    fun thread(count: Int): String
    val messageUnavailable: String
    val decrypting: String
    val addToSelf: String
    val narrowTo: String
    val narrow: String
    val messageHint: String
    val nameless: String
    val yourNickname: String

    // Беды переписки.
    val onlyNarrow: String
    val secretIsNarrow: String
    val openAlreadyOut: String
    val strangerNarrowsAdmin: String
    val messageGone: String
    val offlineRetryLater: String
    fun offlineRetryIn(seconds: Int): String
    val notMemberAnyMore: String
    fun tooLong(limit: Int): String
    val notAPhone: String
    val ownNumber: String
    fun badPhone(reason: String): String

    // Книга контактов и новый контакт.
    val addAndWrite: String
    val addToContacts: String
    val foundInTima: String
    val notInTima: String

    // Меню «•••» переписки (ПЛАН-РАЗДЕЛОВ Р5, ПЛАН-ЧАТА Ч2). Заголовок — «Настройка
    // группы» / «Настройка переписки» (заказчик 2026-09-18), а не «Переписка».
    val chatMenu: String
    val groupSettings: String
    val chatSettings: String
    /** «Мой цвет в группе» — выбор оттенка полосы (заказчик 2026-09-19). */
    val myColor: String
    val myColorAbout: String
    val myColorAuto: String
    val myColorTaken: String
    val myColorReset: String
    /** Неотправленное: подокно с причиной (заказчик 2026-09-19). */
    val notSent: String
    /** Ждёт отправки: заголовок подокна и строка про попытки (заказчик 2026-09-19). */
    val waitingTitle: String
    val waitingNoReason: String
    fun waitingAbout(attempts: Int, seconds: Int): String
    val notSentNoReason: String
    val sendAgain: String
    val deleteMessage: String
    val reportProblem: String
    /** Причина отказа словами по коду сервера; незнакомый код — `null`, покажется сам код. */
    fun failReason(code: String): String?
    val moveToSection: String
    val moveToSectionAbout: String
    fun inSection(name: String): String

    /**
     * То же, но с номером, который проверяли.
     *
     * **Без номера сообщение неотличимо от опечатки.** 2026-09-17 на стенде контакт был
     * заведён с лишним нулём — `+799900000101` вместо `+79990000101`, — и экран честно
     * сказал «в TIMa его нет». Сказал правду: такого номера в TIMa действительно нет.
     * Человек прочитал это как «у него не стоит приложение» и искал беду в приложении.
     *
     * Поэтому сообщение обязано называть то, что проверялось: номер на экране и номер,
     * ушедший серверу, — разные строки, и расходятся они молча.
     */
    fun notInTimaChecked(phone: String): String

    // Полоса сообщений о ключах и круге.
    fun tooLarge(bytes: Int, limit: Int): String
    fun keysAsked(devices: Int): String
    val keysNoHelpers: String
    val keysNothingMissing: String
    val keysNeedPhrase: String
    fun narrowWarning(circle: String): String
    fun narrowed(circle: String): String
    val noGroupKey: String
    val noGroupKeyAbout: String
    val askKey: String
    val asking: String
    val phraseWords: String
    val storyUnavailable: String

    // Список переписок и новая переписка.
    val write: String
    val noChatsYet: String
    val writeFirst: String
    val messageUnreadable: String
    val newMessage: String
    val newChat: String
    val whomToWrite: String
    val phoneInTima: String
    val noSuchNumber: String
    val searching: String
    val find: String

    // Новый контакт.
    val newContact: String
    val phoneNumber: String
    val nameYouCall: String
    val optional: String
    val section: String
    val commonSection: String
    val newSection: String
    val title: String
    val sectionExample: String
    val moveLater: String
    val createSection: String

    // Приглашение.
    fun notInTima(phone: String): String
    val sendSms: String
    val sendSmsAbout: String
    val call: String
    val callAbout: String
    val share: String
    val shareAbout: String

    // Профиль.
    val profile: String
    val nameNotSetYet: String
    val nameHowShown: String
    val nameExample: String
    val nicknameFound: String
    val saved: String
    val save: String
    val nicknameNeverFreed: String

    /**
     * Ник задаётся один раз на личность (решение заказчика 2026-09-15). Пока не задан —
     * поле и это пояснение; задан — текст вместо поля и [nicknameLocked].
     */
    val nicknameOnce: String
    val nicknameLocked: String
    val phone: String

    /** Аватар: выбрать картинку, обрезать квадратом, убрать (Д8, 2026-09-15). */
    val avatarChange: String
    val avatarRemove: String
    val avatarCrop: String
    val avatarCropHint: String

    /**
     * Кнопка «Повернуть» на подрезке аватара.
     *
     * Нужна даже после того, как метка поворота камеры стала учитываться: метки не бывает
     * у снимков экрана и у картинок, прошедших через чужой редактор, а фотографию,
     * лежащую боком, человеку иначе не выправить — в приложение её уже внесли.
     */
    val avatarRotate: String

    /** Кнопка «Выбрать раздел»: открывает список тех, что уже заведены. */
    val pickSection: String


    /**
     * Кнопка «Создать раздел „X“» — когда набранного раздела ещё нет.
     *
     * Без неё контакт уходил В НЕСУЩЕСТВУЮЩИЙ раздел и пропадал с экрана: строка в книге
     * была, а показать её было негде — списки строятся по заведённым разделам. Ошибки при
     * этом не было никакой. Найдено заказчиком 2026-09-17.
     */
    fun createSectionNamed(name: String): String

    /** Отказ сохранить, пока раздела нет. */
    fun noSuchSection(name: String): String
    val avatarNotImage: String
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
    // Беды входа.
    fun badPhone(reason: String): String
    val wrongCode: String
    val codeExpired: String
    val timeIsUp: String
    val wrongPhrase: String
    val identityRefused: String
    val codeTermOver: String
    val notYourVirtual: String
    val cancelDidNotReach: String
    val notTransferCode: String
    val needAccountPhrase: String
    val phraseDoesNotFit: String
    val threeTriesBurned: String
    val codeNotValid: String
    val phraseNotMain: String
    val nicknameTaken: String
    val nicknameRules: String
    val nicknameRulesShort: String
    val fiveIsLimit: String
    val virtualHasNoVirtuals: String
    val nicknameFree: String
    val nicknameBusy: String
    val onlyPhoneConfirms: String
    val codeNoLongerValid: String
    val codeReadWrong: String
    val deviceHasNoKey: String
    val tryAgain: String
    val listHasNothing: String
    val lastDevice: String
    val deviceNotDisconnected: String
    val listDidNotCome: String

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

    // Шаг раздела (ПЛАН-РАЗДЕЛОВ Р5): в какой раздел набора сообществ положить созданное.
    val shelf: String
    val shelfAbout: String
    val shelfCommon: String
    val newShelfHint: String
    val createShelf: String

    /**
     * Кнопка «Перейти в группу» после создания.
     *
     * Нужна ровно в одном случае: часть номеров не нашлась, экран остался показывать
     * непозванных — и уйти с него было НЕЧЕМ. Кнопка при этом оставалась «Создать», и
     * нажатие на неё заводило ещё одну группу. Без предела. Найдено заказчиком 2026-09-17.
     */
    val goToCreated: String
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
 * Ключ — перечень вкладок в оболочке, слово — здесь. До Я2 вкладка была строкой и служила и тем, и другим:
 * перевести её было нельзя, потому что перевод сменил бы ключ.
 */
interface TabWords {
    // Окно «Телефон».
    val chats: String
    val contacts: String
    val calls: String
    val view: String

    // Фильтры журнала звонков.
    val all: String
    val fromBook: String
    val unknown: String
    val missed: String

    // Окна 2 и 3.
    val common: String
    val friends: String
    val catalogue: String

    // Окно 3: два способа смотреть одно и то же.
    val feed: String
    val slides: String

    // Окно 4.
    val answers: String
    val reactions: String
    val collections: String

    // Фильтры «Реакций».
    val comments: String
    val marks: String

    // Окно 5.
    val subscribed: String
    val groups: String

    // Подвкладки и контур «Коллекций».
    val media: String
    val messages: String
    val open: String
    val personal: String
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

    /**
     * **Язык, на котором человек пишет, — не язык приложения** (Я12).
     *
     * Списком из наших трёх словарей он не выражается: сервер держит `lang` свободным
     * текстом, и человек вправе писать по-немецки, хотя немецкого интерфейса у нас нет.
     * Поэтому поле, а не список.
     */
    val writingLanguage: String
    val writingLanguageAbout: String
    val writingLanguageHint: String

    /** Какие языки человек читает. Пусто — только его собственный. */
    val readingLanguages: String
    val readingLanguagesAbout: String
    val readingLanguagesHint: String

    val whatToShow: String
    val onlyMyCountry: String
    val onlyMyLanguages: String
    val filterNotForChats: String
    val on: String
    val off: String
    val chosen: String
    val soon: String

    // Беды настроек языка и страны.
    val localeNotRead: String
    val localeNotSaved: String
}

/**
 * Оформление: названия тем и цветовых мест.
 *
 * **Названия лежат здесь, а не в перечислении.** До Я2 `ThemeChoice` и `ColorSlot` носили
 * их в себе — это ровно тот случай, ради которого выбран словарь на Kotlin: перечисление
 * остаётся ключом, а слово приходит из словаря.
 */
interface AppearanceWords {
    val themeLight: String
    val themeDark: String
    val themeCustom: String

    val slotNavigation: ColorSlotWords
    val slotActivity: ColorSlotWords
    val slotConfirmed: ColorSlotWords
    val slotSurface: ColorSlotWords
    val slotFunctional: ColorSlotWords
    val slotText: ColorSlotWords
    val slotText2: ColorSlotWords
    val slotText3: ColorSlotWords
    val slotMy: ColorSlotWords
    val slotAuthor: ColorSlotWords
    val slotBorder: ColorSlotWords
    val slotLine: ColorSlotWords
    val slotOnAccent: ColorSlotWords
    val slotOnAmber: ColorSlotWords
    val slotInPlate: ColorSlotWords
    val slotSoftAccent: ColorSlotWords
    val slotQuiet: ColorSlotWords

    /**
     * Беда с набранным цветом — фраза собирается здесь, из частей.
     *
     * Видом беды (`ColorTrouble`) она была до Я-D; вид остался в `core-ui`, где цвет и
     * разбирается, а словарю достались части фразы. Склеивать их на экране нельзя: на
     * другом языке порядок слов другой.
     */
    val colorEmpty: String
    fun colorNotHex(listed: String): String
    fun colorWrongLength(length: Int): String
    val qrTooLong: String
    val qrTooLongAbout: String

    val theme: String
    /** Тип своей темы и полосы авторов — «Цвета» (заказчик 2026-09-19). */
    val themeKind: String
    val themeKindAbout: String
    val authorStrips: String
    val authorStripsAbout: String
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

    /** Где видна защищаемая пара — словами, которые человек прочтёт в предупреждении. */
    val placePlate: String
    val placeContent: String

    /**
     * Свои сохранённые оформления.
     *
     * Список стоит ПОД готовыми темами: сначала то, что есть у всех, потом собранное
     * самим. Пусто — вместо списка одна строка [savedNone]: пустой заголовок не
     * объясняет ничего, а строка объясняет, откуда список берётся.
     */

    /**
     * Шрифты и размеры — ПЛАН-ШРИФТОВ, решения заказчика 2026-09-15 и 2026-09-16.
     *
     * Размер показывается ЧИСЛОМ точек, а не процентом: в группе несколько кеглей, и
     * число называет главную строку — остальные едут за ней.
     */
    val fontAndSize: String
    val fontChoice: String
    val fontRoboto: String
    val fontOpenSans: String
    val fontSystem: String

    val sizes: String
    val sizeMessages: String
    val sizeTabs: String
    val sizeMenu: String
    val sizeHeaders: String
    val sizeLists: String
    val sizeSample: String
    val sizeSampleAbout: String
    fun points(value: Int): String

    val savedLooks: String
    val savedNone: String
    val saveName: String
    val saveLook: String
    val forgetLook: String

    /** Имя занято: сохранение перезапишет прежнее. Предупреждение, а не отказ. */
    fun saveWillReplace(name: String): String
}

/**
 * Цветовое место: название и пояснение под ним.
 *
 * Вместе по той же причине, что и [WindowName]: пояснение без названия — сирота, и
 * разойтись им негде. У трёх мест пояснение пустое, и это решение, а не пропуск:
 * название говорит само, подпись под ним была бы повтором.
 */
data class ColorSlotWords(val name: String, val about: String)

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
    fun inside(howMany: Int, role: String): String
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
        override val writingLanguage = "Язык, на котором я пишу"
        override val writingLanguageAbout =
            "По нему вас находят в языковых лентах. Это не язык приложения — их можно " +
                "выбрать разными"
        override val writingLanguageHint = "ru, en, de — код языка"
        override val readingLanguages = "Какие языки читать"
        override val readingLanguagesAbout = "Через запятую. Пусто — только ваш язык"
        override val readingLanguagesHint = "ru, en, es"
        override val whatToShow = "Что показывать"
        override val onlyMyCountry = "Только моя страна"
        override val onlyMyLanguages = "Только мои языки"
        override val filterNotForChats = "Переписки и ленты друзей это не касается"
        override val on = "включено"
        override val off = "выключено"
        override val chosen = "выбран"
        override val localeNotRead = "Не удалось прочитать язык и страну"
        override val localeNotSaved = "Не удалось сохранить — попробуйте позже"
        override val soon = "скоро"
    }

    override val appearance = object : AppearanceWords {
        override val themeLight = "Светлая"
        override val themeDark = "Тёмная"
        override val themeCustom = "Пользовательская"

        override val slotNavigation = ColorSlotWords(
            name = "Навигация и действие",
            about = "логотип, текущее окно, «назад», «отправить»",
        )
        override val slotActivity = ColorSlotWords(
            name = "Активность",
            about = "счётчик непрочитанного",
        )
        override val slotConfirmed = ColorSlotWords(
            name = "Подтверждено",
            about = "доставлено, прочитано, метка E2E",
        )
        override val slotSurface = ColorSlotWords(
            name = "Фон содержимого",
            about = "лента и переписка",
        )
        override val slotFunctional = ColorSlotWords(
            name = "Фон панелей",
            about = "шапка, вкладки, строка ввода",
        )
        override val slotText = ColorSlotWords(
            name = "Текст",
            about = "основной",
        )
        override val slotText2 = ColorSlotWords(
            name = "Текст потише",
            about = "подписи, время",
        )
        override val slotText3 = ColorSlotWords(
            name = "Текст ещё тише",
            about = "третий уровень",
        )
        override val slotMy = ColorSlotWords(
            name = "Фон моих сообщений",
            about = "",
        )
        override val slotAuthor = ColorSlotWords(
            name = "Фон чужих сообщений",
            about = "",
        )
        override val slotBorder = ColorSlotWords(
            name = "Рамка сообщения",
            about = "",
        )
        override val slotLine = ColorSlotWords(
            name = "Линия списка",
            about = "между записями",
        )
        override val slotOnAccent = ColorSlotWords(
            name = "Текст на зелёном",
            about = "на кнопках, вкладках, плашке шапки",
        )
        override val slotOnAmber = ColorSlotWords(
            name = "Текст на янтаре",
            about = "на счётчике непрочитанного",
        )
        override val slotInPlate = ColorSlotWords(
            name = "Внутри плашки",
            about = "логотип и кнопки на салатовом",
        )
        override val slotSoftAccent = ColorSlotWords(
            name = "Тихая подложка",
            about = "невыбранная вкладка, поле ввода",
        )
        override val slotQuiet = ColorSlotWords(
            name = "Нейтральная подложка",
            about = "невыбранная подвкладка, капсула переключателя",
        )

        override val colorEmpty = "Пусто. Наберите цвет: шесть знаков или восемь"
        override fun colorNotHex(listed: String) =
            "Не шестнадцатеричные знаки: $listed. Допустимы 0–9 и A–F"
        override fun colorWrongLength(length: Int) =
            "Знаков $length, а нужно 6 (цвет) или 8 (с непрозрачностью)"
        override val qrTooLong = "Код не показать"
        override val qrTooLongAbout = "Он слишком длинный для QR"

        override val theme = "Тема"
        override val themeKind = "Тип своей темы"
        override val themeKindAbout = "от него зависят цвета полос авторов в группах"
        override val authorStrips = "Цветные полосы авторов"
        override val authorStripsAbout = "в группе у каждого своя; выключено — у всех цвет контура"
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
        override val placePlate = "имя окна в шапке и стрелка «назад»"
        override val placeContent = "переключение окон и список настроек"

        override val fontAndSize = "Шрифты и размеры"
        override val fontChoice = "Шрифт"
        override val fontRoboto = "Roboto"
        override val fontOpenSans = "Open Sans"
        override val fontSystem = "Системный"

        override val sizes = "Размер текста"
        override val sizeMessages = "Сообщения"
        override val sizeTabs = "Вкладки окон"
        override val sizeMenu = "Меню и настройки"
        override val sizeHeaders = "Шапки"
        override val sizeLists = "Списки: чаты, контакты, каталоги"
        override val sizeSample = "Как это будет выглядеть"
        override val sizeSampleAbout = "Образец меняется вместе с настройками"
        override fun points(value: Int) = value.toString()

        override val savedLooks = "Сохранённые оформления"
        override val savedNone = "Подберите цвета и сохраните — оформление появится здесь"
        override val saveName = "Название"
        override val saveLook = "Сохранить"
        override val forgetLook = "Убрать"
        override fun saveWillReplace(name: String) = "«" + name + "» будет перезаписано"

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

    override val trouble = object : TroubleWords {
        override val offline = "Нет связи с сервером"
        override fun refused(reason: String) = "Сервер отказал: $reason"
        override val didNotReach = "Не дошло до сервера. Попробуйте ещё раз"
        override fun retryIn(seconds: Int) = "Нет связи с сервером — повторим через $seconds с"
        override val noConnection = "Нет связи"
    }

    override val problem = object : ProblemWords {
        override val whatHappened = "Что случилось"
        override val describeHint = "Опишите словами: что делали и что пошло не так"
        override val onlyYouKnow =
            "Журнал покажет, что происходило, но не то, чего вы ждали, — " +
                "это можете сказать только вы."
        override val whenBegan = "Когда это началось"
        override val today = "Сегодня"
        override val thisWeek = "На этой неделе"
        override val earlier = "Раньше"
        override val whatAbout = "О чём это"
        override val kindMessages = "Не приходят / не уходят сообщения"
        override val kindCalls = "Проблемы с звонком"
        override val kindLooks = "Отображение в приложении"
        override val kindOther = "Другое"
        override val whatGoes = "Что приложится"
        override val whatGoesAbout =
            "Переписка и файлы НЕ отправляются. В журнал попадают действия и ошибки — " +
                "что нажимали и что ответил сервер, — а не содержимое сообщений."
        override val crashesSentThemselves =
            "Отчёты о внезапном закрытии приложение отправляет само, тем же составом."
        override val watch = "Смотреть"
        override val stateNow = "Состояние сейчас"
        override val whatHappenedLog = "Что происходило"
        override val emptyDiary = "Журнал пуст"
        override val reportSent = "Отчёт отправлен"
        override val sending = "Отправляем…"
        override val send = "Отправить"
        override val writeAgainHow =
            "Чтобы написать ещё раз, выйдите и снова откройте «Сообщить о проблеме»."
        override val reportNumber = "Отчёт получен, номер:"
        override val nameItToSupport = "Назовите его, если будете общаться с технической поддержкой."
        override val willSendWhenOnline = "Отправим, когда появится связь"
        override val willSendWhenOnlineAbout =
            "Сети сейчас нет, отчёт сохранён на устройстве и уйдёт сам. Приложение " +
                "можно закрыть."
        override val couldNotSend = "Не удалось отправить — попробуйте ещё раз"
        override val writeWhatHappened = "Напишите, что случилось — без этого отчёт не отправить."
    }

    override val switching = object : SwitchingWords {
        override val accounts = "Аккаунты"
        override val notSent = "не отправлено"
        override val virtualAccount = "Виртуальный аккаунт"
        override val settingsHelpBugs = "Настройки, помощь, баги"
        override val notSentSection = "Не отправлено"
        override fun waiting(howMany: Int) = if (howMany == 1) {
            "Одно сообщение ещё не ушло."
        } else {
            "$howMany сообщений ещё не ушли."
        }
        override val waitingAbout =
            "Пока вы в этом аккаунте, они дойдут. Уйдёте — будут ждать вашего " +
                "возвращения: отправить их от имени другого аккаунта нельзя."
        override val waitForSending = "Подождать отправки"
        override val leaveNow = "Уйти сейчас"
    }

    override val windows = object : WindowWords {

        override val phone = WindowName(
            full = "Телефон",
            short = "Телефон",
            about = "чаты, книга, звонки",
        )
        override val social = WindowName(
            full = "Социальная лента",
            short = "Социум",
            about = "общая, друзья, каталог",
        )
        override val media = WindowName(
            full = "Медиа-лента",
            short = "Медиа",
            about = "лента и слайды",
        )
        override val activity = WindowName(
            full = "Свободное общение",
            short = "Общение",
            about = "истории, ответы, реакции",
        )
        override val page = WindowName(
            full = "Личная страница",
            short = "Страница",
            about = "профиль, коллекции, роли",
        )

        override fun youAreHere(about: String) = "$about · вы здесь"
        override fun cameFrom(window: String) = "Вы пришли из окна «$window»"
        override fun cameFromTab(window: String, tab: String) =
            "Вы пришли из окна «$window», вкладка «$tab»"
    }

    override val settings2 = object : SettingsListWords {
        override val settings = "Настройки"

        override val groupAccount = "Аккаунт"
        override val groupApplication = "Приложение"
        override val groupBlogger = "Блогер"
        override val groupHelp = "Помощь"

        override val itemProfile = "Профиль"
        override val itemDevices = "Секретная фраза и устройства"
        override val itemNotifications = "Уведомления"
        override val itemVirtuals = "Виртуальные аккаунты"
        override val itemAppearance = "Цвета"
        override val itemText = "Шрифты и размеры"
        override val itemLanguage = "Язык"
        override val itemPrivacy = "Приватность и блокировки"
        override val itemStorage = "Память и трафик"
        override val itemBlogger = "Окна блогера"
        override val itemQuestions = "Частые вопросы"
        override val itemProblem = "Сообщить о проблеме"
        override val itemUpdate = "Обновление"
        override val itemAbout = "О приложении"
    }

    override val update = object : UpdateWords {
        override val installed = "Обновление установлено"
        override fun runningVersion(version: String) = "Работает версия $version."
        override fun whatChanged(notes: String) = "Что изменилось: $notes"
        override val broken = "Обновление не завершилось"
        override fun brokenText(wanted: String, current: String) =
            "Вы начали ставить $wanted, но установка не дошла до конца — " +
                "работает прежняя $current."
        override val version = "версия"
        override val chatsUntouched = "Переписка и аккаунт не пострадали: установщик их не трогает."
        override val importantOut = "Вышло важное обновление"
        override fun availableVersion(version: String) = "Доступна $version."
        override val oldMayMisbehave = "Старая версия может работать неправильно."

        override fun installedVersion(version: String) = "Установлена $version"
        override val streamNotDeclared = "поток не объявлен"
        override val askingServer = "Спрашиваем сервер…"
        override val notConfigured = "Сервер обновлений не раздаёт"
        override fun download(megabytes: String) = "Скачать $megabytes МБ"
        override val install = "Обновить"
        override val notSelfUpdating =
            "Эта сборка обновляется не сама: поставьте новую версию обычным способом."
        override fun alienStream(version: String) =
            "Сервер предлагает $version — это другая сборка, не для этой версии"
        override val latestInstalled = "Установлена последняя версия"
        override val checkAgain = "Проверить ещё раз"

        override val mustUpdate = "Нужно обновиться"
        override val mustUpdateAbout =
            "Сервер больше не работает с этой версией приложения. Переписка и аккаунт на " +
                "месте — их ничто не трогает, — но отправлять и получать до обновления не выйдет."
        override val notSelfUpdatingLong =
            "Эта сборка обновляется не сама: поставьте новую версию обычным способом — " +
                "тем же, каким ставили эту."

        override fun downloading(percent: Int) = "Скачиваем $percent%"
        override val dontCloseApp = "Не закрывайте приложение, пока идёт скачивание"
        override fun installVersion(version: String) = "Установить $version"
        override val installNow = "Установить"
        override val appWillClose = "Приложение закроется, и запустится установщик. Это займёт минуту."
        override val confirmSystemAsk = "Если система спросит разрешение на установку — подтвердите."
        override val comeBackAfter =
            "Когда установщик запустится, приложение автоматически закроется — войдите заново."
        override val dataStays =
            "Переписка, аккаунт и настройки останутся: они лежат отдельно от программы, и " +
                "установщик их не трогает. Неотправленное дойдёт после запуска новой версии."
        override val notNow = "Не сейчас"
        override val installerStarted = "Установщик запущен"
        override val confirmInSystem = "Подтвердите установку в окне системы."
        override val pressAgain = "Если окно закрылось или вы отказались — нажмите ещё раз."
        override val notDownloaded = "Обновление не скачалось"
        override val notDownloadedAbout = "Связь оборвалась. Попробуйте ещё раз."
        override val badPackage = "Скачанное не совпало с тем, что объявил сервер"
        override val badPackageAbout =
            "Ставить это нельзя: файл либо не докачался, либо подменён. Попробуйте ещё раз."
        override val noHash = "Сервер не объявил, что именно он раздаёт"
        override val noHashAbout =
            "Без этого проверить скачанное нечем, и мы не ставим. Это чинится на сервере."
        override val installNotStarted = "Установка не началась"
        override val tryAgain = "Попробовать ещё раз"
        override val installerDidNotStart = "установщик не запустился"
        override val cannotAskServer = "Не удалось спросить сервер — проверьте связь"
    }

    override val storage = object : StorageWords {
        override val weeks = "Недели"
        override val months = "Месяцы"
        override fun keepFor(count: Int, weeks: Boolean): String {
            val hundred = count % 100
            val ten = count % 10
            val form = when {
                hundred in 11..14 -> if (weeks) "недель" else "месяцев"
                ten == 1 -> if (weeks) "неделя" else "месяц"
                ten in 2..4 -> if (weeks) "недели" else "месяца"
                else -> if (weeks) "недель" else "месяцев"
            }
            return "$count $form"
        }
        override val mediaAndFiles = "Медиа и другие файлы"
        override val mediaAndFilesAbout =
            "Пока нечего убирать: приложение не сохраняет вложения на устройство — они " +
                "открываются с сервера. Появятся файлы — появится и срок."
        override val messages = "Сообщения"
        override val messagesAbout =
            "Срок для переписки не заводим, пока не решено, что значит «удалить». Стереть " +
                "сообщение на устройстве — не то же, что освободить место: вернуть его можно " +
                "только у собеседника, и то если у него оно ещё есть."
        override val diary = "Журнал"
        override val diaryAbout =
            "Что приложение записывает о своей работе — то, что уходит в отчёт о проблеме. " +
                "Переписки в нём нет."
        override val occupies = "Занимает"
        override val keep = "Держать"
        override val butNoMore = "Но не больше"
        override fun olderThan(term: String) = "Записи старше $term удаляются сами, по дням."
        override val whicheverFirst = "Что наступит раньше. Лишнее убирается с самых старых дней."
        override val clearDiaryNow = "Очистить журнал сейчас"
        override val clearDiaryAbout =
            "Журнал нужен, когда что-то сломалось: очищенный придётся набирать заново, и " +
                "отчёт о проблеме до тех пор будет пустым."
        override fun megabytes(value: Int) = "$value МБ"
        override fun kilobytesOccupied(value: String) = "$value КБ"
        override fun megabytesOccupied(value: String) = "$value МБ"
    }

    override val social = object : SocialWords {
        override val noGroupsYet = "Групп пока нет"
        override val lookingForGroups = "Смотрим, какие есть группы…"
        override val createFirst = "Создайте первую: плюс в правом нижнем углу."
        override val ifListNeverComes =
            "Если список не появится, значит не дошли до сервера — тогда здесь будет сказано."
        override val create = "＋ Создать"
        override val noCardsYet = "Карточек пока нет"
        override val lookingWhatFriendsOpened = "Смотрим, что открыли друзья…"
        override val cardsAbout =
            "Здесь появляются группы, которые люди из вашей книги положили себе на страницу."
        override val askSent = "просьба ушла"
        override val asking = "просим…"
        override val askToJoin = "Попроситься"
        override val personalGroup = "Личная группа"
        override val publicGroup = "Публичная группа"
        override val youOwner = "вы владелец"
        override val youAdmin = "вы админ"
        override val youModerator = "вы модератор"
        override val youMember = "вы участник"

        override val members = "Участники"
        override val access = "Доступ"
        override val invite = "Позвать"
        override val readingMembers = "Читаем состав"
        override val nobodyHereYet = "Здесь пока никого"
        override val inviteByPhone = "Позовите людей по номеру телефона"
        override val nickField = "Ник"
        override val fromContacts = "Из контактов"
        override val noSuchNickname = "Никто не занял этот ник"
        override val alreadyMember = "уже в группе"
        override val exclude = "Исключить"
        override fun bannedUntil(until: String) = "заблокирован до $until"
        override val owner = "владелец"
        override val admin = "админ"
        override val moderator = "модератор"
        override val member = "участник"
        override val roleUnknown = "роль неизвестна"

        override val closedAccess = "Доступ к закрытым записям"
        override val nobodyAsksAccess = "Доступ никому не открыт и никто его не просит"
        override val loading = "Загружаем…"
        override val accessOpen = "Доступ открыт"
        override val accessOpenAbout =
            "Вы видите закрытые записи этой группы. Срок покажет админ в описании."
        override val askSentTitle = "Просьба ушла"
        override val askSentAbout = "Админ ответит — ответ придёт сюда же. Повторно просить не нужно."
        override val declined = "Отказано"
        override val declinedAbout = "Админ не открыл доступ. Попросить можно снова — решение не вечно."
        override val askAgain = "Попросить снова"
        override val noAccess = "Доступа нет"
        override val noAccessAbout =
            "Часть записей вам не показана. Их существование не скрыто — скрыто содержимое."
        override val ask = "Попросить"
        override val asksAccess = "просит доступ"
        override val openForever = "доступ открыт · бессрочно"
        override fun openUntil(epoch: String) = "доступ открыт · до $epoch"
        override val declinedShort = "отказано"
        override val noAccessShort = "доступа нет"
        override val forever = "Бессрочно"
        override val decline = "Отказать"
        override val deciding = "решаем…"

        override val month = "Месяц"
        override val threeMonths = "Три месяца"

        override val badTerm = "Срок пишется как 2026-10 — год и месяц"
        override val adminOpensAccess = "Доступ открывает админ группы"
        override val communityDidNotOpen = "Сообщество не открылось"
        override val subscriptionNotChanged = "Не удалось изменить подписку"
        override fun alreadyInAnother(title: String) = "«$title» уже в другом сообществе"
        override val ownerLinks = "Вносить может владелец сообщества и владелец элемента"
        override val couldNotLink = "Не удалось внести"
        override val ownerUnlinks = "Вынимать может владелец сообщества и владелец элемента"
        override val couldNotUnlink = "Не удалось вынуть"
        override fun couldNotAsk(reason: String) = "Не получилось попроситься: $reason"
        override val groupsMayBeIncomplete =
            "Нет связи с сервером — список групп может быть неполным"
        override val cardsMayBeIncomplete =
            "Нет связи с сервером — карточки друзей могут быть неполными"
        override val numberAlreadyListed = "Этот номер уже в списке"
        override val membersMayBeStale = "Нет связи с сервером — список может быть устаревшим"
        override val noSuchNumber = "Этого номера в TIMA нет — позовите человека в мессенджер"
        override val ownerOrAdminChangesMembers = "Менять состав может владелец или админ"
    }

    override val book = object : BookWords {
        override val everyone = "Все"
        override val commonSection = "Общий"
        override val phoneSection = "Телефон"
        override val search = "Имя, ник или номер"
        override val searchChats = "Имя собеседника или текст"
        override val notRead = "Контакты не прочитаны"
        override val notReadAbout =
            "Приложение возьмёт из телефонной книги имена и номера, чтобы " +
                "показать, кто из них уже в TIMa. Номера уходят на сервер " +
                "закрытыми: он сверяет их, не читая."
        override val addByHand = "Здесь контакты добавляют вручную"
        override val noBookHere = "Телефонной книги у настольной системы нет — добавьте по номеру."
        override val nobodyFound = "Никого не нашлось"
        override fun nothingMatches(search: String) = "По «$search» в контактах совпадений нет"
        override val bookEmpty = "В контактах пока никого"
        override val bookEmptyAbout = "Прочитаем телефонную книгу или добавьте человека по номеру."
        override val nameless = "Без имени"
        override val view = "Вид"
        override val sectionsLook = "Вид разделов"
        override val lookSample = "Как будет выглядеть"
        override val subsections = "Отображение подразделов"
        override val folders = "Папки"
        override val foldersAbout = "разделы полосами, сворачиваются"
        override val menu = "Меню"
        override val menuAbout = "разделы строкой под вкладками"

        override val labelsTitle = "Подписи разделов"
        override val labelsIcons = "Ярлычки"
        override val labelsIconsAbout = "разделы значками"
        override val labelsNames = "Имена"
        override val labelsNamesAbout = "разделы словами"

        override val tileSizeTitle = "Размер ярлычков"
        override val tileSmall = "Мелкие"
        override val tileNormal = "Обычные"
        override val tileLarge = "Крупные"

        override val commonSectionAbout = "сюда попадают все, кому раздел не выбран; убрать нельзя"
        override val sectionsItem = "Разделы"
        override val sectionsItemAbout = "создать, переименовать, переставить, убрать"
        override val sectionsScreen = "Разделы контактов"
        override val addSection = "Добавить раздел"
        override val newSectionName = "Название"
        override val sectionIcon = "Значок"
        override val noIcon = "без значка"
        override val removeSection = "Убрать раздел"
        override val removeSectionAbout = "люди из него вернутся в «Общий»"
        override val sectionsEmpty = "Разделов пока нет"
        override val sectionsEmptyAbout = "Раздел — полка для контактов: «Работа», «Дом», «Учёба». Заведите первый"
        override val sectionsScreenCommunity = "Разделы сообществ"
        override val sectionsEmptyAboutCommunity =
            "Раздел — полка для групп и каналов: «Работа», «Соседи», «Учёба». Заведите первый"
        override val removeSectionAboutCommunity = "группы из него вернутся в «Общий»"
        override fun groupsInSection(count: Int): String {
            val ten = count % 10
            val hundred = count % 100
            return when {
                count == 0 -> "пусто"
                ten == 1 && hundred != 11 -> "$count группа"
                ten in 2..4 && hundred !in 12..14 -> "$count группы"
                else -> "$count групп"
            }
        }
        override fun peopleInSection(count: Int): String {
            val ten = count % 10
            val hundred = count % 100
            return when {
                count == 0 -> "пусто"
                ten in 2..4 && hundred !in 12..14 -> "$count человека"
                else -> "$count человек"
            }
        }
        override val moveUp = "Выше"
        override val moveDown = "Ниже"
        override val save = "Сохранить"
        override val showPersonAs = "Отображать пользователя как"
        override val name = "Имя"
        override val nameAbout = "своё, иначе из телефонной книги"
        override val userName = "Имя пользователя"
        override val userNameAbout = "как он сам себя назвал"
        override val nickname = "Ник"
        override val nicknameAbout = "если человек его задал"
        override val phone = "Телефон"
        override val phoneAbout = "номер из книги"
        override val whatToShow = "Что показывать"
        override val showOutsiders = "Показывать тех, кого нет в TIMa"
        override val showOutsidersAbout = "раздел «Телефон» в конце списка"
    }

    override val page = object : PageWords {
        override val commentsOn = "Записи можно обсуждать"
        override val commentsOff = "Обсуждения выключены"
        override val turnCommentsOff = "Выключить обсуждения"
        override val turnCommentsOn = "Включить"
        override val emptyHere = "Здесь пока пусто"
        override val emptyMine = "Записи, которые вы принесёте к себе, появятся тут"
        override val emptyTheirs = "Этот человек ещё ничего не показывает"
        override val loading = "Загружаем…"
        override val yourEntry = "Ваша запись"
        override val carriedByYou = "принесено вами"
        override val entryUnavailable = "Запись недоступна"
        override val openDiscussion = "Открыть обсуждение"
        override val closeDiscussion = "Закрыть обсуждение"
        override val remove = "Убрать"

        override val cannotCarry = "Эту запись нельзя унести к себе"
        override val entryGone = "Записи больше нет"
        override val couldNotRemove = "Не удалось убрать запись"
        override val ownerSwitchesPage = "Обсуждения выключает владелец страницы"
        override val pageGone = "Страницы больше нет"
        override val guestPage = "Страница"
        override val whatWeKnow = "Что мы о нём знаем"
        override val subscribe = "Подписаться"
        override val unsubscribe = "Отписаться"
        override val subscribeNotYet = "Пока не работает: у ленты человека нет читательской подписки"
        override val alreadyInContacts = "Уже в контактах"
        override val contactMeansFriend = "Контакт — это друг: на друзей подписка встаёт сама"
        override val nothingKnown = "Мы о нём ничего не знаем — ни имени, ни ника, ни номера"
        override val theyAddedYou = "Он добавил вас — его лента «друзьям» вам открыта"
        override val theyDidNotAddYou = "Он вас не добавил — видно только открытое всем"
        override val friendshipUnknown = "Дружбу выясняем…"
        override val subscribeAsks = "Подписка заберёт его историю и попросит добавить вас в контакты"
        override val ownerOrModeratorCloses = "Обсуждение закрывает владелец или модератор"
    }

    override val call = object : CallWords {
        override val incoming = "Входящий вызов"
        override val outgoing = "Вызов"
        override val calling = "Звоним…"
        override val accept = "Принять"
        override val decline = "Отклонить"
        override val cancel = "Отменить"
        override val hangUp = "Завершить"
        override val microphoneOn = "Микрофон включён"
        override val microphoneOff = "Микрофон выключен"
        override val cameraOn = "Камера включена"
        override val cameraOff = "Камера выключена"
        override val ended = "Звонок завершён"
        override val callAgain = "Перезвонить"
        override val close = "Закрыть"
        override val connecting = "Соединяем…"
        override val reconnecting = "Связь пропала, возвращаемся…"
        override val videoPaused = "Видео выключено: не хватает связи. Звук идёт"
        override fun quality(level: String) = when (level) {
            "Excellent" -> "связь отличная"
            "Good" -> "связь хорошая"
            "Poor" -> "связь плохая"
            "Lost" -> "связи нет"
            else -> "связь выясняем"
        }
        override fun duration(seconds: Int): String {
            val s = seconds.coerceAtLeast(0)
            val m = s / 60
            val rest = s % 60
            return if (m < 60) {
                m.toString() + ":" + rest.toString().padStart(2, '0')
            } else {
                (m / 60).toString() + ":" + (m % 60).toString().padStart(2, '0') +
                    ":" + rest.toString().padStart(2, '0')
            }
        }
    }

    override val chat = object : ChatWords {
        override val yourNickname = "Ваш ник"

        override val onlyNarrow = "Круг можно только сузить — расширить нельзя"
        override val secretIsNarrow =
            "Зашифрованное сообщение читают только участники — сужать нечего"
        override val openAlreadyOut =
            "Открытое сообщение уже разошлось — зашифровать его задним числом нельзя"
        override val strangerNarrowsAdmin = "Чужое сообщение сужает админ группы"
        override val messageGone = "Сообщения больше нет в группе"
        override val offlineRetryLater = "Нет связи с сервером — повторите позже"
        override fun offlineRetryIn(seconds: Int) =
            "Нет связи с сервером — повторите через $seconds с"
        override val notMemberAnyMore = "Вы больше не участник этой группы"
        override fun tooLong(limit: Int) = "Слишком длинно: до $limit знаков"
        override val notAPhone = "Из этого номера не выходит телефона"
        override val ownNumber = "Это ваш собственный номер"
        override fun badPhone(reason: String) = "Номер не тот: $reason"

        override val addAndWrite = "Добавить и написать"
        override val addToContacts = "Добавить в контакты"
        override val foundInTima = "Найден в TIMa — подписка на его ленту оформится сама"
        override val notInTima = "В TIMa его нет. Контакт сохранится — позвонить можно телефоном"
        override val chatMenu = "Переписка"
        override val groupSettings = "Настройка группы"
        override val chatSettings = "Настройка переписки"
        override val myColor = "Мой цвет в группе"
        override val myColorAbout = "полоса у моих реплик — как её видят остальные"
        override val myColorAuto = "автоматический"
        override val myColorTaken = "занят"
        override val myColorReset = "Сбросить — автоматический"
        override val notSent = "Не отправлено"
        override val waitingTitle = "Ждёт отправки"
        override val waitingNoReason = "Ждёт очереди — попыток ещё не было"
        override fun waitingAbout(attempts: Int, seconds: Int): String {
            val tries = if (attempts == 0) "попыток ещё не было" else "попыток: $attempts"
            val next = when {
                seconds <= 0 -> "следующая — при ближайшем проходе"
                seconds < 60 -> "следующая через $seconds с"
                else -> "следующая через ${seconds / 60} мин"
            }
            return "$tries · $next"
        }
        override val notSentNoReason = "Причина не сохранилась — сообщение старше этой сборки"
        override val sendAgain = "Отправить ещё раз"
        override val deleteMessage = "Удалить"
        override val reportProblem = "Сообщить о проблеме"
        override fun failReason(code: String): String? = when (code) {
            "level_in_private" -> "в личной группе такого круга нет — только «Зашифровано» и «Всем и всегда»"
            "secret_in_public" -> "публичная группа не шифруется — выберите открытый круг"
            "banned" -> "вы заблокированы в этой группе"
            "payload_too_large" -> "сообщение слишком большое"
            "bad_level" -> "круг вне допустимых"
            "unknown_gk_version" -> "сервер не знает нашей версии ключа группы"
            "no_gk_version" -> "у зашифрованного сообщения нет версии ключа"
            "not_member" -> "вы не участник этой группы"
            else -> null
        }
        override val moveToSection = "Перенести в раздел"
        override val moveToSectionAbout = "куда положить эту группу"
        override fun inSection(name: String) = "сейчас — «$name»"
        override fun notInTimaChecked(phone: String) =
            "В TIMa нет номера $phone. Контакт сохранится — позвонить можно телефоном. " +
                "Если человек в TIMa есть, проверьте номер"

        override val access = "Доступность"
        override val members = "Участники"
        override val someone = "Участник"
        override fun thread(count: Int): String {
            val tens = count % 100
            val word = if (tens in 11..14) {
                "ответов"
            } else {
                when (count % 10) {
                    1 -> "ответ"
                    2, 3, 4 -> "ответа"
                    else -> "ответов"
                }
            }
            return "ветка · $count $word"
        }
        override val messageUnavailable = "сообщение недоступно"
        override val decrypting = "расшифровывается…"
        override val addToSelf = "Добавить себе"
        override val narrowTo = "сузить до"
        override val narrow = "Сузить"
        override val messageHint = "Сообщение"
        override val nameless = "Без имени"

        override fun tooLarge(bytes: Int, limit: Int) =
            "Слишком большое: $bytes байт при пределе $limit"
        override fun keysAsked(devices: Int) =
            "Ключ запрошен у $devices устройств — история появится, когда кто-то ответит"
        override val keysNoHelpers =
            "Этих ключей нет ни у кого из участников — история до вашего прихода утрачена"
        override val keysNothingMissing =
            "Все ключи уже у вас: сообщение не читается по другой причине"
        override val keysNeedPhrase =
            "Нужна секретная фраза: ею аккаунт защищён от угона номера. " +
                "Не знаете её здесь — напишите в группу с другого своего устройства: " +
                "ключ сменится, и новые сообщения откроются. Прежние — только по фразе"
        override fun narrowWarning(circle: String) =
            "Сузить до «$circle»? У тех, кто уже унёс сообщение к себе, оно останется"
        override fun narrowed(circle: String) = "Круг сужен: теперь «$circle»"
        override val noGroupKey = "Ключа этой группы на устройстве нет — поэтому она пуста"
        override val noGroupKeyAbout =
            "Сообщения есть, но открыть их нечем. Попросите ключ у участников или напишите " +
                "в группу с другого своего устройства: ключ сменится, и группа откроется вперёд."
        override val askKey = "Запросить ключ"
        override val asking = "Просим…"
        override val phraseWords = "Двенадцать слов через пробел"
        override val storyUnavailable = "Часть истории недоступна: она была до вашего прихода"

        override val write = "Написать"
        override val noChatsYet = "Переписок пока нет"
        override val writeFirst = "Напишите первому собеседнику"
        override val messageUnreadable = "сообщение не читается"
        override val newMessage = "новое сообщение"
        override val newChat = "Новая переписка"
        override val whomToWrite = "Кому написать"
        override val phoneInTima = "Номер телефона в TIMA"
        override val noSuchNumber = "Этого номера в TIMA нет — позовите человека"
        override val searching = "Ищем…"
        override val find = "Найти"

        override val newContact = "Новый контакт"
        override val phoneNumber = "Номер телефона"
        override val nameYouCall = "Имя — как будете звать его вы"
        override val optional = "необязательно"
        override val section = "Раздел"
        override val commonSection = "Общий"
        override val newSection = "Новый раздел"
        override val title = "Название"
        override val sectionExample = "Дача"
        override val moveLater = "Людей переложите в него потом — из строки контакта."
        override val createSection = "Создать раздел"

        override fun notInTima(phone: String) = "$phone · нет в TIMa"
        override val sendSms = "Отправить СМС"
        override val sendSmsAbout = "откроется приложение сообщений с готовым текстом"
        override val call = "Позвонить"
        override val callAbout = "обычный звонок телефоном"
        override val share = "Поделиться"
        override val shareAbout = "ссылка в любое приложение на телефоне"

        override val profile = "Профиль"
        override val nameNotSetYet = "Пока имя не задано, собеседники видят ваш номер."
        override val nameHowShown = "Имя — как вас показывать другим"
        override val nameExample = "Пётр Смирнов"
        override val nicknameFound = "Ник — по нему вас найдут"
        override val saved = "Сохранено"
        override val save = "Сохранить"
        override val nicknameNeverFreed =
            "Занятый ник не освобождается: сменив его, вы не отдадите прежний."
        override val nicknameOnce =
            "Ник задаётся один раз. Сменить его можно будет только с новой секретной фразой."
        override val nicknameLocked =
            "Ник задан и закреплён за этой фразой. Начнёте заново с другой — сможете сменить его или оставить."
        override val phone = "Телефон"
        override val avatarChange = "Сменить фото"
        override val avatarRemove = "Убрать фото"
        override val avatarCrop = "Обрезать"
        override val avatarCropHint = "Двигайте и растягивайте: в квадрат попадёт то, что видно"
        override val avatarRotate = "Повернуть"
        override val pickSection = "Выбрать раздел"
        override fun createSectionNamed(name: String) = "Создать раздел «$name»"
        override fun noSuchSection(name: String) =
            "Раздела «$name» нет. Создайте его или выберите из списка — иначе контакт " +
                "попадёт туда, где его не видно"
        override val avatarNotImage = "Это не картинка или файл повреждён"
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

        override fun badPhone(reason: String) = "Номер не тот: $reason"
        override val wrongCode = "Код неверен или просрочен"
        override val codeExpired = "Код просрочен — запросите новый"
        override val timeIsUp = "Время истекло — запросите код заново"
        override val wrongPhrase = "Фраза не та — проверьте запись"
        override val identityRefused = "Сервер отказал в смене личности"
        override val codeTermOver = "Срок кода вышел — попросите новый"
        override val notYourVirtual = "Это не ваш виртуальный аккаунт"
        override val cancelDidNotReach =
            "Отмена не дошла до сервера. Код ещё действует — попробуйте ещё раз"
        override val notTransferCode = "Это не код передачи — проверьте, что вставили целиком"
        override val needAccountPhrase =
            "Нужна фраза передаваемого аккаунта — её даёт тот, кто передаёт"
        override val phraseDoesNotFit =
            "Фраза не подходит. Осталось меньше попыток — после третьей код придётся выдать заново"
        override val threeTriesBurned = "Три неверные попытки — код сгорел. Попросите новый"
        override val codeNotValid = "Код не действует: он погашен, отменён или ему больше получаса"
        override val phraseNotMain =
            "Фраза не подошла. Это фраза вашего основного аккаунта — двенадцать слов через пробел"
        override val nicknameTaken = "Этот ник уже занят — придумайте другой"
        override val nicknameRules = "Ник — от 10 до 20 знаков: латиница, цифры, подчёркивание"
        override val nicknameRulesShort = "10…20 знаков: латиница, цифры, подчёркивание"
        override val fiveIsLimit = "Больше пяти виртуальных аккаунтов на номер нельзя"
        override val virtualHasNoVirtuals = "Виртуальный аккаунт не заводит виртуальных"
        override val nicknameFree = "Свободен"
        override val nicknameBusy = "Занят"
        override val onlyPhoneConfirms =
            "Подтвердить подключение может только телефон — на компьютере это не работает"
        override val codeNoLongerValid = "Код больше не действует — попросите на том устройстве новый"
        override val codeReadWrong = "Код прочитан неверно — отсканируйте заново"
        override val deviceHasNoKey = "Это устройство не может подтверждать: у него нет своего ключа"
        override val tryAgain = "Нет связи — попробуйте ещё раз"
        override val listHasNothing = "Нет связи — список показать не из чего"
        override val lastDevice = "Это единственное устройство аккаунта — отключить его нельзя"
        override val deviceNotDisconnected = "Нет связи — устройство не отключено"
        override val listDidNotCome = "Список не дошёл — нет связи с сервером"

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
        override val goToCreated = "Перейти в группу"
        override val shelf = "Раздел"
        override val shelfAbout = "куда положить: перенести можно потом через «•••»"
        override val shelfCommon = "Общий"
        override val newShelfHint = "новый раздел"
        override val createShelf = "Создать"
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
        override val chats = "Чаты"
        override val contacts = "Контакты"
        override val calls = "Звонки"
        override val view = "Вид"

        override val all = "Все"
        override val fromBook = "Контактов"
        override val unknown = "Неизвестные"
        override val missed = "Пропущенные"

        override val common = "Общая"
        override val friends = "Друзья"
        override val catalogue = "Каталог"

        override val feed = "Лента"
        override val slides = "Слайды"

        override val answers = "Ответы"
        override val reactions = "Реакции"
        override val collections = "Коллекции"

        override val comments = "Комментарии"
        override val marks = "Оценки"

        override val subscribed = "Подписан"
        override val groups = "Группы"

        override val media = "Медиа"
        override val messages = "Сообщения"
        override val open = "Открытое"
        override val personal = "Личное"
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
        override fun inside(howMany: Int, role: String) = "$howMany внутри · $role"
    }
}

/**
 * Языки приложения.
 *
 * Три: русский, английский, испанский — решение заказчика 2026-09-08. Английский и
 * испанский заводятся вместе со своими словарями (Я9, Я10); до тех пор список знает о них,
 * но выбрать можно лишь то, у чего словарь есть — [available].
 */
enum class Language(
    val tag: String,
    val ownName: String,
    /**
     * Способ получить словарь, а не сам словарь.
     *
     * Перечисление создаётся целиком при первом обращении, и держи оно словари
     * значением — запуск собирал бы каждый, включая те два, которых человек не увидит
     * (замер 2026-09-08: 10 мс на словарь, холодная JVM). Лямбда собирает только
     * выбранный.
     */
    private val dictionary: (() -> Words)?,
) {
    Russian("ru", "Русский", { RussianWords }),
    English("en", "English", { EnglishWords }),
    Spanish("es", "Español", { SpanishWords });

    /** Словарь языка. `null` — словаря ещё нет. */
    val words: Words? get() = dictionary?.invoke()

    /** Есть ли словарь. Выбирать язык без словаря — обещать надписи, которых нет. */
    val available: Boolean get() = dictionary != null

    companion object {
        /** По тегу из настроек. Незнакомый тег — русский: приложение обязано открыться. */
        fun of(tag: String): Language = entries.firstOrNull { it.tag == tag } ?: Russian
    }
}

/**
 * Текущий словарь для тех, кто **не рисует** (ПЛАН-ЯЗЫКА, Я2-беды).
 *
 * Store — обычный класс, не `@Composable`, и `LocalWords` из `core-ui` ему недоступен —
 * да и Compose здесь нет вовсе (Я-D). Он получает
 * словарь **ссылкой** — `words: () -> Words`, — и умолчанием этой ссылки служит вот это
 * поле: лямбда читает его в момент беды, а не при создании store, поэтому язык всегда
 * текущий.
 *
 * **Пишет сюда один [io.tima.shared.Root]** — там же, где выбранный язык уходит в
 * `TimaTheme`, и из того же значения. Второго писателя быть не должно: `TimaTheme` зовут
 * и с готовым набором цветов ради предпросмотра оформления, и запись оттуда сбрасывала бы
 * язык на русский.
 *
 * Глобальное состояние здесь законно: язык приложения один на процесс, и другого у него
 * не бывает.
 */
object CurrentWords {
    @Volatile
    var value: Words = RussianWords
}
