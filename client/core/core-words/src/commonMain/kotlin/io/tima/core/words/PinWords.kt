package io.tima.core.words

/**
 * Пин-код и новый вид «Секретная фраза и устройства» (ПЛАН-(ПН)-ПИН-КОДА, пробы
 * `doc/Layout-UI-light/пробы/пробы-пин-код.html`, приняты заказчиком 2026-10-07).
 */
interface PinWords {
    // Группы экрана (§4 плана): «Вход» сверху, красный «Аккаунт» последним.
    val groupEntry: String
    val groupThisDevice: String
    val groupDevices: String
    val groupHistory: String
    val groupAccount: String

    val title: String
    val about: String
    val on: String
    val off: String
    val offChoice: String
    val offChoiceAbout: String
    val enable: String
    val enableAbout: String
    val change: String
    val changeAbout: String
    val remove: String
    val removeAbout: String
    val forgot: String
    val forgotAbout: String

    val create: String
    val createNote: String
    val repeat: String
    val mismatch: String
    val enter: String
    val enterCurrent: String
    fun ofAccount(name: String): String
    val temporary: String
    fun wrong(leftBeforePause: Int): String
    fun paused(seconds: Long): String
    val phraseOnlyTitle: String
    val phraseOnlyAbout: String
    val phraseEnter: String

    val forgotTitle: String
    val forgotOwn: String
    val forgotTemporary: String
    val noSms: String
    val checkPhrase: String
    val phraseOk: String
    val phraseOkAbout: String
    val setNew: String
    val offline: String
    val offlineAbout: String
    val wrongPhrase: String
    val turnedOn: String
    val turnedOff: String
    val changed: String
    val erase: String
    val otherAccount: String
    val cancel: String

    // Подтверждения: в «Аккаунте» — красные, у выхода — спокойное.
    val signOutAsk: String
    val reregAsk: String
    val reregAskText: String
    val banAsk: String
    val banAskText: String
    val phoneAsk: String
    val phoneAskText: String
    val next: String

    // Состояние копии переписки — пузырём под строкой.
    val copyOn: String
    val copyOff: String

    // Короткие описания строк (пробы «а»).
    val signOutShort: String
    val copyTitle: String
    val copyShort: String
    val phoneShort: String
    val reregShort: String
    val banShort: String

    // Подокно «?».
    val helpDoes: String
    val helpResult: String
    val helpOk: String
    fun help(topic: HelpTopic): HelpText
}

/** О чём подокно «?» на экране «Секретная фраза и устройства». */
enum class HelpTopic { SignOut, Pin, ThisDevice, ConfirmWithPhrase, CertifyCode, Devices, Disconnect, Scan, Copy, RotateCopy, KeyRequest, PhoneChange, Rereg, Ban, Disputes }

data class HelpText(val title: String, val does: String, val result: String)

object RussianPinWords : PinWords {
    override val groupEntry = "Вход"
    override val groupThisDevice = "Это устройство"
    override val groupDevices = "Устройства"
    override val groupHistory = "История"
    override val groupAccount = "Аккаунт"

    override val title = "Пин-код"
    override val about = "4 цифры при входе в аккаунт на этом устройстве"
    override val on = "включён"
    override val off = "выключен"
    override val offChoice = "Выключен"
    override val offChoiceAbout = "вход без пин-кода"
    override val enable = "Включить"
    override val enableAbout = "4 цифры при запуске, после 5 минут в фоне и при переходе на этот аккаунт"
    override val change = "Сменить пин-код"
    override val changeAbout = "нужен нынешний пин-код или секретная фраза"
    override val remove = "Убрать пин-код"
    override val removeAbout = "нужен нынешний пин-код или секретная фраза"
    override val forgot = "Забыли пин-код?"
    override val forgotAbout = "сменить или убрать — секретной фразой, нужна сеть"

