package io.tima.core.words

/**
 * Слова группового звонка (ПЛАН-ГРУППОВЫХ-ЗВОНКОВ.md, решения заказчика 2026-10-01).
 *
 * Отдельным файлом, а не строками в `CallWords`: их много, и все про одно — групповой
 * звонок, его настройку, журнал звонка и приглашение. Три языка рядом: новое слово не
 * забудется ни в одном.
 */
interface GroupCallWords {
    /** «Групповой звонок» — заголовок настройки, имя временной группы, пункт меню. */
    val title: String

    // ── Настройка звонка (решения 3, 3б) ──
    val ring: String
    val ringAbout: String
    val video: String
    val videoAbout: String
    val pickFromContacts: String
    val pickFromContactsAbout: String
    val pickFromGroup: String
    val pickFromGroupAbout: String
    val createChat: String
    val createChatAbout: String
    val noRights: String

    // ── Журнал звонка (решение 8) ──
    val ledgerTitle: String
    val participants: String
    val allContacts: String
    val groupMembers: String
    fun picked(count: Int, max: Int): String
    fun tooMany(max: Int): String
    val callNow: String
    val createChatNow: String
    val add: String
    val remove: String
    val mic: String
    val camera: String
    val pause: String
    val resume: String
    val stop: String
    val onlyCreator: String
    val stateIn: String
    val stateInvited: String
    val stateSelf: String
    val stateLeft: String
    val stateRemoved: String
    val stateAdded: String
    val creator: String
    val nobody: String

    // «?» журнала звонка — что делает каждая кнопка.
    val helpTitle: String
    val helpAdd: String
    val helpRemove: String
    val helpMic: String
    val helpCamera: String
    val helpPause: String
    val helpStop: String
    val helpRow: String
    val helpRowTitle: String
    val helpSections: String
    val helpSectionsTitle: String

    // ── Окно звонка ──
    fun count(count: Int, max: Int): String
    val leave: String
    val stopAll: String
    val hangUpQuestion: String
    val alone: String
    val pausedBanner: String
    fun voiceOnly(upTo: Int): String
    val pausedWait: String
    val paused: String
    val resumed: String
    val controlFailed: String
    val stopped: String
    val mutedMic: String
    val mutedVideo: String
    val allowedMic: String
    val allowedVideo: String
    val watching: String
    val forbiddenMic: String
    val forbiddenVideo: String
    val removed: String

    // Журнал стенда группового звонка (заказчик 2026-10-01, 5а).
    val benchJournal: String
    val benchSet: String
    val benchReceive: String
    val benchFrame: String
    val benchCodec: String
    val benchKbit: String
    val benchDecodeMs: String
    val benchDropped: String
    val benchFreezes: String
    val benchUnknown: String
    val benchNothing: String

    // Строки звонка в переписке группы — рисует телефон сам (заказчик 2026-10-01, 8б).
    fun lineStarted(who: String): String
    fun lineParticipants(who: String): String
    val lineEnded: String
    val linePaused: String
    val lineResumed: String
    val lineRemoved: String

    // Вид окна группового (заказчик 2026-10-01).
    val viewButton: String
    val viewOne: String
    val viewTwo: String
    val viewFour: String
    val viewSelf: String
    val back: String
    val voiceTitle: String
    val viewSpeaker: String
    val viewGrid: String
    /** Значок группы звонка поверх аватара создателя (заказчик 2026-10-02). */
    val badge: String
    val collapse: String
    val viewVertical: String
    val viewHorizontal: String
    val viewVerticalAbout: String
    val viewHorizontalAbout: String
    val viewType: String
    val viewSpeakerAbout: String
    val viewGridAbout: String
    val viewSelfAbout: String
    val createCall: String
    val benchFps: String
    val benchQp: String
    val viewAbout: String
    val viewSelfOn: String
    val viewSelfOff: String
    val voiceButton: String
    val voiceForbidden: String
    val pinnedMark: String

    // Клетка участника: его видео пропало (заказчик 2026-10-01).
    val tileVideoNotArriving: String
    fun tileVideoNotDecoding(codec: String): String

    // ── Группа и приглашение (решения 3а, 10, 11) ──
    val start: String
    val join: String
    fun live(inRoom: Int, minutes: Int): String
    val livePaused: String
    fun inviteLine(title: String): String
    val inviteEnded: String
    val inviteChecking: String
    fun ttl(hours: Int): String
    val full: String
    val removedCantJoin: String
    val created: String
    val createFailed: String
}

