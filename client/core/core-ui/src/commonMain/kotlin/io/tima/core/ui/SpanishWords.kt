package io.tima.core.ui

/**
 * Испанский словарь (ПЛАН-ЯЗЫКА Я10).
 *
 * Написан по русскому оригиналу и сверен с английским: там, где английский пришлось
 * писать заново по смыслу, испанский написан по той же мысли, а не по английской фразе —
 * перевод перевода уводит дважды.
 *
 * ── ЧТО ЗАКРЕПЛЕНО ──────────────────────────────────────────────────────────
 *
 * | Русское | Испанское | Почему |
 * |---|---|---|
 * | секретная фраза | frase de recuperación | как и в английском: это ключ восстановления |
 * | круг сообщения | audiencia | «círculo» здесь ничего не значит |
 * | сузить | restringir | не «reducir»: речь о правах, а не о размере |
 * | внести / вынуть | vincular / desvincular | «añadir» смешалось бы с участниками |
 * | журнал | registro | техническая запись, не дневник |
 * | лента | novedades | «feed» в интерфейсе испанцы читают как англицизм |
 *
 * Обращение — **usted**, а не «tú». Мессенджер говорит с незнакомым человеком, и в
 * половине испаноязычных стран «tú» от приложения читается как развязность.
 *
 * ── ФОРМЫ ЧИСЛА ─────────────────────────────────────────────────────────────
 *
 * Как у английского, две: «1 mes» / «3 meses». Правило написано здесь же — там, где
 * живут слова языка.
 *
 * ── ДЛИНА ───────────────────────────────────────────────────────────────────
 *
 * Испанский длиннее русского почти везде, и это ловится не глазами, а `RailWidthTest` и
 * `RowFitTest`: они меряют каждый заведённый словарь.
 */
object SpanishWords : Words {
    override val tag = "es"
    override val ownName = "Español"

    override val common = object : CommonWords {
        override val back = "Atrás"
        override val cancel = "Cancelar"
        override val ready = "Listo"
        override val send = "Enviar"
        override val hide = "Ocultar"
        override val noConnection = "Sin conexión con el servidor"
        override val nothingChosen = "Nada seleccionado"
    }

    override val settings = object : SettingsWords {
        override val settings = "Ajustes"
        override val language = "Idioma"
        override val languageAbout = "Idioma de la aplicación. Los mensajes no se traducen"
        override val appLanguage = "Idioma de la aplicación"
        override val country = "País"
        override val countryAbout =
            "El servidor lo usa para elegir lo que verá: lo suyo, no el mundo entero. " +
                "Vacío significa mostrarlo todo"
        override val countryHint = "ES"
        override val whatToShow = "Qué mostrar"
        override val onlyMyCountry = "Solo mi país"
        override val onlyMyLanguages = "Solo mis idiomas"
        override val filterNotForChats = "Esto no afecta a los chats ni a las novedades de amigos"
        override val on = "activado"
        override val off = "desactivado"
        override val chosen = "elegido"
        override val localeNotRead = "No se pudo leer el idioma y el país"
        override val localeNotSaved = "No se pudo guardar — inténtelo más tarde"
        override val soon = "pronto"
    }

    override val appearance = object : AppearanceWords {
        override fun theme(choice: ThemeChoice) = when (choice) {
            ThemeChoice.Light -> "Claro"
            ThemeChoice.Dark -> "Oscuro"
            ThemeChoice.Custom -> "Personalizado"
        }

        override fun slot(slot: ColorSlot) = when (slot) {
            ColorSlot.NAVIGATION -> "Navegación y acción"
            ColorSlot.ACTIVITY -> "Actividad"
            ColorSlot.CONFIRMED -> "Confirmado"
            ColorSlot.SURFACE -> "Fondo del contenido"
            ColorSlot.FUNCTIONAL -> "Fondo de los paneles"
            ColorSlot.TEXT -> "Texto"
            ColorSlot.TEXT_2 -> "Texto más suave"
            ColorSlot.TEXT_3 -> "Texto aún más suave"
            ColorSlot.MY -> "Mis mensajes"
            ColorSlot.AUTHOR -> "Mensajes de otros"
            ColorSlot.BORDER -> "Borde del mensaje"
            ColorSlot.LINE -> "Línea de la lista"
            ColorSlot.ON_ACCENT -> "Texto sobre verde"
            ColorSlot.ON_AMBER -> "Texto sobre ámbar"
            ColorSlot.IN_PLATE -> "Dentro de la placa"
            ColorSlot.SOFT_ACCENT -> "Fondo suave"
            ColorSlot.QUIET -> "Fondo neutro"
        }

        override fun about(slot: ColorSlot) = when (slot) {
            ColorSlot.NAVIGATION -> "logotipo, ventana actual, «atrás», «enviar»"
            ColorSlot.ACTIVITY -> "contador de no leídos"
            ColorSlot.CONFIRMED -> "entregado, leído, la marca E2E"
            ColorSlot.SURFACE -> "novedades y chat"
            ColorSlot.FUNCTIONAL -> "cabecera, pestañas, línea de entrada"
            ColorSlot.TEXT -> "principal"
            ColorSlot.TEXT_2 -> "leyendas, hora"
            ColorSlot.TEXT_3 -> "tercer nivel"
            ColorSlot.LINE -> "entre entradas"
            ColorSlot.ON_ACCENT -> "en botones, pestañas y la placa de la cabecera"
            ColorSlot.ON_AMBER -> "en el contador de no leídos"
            ColorSlot.IN_PLATE -> "logotipo y botones sobre verde claro"
            ColorSlot.SOFT_ACCENT -> "pestaña no elegida, campo de entrada"
            ColorSlot.QUIET -> "subpestaña no elegida, cápsula del selector"
            ColorSlot.MY, ColorSlot.AUTHOR, ColorSlot.BORDER -> ""
        }

        override fun colorTrouble(trouble: ColorTrouble) = when (trouble) {
            ColorTrouble.Empty -> "Vacío. Escriba un color: seis caracteres u ocho"
            is ColorTrouble.NotHex ->
                "Caracteres no hexadecimales: ${trouble.listed}. Se admiten 0–9 y A–F"
            is ColorTrouble.WrongLength ->
                "Hay ${trouble.length} caracteres y hacen falta 6 (color) u 8 (con opacidad)"
        }

        override val qrTooLong = "No se puede mostrar el código"
        override val qrTooLongAbout = "Es demasiado largo para un QR"
        override val theme = "Tema"
        override val colors = "Colores"
        override val palette = "Paleta"
        override val projectColors = "Colores del proyecto"
        override val customOnly =
            "Sus colores se muestran cuando está elegido «Personalizado». " +
                "Los cambios se conservan aunque vuelva al tema claro u oscuro."
        override val alphaHint =
            "Los dos primeros caracteres son la opacidad: FF opaco, 00 invisible"
        override val apply = "Aplicar"
        override val takeFromLight = "Tomar del claro"
        override val backToLight = "Restaurar el claro"
        override val backToDark = "Restaurar el oscuro"
        override val merged = "Así no se puede salir de aquí"

        override fun place(pair: VitalPair) = when (pair) {
            VitalPair.PLATE -> "el nombre de la ventana en la cabecera y la flecha «atrás»"
            VitalPair.CONTENT -> "el cambio de ventana y la lista de ajustes"
        }

        override fun mergedAbout(front: String, back: String, ratio: String, where: String) =
            "«$front» y «$back» se han fundido: $ratio : 1. Con ellos se dibuja $where — " +
                "sin ellos ya no se llega a la apariencia, así que «atrás» espera."
    }