    override val create = "Придумайте пин-код — 4 цифры"
    override val createNote = "Хранится только на этом устройстве"
    override val repeat = "Повторите пин-код"
    override val mismatch = "Не совпало — придумайте заново"
    override val enter = "Введите пин-код"
    override val enterCurrent = "Введите нынешний пин-код"
    override fun ofAccount(name: String) = "Пин-код аккаунта «$name»"
    override val temporary = "временный аккаунт"
    override fun wrong(leftBeforePause: Int) =
        if (leftBeforePause <= 0) "Неверный пин-код." else "Неверный пин-код. Ещё ${errors(leftBeforePause)} — и пауза."
    override fun paused(seconds: Long) = "Слишком много ошибок. Следующая попытка через ${clock(seconds)}."
    override val phraseOnlyTitle = "Только секретная фраза"
    override val phraseOnlyAbout =
        "Пин-код введён неверно 10 раз. Войти можно только секретной фразой этого аккаунта — после этого задайте новый пин-код или уберите его. Для фразы нужна связь с сервером."
    override val phraseEnter = "Войти фразой"

    override val forgotTitle = "Забыли пин-код"
    override val forgotOwn = "Введите секретную фразу этого аккаунта. Пин-код после этого можно задать новый или убрать."
    override val forgotTemporary =
        "Введите секретную фразу этого аккаунта или фразу владельца, который его завёл. Пин-код после этого можно задать новый или убрать."
    override val noSms = "Код из SMS пин-код не сбрасывает: он пришёл бы на этот же телефон."
    override val checkPhrase = "Проверить фразу"
    override val phraseOk = "Фраза подошла"
    override val phraseOkAbout = "Задайте новый пин-код или уберите его совсем."
    override val setNew = "Задать новый пин-код"
    override val offline = "Нет связи с сервером"
    override val offlineAbout =
        "Фразу проверить нельзя: для неё нужна сеть. Пин-код остался прежним. Знаете пин — сменить или убрать его можно и без сети."
    override val wrongPhrase = "Фраза не та — проверьте запись"
    override val turnedOn = "Пин-код включён"
    override val turnedOff = "Пин-код убран"
    override val changed = "Пин-код сменён"
    override val erase = "Стереть"
    override val otherAccount = "Другой аккаунт"
    override val cancel = "Отмена"

    override val signOutAsk = "Выйти из аккаунта?"
    override val reregAsk = "Перерегистрировать аккаунт?"
    override val reregAskText =
        "Заверения снимутся со всех ваших устройств, собеседники увидят смену ключа. Делайте это, только если доступ к аккаунту есть у кого-то ещё."
    override val banAsk = "Запретить навсегда?"
    override val banAskText =
        "Снять запрет будет нельзя. Без секретной фразы в аккаунт больше не войти — даже с SIM-картой этого номера. Потеряете фразу — аккаунт не вернуть."
    override val phoneAsk = "Подать заявку на смену номера?"
    override val phoneAskText =
        "Нужны секретная фраза и код на прежний номер. Через 3 месяца заявку надо подтвердить фразой и кодом на новый номер — иначе она погаснет."
    override val next = "Дальше"

    override val copyOn = "заведена"
    override val copyOff = "не заведена"

    override val signOutShort = "аккаунт отложится, переписка и ключи останутся здесь"
    override val copyTitle = "Копия переписки"
    override val copyShort = "новый телефон с фразой поднимет историю сам"
    override val phoneShort = "новый телефон или SIM-карта"
    override val reregShort = "новый ключ личности, если доступ к аккаунту есть у кого-то ещё"
    override val banShort = "навсегда: без секретной фразы в аккаунт не войти"

