package io.tima.core.ui

/**
 * Английский словарь (ПЛАН-ЯЗЫКА Я9).
 *
 * ── ЧТО ЗДЕСЬ ПЕРЕВЕДЕНО, А ЧТО НАПИСАНО ЗАНОВО ─────────────────────────────
 *
 * Дословный перевод русских фраз дал бы английский, на котором никто не говорит. Поэтому
 * длинные объяснения — а их тут половина — **написаны заново по смыслу**: та же мысль,
 * тот же порядок «что случилось → чем это грозит → что делать», но своими словами языка.
 *
 * Где смысл держится на слове, а не на фразе, слово выбрано и закреплено:
 *
 * | Русское | Английское | Почему не иначе |
 * |---|---|---|
 * | секретная фраза | recovery phrase | «secret phrase» звучит как пароль, а это ключ восстановления |
 * | круг (сообщения) | audience | «circle» занято соцсетями и значит другое |
 * | сузить круг | narrow the audience | действие, а не свойство |
 * | беда | trouble/problem по месту | у русского одно слово на оба случая |
 * | журнал | log | «diary» здесь неверно: это техническая запись |
 * | лента | feed | |
 * | сообщество | community | |
 * | внести/вынуть (в сообщество) | link/unlink | «add/remove» смешалось бы с участниками |
 *
 * ── ФОРМЫ ЧИСЛА ─────────────────────────────────────────────────────────────
 *
 * У английского их две против русских трёх, и это единственная причина, по которой
 * [keepFor], [thread] и [waiting] написаны здесь заново, а не унаследованы. Правило языка
 * живёт вместе со словами языка — так и задумано (ПЛАН-ЯЗЫКА Я3).
 *
 * ── ЧЕГО ЗДЕСЬ НЕТ ──────────────────────────────────────────────────────────
 *
 * Тела отчёта о проблеме и журнала: они остаются русскими всегда — их читает тот, кто
 * чинит (ПЛАН-ЯЗЫКА §4).
 */
object EnglishWords : Words {
    override val tag = "en"
    override val ownName = "English"

    override val common = object : CommonWords {
        override val back = "Back"
        override val cancel = "Cancel"
        override val ready = "Done"
        override val send = "Send"
        override val hide = "Hide"
        override val noConnection = "No connection to the server"
        override val nothingChosen = "Nothing selected"
    }

    override val settings = object : SettingsWords {
        override val settings = "Settings"
        override val language = "Language"
        override val languageAbout = "Language of the app. Messages are not translated"
        override val appLanguage = "App language"
        override val country = "Country"
        override val countryAbout =
            "The server uses it to pick what you see: your own, not the whole world. " +
                "Empty means show everything"
        override val countryHint = "GB"
        override val whatToShow = "What to show"
        override val onlyMyCountry = "Only my country"
        override val onlyMyLanguages = "Only my languages"
        override val filterNotForChats = "This does not touch chats or friends' feeds"
        override val on = "on"
        override val off = "off"
        override val chosen = "selected"
        override val localeNotRead = "Could not read the language and country"
        override val localeNotSaved = "Could not save — try again later"
        override val soon = "soon"
    }

    override val appearance = object : AppearanceWords {
        override fun theme(choice: ThemeChoice) = when (choice) {
            ThemeChoice.Light -> "Light"
            ThemeChoice.Dark -> "Dark"
            ThemeChoice.Custom -> "Custom"
        }

        override fun slot(slot: ColorSlot) = when (slot) {
            ColorSlot.NAVIGATION -> "Navigation and action"
            ColorSlot.ACTIVITY -> "Activity"
            ColorSlot.CONFIRMED -> "Confirmed"
            ColorSlot.SURFACE -> "Content background"
            ColorSlot.FUNCTIONAL -> "Panel background"
            ColorSlot.TEXT -> "Text"
            ColorSlot.TEXT_2 -> "Quieter text"
            ColorSlot.TEXT_3 -> "Quietest text"
            ColorSlot.MY -> "My messages"
            ColorSlot.AUTHOR -> "Other people's messages"
            ColorSlot.BORDER -> "Message border"
            ColorSlot.LINE -> "List divider"
            ColorSlot.ON_ACCENT -> "Text on green"
            ColorSlot.ON_AMBER -> "Text on amber"
            ColorSlot.IN_PLATE -> "Inside the header plate"
            ColorSlot.SOFT_ACCENT -> "Soft backing"
            ColorSlot.QUIET -> "Neutral backing"
        }

        override fun about(slot: ColorSlot) = when (slot) {
            ColorSlot.NAVIGATION -> "logo, current window, «back», «send»"
            ColorSlot.ACTIVITY -> "unread counter"
            ColorSlot.CONFIRMED -> "delivered, read, the E2E mark"
            ColorSlot.SURFACE -> "feed and chat"
            ColorSlot.FUNCTIONAL -> "header, tabs, input row"
            ColorSlot.TEXT -> "primary"
            ColorSlot.TEXT_2 -> "captions, time"
            ColorSlot.TEXT_3 -> "third level"
            ColorSlot.LINE -> "between entries"
            ColorSlot.ON_ACCENT -> "on buttons, tabs, the header plate"
            ColorSlot.ON_AMBER -> "on the unread counter"
            ColorSlot.IN_PLATE -> "logo and buttons on light green"
            ColorSlot.SOFT_ACCENT -> "unselected tab, input field"
            ColorSlot.QUIET -> "unselected sub-tab, switch capsule"
            ColorSlot.MY, ColorSlot.AUTHOR, ColorSlot.BORDER -> ""
        }

        override fun colorTrouble(trouble: ColorTrouble) = when (trouble) {
            ColorTrouble.Empty -> "Empty. Type a colour: six characters or eight"
            is ColorTrouble.NotHex ->
                "Not hexadecimal: ${trouble.listed}. Allowed are 0–9 and A–F"
            is ColorTrouble.WrongLength ->
                "${trouble.length} characters, but 6 (colour) or 8 (with opacity) are needed"
        }

        override val qrTooLong = "Cannot show the code"
        override val qrTooLongAbout = "It is too long for a QR code"
        override val theme = "Theme"
        override val colors = "Colours"
        override val palette = "Palette"
        override val projectColors = "Project colours"
        override val customOnly =
            "Your own colours are shown when «Custom» is selected. " +
                "Edits to them are kept even if you switch back to light or dark."
        override val alphaHint =
            "The first two characters are opacity: FF is opaque, 00 is invisible"
        override val apply = "Apply"
        override val takeFromLight = "Take from light"
        override val backToLight = "Restore light"
        override val backToDark = "Restore dark"
        override val merged = "There is no way out of here"

        override fun place(pair: VitalPair) = when (pair) {
            VitalPair.PLATE -> "the window name in the header and the «back» arrow"
            VitalPair.CONTENT -> "window switching and the settings list"
        }

        override fun mergedAbout(front: String, back: String, ratio: String, where: String) =
            "«$front» and «$back» have merged: $ratio : 1. They draw $where — " +
                "without them you could not get back to appearance, so «back» waits."
    }