    override val comments = object : CommentWords {
        override val comments = "Comentarios"
        override val thread = "Hilo"
        override val hint = "Escribir un comentario…"
        override val reply = "Responder"
        override val closed = "El debate está cerrado"
        override val closedButOldStay = "El debate está cerrado. Lo escrito antes permanece"
        override val nobodyWroteYet = "Aquí todavía no ha escrito nadie"
        override val postGone = "La entrada ya no existe"
        override val postGoneAbout = "La conversación se fue con ella"
        override val loading = "Cargando la conversación…"
    }

    override val trouble = object : TroubleWords {
        override val offline = "Sin conexión con el servidor"
        override fun refused(reason: String) = "El servidor lo rechazó: $reason"
        override val didNotReach = "No llegó al servidor. Inténtelo otra vez"
        override fun retryIn(seconds: Int) =
            "Sin conexión con el servidor — reintentamos en $seconds s"
        override val noConnection = "Sin conexión"
    }

    override val problem = object : ProblemWords {
        override val whatHappened = "Qué ha pasado"
        override val describeHint = "Descríbalo: qué hacía y qué salió mal"
        override val onlyYouKnow =
            "El registro muestra lo que ocurrió, pero no lo que usted esperaba — " +
                "eso solo puede contarlo usted."
        override val whenBegan = "Cuándo empezó"
        override val today = "Hoy"
        override val thisWeek = "Esta semana"
        override val earlier = "Antes"
        override val whatAbout = "De qué se trata"
        override val kindMessages = "Los mensajes no llegan / no salen"
        override val kindCalls = "Problemas con una llamada"
        override val kindLooks = "Cómo se ve la aplicación"
        override val kindOther = "Otra cosa"
        override val whatGoes = "Qué se adjuntará"
        override val whatGoesAbout =
            "Sus chats y archivos NO se envían. En el registro entran acciones y errores — " +
                "qué pulsó y qué respondió el servidor —, no el contenido de los mensajes."
        override val crashesSentThemselves =
            "Los informes de cierre inesperado los envía la propia aplicación, con el mismo contenido."
        override val watch = "Ver"
        override val stateNow = "Estado actual"
        override val whatHappenedLog = "Qué estaba ocurriendo"
        override val emptyDiary = "El registro está vacío"
        override val reportSent = "Informe enviado"
        override val sending = "Enviando…"
        override val send = "Enviar"
        override val writeAgainHow =
            "Para escribir de nuevo, salga y vuelva a abrir «Informar de un problema»."
        override val reportNumber = "Informe recibido, número:"
        override val nameItToSupport = "Indique este número si habla con el soporte técnico."
        override val willSendWhenOnline = "Lo enviaremos cuando haya conexión"
        override val willSendWhenOnlineAbout =
            "Ahora no hay red. El informe está guardado en el dispositivo y saldrá solo. " +
                "Puede cerrar la aplicación."
        override val couldNotSend = "No se pudo enviar — inténtelo otra vez"
        override val writeWhatHappened = "Cuente qué ha pasado — sin eso el informe no sale."
    }

    override val switching = object : SwitchingWords {
        override val accounts = "Cuentas"
        override val notSent = "sin enviar"
        override val virtualAccount = "Cuenta virtual"
        override val settingsHelpBugs = "Ajustes, ayuda, fallos"
        override val notSentSection = "Sin enviar"

        override fun waiting(howMany: Int) = if (howMany == 1) {
            "Un mensaje aún no ha salido."
        } else {
            "$howMany mensajes aún no han salido."
        }

        override val waitingAbout =
            "Mientras siga en esta cuenta, llegarán. Si se va, esperarán a que vuelva: " +
                "no se pueden enviar desde otra cuenta."
        override val waitForSending = "Esperar al envío"
        override val leaveNow = "Salir ahora"
    }

    override val windows = object : WindowWords {
        override fun full(window: Window) = when (window) {
            Window.Phone -> "Teléfono"
            Window.Social -> "Red social"
            Window.Media -> "Multimedia"
            Window.Activity -> "Conversación"
            Window.Page -> "Página personal"
        }

        override fun short(window: Window) = when (window) {
            Window.Phone -> "Teléfono"
            Window.Social -> "Social"
            Window.Media -> "Media"
            Window.Activity -> "Charla"
            Window.Page -> "Página"
        }

        override fun about(window: Window) = when (window) {
            Window.Phone -> "chats, contactos, llamadas"
            Window.Social -> "común, amigos, catálogo"
            Window.Media -> "novedades y diapositivas"
            Window.Activity -> "historias, respuestas, reacciones"
            Window.Page -> "perfil, colecciones, roles"
        }

        override fun youAreHere(about: String) = "$about · está aquí"
        override fun cameFrom(window: String) = "Viene de la ventana «$window»"
        override fun cameFromTab(window: String, tab: String) =
            "Viene de la ventana «$window», pestaña «$tab»"
    }