    override val helpDoes = "Что делает"
    override val helpResult = "Что получится"
    override val helpOk = "Понятно"
    override fun help(topic: HelpTopic): HelpText = when (topic) {
        HelpTopic.SignOut -> HelpText(
            "Выйти из аккаунта",
            "Откладывает аккаунт на этом устройстве.",
            "Переписка и ключи остаются здесь. Вернуться — на экране входа.",
        )
        HelpTopic.Pin -> HelpText(
            "Пин-код",
            "Закрывает вход в этот аккаунт на этом устройстве четырьмя цифрами. Пин-код хранится только здесь — на сервер он не уходит.",
            "Приложение спросит пин при запуске, после 5 минут в фоне и при переходе на этот аккаунт. Забыли — сменить или убрать его можно только секретной фразой, для неё нужна сеть.",
        )
        HelpTopic.ThisDevice -> HelpText(
            "Это устройство",
            "Показывает, заверено ли устройство ключом личности из вашей фразы.",
            "Заверенному собеседники пишут и принимают его подписи. Незаверенному — нет.",
        )
        HelpTopic.ConfirmWithPhrase -> HelpText(
            "Подтвердить фразой",
            "Заверяет это устройство секретной фразой.",
            "Собеседники начнут писать и ему. На телефоне фраза нужна один раз: дальше он сам заверяет ваши новые устройства.",
        )
        HelpTopic.CertifyCode -> HelpText(
            "Показать код заверения",
            "Показывает код, который сканирует ваш заверенный телефон.",
            "Устройство заверено без ввода фразы на нём.",
        )
        HelpTopic.Devices -> HelpText(
            "Устройства",
            "Все устройства аккаунта и заверено ли каждое.",
            "Незаверенное можно заверить, чужое — отключить.",
        )
        HelpTopic.Disconnect -> HelpText(
            "Отключить",
            "Сервер перестаёт признавать устройство.",
            "Оно больше не получает сообщений. Если оно могло унести ключ копии — смените его в «Истории».",
        )
        HelpTopic.Scan -> HelpText(
            "Сканировать код",
            "Подключает новое устройство или заверяет компьютер по коду на его экране.",
            "Устройство входит в аккаунт и получает историю с этого телефона.",
        )
        HelpTopic.Copy -> HelpText(
            "Копия переписки",
            "Хранит ключи вашей переписки зашифрованными на сервере.",
            "Новый телефон с секретной фразой поднимет историю сам. Сервер копию не прочтёт.",
        )
        HelpTopic.RotateCopy -> HelpText(
            "Сменить ключ копии",
            "Переводит копию на новый ключ.",
            "Отключённое устройство больше не откроет копию.",
        )
        HelpTopic.KeyRequest -> HelpText(
            "Ключ копии контактов",
            "Просит ключ копии контактов у ваших устройств на связи.",
            "Контакты и имена с телефона появятся на этом устройстве.",
        )
        HelpTopic.PhoneChange -> HelpText(
            "Смена номера",
            "Заявка на новый номер: фраза и код на прежний номер.",
            "Через 3 месяца подтвердите её фразой и кодом на новый номер — номер аккаунта сменится.",
        )
        HelpTopic.Rereg -> HelpText(
            "Перерегистрация",
            "Заводит новый ключ личности с новой секретной фразой — на случай, если доступ к аккаунту есть у кого-то ещё. Нужны прежняя фраза и код из SMS.",
            "Заверения снимутся со всех ваших устройств. Собеседники увидят, что ключ сменился. Затем откроется окно подтверждения: не подтвердите — новый ключ будет удалён.",
        )
        HelpTopic.Ban -> HelpText(
            "Запретить «Начать заново без фразы»",
            "Запрещает вход в аккаунт без секретной фразы — даже с SIM-картой этого номера.",
            "Навсегда, снять нельзя. Потеряете фразу — аккаунт не вернуть.",
        )
        HelpTopic.Disputes -> HelpText(
            "Заявки спора",
            "Показывают идущую перерегистрацию или смену номера и сроки окна.",
            "Подтвердить или отменить — в самой заявке; не подтвердите за окно — заявка погаснет.",
        )
    }

    private fun errors(n: Int) = when {
        n % 10 == 1 && n % 100 != 11 -> "$n ошибка"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "$n ошибки"
        else -> "$n ошибок"
    }
}

object EnglishPinWords : PinWords {
    override val groupEntry = "Sign-in"
    override val groupThisDevice = "This device"
    override val groupDevices = "Devices"
    override val groupHistory = "History"
    override val groupAccount = "Account"