    override val comments = object : CommentWords {
        override val comments = "Comments"
        override val thread = "Thread"
        override val hint = "Write a comment…"
        override val reply = "Reply"
        override val closed = "Discussion is closed"
        override val closedButOldStay = "Discussion is closed. What was written before stays"
        override val nobodyWroteYet = "Nobody has written here yet"
        override val postGone = "The entry is gone"
        override val postGoneAbout = "The conversation went with it"
        override val loading = "Loading the conversation…"
    }

    override val trouble = object : TroubleWords {
        override val offline = "No connection to the server"
        override fun refused(reason: String) = "The server refused: $reason"
        override val didNotReach = "It did not reach the server. Try again"
        override fun retryIn(seconds: Int) =
            "No connection to the server — retrying in $seconds s"
        override val noConnection = "No connection"
    }

    override val problem = object : ProblemWords {
        override val whatHappened = "What happened"
        override val describeHint = "Describe it: what you did and what went wrong"
        override val onlyYouKnow =
            "The log shows what happened, but not what you expected — " +
                "only you can tell us that."
        override val whenBegan = "When it started"
        override val today = "Today"
        override val thisWeek = "This week"
        override val earlier = "Earlier"
        override val whatAbout = "What it is about"
        override val kindMessages = "Messages do not arrive / do not send"
        override val kindCalls = "Trouble with a call"
        override val kindLooks = "How the app looks"
        override val kindOther = "Something else"
        override val whatGoes = "What will be attached"
        override val whatGoesAbout =
            "Your chats and files are NOT sent. The log holds actions and errors — " +
                "what you tapped and how the server answered — not the content of messages."
        override val crashesSentThemselves =
            "Reports about a sudden shutdown are sent by the app itself, with the same content."
        override val watch = "Show"
        override val stateNow = "State right now"
        override val whatHappenedLog = "What was happening"
        override val emptyDiary = "The log is empty"
        override val reportSent = "Report sent"
        override val sending = "Sending…"
        override val send = "Send"
        override val writeAgainHow =
            "To write again, leave and open «Report a problem» once more."
        override val reportNumber = "Report received, number:"
        override val nameItToSupport = "Give this number if you talk to technical support."
        override val willSendWhenOnline = "We will send it when you are back online"
        override val willSendWhenOnlineAbout =
            "There is no network right now. The report is saved on the device and will go " +
                "on its own. You can close the app."
        override val couldNotSend = "Could not send — try again"
        override val writeWhatHappened = "Tell us what happened — the report will not go without it."
    }

    override val switching = object : SwitchingWords {
        override val accounts = "Accounts"
        override val notSent = "not sent"
        override val virtualAccount = "Virtual account"
        override val settingsHelpBugs = "Settings, help, bugs"
        override val notSentSection = "Not sent"

        override fun waiting(howMany: Int) = if (howMany == 1) {
            "One message has not been sent yet."
        } else {
            "$howMany messages have not been sent yet."
        }

        override val waitingAbout =
            "While you stay in this account they will go through. Leave, and they will wait " +
                "for you to come back: they cannot be sent from another account."
        override val waitForSending = "Wait for sending"
        override val leaveNow = "Leave now"
    }

    override val windows = object : WindowWords {
        override fun full(window: Window) = when (window) {
            Window.Phone -> "Phone"
            Window.Social -> "Social feed"
            Window.Media -> "Media feed"
            Window.Activity -> "Open conversation"
            Window.Page -> "Personal page"
        }

        override fun short(window: Window) = when (window) {
            Window.Phone -> "Phone"
            Window.Social -> "Social"
            Window.Media -> "Media"
            Window.Activity -> "Talk"
            Window.Page -> "Page"
        }

        override fun about(window: Window) = when (window) {
            Window.Phone -> "chats, contacts, calls"
            Window.Social -> "common, friends, catalogue"
            Window.Media -> "feed and slides"
            Window.Activity -> "stories, answers, reactions"
            Window.Page -> "profile, collections, roles"
        }

        override fun youAreHere(about: String) = "$about · you are here"
        override fun cameFrom(window: String) = "You came from the «$window» window"
        override fun cameFromTab(window: String, tab: String) =
            "You came from the «$window» window, «$tab» tab"
    }