    override val settings2 = object : SettingsListWords {
        override val settings = "Ajustes"

        override fun group(group: SettingsGroup) = when (group) {
            SettingsGroup.ACCOUNT -> "Cuenta"
            SettingsGroup.APPLICATION -> "Aplicación"
            SettingsGroup.BLOGGER -> "Blogger"
            SettingsGroup.HELP -> "Ayuda"
        }

        override fun item(item: SettingsItem) = when (item) {
            SettingsItem.PROFILE -> "Perfil"
            SettingsItem.DEVICES -> "Frase de recuperación y dispositivos"
            SettingsItem.NOTIFICATIONS -> "Notificaciones"
            SettingsItem.VIRTUALS -> "Cuentas virtuales"
            SettingsItem.APPEARANCE -> "Apariencia"
            SettingsItem.LANGUAGE -> "Idioma"
            SettingsItem.PRIVACY -> "Privacidad y bloqueos"
            SettingsItem.STORAGE -> "Memoria y datos"
            SettingsItem.BLOGGER -> "Ventanas de blogger"
            SettingsItem.QUESTIONS -> "Preguntas frecuentes"
            SettingsItem.PROBLEM -> "Informar de un problema"
            SettingsItem.UPDATE -> "Actualización"
            SettingsItem.ABOUT -> "Acerca de la aplicación"
        }
    }

    override val update = object : UpdateWords {
        override val installed = "Actualización instalada"
        override fun runningVersion(version: String) = "Está funcionando la versión $version."
        override fun whatChanged(notes: String) = "Qué ha cambiado: $notes"
        override val broken = "La actualización no terminó"
        override fun brokenText(wanted: String, current: String) =
            "Empezó a instalar $wanted, pero la instalación no llegó al final — " +
                "sigue funcionando la anterior $current."
        override val version = "versión"
        override val chatsUntouched =
            "Sus chats y su cuenta están intactos: el instalador no los toca."
        override val importantOut = "Ha salido una actualización importante"
        override fun availableVersion(version: String) = "Está disponible $version."
        override val oldMayMisbehave = "La versión antigua puede funcionar mal."

        override fun installedVersion(version: String) = "Instalada $version"
        override val streamNotDeclared = "canal no declarado"
        override val askingServer = "Preguntando al servidor…"
        override val notConfigured = "El servidor no reparte actualizaciones"
        override fun download(megabytes: String) = "Descargar $megabytes MB"
        override val install = "Actualizar"
        override val notSelfUpdating =
            "Esta compilación no se actualiza sola: instale la nueva versión como de costumbre."
        override fun alienStream(version: String) =
            "El servidor ofrece $version — es otra compilación, no para esta versión"
        override val latestInstalled = "Está instalada la última versión"
        override val checkAgain = "Comprobar otra vez"

        override val mustUpdate = "Hay que actualizar"
        override val mustUpdateAbout =
            "El servidor ya no funciona con esta versión de la aplicación. Sus chats y su " +
                "cuenta siguen en su sitio — nada los toca —, pero enviar y recibir no " +
                "funcionará hasta que actualice."
        override val notSelfUpdatingLong =
            "Esta compilación no se actualiza sola: instale la nueva versión como de " +
                "costumbre, del mismo modo que instaló esta."

        override fun downloading(percent: Int) = "Descargando $percent%"
        override val dontCloseApp = "No cierre la aplicación mientras descarga"
        override fun installVersion(version: String) = "Instalar $version"
        override val installNow = "Instalar"
        override val appWillClose =
            "La aplicación se cerrará y arrancará el instalador. Tardará un minuto."
        override val confirmSystemAsk = "Si el sistema pide permiso para instalar — concédalo."
        override val comeBackAfter =
            "Cuando arranque el instalador, la aplicación se cerrará sola — vuelva a entrar después."
        override val dataStays =
            "Sus chats, su cuenta y sus ajustes se quedan: viven aparte del programa y el " +
                "instalador no los toca. Lo que no se envió saldrá al arrancar la nueva versión."
        override val notNow = "Ahora no"
        override val installerStarted = "El instalador ha arrancado"
        override val confirmInSystem = "Confirme la instalación en la ventana del sistema."
        override val pressAgain = "Si la ventana se cerró o la rechazó — pulse otra vez."
        override val notDownloaded = "La actualización no se descargó"
        override val notDownloadedAbout = "La conexión se cortó. Inténtelo otra vez."
        override val badPackage = "Lo descargado no coincide con lo que declaró el servidor"
        override val badPackageAbout =
            "Esto no se puede instalar: el archivo no terminó de descargarse o fue " +
                "sustituido. Inténtelo otra vez."
        override val noHash = "El servidor no declaró qué está repartiendo"
        override val noHashAbout =
            "Sin eso no hay con qué comprobar la descarga, así que no la instalamos. " +
                "Se arregla en el servidor."
        override val installNotStarted = "La instalación no empezó"
        override val tryAgain = "Intentar otra vez"
        override val installerDidNotStart = "el instalador no arrancó"
        override val cannotAskServer = "No se pudo preguntar al servidor — compruebe la conexión"
    }

    override val storage = object : StorageWords {
        override val weeks = "Semanas"
        override val months = "Meses"

        override fun keepFor(count: Int, weeks: Boolean): String {
            val unit = if (weeks) "semana" else "mes"
            val plural = if (weeks) "semanas" else "meses"
            return if (count == 1) "$count $unit" else "$count $plural"
        }

        override val mediaAndFiles = "Multimedia y otros archivos"
        override val mediaAndFilesAbout =
            "Todavía no hay nada que limpiar: la aplicación no guarda los adjuntos en el " +
                "dispositivo — se abren desde el servidor. Cuando haya archivos, habrá plazo."
        override val messages = "Mensajes"
        override val messagesAbout =
            "No ponemos plazo a los chats mientras no esté decidido qué significa «borrar». " +
                "Borrar un mensaje del dispositivo no es lo mismo que liberar espacio: solo " +
                "se puede recuperar del otro lado, y solo si allí todavía existe."
        override val diary = "Registro"
        override val diaryAbout =
            "Lo que la aplicación anota sobre su propio funcionamiento — lo que va en un " +
                "informe de problema. No contiene mensajes."
        override val occupies = "Ocupa"
        override val keep = "Guardar"
        override val butNoMore = "Pero no más de"
        override fun olderThan(term: String) =
            "Las entradas de más de $term se borran solas, día a día."
        override val whicheverFirst = "Lo que ocurra antes. Lo que sobra se quita de los días más viejos."
        override val clearDiaryNow = "Vaciar el registro ahora"
        override val clearDiaryAbout =
            "El registro sirve cuando algo se rompe: vaciado, hay que acumularlo de nuevo, " +
                "y hasta entonces un informe de problema irá vacío."
        override fun megabytes(value: Int) = "$value MB"
        override fun kilobytesOccupied(value: String) = "$value KB"
        override fun megabytesOccupied(value: String) = "$value MB"
    }

