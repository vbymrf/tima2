package io.tima.core.words

/**
 * Слова Социума по единому плану (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md): типы сущностей, секретность, приглашения,
 * страница сущности и пузыри состояния.
 */
interface SociumWords {
    // Типы (СЦ2): словами — на странице и для чтения с экрана; в строке — значком.
    val personalGroup: String
    val publicGroup: String
    val secretGroup: String
    val publicChannel: String
    val channel: String
    val secretChannel: String
    val community: String
    val secretCommunity: String
    val voiceChat: String

    // Секретность (СЦ2).
    val secret: String
    val secretAbout: String

    // Приглашения (СЦ2).
    val invite: String
    val invited: String
    val inviteAccept: String
    val inviteDecline: String
    val inviteAccepted: String
    val inviteDeclined: String
    val inviteGone: String
    val invitesTitle: String
    fun inviteCard(title: String): String

    // Пузыри состояния страницы (§1.2, 6).
    val subscribed: String
    val notSubscribed: String
    val member: String
    val notMember: String
    val notifyOn: String
    val notifyOff: String
    val notifyBlocked: String
    val showLevels: String

    // Вкладки страницы (вариант В).
    val tabAbout: String
    val tabMembers: String
    val tabManage: String
    val write: String
    val share: String
    val leave: String

    // Передача владения (СЦ11, §6.4).
    val transferTitle: String
    val transferAbout: String
    val transferNeedsOldKey: String
    val transferNoCandidates: String
    val transferSent: String
    val transferWithdraw: String
    fun transferPending(to: String): String
    fun ownershipOffered(title: String, from: String): String
    val ownershipAccepted: String
    val phraseHint: String
    val phraseAbout: String
    val badPhrase: String
    val offerDropped: String
    val receiverRole: String

    // Цвет названия своей роли (СЦ3, §1.2, 3).
    val ownColorTitle: String
    val ownColorAbout: String
    val ownColorDefault: String
    // Страница и «Управление» (СЦ4, СЦ5).
    val notifyTitle: String
    val manageMain: String
    val manageMainAbout: String
    val manageRoles: String
    val manageRolesAbout: String
    val manageRequests: String
    val manageRequestsAbout: String
    val manageAccess: String
    val manageAccessAbout: String
    val manageMessages: String
    val manageMessagesAbout: String
    val manageVoice: String
    val manageVoiceAbout: String
    val manageAppeals: String
    val manageAppealsAbout: String
    val manageDanger: String
    val manageDangerAbout: String
    val titleHint: String
    val save: String
    val saved: String

    // Удаление с месяцем на отмену (СЦ14).
    val deleteEntity: String
    val deleteAbout: String
    val cancelDeletion: String
    fun willBeDeleted(date: String): String
    // Переписка «сущность — человек» (СЦ15).
    val answersEmpty: String
    val notAccepting: String
    val threadBlocked: String
    val peopleLimit: String
    val writeHint: String
    val send: String
    val acceptMessages: String
    fun peopleToday(n: Int, limit: Int): String
    val blockedMark: String
    val blockPerson: String
    val unblockPerson: String
    val threadAbout: String
    // Звуковой чат (СЦ7).
    val voiceHost: String
    val voicePaused: String
    val voiceNoE2e: String
    val voiceText: String
    val voiceTakeHost: String
    val micOn: String
    val micOff: String
    val soundOn: String
    val soundOff: String
    val voiceChats: String
    val voiceNewHint: String
    val voiceCreate: String
    val voiceAllowMute: String
    val voiceHostAllowed: String
    val voiceHostKicks: String
    val voiceFull: String
    val voiceKicked: String
    fun voiceInside(n: Int): String
    fun voiceMax(n: Int): String
    // Закреплённые группой (СЦ12).
    val pinnedGroup: String
    val pinsDefaultTitle: String
    val pinsDeleteGroup: String
    val pinsDeleteSome: String
    val pinsAdd: String
    val pinsReplace: String
    val pinsClearAll: String
    val pinsAlready: String
    val pinsFull: String
    val cancel: String
    fun pinsDeleteChosen(n: Int): String