    override val title = "PIN code"
    override val about = "4 digits to enter this account on this device"
    override val on = "on"
    override val off = "off"
    override val offChoice = "Off"
    override val offChoiceAbout = "no PIN to enter"
    override val enable = "Turn on"
    override val enableAbout = "4 digits at launch, after 5 minutes in the background and when switching to this account"
    override val change = "Change PIN"
    override val changeAbout = "needs the current PIN or the secret phrase"
    override val remove = "Remove PIN"
    override val removeAbout = "needs the current PIN or the secret phrase"
    override val forgot = "Forgot your PIN?"
    override val forgotAbout = "change or remove it with the secret phrase; needs a connection"

    override val create = "Choose a PIN of 4 digits"
    override val createNote = "Kept only on this device"
    override val repeat = "Repeat the PIN"
    override val mismatch = "They don't match, choose again"
    override val enter = "Enter your PIN"
    override val enterCurrent = "Enter your current PIN"
    override fun ofAccount(name: String) = "PIN of the account \"$name\""
    override val temporary = "temporary account"
    override fun wrong(leftBeforePause: Int) =
        if (leftBeforePause <= 0) "Wrong PIN." else "Wrong PIN. $leftBeforePause more and there will be a pause."
    override fun paused(seconds: Long) = "Too many mistakes. Next try in ${clock(seconds)}."
    override val phraseOnlyTitle = "Secret phrase only"
    override val phraseOnlyAbout =
        "The PIN was entered wrong 10 times. You can only enter with this account's secret phrase, then set a new PIN or remove it. The phrase needs a connection to the server."
    override val phraseEnter = "Enter with the phrase"

    override val forgotTitle = "Forgot your PIN"
    override val forgotOwn = "Enter this account's secret phrase. Then you can set a new PIN or remove it."
    override val forgotTemporary =
        "Enter this account's secret phrase or the phrase of the owner who created it. Then you can set a new PIN or remove it."
    override val noSms = "An SMS code does not reset the PIN: it would arrive on this very phone."
    override val checkPhrase = "Check the phrase"
    override val phraseOk = "The phrase fits"
    override val phraseOkAbout = "Set a new PIN or remove it entirely."
    override val setNew = "Set a new PIN"
    override val offline = "No connection to the server"
    override val offlineAbout =
        "The phrase can't be checked: it needs a connection. The PIN stays as it was. If you know the PIN, you can change or remove it offline too."
    override val wrongPhrase = "Wrong phrase, check what you wrote down"
    override val turnedOn = "PIN turned on"
    override val turnedOff = "PIN removed"
    override val changed = "PIN changed"
    override val erase = "Erase"
    override val otherAccount = "Another account"
    override val cancel = "Cancel"

    override val signOutAsk = "Sign out of the account?"
    override val reregAsk = "Re-register the account?"
    override val reregAskText =
        "Certifications will be removed from all your devices, and your contacts will see the key change. Do this only if someone else has access to the account."
    override val banAsk = "Forbid it forever?"
    override val banAskText =
        "The ban can't be lifted. Without the secret phrase there will be no way into the account, even with this number's SIM card. Lose the phrase and the account is gone."
    override val phoneAsk = "File a request to change the number?"
    override val phoneAskText =
        "You need the secret phrase and a code sent to the old number. In 3 months confirm the request with the phrase and a code sent to the new number, otherwise it lapses."
    override val next = "Next"

    override val copyOn = "set up"
    override val copyOff = "not set up"

    override val signOutShort = "the account is put aside, chats and keys stay here"
    override val copyTitle = "Chat copy"
    override val copyShort = "a new phone with the phrase restores the history itself"
    override val phoneShort = "a new phone or SIM card"
    override val reregShort = "a new identity key, if someone else has access to the account"
    override val banShort = "forever: no way into the account without the secret phrase"