    override val social = object : SocialWords {
        override val noGroupsYet = "Todavía no hay grupos"
        override val lookingForGroups = "Buscando sus grupos…"
        override val createFirst = "Cree el primero: el más de la esquina inferior derecha."
        override val ifListNeverComes =
            "Si la lista no aparece, es que no llegamos al servidor — y aquí se dirá."
        override val create = "＋ Crear"
        override val noCardsYet = "Todavía no hay tarjetas"
        override val lookingWhatFriendsOpened = "Mirando qué han abierto sus amigos…"
        override val cardsAbout =
            "Aquí aparecen los grupos que la gente de sus contactos ha puesto en su página."
        override val askSent = "solicitud enviada"
        override val asking = "solicitando…"
        override val askToJoin = "Solicitar entrar"
        override val personalGroup = "Grupo privado"
        override val publicGroup = "Grupo público"
        override val youOwner = "usted es el dueño"
        override val youAdmin = "usted es admin"
        override val youModerator = "usted es moderador"
        override val youMember = "usted es miembro"

        override val members = "Miembros"
        override val access = "Acceso"
        override val invite = "Invitar"
        override val readingMembers = "Leyendo los miembros"
        override val nobodyHereYet = "Aquí todavía no hay nadie"
        override val inviteByPhone = "Invite a gente por su número de teléfono"
        override val exclude = "Expulsar"
        override fun bannedUntil(until: String) = "bloqueado hasta $until"
        override val owner = "dueño"
        override val admin = "admin"
        override val moderator = "moderador"
        override val member = "miembro"
        override val roleUnknown = "rol desconocido"

        override val closedAccess = "Acceso a las entradas cerradas"
        override val nobodyAsksAccess = "El acceso no está abierto a nadie y nadie lo pide"
        override val loading = "Cargando…"
        override val accessOpen = "Acceso concedido"
        override val accessOpenAbout =
            "Puede ver las entradas cerradas de este grupo. El plazo lo indica el admin en " +
                "la descripción."
        override val askSentTitle = "Solicitud enviada"
        override val askSentAbout =
            "El admin responderá — la respuesta llegará aquí mismo. No hace falta pedirlo dos veces."
        override val declined = "Denegado"
        override val declinedAbout =
            "El admin no concedió el acceso. Puede pedirlo de nuevo — la decisión no es eterna."
        override val askAgain = "Pedirlo de nuevo"
        override val noAccess = "Sin acceso"
        override val noAccessAbout =
            "Algunas entradas no se le muestran. Su existencia no está oculta — lo está el contenido."
        override val ask = "Pedir acceso"
        override val asksAccess = "pide acceso"
        override val openForever = "acceso concedido · sin plazo"
        override fun openUntil(epoch: String) = "acceso concedido · hasta $epoch"
        override val declinedShort = "denegado"
        override val noAccessShort = "sin acceso"
        override val forever = "Sin plazo"
        override val decline = "Denegar"
        override val deciding = "decidiendo…"

        override val month = "Un mes"
        override val threeMonths = "Tres meses"

        override val badTerm = "El plazo se escribe como 2026-10 — año y mes"
        override val adminOpensAccess = "El acceso lo concede un admin del grupo"
        override val communityDidNotOpen = "La comunidad no se abrió"
        override val subscriptionNotChanged = "No se pudo cambiar la suscripción"
        override fun alreadyInAnother(title: String) = "«$title» ya está en otra comunidad"
        override val ownerLinks = "Puede vincular el dueño de la comunidad y el dueño del elemento"
        override val couldNotLink = "No se pudo vincular"
        override val ownerUnlinks = "Puede desvincular el dueño de la comunidad y el dueño del elemento"
        override val couldNotUnlink = "No se pudo desvincular"
        override fun couldNotAsk(reason: String) = "No se pudo solicitar la entrada: $reason"
        override val groupsMayBeIncomplete =
            "Sin conexión con el servidor — la lista de grupos puede estar incompleta"
        override val cardsMayBeIncomplete =
            "Sin conexión con el servidor — las tarjetas de sus amigos pueden estar incompletas"
        override val numberAlreadyListed = "Ese número ya está en la lista"
        override val membersMayBeStale =
            "Sin conexión con el servidor — la lista puede estar desactualizada"
        override val noSuchNumber = "Ese número no está en TIMA — invite a la persona al mensajero"
        override val ownerOrAdminChangesMembers = "Los miembros los cambia el dueño o un admin"
    }

    override val book = object : BookWords {
        override val everyone = "Todos"
        override val commonSection = "General"
        override val phoneSection = "Teléfono"
        override val search = "Buscar por nombre, apodo o número…"
        override val notRead = "Contactos no leídos"
        override val notReadAbout =
            "La aplicación tomará nombres y números de su agenda para mostrar cuáles de " +
                "ellos ya están en TIMa. Los números van al servidor cerrados: los coteja " +
                "sin leerlos."
        override val addByHand = "Aquí los contactos se añaden a mano"
        override val noBookHere = "Un sistema de escritorio no tiene agenda — añada por número."
        override val nobodyFound = "No se encontró a nadie"
        override fun nothingMatches(search: String) = "Nada en los contactos coincide con «$search»"
        override val bookEmpty = "Todavía no hay contactos"
        override val bookEmptyAbout = "Leeremos la agenda, o añada a alguien por su número."
        override val nameless = "Sin nombre"

        override val view = "Vista"
        override val subsections = "Cómo se muestran las secciones"
        override val folders = "Carpetas"
        override val foldersAbout = "secciones en barras, plegables"
        override val menu = "Menú"
        override val menuAbout = "secciones en una fila bajo las pestañas"
        override val showPersonAs = "Mostrar a la persona como"
        override val name = "Nombre"
        override val nameAbout = "el suyo, si no el de la agenda"
        override val userName = "Nombre de usuario"
        override val userNameAbout = "como se llamó a sí mismo"
        override val nickname = "Apodo"
        override val nicknameAbout = "si la persona lo puso"
        override val phone = "Teléfono"
        override val phoneAbout = "el número de la agenda"
        override val whatToShow = "Qué mostrar"
        override val showSearch = "Mostrar la búsqueda"
        override val showSearchAbout = "en una fila sobre la lista"
        override val showOutsiders = "Mostrar a quienes no están en TIMa"
        override val showOutsidersAbout = "la sección «Teléfono» al final de la lista"
    }