    // Роли и бан (СЦ6).
    val makeModerator: String
    val makeAdmin: String
    val makeMember: String
    val banDay: String
}

object RussianSociumWords : SociumWords {
    override val personalGroup = "личная группа"
    override val publicGroup = "публичная группа"
    override val secretGroup = "секретная группа"
    override val publicChannel = "публичный канал"
    override val channel = "канал"
    override val secretChannel = "секретный канал"
    override val community = "сообщество"
    override val secretCommunity = "секретное сообщество"
    override val voiceChat = "звуковой чат"
    override val secret = "Секретная"
    override val secretAbout = "не находится в поиске и лентах, войти можно только по приглашению"
    override val invite = "Пригласить"
    override val invited = "Приглашение отправлено"
    override val inviteAccept = "Принять"
    override val inviteDecline = "Отказаться"
    override val inviteAccepted = "Вы приняли приглашение"
    override val inviteDeclined = "Вы отказались"
    override val inviteGone = "Приглашения уже нет"
    override val invitesTitle = "Приглашения"
    override fun inviteCard(title: String) = "Приглашение: $title"
    override val subscribed = "Подписан"
    override val notSubscribed = "Не подписан"
    override val member = "Участник"
    override val notMember = "Не участник"
    override val notifyOn = "Уведомления включены"
    override val notifyOff = "Уведомления выключены"
    override val notifyBlocked = "Уведомления заблокированы"
    override val showLevels = "Показывать уровень сообщений"
    override val tabAbout = "О сущности"
    override val tabMembers = "Участники"
    override val tabManage = "Управление"
    override val write = "Написать"
    override val share = "Поделиться"
    override val leave = "Выйти"
    override val transferTitle = "Передать владение"
    override val transferAbout = "Выберите, кому передать: принять может модератор и выше (в сообществе — админ). Вы станете участником."
    override val transferNeedsOldKey = "Передать можно ключом, которым владение получено. Введите прежнюю секретную фразу."
    override val transferNoCandidates = "Некому передать: назначьте модератора (в сообществе — админа)"
    override val transferSent = "Предложение отправлено, ждёт ответа 30 минут"
    override val transferWithdraw = "Отозвать предложение"
    override fun transferPending(to: String) = "Ждёт ответа: $to"
    override fun ownershipOffered(title: String, from: String) = "Вам передают владение «$title» от $from"
    override val ownershipAccepted = "Вы владелец"
    override val phraseHint = "12 слов секретной фразы"
    override val phraseAbout = "Подпись — вашей секретной фразой. Фраза не уходит с устройства и не сохраняется."
    override val badPhrase = "Фраза не та"
    override val offerDropped = "Три неверные фразы — предложение погашено"
    override val receiverRole = "Принять может модератор и выше (в сообществе — админ)"
    override val ownColorTitle = "Цвет названия своей роли"
    override val ownColorAbout = "где вы владелец, админ или модератор — название в строке этим цветом"
    override val ownColorDefault = "По умолчанию — зелёный"
    override val notifyTitle = "Уведомления"
    override val manageMain = "Основное"
    override val manageMainAbout = "название, «секретная»"
    override val manageRoles = "Роли"
    override val manageRolesAbout = "назначить и снять"
    override val manageRequests = "Заявки"
    override val manageRequestsAbout = "просят вступить"
    override val manageAccess = "Доступ"
    override val manageAccessAbout = "уровень 3 и сроки"
    override val manageMessages = "Сообщения"
    override val manageMessagesAbout = "суперсообщение, закреплённые"
    override val manageVoice = "Звуковые чаты"
    override val manageVoiceAbout = "создать, настройки, список"
    override val manageAppeals = "Обращения"
    override val manageAppealsAbout = "переписка с людьми, «Принимать сообщения»"
    override val manageDanger = "Опасное"
    override val manageDangerAbout = "передать владение, удалить"
    override val titleHint = "Название"
    override val save = "Сохранить"
    override val saved = "Сохранено"
    override val deleteEntity = "Удалить"
    override val deleteAbout = "Удаление — через месяц: до тех пор его можно отменить, а всё живёт как прежде"
    override val cancelDeletion = "Отменить удаление"
    override fun willBeDeleted(date: String) = "Будет удалена $date"
    override val answersEmpty = "Вам пока никто не ответил и вы никому не писали"
    override val notAccepting = "Сущность не принимает сообщения"
    override val threadBlocked = "Сообщения заблокированы"
    override val peopleLimit = "Сегодня уже написали пятерым — больше нельзя до завтра"
    override val writeHint = "Сообщение"
    override val send = "Отправить"
    override val acceptMessages = "Принимать сообщения"
    override fun peopleToday(n: Int, limit: Int) = "Сегодня написали: $n из $limit"
    override val blockedMark = "блок"
    override val blockPerson = "Заблокировать человека"
    override val unblockPerson = "Разблокировать"
    override val threadAbout = "Переписка с сущностью не шифруется: её читают те, кто за неё пишет"
    override val voiceHost = "ведущий"
    override val voicePaused = "На паузе: идёт звонок"
    override val voiceNoE2e = "Звук без сквозного шифрования, записи нет"
    override val voiceText = "Переписка"
    override val voiceTakeHost = "Стать ведущим"
    override val micOn = "Включить микрофон"
    override val micOff = "Выключить микрофон"
    override val soundOn = "Включить звук"
    override val soundOff = "Выключить звук"
    override val voiceChats = "Звуковые чаты"
    override val voiceNewHint = "Название звукового чата"
    override val voiceCreate = "Создать"
    override val voiceAllowMute = "Участники могут выключать микрофон и звук"
    override val voiceHostAllowed = "Ведущий разрешён"
    override val voiceHostKicks = "Ведущий может выгонять"
    override val voiceFull = "Звуковой чат полон"
    override val voiceKicked = "Вас выгнали из этого разговора"
    override fun voiceInside(n: Int) = "внутри: $n"
    override fun voiceMax(n: Int) = "наибольшее число участников: $n"
    override val pinnedGroup = "Закреплена группа сообщений."
    override val pinsDefaultTitle = "Группа сообщений"
    override val pinsDeleteGroup = "Удалить группу"
    override val pinsDeleteSome = "Удалить сообщения"
    override val pinsAdd = "Добавить"
    override val pinsReplace = "Заменить"
    override val pinsClearAll = "Удалить все"
    override val pinsAlready = "Уже закреплено"
    override val pinsFull = "Закреплено 20 — уберите лишние"
    override val cancel = "Отмена"
    override fun pinsDeleteChosen(n: Int) = "Удалить выбранные: $n"
    override val makeModerator = "Сделать модератором"
    override val makeAdmin = "Сделать админом"
    override val makeMember = "Сделать участником"
    override val banDay = "Бан на сутки"
}