object RussianGroupCall : GroupCallWords {
    override val title = "Групповой звонок"
    override val ring = "Звонить"
    override val ringAbout = "Один вызов отмеченным; кто не ответил — повторно не звоним, ему придёт приглашение"
    override val video = "Включить видео"
    override val videoAbout = "Камера включится сразу; видео принимаем только тех, кто на экране"
    override val pickFromContacts = "Выбрать участников по журналу контактов"
    override val pickFromContactsAbout = "Отметьте людей — звонок начнётся сразу"
    override val pickFromGroup = "Журнал группы"
    override val pickFromGroupAbout = "Отметьте участников группы — звонок начнётся сразу"
    override val createChat = "Создать чат группового звонка"
    override val createChatAbout = "Группа на 12 часов от последнего звонка; позвонить можно из неё, когда соберётесь"
    override val noRights = "Групповой звонок начинает модератор группы, а без них — владелец"

    override val ledgerTitle = "Журнал звонка"
    override val participants = "Участники"
    override val allContacts = "Все контакты"
    override val groupMembers = "Группа"
    override fun picked(count: Int, max: Int) = "Участников $count из $max"
    override fun tooMany(max: Int) = "В звонке не больше $max участников вместе с вами"
    override val callNow = "Позвонить"
    override val createChatNow = "Создать чат"
    override val add = "Добавить"
    override val remove = "Удалить"
    override val mic = "Микрофон"
    override val camera = "Камера"
    override val pause = "Пауза"
    override val resume = "Продолжить"
    override val stop = "Остановить"
    override val onlyCreator = "Командует создатель звонка — у себя микрофон и камеру вы выключаете сами"
    override val stateIn = "в звонке"
    override val stateInvited = "позван"
    override val stateSelf = "вы"
    override val stateLeft = "вышел"
    override val stateRemoved = "удалён из звонка"
    override val stateAdded = "добавлен"
    override val creator = "создатель"
    override val nobody = "Пока никого — отметьте людей и нажмите «Добавить»"

    override val helpTitle = "Что делают кнопки"
    override val helpAdd = "Отмеченных — в участники; в идущем звонке — позвать отмеченных участников группы"
    override val helpRemove = "Отмеченных — из звонка. Из группы человек не удаляется"
    override val helpMic = "Запретить отмеченным микрофон: сервер перестаёт принимать их звук, пока вы не разрешите. Нажать снова — разрешить"
    override val helpCamera = "Запретить отмеченным видео — так же; запрещено и то и другое — человек только смотрит и слушает"
    override val helpPause = "Пауза для всех: все остаются в звонке, звук и видео стоят; «Продолжить» — снять"
    override val helpStop = "Завершить звонок для всех"
    override val helpRowTitle = "Микрофон и камера в строке"
    override val helpRow = "У создателя — у каждого участника: нажатие запрещает или разрешает; у остальных — только у себя"
    override val helpSectionsTitle = "Разделы"
    override val helpSections = "«Участники» — кто добавлен в звонок; остальные разделы — кого можно добавить"

