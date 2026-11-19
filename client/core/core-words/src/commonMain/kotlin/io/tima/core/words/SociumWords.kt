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
}