object EnglishSociumWords : SociumWords {
    override val personalGroup = "personal group"
    override val publicGroup = "public group"
    override val secretGroup = "secret group"
    override val publicChannel = "public channel"
    override val channel = "channel"
    override val secretChannel = "secret channel"
    override val community = "community"
    override val secretCommunity = "secret community"
    override val voiceChat = "voice chat"
    override val secret = "Secret"
    override val secretAbout = "not found in search or feeds; joining is by invitation only"
    override val invite = "Invite"
    override val invited = "Invitation sent"
    override val inviteAccept = "Accept"
    override val inviteDecline = "Decline"
    override val inviteAccepted = "You accepted the invitation"
    override val inviteDeclined = "You declined"
    override val inviteGone = "The invitation is no longer there"
    override val invitesTitle = "Invitations"
    override fun inviteCard(title: String) = "Invitation: $title"
    override val subscribed = "Subscribed"
    override val notSubscribed = "Not subscribed"
    override val member = "Member"
    override val notMember = "Not a member"
    override val notifyOn = "Notifications on"
    override val notifyOff = "Notifications off"
    override val notifyBlocked = "Notifications blocked"
    override val showLevels = "Show message levels"
    override val tabAbout = "About"
    override val tabMembers = "Members"
    override val tabManage = "Manage"
    override val write = "Write"
    override val share = "Share"
    override val leave = "Leave"
    override val transferTitle = "Transfer ownership"
    override val transferAbout = "Choose who receives it: a moderator or above (in a community, an admin). You will become a member."
    override val transferNeedsOldKey = "Ownership can be transferred only with the key it was received with. Enter your previous secret phrase."
    override val transferNoCandidates = "Nobody to transfer to: appoint a moderator (in a community, an admin)"
    override val transferSent = "Offer sent, waiting 30 minutes for an answer"
    override val transferWithdraw = "Withdraw the offer"
    override fun transferPending(to: String) = "Waiting for: $to"
    override fun ownershipOffered(title: String, from: String) = "$from is transferring \"$title\" to you"
    override val ownershipAccepted = "You are the owner"
    override val phraseHint = "12 words of the secret phrase"
    override val phraseAbout = "You sign with your secret phrase. It never leaves the device and is not stored."
    override val badPhrase = "Wrong phrase"
    override val offerDropped = "Three wrong phrases: the offer is cancelled"
    override val receiverRole = "Only a moderator or above (in a community, an admin) can accept"
    override val ownColorTitle = "Colour of your own roles"
    override val ownColorAbout = "where you are the owner, an admin or a moderator, the title is shown in this colour"
    override val ownColorDefault = "Default: green"
    override val notifyTitle = "Notifications"
    override val manageMain = "Main"
    override val manageMainAbout = "title, “secret”"
    override val manageRoles = "Roles"
    override val manageRolesAbout = "appoint and remove"
    override val manageRequests = "Requests"
    override val manageRequestsAbout = "asking to join"
    override val manageAccess = "Access"
    override val manageAccessAbout = "level 3 and terms"
    override val manageMessages = "Messages"
    override val manageMessagesAbout = "super message, pinned"
    override val manageVoice = "Voice chats"
    override val manageVoiceAbout = "create, settings, list"
    override val manageAppeals = "Appeals"
    override val manageAppealsAbout = "conversations with people, “Accept messages”"
    override val manageDanger = "Danger zone"
    override val manageDangerAbout = "transfer ownership, delete"
    override val titleHint = "Title"
    override val save = "Save"
    override val saved = "Saved"
    override val deleteEntity = "Delete"
    override val deleteAbout = "Deletion happens in a month: until then it can be cancelled and everything works as before"
    override val cancelDeletion = "Cancel deletion"
    override fun willBeDeleted(date: String) = "Will be deleted on $date"
    override val answersEmpty = "Nobody has answered you yet and you have written to nobody"
    override val notAccepting = "It does not accept messages"
    override val threadBlocked = "Messages are blocked"
    override val peopleLimit = "Already written to five people today — no more until tomorrow"
    override val writeHint = "Message"
    override val send = "Send"
    override val acceptMessages = "Accept messages"
    override fun peopleToday(n: Int, limit: Int) = "Written to today: $n of $limit"
    override val blockedMark = "blocked"
    override val blockPerson = "Block this person"
    override val unblockPerson = "Unblock"
    override val threadAbout = "Conversation with an entity is not end-to-end encrypted: those who write for it read it"
    override val voiceHost = "host"
    override val voicePaused = "Paused: a call is on"
    override val voiceNoE2e = "Sound is not end-to-end encrypted, nothing is recorded"
    override val voiceText = "Text"
    override val voiceTakeHost = "Become the host"
    override val micOn = "Turn microphone on"
    override val micOff = "Turn microphone off"
    override val soundOn = "Turn sound on"
    override val soundOff = "Turn sound off"
    override val voiceChats = "Voice chats"
    override val voiceNewHint = "Voice chat title"
    override val voiceCreate = "Create"
    override val voiceAllowMute = "Participants may turn microphone and sound off"
    override val voiceHostAllowed = "Host allowed"
    override val voiceHostKicks = "Host may remove people"
    override val voiceFull = "The voice chat is full"
    override val voiceKicked = "You were removed from this conversation"
    override fun voiceInside(n: Int) = "inside: $n"
    override fun voiceMax(n: Int) = "maximum participants: $n"
    override val pinnedGroup = "A group of messages is pinned."
    override val pinsDefaultTitle = "Message group"
    override val pinsDeleteGroup = "Delete the group"
    override val pinsDeleteSome = "Delete messages"
    override val pinsAdd = "Add"
    override val pinsReplace = "Replace"
    override val pinsClearAll = "Delete all"
    override val pinsAlready = "Already pinned"
    override val pinsFull = "20 pinned — remove some first"
    override val cancel = "Cancel"
    override fun pinsDeleteChosen(n: Int) = "Delete selected: $n"
    override val makeModerator = "Make moderator"
    override val makeAdmin = "Make admin"
    override val makeMember = "Make member"
    override val banDay = "Ban for a day"
}