    override val page = object : PageWords {
        override val commentsOn = "Las entradas se pueden debatir"
        override val commentsOff = "Los debates están desactivados"
        override val turnCommentsOff = "Desactivar los debates"
        override val turnCommentsOn = "Activar"
        override val emptyHere = "Aquí todavía no hay nada"
        override val emptyMine = "Las entradas que traiga aquí aparecerán en esta página"
        override val emptyTheirs = "Esta persona todavía no muestra nada"
        override val loading = "Cargando…"
        override val yourEntry = "Su entrada"
        override val carriedByYou = "traída por usted"
        override val entryUnavailable = "La entrada no está disponible"
        override val openDiscussion = "Abrir el debate"
        override val closeDiscussion = "Cerrar el debate"
        override val remove = "Quitar"

        override val cannotCarry = "Esta entrada no se puede traer a su página"
        override val entryGone = "La entrada ya no existe"
        override val couldNotRemove = "No se pudo quitar la entrada"
        override val ownerSwitchesPage = "Los debates los desactiva el dueño de la página"
        override val pageGone = "La página ya no existe"
        override val ownerOrModeratorCloses = "Un debate lo cierra el dueño o un moderador"
    }

    override val chat = object : ChatWords {
        override val yourNickname = "Su apodo"

        override val onlyNarrow = "La audiencia solo se puede restringir, nunca ampliar"
        override val secretIsNarrow =
            "Un mensaje cifrado solo lo leen los miembros — no hay nada que restringir"
        override val openAlreadyOut =
            "Un mensaje abierto ya se ha difundido — no se puede cifrar a posteriori"
        override val strangerNarrowsAdmin = "El mensaje de otro lo restringe un admin del grupo"
        override val messageGone = "El mensaje ya no está en el grupo"
        override val offlineRetryLater = "Sin conexión con el servidor — inténtelo más tarde"
        override fun offlineRetryIn(seconds: Int) =
            "Sin conexión con el servidor — inténtelo en $seconds s"
        override val notMemberAnyMore = "Ya no es miembro de este grupo"
        override fun tooLong(limit: Int) = "Demasiado largo: hasta $limit caracteres"
        override val notAPhone = "De ahí no sale un número de teléfono"
        override val ownNumber = "Ese es su propio número"
        override fun badPhone(reason: String) = "Número incorrecto: $reason"

        override val addAndWrite = "Añadir y escribir"
        override val addToContacts = "Añadir a contactos"
        override val foundInTima = "Encontrado en TIMa — se suscribirá a sus novedades automáticamente"
        override val notInTima = "No está en TIMa. El contacto se guardará — puede llamar por teléfono"

        override val access = "Audiencia"
        override val members = "Miembros"
        override val someone = "Miembro"

        override fun thread(count: Int): String {
            val word = if (count == 1) "respuesta" else "respuestas"
            return "hilo · $count $word"
        }

        override val messageUnavailable = "mensaje no disponible"
        override val decrypting = "descifrando…"
        override val addToSelf = "Traer a mi página"
        override val narrowTo = "restringir a"
        override val narrow = "Restringir"
        override val messageHint = "Mensaje"
        override val nameless = "Sin nombre"

        override fun tooLarge(bytes: Int, limit: Int) =
            "Demasiado grande: $bytes bytes frente a un límite de $limit"
        override fun keysAsked(devices: Int) =
            "La clave se pidió a $devices dispositivos — el historial aparecerá cuando alguien responda"
        override val keysNoHelpers =
            "Ninguno de los miembros tiene estas claves — el historial anterior a su llegada se perdió"
        override val keysNothingMissing =
            "Ya tiene todas las claves: el mensaje no se lee por otro motivo"
        override val keysNeedPhrase =
            "Hace falta la frase de recuperación: es lo que protege la cuenta si le roban " +
                "el número. Si aquí no la sabe, escriba al grupo desde otro de sus " +
                "dispositivos: la clave cambiará y los mensajes nuevos se abrirán. Los " +
                "anteriores, solo con la frase"
        override fun narrowWarning(circle: String) =
            "¿Restringir a «$circle»? Quien ya se llevó el mensaje a su página lo conserva"
        override fun narrowed(circle: String) = "Audiencia restringida: ahora «$circle»"
        override val noGroupKey = "Este dispositivo no tiene la clave del grupo — por eso está vacío"
        override val noGroupKeyAbout =
            "Hay mensajes, pero nada con que abrirlos. Pida la clave a los miembros o " +
                "escriba al grupo desde otro de sus dispositivos: la clave cambiará y el " +
                "grupo se abrirá de ahí en adelante."
        override val askKey = "Pedir la clave"
        override val asking = "Pidiendo…"
        override val phraseWords = "Doce palabras separadas por espacios"
        override val storyUnavailable =
            "Parte del historial no está disponible: es anterior a su llegada"

        override val write = "Escribir"
        override val noChatsYet = "Todavía no hay chats"
        override val writeFirst = "Escriba a su primer contacto"
        override val messageUnreadable = "mensaje ilegible"
        override val newMessage = "mensaje nuevo"
        override val newChat = "Chat nuevo"
        override val whomToWrite = "A quién escribir"
        override val phoneInTima = "Un número de teléfono en TIMA"
        override val noSuchNumber = "Ese número no está en TIMA — invite a la persona"
        override val searching = "Buscando…"
        override val find = "Buscar"

        override val newContact = "Contacto nuevo"
        override val phoneNumber = "Número de teléfono"
        override val nameYouCall = "Nombre — cómo lo llamará usted"
        override val optional = "opcional"
        override val section = "Sección"
        override val commonSection = "General"
        override val newSection = "Sección nueva"
        override val title = "Título"
        override val sectionExample = "Vecinos"
        override val moveLater = "Mueva a la gente allí después — desde la fila del contacto."
        override val createSection = "Crear la sección"

        override fun notInTima(phone: String) = "$phone · no está en TIMa"
        override val sendSms = "Enviar un SMS"
        override val sendSmsAbout = "se abrirá la aplicación de mensajes con el texto listo"
        override val call = "Llamar"
        override val callAbout = "una llamada de teléfono normal"
        override val share = "Compartir"
        override val shareAbout = "un enlace a cualquier aplicación del teléfono"

        override val profile = "Perfil"
        override val nameNotSetYet = "Mientras no ponga un nombre, los demás ven su número."
        override val nameHowShown = "Nombre — cómo se le muestra a los demás"
        override val nameExample = "Pedro Herrera"
        override val nicknameFound = "Apodo — por él lo encontrarán"
        override val saved = "Guardado"
        override val save = "Guardar"
        override val nicknameNeverFreed =
            "Un apodo ocupado no se libera: cambiarlo no devuelve el anterior."
    }