    override fun count(count: Int, max: Int) = "$count из $max"
    override val leave = "Выйти из звонка"
    override val stopAll = "Завершить для всех"
    override val hangUpQuestion = "Выйти самому или завершить звонок для всех?"
    override val alone = "Пока вы одни — позванные войдут по вызову или приглашению"
    override val pausedBanner = "Пауза — звук и видео стоят у всех"
    override fun voiceOnly(upTo: Int) = "Участников больше $upTo — только голос: видео выключено у всех"
    override val pausedWait = "Звонок на паузе — камера включится, когда создатель продолжит"
    override val paused = "Создатель поставил звонок на паузу"
    override val resumed = "Звонок продолжается"
    override val controlFailed = "Команда не прошла — нет связи с сервером"
    override val stopped = "Вы завершили звонок для всех"
    override val mutedMic = "Создатель запретил вам микрофон — вас не слышно, пока он не разрешит"
    override val mutedVideo = "Создатель запретил вам видео — вас не видно, пока он не разрешит"
    override val allowedMic = "Создатель разрешил микрофон — включите его кнопкой"
    override val allowedVideo = "Создатель разрешил видео — включите камеру кнопкой"
    override val watching = "Вы смотрите и слушаете звонок; писать можно в группе звонка"
    override val forbiddenMic = "микрофон запрещён"
    override val forbiddenVideo = "видео запрещено"
    override val removed = "Создатель удалил вас из звонка"
    override val benchJournal = "Журнал стенда"
    override val benchSet = "Набор"
    override val benchReceive = "Что принимаю"
    override val benchFrame = "Кадр"
    override val benchCodec = "Кодек"
    override val benchKbit = "кбит/с"
    override val benchDecodeMs = "Раскод мс"
    override val benchDropped = "Выброшено"
    override val benchFreezes = "Замирания"
    override val benchUnknown = "не сказал — версия старее"
    override val benchNothing = "видео от него не приходит"
    override fun lineStarted(who: String) = "Звонок начат: $who"
    override fun lineParticipants(who: String) = "Участники: $who"
    override val lineEnded = "Звонок завершён"
    override val linePaused = "Пауза звонка"
    override val lineResumed = "Звонок продолжается"
    override val lineRemoved = "Вас удалили из звонка"
    override val viewButton = "Вид"
    override val viewOne = "По одному"
    override val viewTwo = "По 2"
    override val viewFour = "По 4"
    override val viewSelf = "Показывать себя"
    override val back = "Назад"
    override val voiceTitle = "Голосом"
    override val viewSpeaker = "Говорящий"
    override val viewAbout = "Как показывать участников в звонке"
    override val viewSelfOn = "себя показываем"
    override val viewSelfOff = "себя не показываем"
    override val voiceButton = "Голос"
    override val voiceForbidden = "голос запрещён"
    override val pinnedMark = "закреплён"
    override val viewGrid = "Сетка"
    override val viewType = "Тип"
    override val viewSpeakerAbout = "Наверху — двое говорящих, внизу — все участники"
    override val viewGridAbout = "Участники на странице, листать «‹ ›»"
    override val viewSelfAbout = "Включено — малое окно «Я» справа внизу; выключено — я в сетке первым"
    override val createCall = "Создать звонок"
    override val benchFps = "Кадров/с"
    override val benchQp = "Качество (QP)"
    override val viewVertical = "Вертикально"
    override val viewHorizontal = "Горизонтально"
    override val viewVerticalAbout = "Говорящие один над другим, участники внизу"
    override val viewHorizontalAbout = "Говорящие рядом, участники двумя строками снизу"
    override val collapse = "Свернуть"
    override val badge = "ГЗ"
    override val tileVideoNotArriving = "Видео не приходит"
    override fun tileVideoNotDecoding(codec: String) = "Видео $codec не раскодируется"

    override val start = "Совершить групповой звонок"
    override val join = "Присоединиться"
    override fun live(inRoom: Int, minutes: Int) = "Идёт звонок · в звонке $inRoom · $minutes мин"
    override val livePaused = "Звонок на паузе"
    override fun inviteLine(title: String) = "Групповой звонок · $title"
    override val inviteEnded = "Звонок завершён"
    override val inviteChecking = "Узнаём, идёт ли звонок…"
    override fun ttl(hours: Int) = if (hours < 1) "удалится меньше чем через час" else "удалится через $hours ч"
    override val full = "В звонке нет мест"
    override val removedCantJoin = "Создатель удалил вас из этого звонка"
    override val created = "Чат группового звонка создан — позвонить можно из него"
    override val createFailed = "Чат не создан — нет связи с сервером"
}

object EnglishGroupCall : GroupCallWords {
    override val title = "Group call"
    override val ring = "Ring"
    override val ringAbout = "One ring to the chosen people; those who miss it are not called again — they get an invitation"
    override val video = "Turn on video"
    override val videoAbout = "The camera starts right away; video comes only from those on screen"
    override val pickFromContacts = "Choose people from the contact ledger"
    override val pickFromContactsAbout = "Tick people — the call starts right away"
    override val pickFromGroup = "Group ledger"
    override val pickFromGroupAbout = "Tick group members — the call starts right away"
    override val createChat = "Create a group call chat"
    override val createChatAbout = "A group for 12 hours after the last call; call from it when everyone is ready"
    override val noRights = "A group call is started by a moderator, or by the owner if there are none"

    override val ledgerTitle = "Call ledger"
    override val participants = "Participants"
    override val allContacts = "All contacts"
    override val groupMembers = "Group"
    override fun picked(count: Int, max: Int) = "Participants $count of $max"
    override fun tooMany(max: Int) = "No more than $max people in a call, you included"
    override val callNow = "Call"
    override val createChatNow = "Create chat"
    override val add = "Add"
    override val remove = "Remove"
    override val mic = "Microphone"
    override val camera = "Camera"
    override val pause = "Pause"
    override val resume = "Resume"
    override val stop = "Stop"
    override val onlyCreator = "The call creator is in charge — you turn your own microphone and camera off yourself"
    override val stateIn = "in the call"
    override val stateInvited = "called"
    override val stateSelf = "you"
    override val stateLeft = "left"
    override val stateRemoved = "removed from the call"
    override val stateAdded = "added"
    override val creator = "creator"
    override val nobody = "Nobody yet — tick people and tap “Add”"