    override val helpDoes = "What it does"
    override val helpResult = "What you get"
    override val helpOk = "Got it"
    override fun help(topic: HelpTopic): HelpText = when (topic) {
        HelpTopic.SignOut -> HelpText(
            "Sign out",
            "Puts the account aside on this device.",
            "Chats and keys stay here. Come back from the sign-in screen.",
        )
        HelpTopic.Pin -> HelpText(
            "PIN code",
            "Locks this account on this device with four digits. The PIN is kept only here and never goes to the server.",
            "The app asks for it at launch, after 5 minutes in the background and when switching to this account. Forgot it: only the secret phrase can change or remove it, and that needs a connection.",
        )
        HelpTopic.ThisDevice -> HelpText(
            "This device",
            "Shows whether the device is certified with the identity key from your phrase.",
            "Contacts write to a certified device and accept its signatures. Not to an uncertified one.",
        )
        HelpTopic.ConfirmWithPhrase -> HelpText(
            "Confirm with the phrase",
            "Certifies this device with the secret phrase.",
            "Contacts will start writing to it too. A phone needs the phrase once: after that it certifies your new devices itself.",
        )
        HelpTopic.CertifyCode -> HelpText(
            "Show the certification code",
            "Shows a code that your certified phone scans.",
            "The device is certified without typing the phrase on it.",
        )
        HelpTopic.Devices -> HelpText(
            "Devices",
            "All devices of the account and whether each is certified.",
            "An uncertified one can be certified, a stranger's one disconnected.",
        )
        HelpTopic.Disconnect -> HelpText(
            "Disconnect",
            "The server stops recognising the device.",
            "It gets no more messages. If it could have taken the copy key, change it under History.",
        )
        HelpTopic.Scan -> HelpText(
            "Scan a code",
            "Connects a new device or certifies a computer by the code on its screen.",
            "The device enters the account and gets the history from this phone.",
        )
        HelpTopic.Copy -> HelpText(
            "Chat copy",
            "Keeps the keys of your chats encrypted on the server.",
            "A new phone with the secret phrase restores the history by itself. The server can't read the copy.",
        )
        HelpTopic.RotateCopy -> HelpText(
            "Change the copy key",
            "Moves the copy to a new key.",
            "A disconnected device can no longer open the copy.",
        )
        HelpTopic.KeyRequest -> HelpText(
            "Contacts copy key",
            "Asks your online devices for the contacts copy key.",
            "Contacts and names from the phone appear on this device.",
        )
        HelpTopic.PhoneChange -> HelpText(
            "Change of number",
            "A request for a new number: the phrase and a code sent to the old number.",
            "In 3 months confirm it with the phrase and a code sent to the new number, and the account's number changes.",
        )
        HelpTopic.Rereg -> HelpText(
            "Re-registration",
            "Creates a new identity key with a new secret phrase, in case someone else has access to the account. Needs the old phrase and an SMS code.",
            "Certifications are removed from all your devices. Contacts will see that the key changed. Then a confirmation window opens: don't confirm and the new key is deleted.",
        )
        HelpTopic.Ban -> HelpText(
            "Forbid “Start over without the phrase”",
            "Forbids entering the account without the secret phrase, even with this number's SIM card.",
            "Forever, it can't be lifted. Lose the phrase and the account is gone.",
        )
        HelpTopic.Disputes -> HelpText(
            "Dispute requests",
            "Show an ongoing re-registration or change of number and the window dates.",
            "Confirm or cancel inside the request; not confirmed within the window, it lapses.",
        )
    }
}

object SpanishPinWords : PinWords {
    override val groupEntry = "Acceso"
    override val groupThisDevice = "Este dispositivo"
    override val groupDevices = "Dispositivos"
    override val groupHistory = "Historial"
    override val groupAccount = "Cuenta"

    override val title = "Código PIN"
    override val about = "4 cifras para entrar en esta cuenta en este dispositivo"
    override val on = "activado"
    override val off = "desactivado"
    override val offChoice = "Desactivado"
    override val offChoiceAbout = "entrar sin PIN"
    override val enable = "Activar"
    override val enableAbout = "4 cifras al abrir, tras 5 minutos en segundo plano y al pasar a esta cuenta"
    override val change = "Cambiar el PIN"
    override val changeAbout = "hace falta el PIN actual o la frase secreta"
    override val remove = "Quitar el PIN"
    override val removeAbout = "hace falta el PIN actual o la frase secreta"
    override val forgot = "¿Olvidó el PIN?"
    override val forgotAbout = "cambiarlo o quitarlo con la frase secreta; hace falta conexión"