    override val auth = object : AuthWords {
        override fun build(version: String) = "compilación $version"

        override val welcome = "Bienvenido"
        override val enterPhone = "Escriba su número de teléfono — le enviaremos un código"
        override val sending = "Enviando…"
        override val getCode = "Recibir el código"
        override val alreadyHaveAccount =
            "¿Ya tiene cuenta en el teléfono? Este dispositivo puede conectarse a ella — " +
                "confirme el código en el teléfono."
        override val connectToAccount = "Conectar a una cuenta"
        override val confirmation = "Confirmación"
        override fun codeSentTo(phone: String) = "El código se envió a $phone"
        override fun standSentCode(code: String) = "El servidor de pruebas devolvió el código: $code"
        override val checking = "Comprobando…"
        override val confirm = "Confirmar"
        override val changeNumber = "Cambiar el número"

        override val connectingDevice = "Conexión de un dispositivo"
        override val connectingDeviceAbout =
            "Abra la cámara en el teléfono donde ya ha entrado y apúntela a este código. " +
                "El teléfono pedirá confirmación — el código dura cinco minutos."
        override val askingCode = "Pidiendo el código al servidor…"
        override val oldChatsWontMove =
            "Sus chats anteriores no pasarán a este dispositivo: las claves de los mensajes " +
                "viejos se envolvieron para otros dispositivos. Los mensajes nuevos llegarán a ambos."
        override val newCode = "Código nuevo"

        override val secretPhrase = "Frase de recuperación"
        override val secretPhraseAbout =
            "Doce palabras son la única forma de volver a la cuenta si pierde el teléfono. " +
                "Anótelas en orden y guárdelas aparte del teléfono."
        override val wroteDown = "Anotadas"
        override val phraseHint = "palabra palabra palabra…"
        override val phraseEntry = "Entrar con la frase"
        override fun accountExistsFor(phone: String) =
            "El número $phone ya tiene cuenta. Escriba su frase de recuperación — " +
                "doce palabras separadas por espacios."
        override val enter = "Entrar"
        override val otherNumber = "Otro número"
        override val noPhrase =
            "¿No tiene la frase? Puede empezar de cero: los chats anteriores no volverán, y " +
                "sus contactos verán un aviso de que la identidad ha cambiado."
        override val startAnew = "Empezar de cero"

        override val virtualAccount = "Cuenta virtual"
        override val newNickname = "Apodo de la cuenta nueva"
        override val newNicknameAbout =
            "Es un usuario aparte: sus propios chats, sus propias claves, su propia frase de " +
                "recuperación. No tiene teléfono — solo se encuentra por el apodo, y por eso " +
                "el apodo es obligatorio."
        override val further = "Siguiente"
        override val fiveAtMost =
            "No más de cinco cuentas virtuales por número. Un apodo ocupado no se libera nunca."
        override val linkNotHiddenFromUs =
            "Sus contactos no verán el vínculo con su cuenta principal. De nosotros no está " +
                "oculto: el vínculo se guarda en el servidor."
        override val yourSecretPhrase = "Su frase de recuperación"
        override val yourSecretPhraseAbout =
            "La cuenta nueva se crea con su firma: no tiene teléfono y no le llegará ningún " +
                "código. Escriba las doce palabras de su cuenta principal, separadas por espacios."
        override val creating = "Creando…"
        override fun createAccount(nickname: String) = "Crear la cuenta «$nickname»"
        override val wordsGoNowhere =
            "Las palabras no se envían a ninguna parte: de ellas se calcula una firma, y ahí se olvidan."
        override fun phraseOf(nickname: String) = "Frase de recuperación de «$nickname»"
        override val virtualPhraseSaved =
            "La cuenta está creada. Estas doce palabras son la única forma de volver a ella. " +
                "Anótelas en orden: no habrá con qué mostrarlas una segunda vez."
        override val virtualEntryFromYourNumber =
            "Solo podrá volver a entrar en esta cuenta desde su propio número: ella no tiene " +
                "teléfono, y el código le llega a usted."

        override val giveAccount = "Ceder la cuenta"
        override val takeAccount = "Recibir una cuenta"
        override val whatHappens = "Qué ocurrirá"
        override val transferTakesAll =
            "La cuenta se va entera: chats, grupos, canales, roles y propiedad. Sus " +
                "dispositivos quedarán desconectados de ella y no podrá volver a entrar — " +
                "el código de entrada le llega al dueño, y el dueño será otra persona."
        override val transferCutsFuture =
            "Ceder corta el futuro, no el pasado: todo lo que ya ha leído sigue en su " +
                "teléfono, y la cesión no lo borra."
        override val othersWontNotice =
            "Sus contactos no notarán nada: la cuenta no tiene teléfono, y desde el principio " +
                "hablan con un apodo, no con un número."
        override val preparingCode = "Preparando el código…"
        override val issueTransferCode = "Emitir el código de cesión"
        override val transferCode = "Código de cesión"
        override fun codeLives(minutes: Int) =
            "Muéstreselo a quien recibe la cuenta: apuntará la cámara. El código dura " +
                "$minutes minutos y sirve una sola vez."
        override val phraseSeparately =
            "La frase de la cuenta envíela POR SEPARADO y por otra vía — no en el mismo " +
                "mensaje que el código. Juntos son la cuenta: quien intercepte una sola " +
                "conversación se lleva ambos."
        override val wrongPhraseCosts =
            "Una frase incorrecta gasta un intento: tras el tercero el código se quema y hay " +
                "que emitir uno nuevo."
        override val transferCancelled = "La cesión está cancelada — el código ya no sirve"
        override val cancelTransfer = "Cancelar la cesión"
        override val takeAccountAbout =
            "Hacen falta dos cosas, y ambas de quien cede: el código y la frase de " +
                "recuperación de la cuenta. El código solo no basta — sin la frase no abre nada."
        override val accountPhrase = "Frase de recuperación de la cuenta"
        override val bringCodeHint = "apunte la cámara o pegue el código"
        override val entryFromYourNumber =
            "A partir de ahora entrará en esta cuenta desde su propio número: ella no tiene " +
                "teléfono, y el código le llega a usted."
        override val accountYours = "La cuenta es suya"
        override val accountYoursAbout =
            "Los dispositivos del dueño anterior están desconectados y la entrada es ahora " +
                "suya. Cambie la frase: la anterior la conoce quien se la dio."
        override val rotateGroupKeys =
            "Hay que cambiar la clave en los grupos de esta cuenta: hasta entonces el dueño " +
                "anterior seguirá leyendo lo nuevo. Abra los miembros del grupo y cambie la clave."
        override val enterAccount = "Entrar en la cuenta"

        override val deviceConnected = "Dispositivo conectado"
        override val deviceConnectedAbout =
            "Los mensajes nuevos llegarán también a él. Sus chats anteriores no pasarán allí: " +
                "las claves de los mensajes viejos se envolvieron para otros dispositivos."
        override val notConnectionCode = "Este no es un código de conexión"
        override val notConnectionCodeAbout =
            "Se ha escaneado otro código. Abra «Conectar a una cuenta» en el ordenador y " +
                "apunte la cámara al código de allí."
        override val confirmConnection = "¿Confirmar la conexión?"
        override fun deviceNamed(name: String) = "Dispositivo: $name"
        override val deviceUnnamed = "El dispositivo no dijo su nombre"
        override val connectedDeviceCan =
            "Un dispositivo conectado podrá leer los mensajes nuevos de esta cuenta y " +
                "escribir en su nombre. Puede desconectarlo en la lista de dispositivos."
        override val connecting = "Conectando…"
        override val trust = "Confiar"
        override val reject = "Rechazar"

        override val watching = "Mirando…"
        override val listNotCame = "La lista no llegó"
        override val noDevices = "No hay dispositivos"
        override val reasonAbove = "El motivo está arriba. No significa que no haya dispositivos"
        override val emptyListIsOurs =
            "El servidor nunca devuelve una lista vacía — así que esto es cosa de este lado"
        override val nameless = "Sin nombre"
        override val thisDevice = "este dispositivo"
        override val disconnect = "Desconectar"
        override val disconnectDevice = "¿Desconectar el dispositivo?"
        override val disconnectAbout =
            "Dejará de recibir mensajes y perderá el acceso a la cuenta. No se puede " +
                "recuperar — habrá que conectarlo de nuevo desde cero."
        override val keep = "Dejarlo"

        override fun badPhone(reason: String) = "Número incorrecto: $reason"
        override val wrongCode = "El código es incorrecto o ha caducado"
        override val codeExpired = "El código ha caducado — pida uno nuevo"
        override val timeIsUp = "Se acabó el tiempo — pida el código otra vez"
        override val wrongPhrase = "La frase no es la correcta — revise lo que anotó"
        override val identityRefused = "El servidor rechazó el cambio de identidad"
        override val codeTermOver = "El plazo del código ha terminado — pida uno nuevo"
        override val notYourVirtual = "Esa no es su cuenta virtual"
        override val cancelDidNotReach =
            "La cancelación no llegó al servidor. El código sigue activo — inténtelo otra vez"
        override val notTransferCode = "Este no es un código de cesión — compruebe que lo pegó entero"
        override val needAccountPhrase =
            "Hace falta la frase de la cuenta que se cede — la da quien la cede"
        override val phraseDoesNotFit =
            "La frase no coincide. Quedan menos intentos — tras el tercero habrá que emitir " +
                "el código de nuevo"
        override val threeTriesBurned = "Tres intentos fallidos — el código se quemó. Pida uno nuevo"
        override val codeNotValid =
            "El código no sirve: ya se usó, se canceló o tiene más de media hora"
        override val phraseNotMain =
            "La frase no coincide. Es la frase de su cuenta principal — doce palabras " +
                "separadas por espacios"
        override val nicknameTaken = "Ese apodo ya está ocupado — piense otro"
        override val nicknameRules = "Apodo — de 10 a 20 caracteres: letras latinas, cifras, guion bajo"
        override val nicknameRulesShort = "10…20 caracteres: letras latinas, cifras, guion bajo"
        override val fiveIsLimit = "No se admiten más de cinco cuentas virtuales por número"
        override val virtualHasNoVirtuals = "Una cuenta virtual no crea cuentas virtuales"
        override val nicknameFree = "Libre"
        override val nicknameBusy = "Ocupado"
        override val onlyPhoneConfirms =
            "Solo un teléfono puede confirmar la conexión — en el ordenador no funciona"
        override val codeNoLongerValid = "El código ya no sirve — pida uno nuevo en aquel dispositivo"
        override val codeReadWrong = "El código se leyó mal — escanéelo de nuevo"
        override val deviceHasNoKey = "Este dispositivo no puede confirmar: no tiene clave propia"
        override val tryAgain = "Sin conexión — inténtelo otra vez"
        override val listHasNothing = "Sin conexión — no hay con qué mostrar la lista"
        override val lastDevice = "Es el único dispositivo de la cuenta — no se puede desconectar"
        override val deviceNotDisconnected = "Sin conexión — el dispositivo no se desconectó"
        override val listDidNotCome = "La lista no llegó — sin conexión con el servidor"

        override val virtualAboutShort =
            "Una cuenta virtual es un usuario aparte: sus propios chats, sus propias claves, " +
                "su propia frase de recuperación. No tiene teléfono y se encuentra por el apodo."
        override val yourAccounts = "Sus cuentas"
        override val noPhoneFoundByNickname = "sin teléfono · se encuentra por el apodo"
        override val give = "Ceder"
        override val noVirtualsYet = "Todavía no hay cuentas virtuales."
        override val actions = "Acciones"
        override val createVirtual = "Crear una cuenta virtual"
        override val fiveAtMostShort = "no más de cinco por número"
        override val takeNeedsCodeAndPhrase = "el código y la frase de quien la cede"
        override val linkNotHiddenLong =
            "El vínculo con su cuenta principal no es visible para sus contactos. De " +
                "nosotros no está oculto: se guarda en el servidor — de otro modo no " +
                "habría con qué comprobarlo."
    }