    override val helpTitle = "What the buttons do"
    override val helpAdd = "Ticked people become participants; during a call — call the ticked group members"
    override val helpRemove = "Remove the ticked from the call. They stay in the group"
    override val helpMic = "Forbid the ticked people's microphone: the server stops taking their sound until you allow it. Tap again to allow"
    override val helpCamera = "Forbid the ticked people's video the same way; with both forbidden a person only watches and listens"
    override val helpPause = "Pause for everyone: all stay in the call, sound and video stop; “Resume” lifts it"
    override val helpStop = "End the call for everyone"
    override val helpRowTitle = "Microphone and camera in a row"
    override val helpRow = "The creator has them for every participant: a tap forbids or allows; others only for themselves"
    override val helpSectionsTitle = "Sections"
    override val helpSections = "“Participants” — who is in the call; the other sections — who can be added"

    override fun count(count: Int, max: Int) = "$count of $max"
    override val leave = "Leave the call"
    override val stopAll = "End for everyone"
    override val hangUpQuestion = "Leave yourself or end the call for everyone?"
    override val alone = "You are alone so far — the others join by the ring or the invitation"
    override val pausedBanner = "Paused — sound and video stopped for everyone"
    override fun voiceOnly(upTo: Int) = "More than $upTo people — voice only: video is off for everyone"
    override val pausedWait = "The call is paused — the camera turns on when the creator resumes"
    override val paused = "The creator paused the call"
    override val resumed = "The call continues"
    override val controlFailed = "The command did not go through — no connection to the server"
    override val stopped = "You ended the call for everyone"
    override val mutedMic = "The creator forbade your microphone — nobody hears you until it is allowed"
    override val mutedVideo = "The creator forbade your video — nobody sees you until it is allowed"
    override val allowedMic = "The creator allowed your microphone — turn it on with the button"
    override val allowedVideo = "The creator allowed your video — turn the camera on with the button"
    override val watching = "You watch and listen to the call; you can write in the call's group"
    override val forbiddenMic = "microphone forbidden"
    override val forbiddenVideo = "video forbidden"
    override val removed = "The creator removed you from the call"
    override val benchJournal = "Test log"
    override val benchSet = "Set"
    override val benchReceive = "What I receive"
    override val benchFrame = "Frame"
    override val benchCodec = "Codec"
    override val benchKbit = "kbit/s"
    override val benchDecodeMs = "Decode ms"
    override val benchDropped = "Dropped"
    override val benchFreezes = "Freezes"
    override val benchUnknown = "did not say — older version"
    override val benchNothing = "no video arriving from them"
    override fun lineStarted(who: String) = "Call started: $who"
    override fun lineParticipants(who: String) = "Participants: $who"
    override val lineEnded = "Call ended"
    override val linePaused = "Call paused"
    override val lineResumed = "Call continues"
    override val lineRemoved = "You were removed from the call"
    override val viewButton = "View"
    override val viewOne = "One"
    override val viewTwo = "By 2"
    override val viewFour = "By 4"
    override val viewSelf = "Show myself"
    override val back = "Back"
    override val voiceTitle = "Voice only"
    override val viewSpeaker = "Speaker"
    override val viewAbout = "How to show the call participants"
    override val viewSelfOn = "I show myself"
    override val viewSelfOff = "I hide myself"
    override val voiceButton = "Voice"
    override val voiceForbidden = "voice forbidden"
    override val pinnedMark = "pinned"
    override val viewGrid = "Grid"
    override val viewType = "Type"
    override val viewSpeakerAbout = "Two speakers on top, everyone below"
    override val viewGridAbout = "Participants per page, flip with «‹ ›»"
    override val viewSelfAbout = "On — a small “Me” window at the bottom right; off — me first in the grid"
    override val createCall = "Start a call"
    override val benchFps = "Frames/s"
    override val benchQp = "Quality (QP)"
    override val viewVertical = "Vertical"
    override val viewHorizontal = "Horizontal"
    override val viewVerticalAbout = "Speakers one above the other, participants below"
    override val viewHorizontalAbout = "Speakers side by side, participants in two rows below"
    override val collapse = "Collapse"
    override val badge = "GC"
    override val tileVideoNotArriving = "Video is not arriving"
    override fun tileVideoNotDecoding(codec: String) = "Cannot decode $codec video"

