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
}