    override val settings2 = object : SettingsListWords {
        override val settings = "Settings"

        override fun group(group: SettingsGroup) = when (group) {
            SettingsGroup.ACCOUNT -> "Account"
            SettingsGroup.APPLICATION -> "Application"
            SettingsGroup.BLOGGER -> "Blogger"
            SettingsGroup.HELP -> "Help"
        }

        override fun item(item: SettingsItem) = when (item) {
            SettingsItem.PROFILE -> "Profile"
            SettingsItem.DEVICES -> "Recovery phrase and devices"
            SettingsItem.NOTIFICATIONS -> "Notifications"
            SettingsItem.VIRTUALS -> "Virtual accounts"
            SettingsItem.APPEARANCE -> "Appearance"
            SettingsItem.LANGUAGE -> "Language"
            SettingsItem.PRIVACY -> "Privacy and blocking"
            SettingsItem.STORAGE -> "Storage and traffic"
            SettingsItem.BLOGGER -> "Blogger windows"
            SettingsItem.QUESTIONS -> "Frequent questions"
            SettingsItem.PROBLEM -> "Report a problem"
            SettingsItem.UPDATE -> "Update"
            SettingsItem.ABOUT -> "About the app"
        }
    }

    override val update = object : UpdateWords {
        override val installed = "Update installed"
        override fun runningVersion(version: String) = "Version $version is running."
        override fun whatChanged(notes: String) = "What changed: $notes"
        override val broken = "The update did not finish"
        override fun brokenText(wanted: String, current: String) =
            "You started installing $wanted, but it did not go through — " +
                "the previous $current is running."
        override val version = "version"
        override val chatsUntouched =
            "Your chats and account are unharmed: the installer does not touch them."
        override val importantOut = "An important update is out"
        override fun availableVersion(version: String) = "$version is available."
        override val oldMayMisbehave = "The old version may work incorrectly."

        override fun installedVersion(version: String) = "$version is installed"
        override val streamNotDeclared = "stream not declared"
        override val askingServer = "Asking the server…"
        override val notConfigured = "The server does not serve updates"
        override fun download(megabytes: String) = "Download $megabytes MB"
        override val install = "Update"
        override val notSelfUpdating =
            "This build does not update itself: install the new version the usual way."
        override fun alienStream(version: String) =
            "The server offers $version — that is a different build, not for this version"
        override val latestInstalled = "The latest version is installed"
        override val checkAgain = "Check again"

        override val mustUpdate = "You need to update"
        override val mustUpdateAbout =
            "The server no longer works with this version of the app. Your chats and account " +
                "are in place — nothing touches them — but sending and receiving will not " +
                "work until you update."
        override val notSelfUpdatingLong =
            "This build does not update itself: install the new version the usual way — " +
                "the same way you installed this one."

        override fun downloading(percent: Int) = "Downloading $percent%"
        override val dontCloseApp = "Do not close the app while it is downloading"
        override fun installVersion(version: String) = "Install $version"
        override val installNow = "Install"
        override val appWillClose = "The app will close and the installer will start. It takes a minute."
        override val confirmSystemAsk = "If the system asks permission to install — allow it."
        override val comeBackAfter =
            "When the installer starts, the app closes by itself — come back in afterwards."
        override val dataStays =
            "Your chats, account and settings stay: they live apart from the program, and " +
                "the installer does not touch them. Anything unsent goes after the new " +
                "version starts."
        override val notNow = "Not now"
        override val installerStarted = "The installer has started"
        override val confirmInSystem = "Confirm the installation in the system window."
        override val pressAgain = "If the window closed or you declined — tap again."
        override val notDownloaded = "The update did not download"
        override val notDownloadedAbout = "The connection dropped. Try again."
        override val badPackage = "What was downloaded does not match what the server declared"
        override val badPackageAbout =
            "This must not be installed: the file either did not finish downloading or was " +
                "replaced. Try again."
        override val noHash = "The server did not declare what it is serving"
        override val noHashAbout =
            "Without that there is nothing to check the download against, so we do not " +
                "install it. This is fixed on the server."
        override val installNotStarted = "The installation did not start"
        override val tryAgain = "Try again"
        override val installerDidNotStart = "the installer did not start"
        override val cannotAskServer = "Could not ask the server — check your connection"
    }

    override val storage = object : StorageWords {
        override val weeks = "Weeks"
        override val months = "Months"

        override fun keepFor(count: Int, weeks: Boolean): String {
            val unit = if (weeks) "week" else "month"
            return if (count == 1) "$count $unit" else "$count ${unit}s"
        }

        override val mediaAndFiles = "Media and other files"
        override val mediaAndFilesAbout =
            "Nothing to clear yet: the app does not keep attachments on the device — they " +
                "open from the server. Once there are files, there will be a term as well."
        override val messages = "Messages"
        override val messagesAbout =
            "We are not setting a term for chats until it is decided what «delete» means. " +
                "Erasing a message on the device is not the same as freeing space: you can " +
                "only get it back from the other person, and only while they still have it."
        override val diary = "Log"
        override val diaryAbout =
            "What the app records about its own work — the part that goes into a problem " +
                "report. There are no messages in it."
        override val occupies = "Occupies"
        override val keep = "Keep for"
        override val butNoMore = "But no more than"
        override fun olderThan(term: String) =
            "Entries older than $term are removed automatically, day by day."
        override val whicheverFirst = "Whichever comes first. The excess goes from the oldest days."
        override val clearDiaryNow = "Clear the log now"
        override val clearDiaryAbout =
            "The log matters when something breaks: cleared, it has to build up again, and " +
                "until then a problem report will be empty."
        override fun megabytes(value: Int) = "$value MB"
        override fun kilobytesOccupied(value: String) = "$value KB"
        override fun megabytesOccupied(value: String) = "$value MB"
    }