    override val create = "Elija un PIN de 4 cifras"
    override val createNote = "Se guarda solo en este dispositivo"
    override val repeat = "Repita el PIN"
    override val mismatch = "No coincide, elija otro"
    override val enter = "Introduzca el PIN"
    override val enterCurrent = "Introduzca el PIN actual"
    override fun ofAccount(name: String) = "PIN de la cuenta «$name»"
    override val temporary = "cuenta temporal"
    override fun wrong(leftBeforePause: Int) =
        if (leftBeforePause <= 0) "PIN incorrecto." else "PIN incorrecto. $leftBeforePause más y habrá una pausa."
    override fun paused(seconds: Long) = "Demasiados errores. Próximo intento en ${clock(seconds)}."
    override val phraseOnlyTitle = "Solo la frase secreta"
    override val phraseOnlyAbout =
        "El PIN se introdujo mal 10 veces. Solo se puede entrar con la frase secreta de esta cuenta; después elija un PIN nuevo o quítelo. La frase necesita conexión con el servidor."
    override val phraseEnter = "Entrar con la frase"

    override val forgotTitle = "Olvidó el PIN"
    override val forgotOwn = "Introduzca la frase secreta de esta cuenta. Después podrá elegir un PIN nuevo o quitarlo."
    override val forgotTemporary =
        "Introduzca la frase secreta de esta cuenta o la del propietario que la creó. Después podrá elegir un PIN nuevo o quitarlo."
    override val noSms = "Un código por SMS no restablece el PIN: llegaría a este mismo teléfono."
    override val checkPhrase = "Comprobar la frase"
    override val phraseOk = "La frase es correcta"
    override val phraseOkAbout = "Elija un PIN nuevo o quítelo del todo."
    override val setNew = "Elegir un PIN nuevo"
    override val offline = "Sin conexión con el servidor"
    override val offlineAbout =
        "No se puede comprobar la frase: hace falta conexión. El PIN sigue igual. Si conoce el PIN, puede cambiarlo o quitarlo también sin conexión."
    override val wrongPhrase = "La frase no es la correcta, revise lo que anotó"
    override val turnedOn = "PIN activado"
    override val turnedOff = "PIN quitado"
    override val changed = "PIN cambiado"
    override val erase = "Borrar"
    override val otherAccount = "Otra cuenta"
    override val cancel = "Cancelar"

    override val signOutAsk = "¿Salir de la cuenta?"
    override val reregAsk = "¿Volver a registrar la cuenta?"
    override val reregAskText =
        "Se quitarán las certificaciones de todos sus dispositivos y sus contactos verán el cambio de clave. Hágalo solo si alguien más tiene acceso a la cuenta."
    override val banAsk = "¿Prohibirlo para siempre?"
    override val banAskText =
        "No se podrá levantar. Sin la frase secreta no habrá forma de entrar en la cuenta, ni siquiera con la SIM de este número. Si pierde la frase, la cuenta no se recupera."
    override val phoneAsk = "¿Solicitar el cambio de número?"
    override val phoneAskText =
        "Hacen falta la frase secreta y un código al número anterior. En 3 meses confirme la solicitud con la frase y un código al número nuevo; si no, caduca."
    override val next = "Siguiente"

    override val copyOn = "creada"
    override val copyOff = "sin crear"

    override val signOutShort = "la cuenta queda apartada, los chats y las claves se quedan aquí"
    override val copyTitle = "Copia de los chats"
    override val copyShort = "un teléfono nuevo con la frase recupera el historial solo"
    override val phoneShort = "un teléfono o una SIM nuevos"
    override val reregShort = "una clave de identidad nueva, si alguien más tiene acceso a la cuenta"
    override val banShort = "para siempre: sin la frase secreta no se entra en la cuenta"