    override val wizard = object : WizardWords {
        override val create = "Crear"
        override val creating = "Creando…"
        override val next = "Siguiente"
        override val gotIt = "Entendido"

        override val whatCreate = "¿Qué creamos?"
        override val whichGroup = "¿Qué clase de grupo?"
        override val howJoin = "¿Cómo se entra?"
        override val howFound = "¿Cómo se encuentra el canal?"
        override val discussable = "¿Se pueden debatir las entradas?"
        override val naming = "Título y descripción"
        override val bringing = "¿Qué vinculamos?"

        override val sectionGroup = "Grupo"
        override val sectionGroupAbout = "Conversación de varias personas. Privado o público"
        override val sectionChannel = "Canal"
        override val sectionChannelAbout = "Publicaciones para suscriptores"
        override val sectionCommunity = "Comunidad"
        override val sectionCommunityAbout =
            "Un contenedor: grupos y canales. Vincula lo que existe, no crea nada nuevo"
        override val sectionVoice = "Sala de voz"
        override val sectionVoiceAbout = "Una sala de voz. Pendiente de implementación"
        override val waitsImplementation = "pendiente de implementación"

        override val personal = "Privado"
        override val personalAbout =
            "Los mensajes van cifrados. No se encuentra buscando — se entra por conocidos"
        override val personalExplain =
            "Grupo privado: cifrado de extremo a extremo, el servidor no ve los mensajes. " +
                "No se encuentra buscando — se sabe de él por quienes están dentro."
        override val public = "Público"
        override val publicAbout = "Se encuentra buscando. Acceso abierto o cerrado para los miembros"
        override val publicExplain =
            "Grupo público: conversación abierta, se encuentra buscando y en el catálogo. " +
                "Los mensajes no van cifrados."
        override val kindIsFinal = "La clase no cambia después de crearlo: de ella depende el cifrado"

        override val openJoining = "Abierto"
        override val openJoiningAbout = "Lo encontró y entró"
        override val closedJoining = "Cerrado"
        override val closedJoiningAbout = "Solicitó entrar, un admin lo permitió"
        override val noneForPersonal = "no en los privados"
        override val openExplain = "Abierto: la persona encuentra el grupo y entra sola."
        override val closedExplain = "Cerrado: la persona solicita entrar y un admin lo permite."
        override val personalAlwaysClosed =
            "Un grupo privado siempre es cerrado: no se encuentra buscando, así que no hay " +
                "dónde entrar por cuenta propia"

        override val inCatalogue = "Abierto"
        override val inCatalogueAbout = "Visible en el catálogo, cualquiera puede suscribirse"
        override val inCatalogueExplain =
            "Canal abierto. Visible en el catálogo, cualquiera puede suscribirse"
        override val byLink = "Por enlace"
        override val byLinkAbout = "No se muestra en el catálogo — se encuentra por enlace"
        override val byLinkExplain = "Por enlace. El canal no está en el catálogo, se encuentra por enlace"
        override val commentsAllowed = "Sí"
        override val commentsAllowedAbout =
            "Bajo la entrada se abre una conversación. Comenta quien ve la entrada"
        override val commentsAllowedExplain =
            "Comenta quien ve la entrada: no hay un permiso aparte para ello"
        override val commentsForbidden = "No"
        override val commentsForbiddenAbout = "Un canal sin debates. Esto se puede cambiar después"
        override val commentsForbiddenExplain =
            "Desactivado significa «no admitimos nuevos»: lo escrito antes se queda"

        override val groupName = "Título del grupo"
        override val descriptionAbout = "La descripción la ven todos los que pueden abrir la ficha"
        override val whomInvite = "A quién invitar"
        override val add = "Añadir"
        override val remove = "quitar"
        override val numberAlreadyListed = "Ese número ya está en la lista"
        override val groupCreatedNotInvited =
            "El grupo está creado. Estos números no están en TIMA — invite a esas personas:"
        override val communityCreatedNotLinked =
            "La comunidad está creada. Estos no se pudieron vincular — ya están en otra:"
        override val nothingFreeToBring =
            "No tiene grupos ni canales libres para vincular. La comunidad se puede crear vacía"
    }