    override val social = object : SocialWords {
        override val noGroupsYet = "No groups yet"
        override val lookingForGroups = "Looking for your groups…"
        override val createFirst = "Create the first one: the plus in the bottom right corner."
        override val ifListNeverComes =
            "If the list never appears, we did not reach the server — and it will say so here."
        override val create = "＋ Create"
        override val noCardsYet = "No cards yet"
        override val lookingWhatFriendsOpened = "Looking at what your friends opened…"
        override val cardsAbout =
            "Here appear the groups that people from your contacts put on their page."
        override val askSent = "request sent"
        override val asking = "asking…"
        override val askToJoin = "Ask to join"
        override val personalGroup = "Private group"
        override val publicGroup = "Public group"
        override val youOwner = "you are the owner"
        override val youAdmin = "you are an admin"
        override val youModerator = "you are a moderator"
        override val youMember = "you are a member"

        override val members = "Members"
        override val access = "Access"
        override val invite = "Invite"
        override val readingMembers = "Reading the members"
        override val nobodyHereYet = "Nobody here yet"
        override val inviteByPhone = "Invite people by phone number"
        override val exclude = "Remove"
        override fun bannedUntil(until: String) = "blocked until $until"
        override val owner = "owner"
        override val admin = "admin"
        override val moderator = "moderator"
        override val member = "member"
        override val roleUnknown = "role unknown"

        override val closedAccess = "Access to closed entries"
        override val nobodyAsksAccess = "Access is open to nobody and nobody is asking for it"
        override val loading = "Loading…"
        override val accessOpen = "Access granted"
        override val accessOpenAbout =
            "You can see the closed entries of this group. The admin states the term in the " +
                "description."
        override val askSentTitle = "Request sent"
        override val askSentAbout =
            "The admin will answer — the answer arrives right here. No need to ask twice."
        override val declined = "Declined"
        override val declinedAbout =
            "The admin did not grant access. You may ask again — the decision is not forever."
        override val askAgain = "Ask again"
        override val noAccess = "No access"
        override val noAccessAbout =
            "Some entries are not shown to you. Their existence is not hidden — the content is."
        override val ask = "Ask"
        override val asksAccess = "asks for access"
        override val openForever = "access granted · no time limit"
        override fun openUntil(epoch: String) = "access granted · until $epoch"
        override val declinedShort = "declined"
        override val noAccessShort = "no access"
        override val forever = "No limit"
        override val decline = "Decline"
        override val deciding = "deciding…"

        override val month = "A month"
        override val threeMonths = "Three months"

        override val badTerm = "The term is written as 2026-10 — year and month"
        override val adminOpensAccess = "Access is granted by a group admin"
        override val communityDidNotOpen = "The community did not open"
        override val subscriptionNotChanged = "Could not change the subscription"
        override fun alreadyInAnother(title: String) = "«$title» is already in another community"
        override val ownerLinks = "Linking is for the community owner and the item's owner"
        override val couldNotLink = "Could not link it"
        override val ownerUnlinks = "Unlinking is for the community owner and the item's owner"
        override val couldNotUnlink = "Could not unlink it"
        override fun couldNotAsk(reason: String) = "Could not ask to join: $reason"
        override val groupsMayBeIncomplete =
            "No connection to the server — the list of groups may be incomplete"
        override val cardsMayBeIncomplete =
            "No connection to the server — your friends' cards may be incomplete"
        override val numberAlreadyListed = "That number is already on the list"
        override val membersMayBeStale = "No connection to the server — the list may be out of date"
        override val noSuchNumber = "That number is not in TIMA — invite the person to the messenger"
        override val ownerOrAdminChangesMembers = "Members are changed by the owner or an admin"
    }

    override val book = object : BookWords {
        override val everyone = "All"
        override val commonSection = "General"
        override val phoneSection = "Phone"
        override val search = "Search by name, nickname or number…"
        override val notRead = "Contacts not read"
        override val notReadAbout =
            "The app will take names and numbers from your phone book to show which of them " +
                "are already in TIMa. The numbers go to the server closed: it matches them " +
                "without reading them."
        override val addByHand = "Here contacts are added by hand"
        override val noBookHere = "A desktop system has no phone book — add people by number."
        override val nobodyFound = "Nobody found"
        override fun nothingMatches(search: String) = "Nothing in contacts matches «$search»"
        override val bookEmpty = "No contacts yet"
        override val bookEmptyAbout = "We will read the phone book, or add a person by number."
        override val nameless = "No name"

        override val view = "View"
        override val subsections = "How sections are shown"
        override val folders = "Folders"
        override val foldersAbout = "sections as bars, collapsible"
        override val menu = "Menu"
        override val menuAbout = "sections as a row under the tabs"
        override val showPersonAs = "Show a person as"
        override val name = "Name"
        override val nameAbout = "your own, otherwise from the phone book"
        override val userName = "User name"
        override val userNameAbout = "what they called themselves"
        override val nickname = "Nickname"
        override val nicknameAbout = "if the person set one"
        override val phone = "Phone"
        override val phoneAbout = "the number from the book"
        override val whatToShow = "What to show"
        override val showSearch = "Show search"
        override val showSearchAbout = "as a row above the list"
        override val showOutsiders = "Show people who are not in TIMa"
        override val showOutsidersAbout = "the «Phone» section at the end of the list"
    }