object SpanishSociumWords : SociumWords {
    override val personalGroup = "grupo personal"
    override val publicGroup = "grupo público"
    override val secretGroup = "grupo secreto"
    override val publicChannel = "canal público"
    override val channel = "canal"
    override val secretChannel = "canal secreto"
    override val community = "comunidad"
    override val secretCommunity = "comunidad secreta"
    override val voiceChat = "chat de voz"
    override val secret = "Secreto"
    override val secretAbout = "no aparece en búsquedas ni en feeds; solo se entra por invitación"
    override val invite = "Invitar"
    override val invited = "Invitación enviada"
    override val inviteAccept = "Aceptar"
    override val inviteDecline = "Rechazar"
    override val inviteAccepted = "Aceptaste la invitación"
    override val inviteDeclined = "La rechazaste"
    override val inviteGone = "La invitación ya no existe"
    override val invitesTitle = "Invitaciones"
    override fun inviteCard(title: String) = "Invitación: $title"
    override val subscribed = "Suscrito"
    override val notSubscribed = "No suscrito"
    override val member = "Miembro"
    override val notMember = "No es miembro"
    override val notifyOn = "Notificaciones activadas"
    override val notifyOff = "Notificaciones desactivadas"
    override val notifyBlocked = "Notificaciones bloqueadas"
    override val showLevels = "Mostrar nivel de mensajes"
    override val tabAbout = "Acerca de"
    override val tabMembers = "Miembros"
    override val tabManage = "Gestión"
    override val write = "Escribir"
    override val share = "Compartir"
    override val leave = "Salir"
    override val transferTitle = "Transferir la propiedad"
    override val transferAbout = "Elige a quién: puede aceptar un moderador o superior (en una comunidad, un administrador). Pasarás a ser miembro."
    override val transferNeedsOldKey = "Solo se transfiere con la clave con la que se recibió. Introduce tu frase secreta anterior."
    override val transferNoCandidates = "No hay a quién transferir: nombra un moderador (en una comunidad, un administrador)"
    override val transferSent = "Oferta enviada, espera respuesta 30 minutos"
    override val transferWithdraw = "Retirar la oferta"
    override fun transferPending(to: String) = "Esperando a: $to"
    override fun ownershipOffered(title: String, from: String) = "$from te transfiere «$title»"
    override val ownershipAccepted = "Eres el propietario"
    override val phraseHint = "12 palabras de la frase secreta"
    override val phraseAbout = "Firmas con tu frase secreta. No sale del dispositivo ni se guarda."
    override val badPhrase = "Frase incorrecta"
    override val offerDropped = "Tres frases incorrectas: la oferta se canceló"
    override val receiverRole = "Solo un moderador o superior (en una comunidad, un administrador) puede aceptar"
    override val ownColorTitle = "Color de tus roles"
    override val ownColorAbout = "donde eres propietario, administrador o moderador, el título va en este color"
    override val ownColorDefault = "Por defecto: verde"
    override val notifyTitle = "Notificaciones"
    override val manageMain = "Principal"
    override val manageMainAbout = "nombre, «secreto»"
    override val manageRoles = "Roles"
    override val manageRolesAbout = "asignar y quitar"
    override val manageRequests = "Solicitudes"
    override val manageRequestsAbout = "piden entrar"
    override val manageAccess = "Acceso"
    override val manageAccessAbout = "nivel 3 y plazos"
    override val manageMessages = "Mensajes"
    override val manageMessagesAbout = "supermensaje, fijados"
    override val manageVoice = "Chats de voz"
    override val manageVoiceAbout = "crear, ajustes, lista"
    override val manageAppeals = "Consultas"
    override val manageAppealsAbout = "conversaciones con personas, «Aceptar mensajes»"
    override val manageDanger = "Zona peligrosa"
    override val manageDangerAbout = "transferir la propiedad, eliminar"
    override val titleHint = "Nombre"
    override val save = "Guardar"
    override val saved = "Guardado"
    override val deleteEntity = "Eliminar"
    override val deleteAbout = "Se elimina en un mes: hasta entonces se puede cancelar y todo funciona como antes"
    override val cancelDeletion = "Cancelar la eliminación"
    override fun willBeDeleted(date: String) = "Se eliminará el $date"
    override val answersEmpty = "Aún nadie te respondió y no escribiste a nadie"
    override val notAccepting = "No acepta mensajes"
    override val threadBlocked = "Mensajes bloqueados"
    override val peopleLimit = "Hoy ya se escribió a cinco personas: no más hasta mañana"
    override val writeHint = "Mensaje"
    override val send = "Enviar"
    override val acceptMessages = "Aceptar mensajes"
    override fun peopleToday(n: Int, limit: Int) = "Hoy se escribió a: $n de $limit"
    override val blockedMark = "bloq."
    override val blockPerson = "Bloquear a la persona"
    override val unblockPerson = "Desbloquear"
    override val threadAbout = "La conversación con una entidad no está cifrada de extremo a extremo: la leen quienes escriben por ella"
    override val voiceHost = "anfitrión"
    override val voicePaused = "En pausa: hay una llamada"
    override val voiceNoE2e = "El sonido no está cifrado de extremo a extremo, no se graba"
    override val voiceText = "Texto"
    override val voiceTakeHost = "Ser anfitrión"
    override val micOn = "Activar micrófono"
    override val micOff = "Silenciar micrófono"
    override val soundOn = "Activar sonido"
    override val soundOff = "Silenciar sonido"
    override val voiceChats = "Chats de voz"
    override val voiceNewHint = "Nombre del chat de voz"
    override val voiceCreate = "Crear"
    override val voiceAllowMute = "Los participantes pueden silenciar micrófono y sonido"
    override val voiceHostAllowed = "Anfitrión permitido"
    override val voiceHostKicks = "El anfitrión puede expulsar"
    override val voiceFull = "El chat de voz está lleno"
    override val voiceKicked = "Te expulsaron de esta conversación"
    override fun voiceInside(n: Int) = "dentro: $n"
    override fun voiceMax(n: Int) = "máximo de participantes: $n"
    override val pinnedGroup = "Hay un grupo de mensajes fijado."
    override val pinsDefaultTitle = "Grupo de mensajes"
    override val pinsDeleteGroup = "Eliminar el grupo"
    override val pinsDeleteSome = "Eliminar mensajes"
    override val pinsAdd = "Añadir"
    override val pinsReplace = "Reemplazar"
    override val pinsClearAll = "Eliminar todos"
    override val pinsAlready = "Ya fijado"
    override val pinsFull = "20 fijados: quita alguno"
    override val cancel = "Cancelar"
    override fun pinsDeleteChosen(n: Int) = "Eliminar seleccionados: $n"
    override val makeModerator = "Hacer moderador"
    override val makeAdmin = "Hacer administrador"
    override val makeMember = "Hacer miembro"
    override val banDay = "Bloquear un día"
}