    override val tabs = object : TabWords {
        override fun label(tab: WindowTab) = when (tab) {
            WindowTab.Chats -> "Chats"
            WindowTab.Contacts -> "Contactos"
            WindowTab.Calls -> "Llamadas"
            WindowTab.View -> "Vista"
            WindowTab.All -> "Todas"
            WindowTab.FromBook -> "De contactos"
            WindowTab.Unknown -> "Desconocidas"
            WindowTab.Missed -> "Perdidas"
            WindowTab.Common -> "Común"
            WindowTab.Friends -> "Amigos"
            WindowTab.Catalogue -> "Catálogo"
            WindowTab.Feed -> "Novedades"
            WindowTab.Slides -> "Diapositivas"
            WindowTab.Answers -> "Respuestas"
            WindowTab.Reactions -> "Reacciones"
            WindowTab.Collections -> "Colecciones"
            WindowTab.Comments -> "Comentarios"
            WindowTab.Marks -> "Valoraciones"
            WindowTab.Subscribed -> "Suscrito"
            WindowTab.Groups -> "Grupos"
            WindowTab.Media -> "Media"
            WindowTab.Messages -> "Mensajes"
            WindowTab.Open -> "Abierto"
            WindowTab.Personal -> "Privado"
        }
    }

    override val communities = object : CommunityWords {
        override val community = "Comunidad"
        override val communities = "Comunidades"
        override val subscribe = "Suscribirse"
        override val unsubscribe = "Cancelar la suscripción"
        override val bring = "Vincular"
        override val takeOut = "Desvincular"
        override val bringOwn = "Vincular lo suyo"
        override val bringingKeepsEverything =
            "Los chats, los miembros y las claves no cambian — solo cambia un vínculo"
        override val noDescription = "Todavía no hay descripción"
        override val emptyInside = "La comunidad está vacía por ahora"
        override val opening = "Abriendo la comunidad…"
        override val channel = "canal"
        override val group = "grupo"
        override val personalGroup = "grupo privado"
        override val yourCommunity = "su comunidad"
        override val youSubscribed = "está suscrito"
        override val youOwner = "usted es el dueño"
        override val youAdmin = "usted es admin"
        override val youNotSubscribed = "no está suscrito"
        override fun inside(howMany: Int, role: String) = "$howMany dentro · $role"
    }
}