    override val page = object : PageWords {
        override val commentsOn = "Entries can be discussed"
        override val commentsOff = "Discussions are off"
        override val turnCommentsOff = "Turn discussions off"
        override val turnCommentsOn = "Turn on"
        override val emptyHere = "Nothing here yet"
        override val emptyMine = "Entries you bring to yourself will appear here"
        override val emptyTheirs = "This person is not showing anything yet"
        override val loading = "Loading…"
        override val yourEntry = "Your entry"
        override val carriedByYou = "brought by you"
        override val entryUnavailable = "The entry is unavailable"
        override val openDiscussion = "Open the discussion"
        override val closeDiscussion = "Close the discussion"
        override val remove = "Remove"

        override val cannotCarry = "This entry cannot be brought to your page"
        override val entryGone = "The entry is gone"
        override val couldNotRemove = "Could not remove the entry"
        override val ownerSwitchesPage = "Discussions are switched off by the page owner"
        override val pageGone = "The page is gone"
        override val ownerOrModeratorCloses = "A discussion is closed by the owner or a moderator"
    }

    override val chat = object : ChatWords {
        override val yourNickname = "Your nickname"

        override val onlyNarrow = "The audience can only be narrowed, never widened"
        override val secretIsNarrow =
            "An encrypted message is read by members only — there is nothing to narrow"
        override val openAlreadyOut =
            "An open message is already out — it cannot be encrypted after the fact"
        override val strangerNarrowsAdmin = "Someone else's message is narrowed by a group admin"
        override val messageGone = "The message is no longer in the group"
        override val offlineRetryLater = "No connection to the server — try again later"
        override fun offlineRetryIn(seconds: Int) =
            "No connection to the server — try again in $seconds s"
        override val notMemberAnyMore = "You are no longer a member of this group"
        override fun tooLong(limit: Int) = "Too long: up to $limit characters"
        override val notAPhone = "That does not make a phone number"
        override val ownNumber = "That is your own number"
        override fun badPhone(reason: String) = "Wrong number: $reason"

        override val addAndWrite = "Add and write"
        override val addToContacts = "Add to contacts"
        override val foundInTima = "Found in TIMa — you will be subscribed to their feed automatically"
        override val notInTima = "Not in TIMa. The contact will be saved — you can call by phone"

        override val access = "Audience"
        override val members = "Members"
        override val someone = "Member"

        override fun thread(count: Int): String {
            val word = if (count == 1) "reply" else "replies"
            return "thread · $count $word"
        }

        override val messageUnavailable = "message unavailable"
        override val decrypting = "decrypting…"
        override val addToSelf = "Bring to my page"
        override val narrowTo = "narrow to"
        override val narrow = "Narrow"
        override val messageHint = "Message"
        override val nameless = "No name"

        override fun tooLarge(bytes: Int, limit: Int) =
            "Too large: $bytes bytes against a limit of $limit"
        override fun keysAsked(devices: Int) =
            "The key was requested from $devices devices — the history appears when someone answers"
        override val keysNoHelpers =
            "Nobody among the members has these keys — the history from before you joined is lost"
        override val keysNothingMissing =
            "You already have every key: the message is unreadable for another reason"
        override val keysNeedPhrase =
            "The recovery phrase is needed: it is what guards the account against a stolen " +
                "number. If you do not know it here — write to the group from another of " +
                "your devices: the key will change and new messages will open. The older " +
                "ones need the phrase"
        override fun narrowWarning(circle: String) =
            "Narrow to «$circle»? Those who already brought the message to their page keep it"
        override fun narrowed(circle: String) = "Audience narrowed: now «$circle»"
        override val noGroupKey = "This device has no key for the group — that is why it is empty"
        override val noGroupKeyAbout =
            "There are messages, but nothing to open them with. Ask the members for the key, " +
                "or write to the group from another of your devices: the key will change and " +
                "the group will open from there on."
        override val askKey = "Request the key"
        override val asking = "Asking…"
        override val phraseWords = "Twelve words separated by spaces"
        override val storyUnavailable = "Part of the history is unavailable: it is from before you joined"

        override val write = "Write"
        override val noChatsYet = "No chats yet"
        override val writeFirst = "Write to your first correspondent"
        override val messageUnreadable = "message unreadable"
        override val newMessage = "new message"
        override val newChat = "New chat"
        override val whomToWrite = "Who to write to"
        override val phoneInTima = "A phone number in TIMA"
        override val noSuchNumber = "That number is not in TIMA — invite the person"
        override val searching = "Searching…"
        override val find = "Find"

        override val newContact = "New contact"
        override val phoneNumber = "Phone number"
        override val nameYouCall = "Name — what you will call them"
        override val optional = "optional"
        override val section = "Section"
        override val commonSection = "General"
        override val newSection = "New section"
        override val title = "Title"
        override val sectionExample = "Neighbours"
        override val moveLater = "Move people into it later — from the contact's row."
        override val createSection = "Create the section"

        override fun notInTima(phone: String) = "$phone · not in TIMa"
        override val sendSms = "Send an SMS"
        override val sendSmsAbout = "the messaging app opens with the text ready"
        override val call = "Call"
        override val callAbout = "an ordinary phone call"
        override val share = "Share"
        override val shareAbout = "a link into any app on the phone"

        override val profile = "Profile"
        override val nameNotSetYet = "Until you set a name, people see your number."
        override val nameHowShown = "Name — how you are shown to others"
        override val nameExample = "Peter Smith"
        override val nicknameFound = "Nickname — people find you by it"
        override val saved = "Saved"
        override val save = "Save"
        override val nicknameNeverFreed =
            "A taken nickname is never released: changing it does not give the old one away."
    }