    override val start = "Start a group call"
    override val join = "Join"
    override fun live(inRoom: Int, minutes: Int) = "Call in progress · $inRoom in the call · $minutes min"
    override val livePaused = "The call is paused"
    override fun inviteLine(title: String) = "Group call · $title"
    override val inviteEnded = "The call has ended"
    override val inviteChecking = "Checking whether the call is on…"
    override fun ttl(hours: Int) = if (hours < 1) "deleted in less than an hour" else "deleted in $hours h"
    override val full = "The call is full"
    override val removedCantJoin = "The creator removed you from this call"
    override val created = "The group call chat is created — call from it"
    override val createFailed = "The chat was not created — no connection to the server"
}

object SpanishGroupCall : GroupCallWords {
    override val title = "Llamada grupal"
    override val ring = "Llamar"
    override val ringAbout = "Una llamada a los marcados; a quien no conteste no se le vuelve a llamar — le llega una invitación"
    override val video = "Activar vídeo"
    override val videoAbout = "La cámara se enciende enseguida; solo recibimos el vídeo de quienes están en pantalla"
    override val pickFromContacts = "Elegir participantes del registro de contactos"
    override val pickFromContactsAbout = "Marque a las personas — la llamada empieza enseguida"
    override val pickFromGroup = "Registro del grupo"
    override val pickFromGroupAbout = "Marque a miembros del grupo — la llamada empieza enseguida"
    override val createChat = "Crear chat de llamada grupal"
    override val createChatAbout = "Un grupo por 12 horas desde la última llamada; llame desde él cuando estén listos"
    override val noRights = "La llamada grupal la inicia un moderador, o el propietario si no hay"

    override val ledgerTitle = "Registro de la llamada"
    override val participants = "Participantes"
    override val allContacts = "Todos los contactos"
    override val groupMembers = "Grupo"
    override fun picked(count: Int, max: Int) = "Participantes $count de $max"
    override fun tooMany(max: Int) = "No más de $max personas en la llamada, usted incluido"
    override val callNow = "Llamar"
    override val createChatNow = "Crear chat"
    override val add = "Añadir"
    override val remove = "Quitar"
    override val mic = "Micrófono"
    override val camera = "Cámara"
    override val pause = "Pausa"
    override val resume = "Continuar"
    override val stop = "Detener"
    override val onlyCreator = "Manda el creador de la llamada — su micrófono y su cámara los apaga usted mismo"
    override val stateIn = "en la llamada"
    override val stateInvited = "llamado"
    override val stateSelf = "usted"
    override val stateLeft = "salió"
    override val stateRemoved = "quitado de la llamada"
    override val stateAdded = "añadido"
    override val creator = "creador"
    override val nobody = "Nadie todavía — marque personas y pulse «Añadir»"

    override val helpTitle = "Qué hacen los botones"
    override val helpAdd = "Los marcados pasan a participantes; durante la llamada — llamar a los miembros marcados"
    override val helpRemove = "Quitar a los marcados de la llamada. Siguen en el grupo"
    override val helpMic = "Prohibir el micrófono a los marcados: el servidor deja de recibir su sonido hasta que usted lo permita. Pulsar otra vez — permitir"
    override val helpCamera = "Prohibir el vídeo a los marcados igual; con ambos prohibidos la persona solo mira y escucha"
    override val helpPause = "Pausa para todos: siguen en la llamada, sonido y vídeo parados; «Continuar» la quita"
    override val helpStop = "Terminar la llamada para todos"
    override val helpRowTitle = "Micrófono y cámara en la fila"
    override val helpRow = "El creador los tiene para cada participante: pulsar prohíbe o permite; los demás, solo para sí mismos"
    override val helpSectionsTitle = "Secciones"
    override val helpSections = "«Participantes» — quién está en la llamada; las demás — a quién se puede añadir"