    override val helpDoes = "Qué hace"
    override val helpResult = "Qué se obtiene"
    override val helpOk = "Entendido"
    override fun help(topic: HelpTopic): HelpText = when (topic) {
        HelpTopic.SignOut -> HelpText(
            "Salir de la cuenta",
            "Aparta la cuenta en este dispositivo.",
            "Los chats y las claves se quedan aquí. Se vuelve desde la pantalla de acceso.",
        )
        HelpTopic.Pin -> HelpText(
            "Código PIN",
            "Cierra esta cuenta en este dispositivo con cuatro cifras. El PIN se guarda solo aquí y nunca va al servidor.",
            "La aplicación lo pide al abrir, tras 5 minutos en segundo plano y al pasar a esta cuenta. Si lo olvida, solo la frase secreta permite cambiarlo o quitarlo, y hace falta conexión.",
        )
        HelpTopic.ThisDevice -> HelpText(
            "Este dispositivo",
            "Muestra si el dispositivo está certificado con la clave de identidad de su frase.",
            "A un dispositivo certificado sus contactos le escriben y aceptan sus firmas. A uno sin certificar, no.",
        )
        HelpTopic.ConfirmWithPhrase -> HelpText(
            "Confirmar con la frase",
            "Certifica este dispositivo con la frase secreta.",
            "Sus contactos empezarán a escribirle también. Un teléfono necesita la frase una vez: después certifica él mismo sus dispositivos nuevos.",
        )
        HelpTopic.CertifyCode -> HelpText(
            "Mostrar el código de certificación",
            "Muestra un código que escanea su teléfono certificado.",
            "El dispositivo queda certificado sin escribir la frase en él.",
        )
        HelpTopic.Devices -> HelpText(
            "Dispositivos",
            "Todos los dispositivos de la cuenta y si cada uno está certificado.",
            "Uno sin certificar se puede certificar; uno ajeno, desconectar.",
        )
        HelpTopic.Disconnect -> HelpText(
            "Desconectar",
            "El servidor deja de reconocer el dispositivo.",
            "Ya no recibe mensajes. Si pudo llevarse la clave de la copia, cámbiela en Historial.",
        )
        HelpTopic.Scan -> HelpText(
            "Escanear un código",
            "Conecta un dispositivo nuevo o certifica un ordenador por el código de su pantalla.",
            "El dispositivo entra en la cuenta y recibe el historial desde este teléfono.",
        )
        HelpTopic.Copy -> HelpText(
            "Copia de los chats",
            "Guarda las claves de sus chats cifradas en el servidor.",
            "Un teléfono nuevo con la frase secreta recupera el historial solo. El servidor no puede leer la copia.",
        )
        HelpTopic.RotateCopy -> HelpText(
            "Cambiar la clave de la copia",
            "Pasa la copia a una clave nueva.",
            "Un dispositivo desconectado ya no podrá abrir la copia.",
        )
        HelpTopic.KeyRequest -> HelpText(
            "Clave de la copia de contactos",
            "Pide la clave de la copia de contactos a sus dispositivos en línea.",
            "Los contactos y nombres del teléfono aparecen en este dispositivo.",
        )
        HelpTopic.PhoneChange -> HelpText(
            "Cambio de número",
            "Solicitud de número nuevo: la frase y un código al número anterior.",
            "En 3 meses confírmela con la frase y un código al número nuevo: el número de la cuenta cambiará.",
        )
        HelpTopic.Rereg -> HelpText(
            "Nuevo registro",
            "Crea una clave de identidad nueva con una frase secreta nueva, por si alguien más tiene acceso a la cuenta. Hacen falta la frase anterior y un código por SMS.",
            "Se quitarán las certificaciones de todos sus dispositivos. Sus contactos verán que la clave cambió. Después se abre la ventana de confirmación: si no confirma, la clave nueva se borra.",
        )
        HelpTopic.Ban -> HelpText(
            "Prohibir «Empezar de cero sin la frase»",
            "Prohíbe entrar en la cuenta sin la frase secreta, incluso con la SIM de este número.",
            "Para siempre, no se puede levantar. Si pierde la frase, la cuenta no se recupera.",
        )
        HelpTopic.Disputes -> HelpText(
            "Solicitudes en disputa",
            "Muestran un nuevo registro o un cambio de número en curso y las fechas de la ventana.",
            "Confirmar o cancelar, dentro de la solicitud; si no se confirma a tiempo, caduca.",
        )
    }
}

/** «0:30», «2:00» — сколько ждать до следующей попытки. */
private fun clock(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}