    override val auth = object : AuthWords {
        override fun build(version: String) = "build $version"

        override val welcome = "Welcome"
        override val enterPhone = "Enter your phone number — we will send a code"
        override val sending = "Sending…"
        override val getCode = "Get the code"
        override val alreadyHaveAccount =
            "Already have an account on your phone? This device can be connected to it — " +
                "confirm the code on the phone."
        override val connectToAccount = "Connect to an account"
        override val confirmation = "Confirmation"
        override fun codeSentTo(phone: String) = "The code was sent to $phone"
        override fun standSentCode(code: String) = "The test server returned the code: $code"
        override val checking = "Checking…"
        override val confirm = "Confirm"
        override val changeNumber = "Change the number"

        override val connectingDevice = "Connecting a device"
        override val connectingDeviceAbout =
            "Open the camera on the phone where you are already signed in and point it at " +
                "this code. The phone will ask for confirmation — the code lasts five minutes."
        override val askingCode = "Asking the server for a code…"
        override val oldChatsWontMove =
            "Your earlier chats will not move to this device: the keys of old messages were " +
                "wrapped for other devices. New messages will arrive on both."
        override val newCode = "New code"

        override val secretPhrase = "Recovery phrase"
        override val secretPhraseAbout =
            "Twelve words are the only way back into the account if the phone is lost. " +
                "Write them down in order and keep them away from the phone."
        override val wroteDown = "Written down"
        override val phraseHint = "word word word…"
        override val phraseEntry = "Sign in with the phrase"
        override fun accountExistsFor(phone: String) =
            "The number $phone already has an account. Enter its recovery phrase — " +
                "twelve words separated by spaces."
        override val enter = "Sign in"
        override val otherNumber = "Another number"
        override val noPhrase =
            "No phrase? You can start over: the earlier chats will not come back, and the " +
                "people you talk to will see a warning that the identity changed."
        override val startAnew = "Start over"

        override val virtualAccount = "Virtual account"
        override val newNickname = "Nickname of the new account"
        override val newNicknameAbout =
            "This is a separate user: its own chats, its own keys, its own recovery phrase. " +
                "It has no phone number — people find it only by the nickname, so the " +
                "nickname is required."
        override val further = "Next"
        override val fiveAtMost =
            "No more than five virtual accounts per number. A taken nickname is never released."
        override val linkNotHiddenFromUs =
            "The people you talk to will not see the link to your main account. It is not " +
                "hidden from us: the link is stored on the server."
        override val yourSecretPhrase = "Your recovery phrase"
        override val yourSecretPhraseAbout =
            "The new account is created with your signature: it has no phone number and no " +
                "code will arrive for it. Enter the twelve words of your main account, " +
                "separated by spaces."
        override val creating = "Creating…"
        override fun createAccount(nickname: String) = "Create the account «$nickname»"
        override val wordsGoNowhere =
            "The words go nowhere: a signature is computed from them, and there they are forgotten."
        override fun phraseOf(nickname: String) = "Recovery phrase of «$nickname»"
        override val virtualPhraseSaved =
            "The account is created. These twelve words are the only way back into it. " +
                "Write them down in order: there is nothing to show them from a second time."
        override val virtualEntryFromYourNumber =
            "You can only sign back into this account from your own number: it has no phone " +
                "of its own, and the code comes to you."

        override val giveAccount = "Hand over the account"
        override val takeAccount = "Take an account"
        override val whatHappens = "What will happen"
        override val transferTakesAll =
            "The account goes whole: chats, groups, channels, roles and ownership. Your " +
                "devices in it will be disconnected and you will not be able to sign in " +
                "again — the sign-in code goes to the owner, and the owner will be someone else."
        override val transferCutsFuture =
            "Handing over cuts off the future, not the past: everything you have already read " +
                "stays on your phone, and the handover does not erase it."
        override val othersWontNotice =
            "The people you talk to will notice nothing: the account has no phone number, and " +
                "from the very start they talk to a nickname, not to a number."
        override val preparingCode = "Preparing the code…"
        override val issueTransferCode = "Issue a handover code"
        override val transferCode = "Handover code"
        override fun codeLives(minutes: Int) =
            "Show it to the person you are handing over to: they point their camera at it. " +
                "The code lives $minutes minutes and works once."
        override val phraseSeparately =
            "Send the account's phrase SEPARATELY and by another route — not in the same " +
                "message as the code. Together they are the account: whoever intercepts one " +
                "conversation gets both."
        override val wrongPhraseCosts =
            "A wrong phrase costs an attempt: after the third the code burns and a new one " +
                "has to be issued."
        override val transferCancelled = "The handover is cancelled — the code no longer works"
        override val cancelTransfer = "Cancel the handover"
        override val takeAccountAbout =
            "Two things are needed, and both come from the person handing over: the code and " +
                "the account's recovery phrase. The code alone is not enough — it opens " +
                "nothing without the phrase."
        override val accountPhrase = "Recovery phrase of the account"
        override val bringCodeHint = "point the camera or paste the code"
        override val entryFromYourNumber =
            "From now on you will sign into this account from your own number: it has no " +
                "phone of its own, and the code comes to you."
        override val accountYours = "The account is yours"
        override val accountYoursAbout =
            "The previous owner's devices are disconnected and signing in is yours now. " +
                "Change the phrase: the old one is known to whoever gave it to you."
        override val rotateGroupKeys =
            "The keys of this account's groups need changing: until then the previous owner " +
                "keeps reading what is new in them. Open the group's members and change the key."
        override val enterAccount = "Sign into the account"

        override val deviceConnected = "Device connected"
        override val deviceConnectedAbout =
            "New messages will arrive on it as well. Your earlier chats will not move there: " +
                "the keys of old messages were wrapped for other devices."
        override val notConnectionCode = "This is not a connection code"
        override val notConnectionCodeAbout =
            "A different code was scanned. Open «Connect to an account» on the computer and " +
                "point the camera at the code from there."
        override val confirmConnection = "Confirm the connection?"
        override fun deviceNamed(name: String) = "Device: $name"
        override val deviceUnnamed = "The device did not name itself"
        override val connectedDeviceCan =
            "A connected device will be able to read new messages of this account and write " +
                "on your behalf. You can disconnect it in the device list."
        override val connecting = "Connecting…"
        override val trust = "Trust it"
        override val reject = "Reject"

        override val watching = "Looking…"
        override val listNotCame = "The list did not arrive"
        override val noDevices = "No devices"
        override val reasonAbove = "The reason is above. It does not mean there are no devices"
        override val emptyListIsOurs =
            "The server never returns an empty list — so this is on our side"
        override val nameless = "No name"
        override val thisDevice = "this device"
        override val disconnect = "Disconnect"
        override val disconnectDevice = "Disconnect the device?"
        override val disconnectAbout =
            "It will stop receiving messages and lose access to the account. It cannot be " +
                "brought back — it will have to connect again from scratch."
        override val keep = "Keep it"

        override fun badPhone(reason: String) = "Wrong number: $reason"
        override val wrongCode = "The code is wrong or expired"
        override val codeExpired = "The code has expired — request a new one"
        override val timeIsUp = "Time is up — request the code again"
        override val wrongPhrase = "Wrong phrase — check what you wrote down"
        override val identityRefused = "The server refused to change the identity"
        override val codeTermOver = "The code's term is over — ask for a new one"
        override val notYourVirtual = "That is not your virtual account"
        override val cancelDidNotReach =
            "The cancellation did not reach the server. The code still works — try again"
        override val notTransferCode = "This is not a handover code — check that you pasted all of it"
        override val needAccountPhrase =
            "The phrase of the account being handed over is needed — it comes from the person handing it"
        override val phraseDoesNotFit =
            "The phrase does not fit. Fewer attempts remain — after the third the code has to " +
                "be issued anew"
        override val threeTriesBurned = "Three wrong attempts — the code burned. Ask for a new one"
        override val codeNotValid = "The code does not work: it was used, cancelled, or is over half an hour old"
        override val phraseNotMain =
            "The phrase does not fit. This is the phrase of your main account — twelve words " +
                "separated by spaces"
        override val nicknameTaken = "That nickname is taken — think of another"
        override val nicknameRules = "Nickname — 10 to 20 characters: Latin letters, digits, underscore"
        override val nicknameRulesShort = "10…20 characters: Latin letters, digits, underscore"
        override val fiveIsLimit = "No more than five virtual accounts per number"
        override val virtualHasNoVirtuals = "A virtual account does not create virtual accounts"
        override val nicknameFree = "Available"
        override val nicknameBusy = "Taken"
        override val onlyPhoneConfirms =
            "Only a phone can confirm the connection — it does not work on a computer"
        override val codeNoLongerValid = "The code no longer works — ask that device for a new one"
        override val codeReadWrong = "The code was read wrong — scan it again"
        override val deviceHasNoKey = "This device cannot confirm: it has no key of its own"
        override val tryAgain = "No connection — try again"
        override val listHasNothing = "No connection — there is nothing to show the list from"
        override val lastDevice = "This is the account's only device — it cannot be disconnected"
        override val deviceNotDisconnected = "No connection — the device was not disconnected"
        override val listDidNotCome = "The list did not arrive — no connection to the server"

        override val virtualAboutShort =
            "A virtual account is a separate user: its own chats, its own keys, its own " +
                "recovery phrase. It has no phone number and is found by nickname."
        override val yourAccounts = "Your accounts"
        override val noPhoneFoundByNickname = "no phone · found by nickname"
        override val give = "Hand over"
        override val noVirtualsYet = "No virtual accounts yet."
        override val actions = "Actions"
        override val createVirtual = "Create a virtual account"
        override val fiveAtMostShort = "no more than five per number"
        override val takeNeedsCodeAndPhrase = "the code and the phrase from the person handing over"
        override val linkNotHiddenLong =
            "The link to your main account is not visible to the people you talk to. It is " +
                "not hidden from us: the link is stored on the server — otherwise there " +
                "would be nothing to verify it against."
    }