    override fun count(count: Int, max: Int) = "$count de $max"
    override val leave = "Salir de la llamada"
    override val stopAll = "Terminar para todos"
    override val hangUpQuestion = "¿Salir usted o terminar la llamada para todos?"
    override val alone = "Por ahora está solo — los demás entran por la llamada o la invitación"
    override val pausedBanner = "Pausa — sonido y vídeo parados para todos"
    override fun voiceOnly(upTo: Int) = "Más de $upTo personas — solo voz: el vídeo está apagado para todos"
    override val pausedWait = "La llamada está en pausa — la cámara se encenderá cuando el creador continúe"
    override val paused = "El creador pausó la llamada"
    override val resumed = "La llamada continúa"
    override val controlFailed = "La orden no pasó — sin conexión con el servidor"
    override val stopped = "Ha terminado la llamada para todos"
    override val mutedMic = "El creador le prohibió el micrófono — no se le oye hasta que lo permita"
    override val mutedVideo = "El creador le prohibió el vídeo — no se le ve hasta que lo permita"
    override val allowedMic = "El creador permitió su micrófono — enciéndalo con el botón"
    override val allowedVideo = "El creador permitió su vídeo — encienda la cámara con el botón"
    override val watching = "Mira y escucha la llamada; puede escribir en el grupo de la llamada"
    override val forbiddenMic = "micrófono prohibido"
    override val forbiddenVideo = "vídeo prohibido"
    override val removed = "El creador le quitó de la llamada"
    override val benchJournal = "Registro de pruebas"
    override val benchSet = "Conjunto"
    override val benchReceive = "Lo que recibo"
    override val benchFrame = "Cuadro"
    override val benchCodec = "Códec"
    override val benchKbit = "kbit/s"
    override val benchDecodeMs = "Decodif. ms"
    override val benchDropped = "Descartados"
    override val benchFreezes = "Congelaciones"
    override val benchUnknown = "no lo dijo — versión antigua"
    override val benchNothing = "no llega vídeo suyo"
    override fun lineStarted(who: String) = "Llamada iniciada: $who"
    override fun lineParticipants(who: String) = "Participantes: $who"
    override val lineEnded = "Llamada terminada"
    override val linePaused = "Llamada en pausa"
    override val lineResumed = "La llamada continúa"
    override val lineRemoved = "Le quitaron de la llamada"
    override val viewButton = "Vista"
    override val viewOne = "De uno"
    override val viewTwo = "De 2"
    override val viewFour = "De 4"
    override val viewSelf = "Mostrarme"
    override val back = "Atrás"
    override val voiceTitle = "Solo voz"
    override val viewSpeaker = "Quien habla"
    override val viewAbout = "Cómo mostrar a los participantes"
    override val viewSelfOn = "me muestro"
    override val viewSelfOff = "no me muestro"
    override val voiceButton = "Voz"
    override val voiceForbidden = "voz prohibida"
    override val pinnedMark = "fijado"
    override val viewGrid = "Cuadrícula"
    override val viewType = "Tipo"
    override val viewSpeakerAbout = "Arriba, dos que hablan; abajo, todos"
    override val viewGridAbout = "Participantes por página, pasar con «‹ ›»"
    override val viewSelfAbout = "Activado — ventana pequeña «Yo» abajo a la derecha; desactivado — yo primero en la cuadrícula"
    override val createCall = "Crear llamada"
    override val benchFps = "Cuadros/s"
    override val benchQp = "Calidad (QP)"
    override val viewVertical = "Vertical"
    override val viewHorizontal = "Horizontal"
    override val viewVerticalAbout = "Quienes hablan uno sobre otro, participantes abajo"
    override val viewHorizontalAbout = "Quienes hablan lado a lado, participantes en dos filas abajo"
    override val collapse = "Plegar"
    override val badge = "LG"
    override val tileVideoNotArriving = "El vídeo no llega"
    override fun tileVideoNotDecoding(codec: String) = "No se puede decodificar el vídeo $codec"

    override val start = "Hacer una llamada grupal"
    override val join = "Unirse"
    override fun live(inRoom: Int, minutes: Int) = "Llamada en curso · $inRoom en la llamada · $minutes min"
    override val livePaused = "La llamada está en pausa"
    override fun inviteLine(title: String) = "Llamada grupal · $title"
    override val inviteEnded = "La llamada ha terminado"
    override val inviteChecking = "Comprobando si la llamada sigue…"
    override fun ttl(hours: Int) = if (hours < 1) "se borra en menos de una hora" else "se borra en $hours h"
    override val full = "La llamada está llena"
    override val removedCantJoin = "El creador le quitó de esta llamada"
    override val created = "El chat de la llamada grupal está creado — llame desde él"
    override val createFailed = "El chat no se creó — sin conexión con el servidor"
}