    override val wizard = object : WizardWords {
        override val create = "Create"
        override val creating = "Creating…"
        override val next = "Next"
        override val gotIt = "Got it"

        override val whatCreate = "What are we creating?"
        override val whichGroup = "What kind of group?"
        override val howJoin = "How do people join?"
        override val howFound = "How is the channel found?"
        override val discussable = "Can entries be discussed?"
        override val naming = "Title and description"
        override val bringing = "What do we link?"

        override val sectionGroup = "Group"
        override val sectionGroupAbout = "A conversation between several people. Private or public"
        override val sectionChannel = "Channel"
        override val sectionChannelAbout = "Posts for subscribers"
        override val sectionCommunity = "Community"
        override val sectionCommunityAbout =
            "A container: groups and channels. It links what exists, it does not create new"
        override val sectionVoice = "Voice room"
        override val sectionVoiceAbout = "A voice room. Awaiting implementation"
        override val waitsImplementation = "awaiting implementation"

        override val personal = "Private"
        override val personalAbout =
            "Messages are encrypted. Not found by search — people are invited by acquaintance"
        override val personalExplain =
            "A private group: end-to-end encryption, the server does not see the messages. " +
                "It is not found by search — people learn about it from those who are in it."
        override val public = "Public"
        override val publicAbout = "Found by search. Open and closed access for members"
        override val publicExplain =
            "A public group: open conversation, found by search and in the catalogue. " +
                "The messages are not encrypted."
        override val kindIsFinal = "The kind does not change after creation: encryption depends on it"

        override val openJoining = "Open"
        override val openJoiningAbout = "Found it and joined"
        override val closedJoining = "Closed"
        override val closedJoiningAbout = "Asked to join, an admin allowed it"
        override val noneForPersonal = "not for private"
        override val openExplain = "Open: a person finds the group and joins by themselves."
        override val closedExplain = "Closed: a person asks to join, an admin allows it."
        override val personalAlwaysClosed =
            "A private group is always closed: it is not found by search, so there is nowhere " +
                "to join by yourself"

        override val inCatalogue = "Open"
        override val inCatalogueAbout = "Visible in the catalogue, anyone can subscribe"
        override val inCatalogueExplain =
            "An open channel. Visible in the catalogue, anyone can subscribe"
        override val byLink = "By link"
        override val byLinkAbout = "Not shown in the catalogue — found by link"
        override val byLinkExplain = "By link. The channel is not in the catalogue, it is found by link"
        override val commentsAllowed = "Yes"
        override val commentsAllowedAbout =
            "A conversation opens under the entry. Whoever sees the entry can comment"
        override val commentsAllowedExplain =
            "Whoever sees the entry can comment: there is no separate right for it"
        override val commentsForbidden = "No"
        override val commentsForbiddenAbout = "A channel without discussions. This can be changed later"
        override val commentsForbiddenExplain =
            "Off means «we take no new ones»: what was written before stays"

        override val groupName = "Group title"
        override val descriptionAbout = "The description is seen by everyone who can open the card"
        override val whomInvite = "Who to invite"
        override val add = "Add"
        override val remove = "remove"
        override val numberAlreadyListed = "That number is already on the list"
        override val groupCreatedNotInvited =
            "The group is created. These numbers are not in TIMA — invite the people:"
        override val communityCreatedNotLinked =
            "The community is created. These could not be linked — they are already in another:"
        override val nothingFreeToBring =
            "You have no groups or channels free to link. A community can be created empty"
    }

    override val tabs = object : TabWords {
        override fun label(tab: WindowTab) = when (tab) {
            WindowTab.Chats -> "Chats"
            WindowTab.Contacts -> "Contacts"
            WindowTab.Calls -> "Calls"
            WindowTab.View -> "View"
            WindowTab.All -> "All"
            WindowTab.FromBook -> "In contacts"
            WindowTab.Unknown -> "Unknown"
            WindowTab.Missed -> "Missed"
            WindowTab.Common -> "Common"
            WindowTab.Friends -> "Friends"
            WindowTab.Catalogue -> "Catalogue"
            WindowTab.Feed -> "Feed"
            WindowTab.Slides -> "Slides"
            WindowTab.Answers -> "Answers"
            WindowTab.Reactions -> "Reactions"
            WindowTab.Collections -> "Collections"
            WindowTab.Comments -> "Comments"
            WindowTab.Marks -> "Marks"
            WindowTab.Subscribed -> "Subscribed"
            WindowTab.Groups -> "Groups"
            WindowTab.Media -> "Media"
            WindowTab.Messages -> "Messages"
            WindowTab.Open -> "Open"
            WindowTab.Personal -> "Private"
        }
    }

    override val communities = object : CommunityWords {
        override val community = "Community"
        override val communities = "Communities"
        override val subscribe = "Subscribe"
        override val unsubscribe = "Unsubscribe"
        override val bring = "Link"
        override val takeOut = "Unlink"
        override val bringOwn = "Link your own"
        override val bringingKeepsEverything =
            "Chats, members and keys stay as they are — only one link changes"
        override val noDescription = "No description yet"
        override val emptyInside = "The community is empty so far"
        override val opening = "Opening the community…"
        override val channel = "channel"
        override val group = "group"
        override val personalGroup = "private group"
        override val yourCommunity = "your community"
        override val youSubscribed = "you are subscribed"
        override val youOwner = "you are the owner"
        override val youAdmin = "you are an admin"
        override val youNotSubscribed = "you are not subscribed"
        override fun inside(howMany: Int, role: String) = "$howMany inside · $role"
    }
}
