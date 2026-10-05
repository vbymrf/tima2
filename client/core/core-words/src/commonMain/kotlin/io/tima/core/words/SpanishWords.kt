package io.tima.core.words

/**
 * Испанский словарь (ПЛАН-(Я)-ЯЗЫКА Я10).
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
        override val writingLanguage = "El idioma en el que escribo"
        override val writingLanguageAbout =
            "Por él lo encuentran en las novedades por idioma. No es el idioma de la " +
                "aplicación: pueden ser distintos"
        override val writingLanguageHint = "ru, en, de — código de idioma"
        override val readingLanguages = "Qué idiomas leer"
        override val readingLanguagesAbout = "Separados por comas. Vacío significa solo su idioma"
        override val readingLanguagesHint = "ru, en, es"
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
        override val themeLight = "Claro"
        override val themeDark = "Oscuro"
        override val themeCustom = "Personalizado"

        override val slotNavigation = ColorSlotWords(
            name = "Navegación y acción",
            about = "logotipo, ventana actual, «atrás», «enviar»",
        )
        override val slotActivity = ColorSlotWords(
            name = "Actividad",
            about = "contador de no leídos",
        )
        override val slotConfirmed = ColorSlotWords(
            name = "Confirmado",
            about = "entregado, leído, la marca E2E",
        )
        override val slotSurface = ColorSlotWords(
            name = "Fondo del contenido",
            about = "novedades y chat",
        )
        override val slotFunctional = ColorSlotWords(
            name = "Fondo de los paneles",
            about = "cabecera, pestañas, línea de entrada",
        )
        override val slotText = ColorSlotWords(
            name = "Texto",
            about = "principal",
        )
        override val slotText2 = ColorSlotWords(
            name = "Texto más suave",
            about = "leyendas, hora",
        )
        override val slotText3 = ColorSlotWords(
            name = "Texto aún más suave",
            about = "tercer nivel",
        )
        override val slotMy = ColorSlotWords(
            name = "Mis mensajes",
            about = "",
        )
        override val slotAuthor = ColorSlotWords(
            name = "Mensajes de otros",
            about = "",
        )
        override val slotBorder = ColorSlotWords(
            name = "Borde del mensaje",
            about = "",
        )
        override val slotLine = ColorSlotWords(
            name = "Línea de la lista",
            about = "entre entradas",
        )
        override val slotOnAccent = ColorSlotWords(
            name = "Texto sobre verde",
            about = "en botones, pestañas y la placa de la cabecera",
        )
        override val slotOnAmber = ColorSlotWords(
            name = "Texto sobre ámbar",
            about = "en el contador de no leídos",
        )
        override val slotInPlate = ColorSlotWords(
            name = "Dentro de la placa",
            about = "logotipo y botones sobre verde claro",
        )
        override val slotSoftAccent = ColorSlotWords(
            name = "Fondo suave",
            about = "pestaña no elegida, campo de entrada",
        )
        override val slotQuiet = ColorSlotWords(
            name = "Fondo neutro",
            about = "subpestaña no elegida, cápsula del selector",
        )

        override val colorEmpty = "Vacío. Escriba un color: seis caracteres u ocho"
        override fun colorNotHex(listed: String) =
            "Caracteres no hexadecimales: $listed. Se admiten 0–9 y A–F"
        override fun colorWrongLength(length: Int) =
            "Hay $length caracteres y hacen falta 6 (color) u 8 (con opacidad)"

        override val qrTooLong = "No se puede mostrar el código"
        override val qrTooLongAbout = "Es demasiado largo para un QR"
        override val theme = "Tema"
        override val themeKind = "Tipo del tema propio"
        override val themeKindAbout = "define los colores de las franjas de autor en grupos"
        override val authorStrips = "Franjas de autor en color"
        override val authorStripsAbout = "cada miembro tiene la suya; apagado — color del contorno para todos"
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

        override val placePlate = "el nombre de la ventana en la cabecera y la flecha «atrás»"
        override val placeContent = "el cambio de ventana y la lista de ajustes"

        override val fontAndSize = "Fuentes y tamaños"
        override val fontChoice = "Fuente"
        override val fontRoboto = "Roboto"
        override val fontOpenSans = "Open Sans"
        override val fontSystem = "Del sistema"

        override val sizes = "Tamaño del texto"
        override val sizeMessages = "Mensajes"
        override val sizeTabs = "Pestañas de ventana"
        override val sizeMenu = "Menú y ajustes"
        override val sizeHeaders = "Cabeceras"
        override val sizeLists = "Listas: chats, contactos, catálogos"
        override val sizeSample = "Cómo se verá"
        override val sizeSampleAbout = "La muestra cambia junto con los ajustes"
        override fun points(value: Int) = value.toString()

        override val savedLooks = "Apariencias guardadas"
        override val savedNone = "Elija los colores y guarde — la apariencia aparecerá aquí"
        override val saveName = "Nombre"
        override val saveLook = "Guardar"
        override val forgetLook = "Quitar"
        override fun saveWillReplace(name: String) = "«" + name + "» se sobrescribirá"

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
        override val photos = "Fotos"
        override val photosAbout = "Una captura o una foto de lo que se ve: rayas, ondas, algo mal dibujado. Hasta tres."
        override val addPhoto = "Adjuntar foto"
        override val removePhoto = "Quitar"
        override val photoNotImage = "No es una imagen y no se puede adjuntar"
        override val callFrame = "Un fotograma de la otra persona de la llamada: se envía con el informe"
        override fun photosGo(count: Int) = "Fotos que se enviarán: $count."
    }

    override val notices = object : NoticeWords {
        override val newMessage = "Mensaje nuevo"
        override val wroteToYou = "Te ha escrito"
        override val incomingCall = "Llamada entrante"
        override fun messagesFrom(people: Int) = "Mensajes de $people " + if (people == 1) "persona" else "personas"
        override fun messagesInGroups(groups: Int) = "Mensajes en $groups " + if (groups == 1) "grupo" else "grupos"
        override val newInGroup = "Mensaje nuevo en el grupo"
        override fun missedFrom(people: Int) = "Llamadas perdidas de $people " + if (people == 1) "persona" else "personas"
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

    override val bench = object : BenchWords {
        override val title = "Banco de pruebas de llamadas"
        override val about = "ajustes de publicación y números medidos"

        override val flag = "Modo de pruebas de llamadas"
        override val flagAbout =
            "Abre la ventana del banco: elección de códec y capas, números de tráfico y carga. " +
                "Apagarlo NO borra el conjunto elegido: sigue siendo el comportamiento normal de la aplicación."
        override fun presetNow(name: String) = "Las llamadas usan el conjunto «$name»"

        override val sectionHow = "Cómo llamamos"
        override val sectionPresets = "Conjuntos"
        override val sectionRun = "Prueba"
        override val sectionTraffic = "Qué se transmite"
        override val sectionLoad = "Qué paga el teléfono"
        override val sectionRuns = "Pruebas anteriores"

        override val codec = "Códec de vídeo"
        override val backup = "Códec de reserva"
        override val noBackup = "ninguno"
        override val layers = "Capas"
        override val single = "una capa"
        override val simulcast = "simulcast"
        override val svc = "SVC"
        override val scalability = "Modo SVC"
        override val size = "Tamaño del cuadro"
        override val fps = "Cuadros por segundo"
        override val bitrate = "Bitrate máximo"
        override val degradation = "Qué sacrificar cuando falta ancho de banda"
        override val keepSize = "mantener resolución"
        override val keepFrames = "mantener cuadros"
        override val asWebrtc = "que decida WebRTC"
        override val dynacast = "Dynacast"
        override val crop = "Recorte a múltiplo de 16"
        override val encoderChoice = "Codificador (envío)"
        override val decoderChoice = "Decodificador (recepción)"
        override val asSettings = "como en ajustes"
        override val adaptiveStream = "Adaptive Stream"

        override val sound = "Audio"
        override val red = "RED — redundancia"
        override val dtx = "DTX — no codificar el silencio"
        override val stereo = "Estéreo"
        override val audioBitrate = "Bitrate de audio"

        override val presetName = "Nombre del conjunto"
        override val remember = "Guardar"
        override val forget = "Olvidar"
        override val noPresets = "Aún no hay conjuntos. Configura uno y guárdalo con nombre: si no, las pruebas no se pueden comparar"

        override val apply = "Aplicar ahora"
        override val applyAbout =
            "El conjunto se aplicará a la llamada en curso: la sala se reabre y la conexión se corta dos o tres segundos. " +
                "La prueba también se cierra: una prueba nunca contiene dos conjuntos"
        override val arm = "Iniciar la serie"
        override val disarm = "Detener la serie"
        override val armAbout =
            "La serie está en marcha: cada llamada toma el siguiente conjunto, se registran los números, " +
                "el archivo queda en el teléfono y la ventana de llamada muestra el número de prueba"
        override val idleAbout =
            "Mientras no se pulse, la llamada va como siempre: los conjuntos no rotan, los números no se registran. " +
                "El conjunto elegido se publica igualmente: es el comportamiento normal de la aplicación"
        override val stop = "Detener la grabación"
        override val sectionProbe = "Prueba de códecs"
        override val probeRun = "Probar códecs"
        override val probeAbout = "Cada codificador y decodificador del teléfono comprime y restaura una imagen de prueba en todos los tamaños que usa WebRTC. Cerca de un minuto, sin llamada"
        override fun probeGoing(step: String) = "Prueba en curso: $step"
        override fun probeSaved(path: String) = "Informe de la prueba: $path"
        override val runsBySelf =
            "La grabación corre sola mientras dura la llamada, y el archivo se escribe cuando termina"
        override val speakerOff = "Apagar el altavoz"
        override val speakerOffAbout =
            "Durante la prueba, el sonido del servidor se recibe y se cuenta, pero no suena: los teléfonos en una mesa no se oyen entre sí"
        override val speakerOffNow = "Altavoz apagado: el sonido del servidor no suena"
        override val skip = "No contar los primeros segundos"
        override fun runAt(at: Int, total: Int) = "Prueba $at de $total"
        override val ringAbout =
            "Cada llamada nueva toma el siguiente conjunto, en círculo. Ambos teléfonos solo cuentan llamadas: " +
                "con listas iguales van al paso; si se desvían, el número aquí y en la ventana de llamada lo muestra"
        override fun savedTo(path: String) = "Informe escrito: $path"
        override val notSaved = "No se pudo escribir el informe: mira el diario"
        override fun going(seconds: Int, samples: Int) = "En curso: $seconds s, $samples muestras"
        override val startWhenSettled =
            "Empieza unos segundos después de conectar: los primeros segundos son la subida del ancho de banda y estropean la media"
        override val appliesToNextCall =
            "El conjunto se aplica a la próxima llamada: el códec y las capas se negocian, y cambiarlos a mitad significa renegociar y a veces cortar"

        override val up = "Subida, por pista"
        override val down = "Bajada, por pista"
        override val rtt = "Ida y vuelta"
        override val lost = "Paquetes perdidos"
        override val codecUp = "Códec de subida"
        override val codecDown = "Códec de bajada"
        override val hardwareShort = "H"
        override val softwareShort = "S"
        override val framesUp = "Cuadro saliente, por copia"
        override val frameDown = "Cuadro entrante"
        override val fpsUp = "FPS saliente"
        override val qpUp = "QP saliente"
        override val fpsDown = "FPS entrante"
        override val qpDown = "QP entrante"
        override val current = "Corriente"
        override val onCharger = "cargando"
        override val mahSpent = "Gastado, mAh"
        override val currentAverage = "Corriente, media"
        override val milliAmps = "mA"
        override val hardware = "por hardware"
        override val software = "por software"
        override val phoneSent = "El teléfono envió"
        override val phoneReceived = "El teléfono recibió"

        override val cpu = "Procesador"
        override val memory = "Memoria"
        override val heat = "Temperatura"
        override val battery = "Batería"

        override val seconds = "Segundos"
        override val upAverage = "Subida, media"
        override val upPeak = "Subida, pico"
        override val cpuAverage = "Procesador, media"
        override val cpuPeak = "Procesador, pico"
        override val heatPeak = "Temperatura, pico"
        override val batterySpent = "Batería gastada"
        override val millis = "ms"
        override val megabytes = "MB"
        override val megabits = "Mbit/s"
        override val kilobits = "kbit/s"

        override val on = "sí"
        override val off = "no"
    }

    override val windows = object : WindowWords {

        override val call = WindowName(
            full = "Llamada",
            short = "Llamada",
            about = "hay una llamada en curso",
        )
        override val phone = WindowName(
            full = "Teléfono",
            short = "Teléfono",
            about = "chats, contactos, llamadas",
        )
        override val social = WindowName(
            full = "Red social",
            short = "Social",
            about = "común, amigos, catálogo",
        )
        override val media = WindowName(
            full = "Multimedia",
            short = "Media",
            about = "novedades y diapositivas",
        )
        override val activity = WindowName(
            full = "Conversación",
            short = "Charla",
            about = "historias, respuestas, reacciones",
        )
        override val page = WindowName(
            full = "Página personal",
            short = "Página",
            about = "perfil, colecciones, roles",
        )
        override val bench = WindowName(
            full = "Banco de llamadas",
            short = "Banco",
            about = "ajustes y números medidos",
        )

        override fun youAreHere(about: String) = "$about · está aquí"
        override fun cameFrom(window: String) = "Viene de la ventana «$window»"
        override fun cameFromTab(window: String, tab: String) =
            "Viene de la ventana «$window», pestaña «$tab»"
    }

    override val settings2 = object : SettingsListWords {
        override val settings = "Ajustes"

        override val groupAccount = "Cuenta"
        override val groupApplication = "Aplicación"
        override val groupBlogger = "Blogger"
        override val groupHelp = "Ayuda"

        override val itemProfile = "Perfil"
        override val itemDevices = "Frase de recuperación y dispositivos"
        override val itemNotifications = "Notificaciones"
        override val itemPermissions = "Permisos"
        override val permMicrophone = "Micrófono"
        override val permMicrophoneAbout = "Sin micrófono no hay llamada: la otra persona no le oirá."
        override val permCamera = "Cámara"
        override val permCameraAbout = "Necesaria para videollamadas."
        override val permContacts = "Contactos"
        override val permContactsAbout = "Para encontrar a conocidos en TIMa desde la agenda."
        override val itemMedia = "Micrófono y cámara"
        override val mediaMicrophone = "Micrófono"
        override val mediaMicrophoneAbout = "Di algo: la barra debe moverse. Si te oyen bajo, sube el volumen."
        override val mediaSpeaker = "Altavoces"
        override val mediaCamera = "Cámara"
        override val mediaDefault = "Como en el sistema"
        override val mediaVolume = "Volumen del micrófono"
        override val mediaLevelHint = "Nivel"
        override val mediaTestSound = "Probar sonido"
        override val mediaNoCamera = "La cámara no muestra imagen"
        override val mediaProcessing = "Procesado de sonido"
        override val mediaEcho = "Cancelación de eco"
        override val mediaNoise = "Supresión de ruido"
        override val mediaGain = "Ganancia automática"
        override val mediaProcessingAbout = "Desactívalo solo si molesta: auriculares con su propio filtro, música. Con auriculares la cancelación de eco no hace falta. En esta prueba no se oye: el eco solo existe en una llamada."
        override val mediaNextCall = "Los cambios se aplican desde la próxima llamada."
        override val mediaListen = "Escucharte — mejor con auriculares, o se acoplará"
        override val exitApp = "Cerrar la aplicación"
        override val leaveApp = "Salir"
        override val leaveAbout = "TIMA sigue en segundo plano: las llamadas y mensajes llegarán"
        override val closeQuestion = "¿Quieres cerrar o salir?"
        override val closeYes = "Cerrar"
        override val exitAbout = "Las llamadas y mensajes no llegarán hasta que abras TIMA de nuevo"
        override val permBackground = "Funcionamiento en segundo plano"
        override val permAutostart = "Inicio con Windows"
        override val permAutostartAbout = "Las llamadas y los mensajes llegan solo mientras TIMA está abierta. Así se inicia al entrar en Windows, directamente en la bandeja, sin ventana."
        override val permAutostartOn = "Iniciar al entrar en Windows"
        override val permAutostartNoProgram = "Esta copia no se ejecuta desde un programa instalado: no hay nada que iniciar."
        override val warnTitle = "Las llamadas pueden no llegar"
        override val warnWhere = "Se corrige en Ajustes → Permisos."
        override val eventsTitle = "Avisos"
        override fun eventsCount(position: Int, total: Int) = "$position de $total"
        override val eventsNext = "Siguiente"
        override val eventsSkipAll = "Omitir todos"
        override val eventsMore = "Más"
        override val eventsNew = "nuevo"
        override val eventsGoToUpdate = "Ir a la actualización"
        override val eventsGotIt = "Entendido"
        override val eventsLineNotices = "Notificaciones desactivadas"
        override val eventsLineCalls = "Canal «Llamadas» desactivado"
        override val eventsLineBattery = "El ahorro de batería limita TIMA"
        override val noticesShow = "Mostrar notificaciones"
        override val noticesWhat = "La notificación muestra quién escribió o llama — y nada más."
        override val noticesNoText =
            "El texto del mensaje no se muestra: está cifrado, y lo verá al abrir la conversación."
        override val noticesAllowed = "Permitido"
        override val noticesAllow = "Permitir"
        override val noticesRefused =
            "El sistema no volverá a preguntar. Actívelo en la página de la aplicación en los ajustes."
        override val noticesOpenSettings = "Abrir ajustes"
        override val noticesAwake = "No suspender la aplicación"
        override val noticesAwakeAbout =
            "Para que lleguen mensajes y llamadas con la aplicación cerrada, el sistema no debe detenerla."
        override val noticesAwakeAsk = "Permitir el trabajo en segundo plano"
        override val noticesAwakeDone = "Permitido"
        override val noticesVendors =
            "En teléfonos realme, Xiaomi, Huawei y similares esto no basta: tienen sus propias listas " +
                "de inicio automático en los ajustes de batería, y allí la aplicación solo se añade a mano."
        override val noticesCalls = "Llamadas"
        override val noticesCallsAbout = "El canal Llamadas se puede apagar aparte de las demás notificaciones — entonces la llamada entrante no se mostrará."
        override val noticesCallsOn = "El canal Llamadas está activado"
        override val noticesCallsOff = "El canal Llamadas está desactivado"
        override val warnNotices = "Las notificaciones están desactivadas — la llamada entrante no se mostrará ni sonará."
        override val warnCalls = "El canal Llamadas está desactivado — la llamada entrante no se mostrará ni sonará."
        override val warnBattery = "El ahorro de batería limita la aplicación — puede detenerse en segundo plano y la llamada no llegará."
        override val warnFix = "Activar"
        override val warnLater = "Más tarde"
        override val noticesFullScreenOn = "Llamada entrante en la pantalla de bloqueo — a pantalla completa"
        override val noticesFullScreenOff = "La llamada entrante en la pantalla de bloqueo se mostrará como línea: pantalla completa no permitida"
        override val soundsTitle = "Sonidos"
        override val soundRing = "Tono de llamada"
        override val soundMessage = "Sonido de notificación"
        override val soundDefault = "Como en el sistema"
        override val soundSilent = "Sin sonido"
        override val soundFromSystem = "De los estándar"
        override val soundFromFile = "Cargar un archivo"
        override val noticesSeen = "Qué muestra una notificación"
        override val quietTitle = "No molestar, en las horas:"
        override val quietAbout = "Qué silenciar: llamadas, mensajes"
        override val quietOff = "Desactivado"
        override val quietOffAbout = "Notificaciones a cualquier hora"
        override val quietOn = "Activado"
        override val quietOnAbout = "En estas horas — sin notificación y sin sonido"
        override val quietFrom = "Desde"
        override val quietTo = "Hasta"
        override val quietCalls = "Llamadas"
        override val quietCallsAbout = "Entrante — notificación sin tono ni pantalla completa; perdidas — sin notificación"
        override val quietMessages = "Mensajes"
        override val quietMessagesAbout = "Directos y de grupo — sin notificación ni sonido"
        override val soundRingAbout = "Llamada entrante"
        override val soundMessageAbout = "Mensaje nuevo y llamada perdida"
        override val soundDefaultAbout = "El sonido elegido en los ajustes del teléfono"
        override val soundFromSystemAbout = "Uno de los sonidos del teléfono"
        override val soundFromFileAbout = "Tu propio archivo de sonido"
        override val soundSilentAbout = "La notificación llega sin sonido"
        override val soundTooBig = "El archivo supera 5 MB — no se tomó"
        override val soundBadType = "Se necesita un sonido: mp3, ogg, m4a o wav"
        override val soundsNotSynced = "Cada dispositivo guarda su elección; no se transfiere entre ellos."
        override val itemVirtuals = "Cuentas virtuales"
        override val itemAppearance = "Colores"
        override val itemText = "Fuentes y tamaños"
        override val itemLanguage = "Idioma"
        override val itemPrivacy = "Privacidad y bloqueos"
        override val itemStorage = "Memoria y datos"
        override val itemCalls = "Llamadas"
        override val itemCallBench = "Modo de pruebas de llamadas"
        override val itemBlogger = "Ventanas de blogger"
        override val itemQuestions = "Preguntas frecuentes"
        override val itemProblem = "Informar de un problema"
        override val itemUpdate = "Actualización"
        override val itemAbout = "Acerca de la aplicación"
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

    override val callLog = object : CallLogWords {
        override val outgoing = "saliente"
        override val incoming = "entrante"
        override val notAnswered = "sin respuesta"
        override val missed = "perdida"
        override val cancelled = "cancelada"
        override val declined = "rechazada"
        override val busy = "ocupado"
        override val lost = "interrumpida"
        override val ringing = "llamando"
        override val video = "v\u00eddeo"
        override val voice = "voz"
        override val today = "hoy"
        override val yesterday = "ayer"
        override val nothingYet = "A\u00fan no hay llamadas"
        override val nothingYetAbout =
            "Aqu\u00ed aparecer\u00e1n las llamadas entrantes, salientes y perdidas: la direcci\u00f3n con " +
                "una flecha y el tipo con un icono. Volver a llamar usa el mismo tipo de entonces."
        override val noConnection =
            "El registro vive en el servidor y ahora no hay conexi\u00f3n. Lo ya recibido seguir\u00eda " +
                "aqu\u00ed, as\u00ed que este tel\u00e9fono todav\u00eda no lo ha descargado."
        override val journal = "Registro de llamadas"
        override val journalAbout =
            "El registro se guarda en el servidor y no se borra. Esto es una copia, para que la " +
                "pesta\u00f1a abra sin conexi\u00f3n; lo que se quite aqu\u00ed siempre puede volver a pedirse."
        override fun rows(count: Int) = if (count == 1) "1 fila" else "$count filas"
        override fun occupied(rows: String) = "Ocupa $rows"
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
        override val identityClaims = "Confirme la identidad"
        override fun identityClaimLine(newName: String, oldName: String) = "$newName empezó de nuevo en lugar de $oldName: mismo número, clave nueva. Confirme si está seguro de que es él: la clave del grupo cambiará"
        override val identityClaimConfirm = "Confirmar"
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
        override val nickField = "Apodo"
        override val fromContacts = "De los contactos"
        override val noSuchNickname = "Nadie tiene ese apodo"
        override val alreadyMember = "ya está en el grupo"
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
        override fun inCard(count: Int) = "en la tarjeta: $count"
        override val everyone = "Todos"
        override val commonSection = "General"
        override val phoneSection = "Teléfono"
        override val search = "Nombre, apodo o número"
        override val searchChats = "Nombre o texto del mensaje"
        override val allow = "Permitir"
        override val openSettings = "Abrir ajustes"
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
        override val sectionsLook = "Aspecto de las secciones"
        override val lookSample = "Vista previa"
        override val sampleSectionWork = "Trabajo"
        override val sampleSectionHome = "Casa"
        override val samplePerson = "Ana Pérez"
        override val samplePersonShort = "Ana"
        override val sampleGroup = "Equipo de desarrollo"
        override val sampleGroupAbout = "Reuniones, tareas, versiones"
        override val sampleMessage = "¡Hola! Quedamos a las siete"
        override val subsections = "Cómo se muestran las secciones"
        override val folders = "Carpetas"
        override val foldersAbout = "secciones en barras, plegables"
        override val menu = "Menú"
        override val menuAbout = "secciones en una fila bajo las pestañas"

        override val labelsTitle = "Etiquetas de secciones"
        override val labelsIcons = "Iconos"
        override val labelsIconsAbout = "secciones como iconos"
        override val labelsNames = "Nombres"
        override val labelsNamesAbout = "secciones con palabras"

        override val tileSizeTitle = "Tamaño de los iconos"
        override val tileSmall = "Pequeños"
        override val tileNormal = "Normales"
        override val tileLarge = "Grandes"

        override val commonSectionAbout = "aquí van todos los que no tienen sección; no se puede quitar"
        override val sectionsItem = "Secciones"
        override val sectionsItemAbout = "crear, renombrar, ordenar, quitar"
        override val sectionsScreen = "Secciones de contactos"
        override val addSection = "Añadir sección"
        override val newSectionName = "Nombre"
        override val sectionIcon = "Icono"
        override val noIcon = "sin icono"
        override val removeSection = "Quitar sección"
        override val removeSectionAbout = "sus personas vuelven a «General»"
        override val sectionsEmpty = "Aún no hay secciones"
        override val sectionsEmptyAbout = "Una sección es un estante para contactos: «Trabajo», «Casa», «Estudio». Cree la primera"
        override val sectionsScreenCommunity = "Secciones de comunidades"
        override val sectionsEmptyAboutCommunity =
            "Una sección es un estante para grupos y canales: «Trabajo», «Vecinos», «Estudio». Cree la primera"
        override val removeSectionAboutCommunity = "sus grupos vuelven a «General»"
        override fun groupsInSection(count: Int) = when (count) {
            0 -> "vacía"
            1 -> "1 grupo"
            else -> "$count grupos"
        }
        override fun peopleInSection(count: Int) = when (count) {
            0 -> "vacía"
            1 -> "1 persona"
            else -> "$count personas"
        }
        override val moveUp = "Subir"
        override val moveDown = "Bajar"
        override val save = "Guardar"
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
        override val showOutsiders = "Mostrar a quienes no están en TIMa"
        override val showOutsidersAbout = "la sección «Teléfono» al final de la lista"

        override val listsTitle = "Listas"
        override val listBook = "Agenda"
        override val listBookAbout = "leídos de la agenda del teléfono"
        override val listTima = "TIMa"
        override val listTimaAbout = "añadidos por ti — por número o por apodo"
        override val listRemoved = "Retirados"
        override val listRemovedAbout = "fuera de contactos; los chats y llamadas siguen igual"
        override val listBlocked = "Bloqueados"
        override val listBlockedAbout = "fuera de contactos; chats ocultos, la llamada no suena"
        override val listEmpty = "Aquí no hay nadie"
        override val ledgerTitle = "Registro de contactos"
        override val ledgerAbout = "sección, lista y tono propio — para varios a la vez"
        override val ledgerAll = "Todas las listas"
        override val ledgerHelpTitle = "Qué hacen los botones"
        override val ledgerHelpToSection = "Mueve a los seleccionados a una sección — en Contactos aparecerán allí"
        override val ledgerHelpRemove = "Los pone en Quitados: salen de contactos, chats y llamadas funcionan como siempre"
        override val ledgerHelpBlock = "Los pone en Bloqueados: salen de contactos, chats ocultos, la llamada no suena"
        override val ledgerHelpRestore = "Los devuelve de Quitados o Bloqueados a contactos"
        override val ledgerHelpSound = "Tono propio para los seleccionados: uno estándar, tu archivo o como en ajustes"
        override val ledgerHelpSelectTitle = "Cuadro a la derecha"
        override val ledgerHelpSelect = "Selecciona a la persona para los botones de abajo. Tocar la fila abre su página"
        override val ledgerHelpFiltersTitle = "Franjas de arriba"
        override val ledgerHelpFilters = "Secciones y listas son filtros y se combinan: Trabajo y Bloqueados — bloqueados de Trabajo"
        override val ledgerOwnSound = "Con tono propio"
        override val ledgerAllSections = "Todas las secciones"
        override val ledgerSelectAll = "Seleccionar todo"
        override val ledgerSelectNone = "Quitar selección"
        override val ledgerSearch = "Nombre o número"
        override val ledgerToSection = "A sección"
        override val ledgerRemove = "Quitar"
        override val ledgerBlock = "Bloquear"
        override val ledgerRestore = "Devolver"
        override val ledgerSound = "Sonido"
        override val ledgerPickSection = "Dónde poner a los seleccionados"
        override val ledgerSoundTitle = "Tono para los seleccionados"
        override val ledgerSoundAsSettings = "Como en ajustes"
        override val ledgerSoundOnlyTima = "Tono solo para quienes están en TIMa: los demás no le llaman por la aplicación"
        override fun ledgerSelected(count: Int) = "Seleccionados: $count"
        override val listPick = "La marca significa que la persona está en la lista"
        override fun peopleInList(count: Int) = "$count " + if (count == 1) "persona" else "personas"
    }

    override val page = object : PageWords {
        override val call = "Llamar"
        override val videoCall = "Videollamada"
        override val groupCall = "Llamada en grupo"
        override val write = "Escribir"
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
        override val guestPage = "Página"
        override val whatWeKnow = "Lo que sabemos de esta persona"
        override val subscribe = "Suscribirse"
        override val unsubscribe = "Cancelar la suscripción"
        override val subscribeNotYet = "Todavía no funciona: el muro de una persona no tiene suscripción del lector"
        override val alreadyInContacts = "Ya está en contactos"
        override val contactMeansFriend = "Un contacto es un amigo: a los amigos se les suscribe solo"
        override val nothingKnown = "No sabemos nada de esta persona: ni nombre, ni apodo, ni número"
        override val theyAddedYou = "Le ha añadido: su muro «para amigos» está abierto para usted"
        override val theyDidNotAddYou = "No le ha añadido: solo se ve lo público"
        override val friendshipUnknown = "Comprobando…"
        override val subscribeAsks = "La suscripción traerá su historial y pedirá que le añada a contactos"
        override val ownerOrModeratorCloses = "Un debate lo cierra el dueño o un moderador"

        override val myPage = "Mi página"
        override val account = "Cuenta"
        override val accountTemporary = "Cuenta temporal: se eliminará 3 meses después de su creación"
        override val editProfile = "Editar perfil"
        override val nameSetByMe = "puesto por mí"
        override val nameSelfChosen = "cómo se llamó a sí mismo"
    }

    override val groupCall: GroupCallWords = SpanishGroupCall
    override val call = object : CallWords {
        override val incoming = "Llamada entrante"
        override val outgoing = "Llamando"
        override val calling = "Sonando…"
        override val accept = "Aceptar"
        override val noEngineHere = "Las llamadas aún no funcionan en este dispositivo: contesta en el teléfono"
        override val decline = "Rechazar"
        override val cancel = "Cancelar"
        override val hangUp = "Colgar"
        override val microphoneOn = "Micrófono activado"
        override val microphoneOff = "Micrófono apagado"
        override val cameraOn = "Cámara activada"
        override val cameraOff = "Cámara apagada"
        override val ended = "Llamada finalizada"
        override val callAgain = "Volver a llamar"
        override val close = "Cerrar"
        override val connecting = "Conectando…"
        override val reconnecting = "Se perdió la conexión, volviendo…"
        override val videoPaused = "Vídeo desactivado: no hay banda suficiente. El audio sigue"
        override val noMicrophone = "Sin acceso al micrófono: la llamada no funciona sin él. Actívalo en los ajustes del dispositivo"
        override val noCamera = "Cámara no permitida: la llamada sigue con audio. Actívala en los ajustes del dispositivo"
        override val activeCall = "Llamada activa"
        override val codingTitle = "Vídeo en llamadas"
        override val codingAbout = "El vídeo lo codifica y decodifica el chip del teléfono. Si la imagen sale con rayas u ondas, " +
            "apaga el interruptor correspondiente y el teléfono pasará a software. Surte efecto al instante, también en una llamada."
        override val codingEncode = "Codificación por hardware"
        override val codingEncodeAbout = "Cómo te ve la otra persona. Apagado: tu vídeo se codifica por software; " +
            "H.264 deja de estar disponible y se envía VP8."
        override val codingDecode = "Decodificación por hardware"
        override val codingDecodeAbout = "Cómo ves a la otra persona. Apagado: su vídeo se decodifica por software."
        override val codingOn = "Activado"
        override val codingOff = "Desactivado"
        override val backgroundVideo = "Vídeo al minimizar"
        override val backgroundPause = "Pausa"
        override val backgroundKeep = "Seguir mostrando"
        override val backgroundVideoAbout = "Pausa — al minimizar la aplicación, tu vídeo se pausa a los 2 segundos: la otra persona ve que la minimizaste y el vídeo vuelve al regresar. Seguir mostrando — la cámara sigue funcionando con la aplicación minimizada; el teléfono muestra el indicador de cámara."
        override val peerShowsSelf = "La otra persona muestra vídeo y tu cámara está apagada: toca la cámara para mostrarte"
        override val peerStoppedVideo = "La otra persona dejó de mostrar vídeo"
        override val peerPausedVideo = "La otra persona minimizó la aplicación — su vídeo está en pausa"
        override val peerLeft = "La otra persona colgó"
        override fun presetApplied(name: String) =
            "Conjunto «$name» aplicado: el corte de dos segundos no fue un fallo"
        override val presetRefused =
            "Conjunto no aplicado: el servidor no permitió volver a entrar. La llamada sigue con el anterior"
        override val peerOffline = "El teléfono de la otra persona está sin conexión: la llamada llegará cuando vuelva"
        override val noAnswer = "Sin respuesta"
        override val peerBusy = "La otra persona está en otra llamada"
        override val ringing = "Sonando…"
        override val peerDeclined = "La otra persona rechazó la llamada"
        override val missedCall = "Llamada perdida"
        override val remoteHidden = "Vídeo oculto: no se recibe y no consume datos"
        override val remoteVideoNotArriving = "El vídeo del otro no llega: lo está mostrando, pero no te llega ni un fotograma"
        override fun remoteVideoNotDecoding(codec: String) = "No podemos mostrar el vídeo. El vídeo del otro llega en $codec, pero este teléfono no puede mostrarlo."
        override fun ownCodecUnsupported(codec: String) = "No te verán: el preset de prueba exige $codec y este teléfono no lo codifica"
        override fun sentCodecDiffers(asked: String, sent: String) = "El vídeo sale en $sent, pero se pidió $asked: el teléfono no dio el códec pedido. Puede que no te vean"
        override val hideRemote = "Ocultar vídeo"
        override val showRemote = "Mostrar vídeo"
        override val expand = "Desplegar"
        override val collapse = "Plegar"
        override val openSettings = "Abrir ajustes"
        override val report = "Reportar"
        override val nextEvent = "Siguiente"
        override val previousEvent = "Atrás"
        override fun ofTotal(one: Int, total: Int) = "$one de $total"
        override fun quality(level: String) = when (level) {
            "Excellent" -> "conexión excelente"
            "Good" -> "conexión buena"
            "Poor" -> "conexión mala"
            "Lost" -> "sin conexión"
            else -> "comprobando la conexión"
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
        override val videoCall = "Videollamada"
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

        override val addToContacts = "Añadir a contactos"
        override val foundInTima = "Encontrado en TIMa — se suscribirá a sus novedades automáticamente"
        override fun alreadyInBook(name: String) = "Ya está en sus contactos: $name"
        override val openPersonPage = "Abrir su página"
        override val notInTima = "No está en TIMa. El contacto se guardará — puede llamar por teléfono"
        override val byNickname = "Por apodo"
        override val nicknameHint = "parte de un apodo, tres caracteres o más"
        override val findByNickname = "Buscar"
        override val nobodyWithNickname = "Nadie tiene ese apodo"
        override val searchFailed = "No se pudo preguntar al servidor"
        override val hitRemoved = "en tus «Retirados»"
        override val hitBlocked = "en tus «Bloqueados»"
        override val blockedYou = "El usuario te ha bloqueado"
        override val chatMenu = "Conversación"
        override val groupSettings = "Ajustes del grupo"
        override val chatSettings = "Ajustes de la conversación"
        override val myColor = "Mi color en el grupo"
        override val myColorAbout = "la franja de mis mensajes como la ven los demás"
        override val myColorAuto = "automático"
        override val myColorTaken = "ocupado"
        override val myColorReset = "Restablecer — automático"
        override val notSent = "No enviado"
        override val waitingTitle = "Esperando el envío"
        override val waitingNoReason = "En la cola: aún no hubo intentos"
        override fun waitingAbout(attempts: Int, seconds: Int): String {
            val tries = if (attempts == 0) "aún no hubo intentos" else "intentos: $attempts"
            val next = when {
                seconds <= 0 -> "el siguiente, en la próxima pasada"
                seconds < 60 -> "el siguiente en $seconds s"
                else -> "el siguiente en ${seconds / 60} min"
            }
            return "$tries · $next"
        }
        override val notSentNoReason = "El motivo no se guardó: el mensaje es anterior a esta versión"
        override val sendAgain = "Enviar de nuevo"
        override val deleteMessage = "Eliminar"
        override val reportProblem = "Informar de un problema"
        override fun failReason(code: String): String? = when (code) {
            "level_in_private" -> "un grupo privado no tiene ese círculo: solo «Cifrado» y «Todos, siempre»"
            "secret_in_public" -> "un grupo público no se cifra: elija un círculo abierto"
            "banned" -> "está bloqueado en este grupo"
            "payload_too_large" -> "el mensaje es demasiado grande"
            "bad_level" -> "círculo fuera de rango"
            "unknown_gk_version" -> "el servidor no conoce nuestra versión de la clave del grupo"
            "no_gk_version" -> "un mensaje cifrado sin versión de clave"
            "not_member" -> "no es miembro de este grupo"
            else -> null
        }
        override val moveToSection = "Mover a una sección"
        override val moveToSectionAbout = "dónde vive este grupo"
        override fun inSection(name: String) = "ahora — «$name»"
        override fun notInTimaChecked(phone: String) =
            "TIMa no tiene el número $phone. El contacto se guardará — puede llamar por " +
                "teléfono. Si la persona está en TIMa, revise el número"

        override val access = "Audiencia"
        override val members = "Miembros"
        override val someone = "Miembro"
        override val noGroups = "Aún no hay grupos"
        override val noGroupsAbout =
            "Aquí aparecerán los grupos que tienes, que diriges y en los que participas. " +
                "Para crear un grupo, abre el catálogo de la ventana Social."

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
        override fun saveNeedsNick(rules: String) = "No se puede guardar — complete el apodo: $rules"
        override val saveNickTaken = "No se puede guardar — el apodo está ocupado, piense otro"
        override val nicknameNeverFreed =
            "Un apodo ocupado no se libera: cambiarlo no devuelve el anterior."
        override val nicknameOnce =
            "El apodo se establece una vez. Solo podrá cambiarse junto con una nueva frase secreta."
        override val nicknameLocked =
            "El apodo está establecido y ligado a esta frase. Empiece de nuevo con otra — y podrá cambiarlo o dejarlo."
        override val phone = "Teléfono"
        override val avatarChange = "Cambiar foto"
        override val avatarRemove = "Quitar foto"
        override val avatarCrop = "Recortar"
        override val avatarCropHint = "Arrastre y estire: lo que se ve en el cuadrado es lo que queda"
        override val avatarRotate = "Girar"
        override val pickSection = "Elegir sección"
        override fun createSectionNamed(name: String) = "Crear la sección «$name»"
        override fun noSuchSection(name: String) =
            "No existe la sección «$name». Créela o elija una de la lista — si no, el " +
                "contacto irá a donde nadie lo ve"
        override val avatarNotImage = "Esto no es una imagen o el archivo está dañado"
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
                "sus contactos verán un aviso de que la clave de identidad ha cambiado."
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
        override val signOut = "Cerrar sesión en este dispositivo"
        override val keyMissingTitle = "La clave de la copia de contactos no ha llegado"
        override val keyMissingAbout =
            "Sin ella, los contactos y secciones de tus otros dispositivos no llegarán aquí. Cualquiera de " +
                "tus dispositivos conectados enviará la clave: firma la solicitud con tu frase secreta."
        override val requestKey = "Solicitar la clave"
        override val requestKeySend = "Enviar la solicitud"
        override val requestKeySending = "Enviando…"
        override fun requestKeyAsked(devices: Int) =
            "Solicitud enviada; esperando a tus dispositivos conectados: $devices. Hasta tres minutos."
        override val requestKeyNoHelpers =
            "Nadie puede responder: ninguno de tus otros dispositivos está conectado. Abre la app en el teléfono y vuelve a intentarlo."
        override val requestKeyGot = "Clave recibida: los contactos están llegando."
        override val requestKeyNoAnswer = "La clave no llegó en tres minutos. Abre la app en el teléfono y vuelve a intentarlo."
        override fun requestKeyFailed(reason: String) = "La solicitud no se envió: $reason"
        override val scanCode = "Escanear código"
        override val scanCodeAbout = "Conectar un ordenador o teléfono: el código está en su pantalla"
        override val scanTitle = "Código de conexión"
        override val scanHint = "Apunta la cámara al código de la pantalla del nuevo dispositivo"
        override val scanNotOurs = "Este no es un código de conexión de TIMA"
        override val scanNoCamera = "Sin acceso a la cámara: permítelo en los ajustes del teléfono"
        override val scanClose = "Cerrar"
        override val signOutAbout = "La cuenta quedará apartada: los chats y las claves siguen en el dispositivo. Vuelve con «Recuperar la cuenta anterior» en la pantalla de acceso"
        override val signOutLast = "Es el último dispositivo de la cuenta. También puedes volver a entrar con tu número y la frase secreta"
        override val signOutYes = "Cerrar sesión"
        override val revokedTitle = "Este dispositivo fue desconectado de la cuenta"
        override val revokedAbout = "El servidor ya no reconoce este dispositivo. Vuelve a entrar: con un código QR desde un teléfono con sesión iniciada o con tu número"
        override val signInAgain = "Volver a entrar"
        override val returnTitle = "Recuperar la cuenta anterior"
        override fun returnTo(name: String) = "Recuperar: $name"
        override val listTimedOut = "La lista no llegó en 20 segundos"
        override val retryList = "Reintentar"

        override fun badPhone(reason: String) = "Número incorrecto: $reason"
        override val wrongCode = "El código es incorrecto o ha caducado"
        override val codeExpired = "El código ha caducado — pida uno nuevo"
        override val timeIsUp = "Se acabó el tiempo — pida el código otra vez"
        override val wrongPhrase = "La frase no es la correcta — revise lo que anotó"
        override val identityRefused = "El servidor rechazó el cambio de clave de identidad"
        override val identityClosed =
            "Esta es la frase de tu clave de identidad anterior: alguien empezó de cero con este número. " +
                "Puedes recuperarla cancelando la nueva clave de identidad desde tu dispositivo anterior o volviendo a registrarte."
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
        override val phoneUnproven =
            "Primero confirme este teléfono con su frase secreta: Ajustes → Dispositivos → Confirmar con la frase"
        override val deviceCertified = "verificado"
        override val deviceUncertified = "sin verificar"
        override val confirmWithPhrase = "Confirmar con la frase"
        override val confirmWithPhraseAbout = "Este dispositivo aún no está verificado. Introduzca su frase secreta para que los contactos sepan que es suyo. En un teléfono la frase se pide una vez: después verifica él mismo sus dispositivos nuevos."
        override val confirmWithPhraseSend = "Confirmar"
        override val certifyDevice = "Verificar"
        override val trustDone = "Listo: el dispositivo está verificado"
        override val trustNoKey = "Primero confirme este teléfono con su frase"
        override fun trustFailed(reason: String) = "No funcionó: $reason"
        override val replacedTitle = "Alguien empezó de nuevo con su número"
        override val replacedAbout = "En otro dispositivo se creó una clave de identidad nueva de su cuenta: pasa cuando se pierden el teléfono y la frase o se reemite la SIM. Los contactos ahora le escriben a ella, no a usted. Si no fue usted, cancélela: necesitará su frase secreta."
        override val replacedCancel = "Cancelar la clave de identidad nueva"
        override val replacedCancelSend = "Cancelar"
        override val replacedItsMe = "Fui yo"
        override val replacedCancelled = "La clave de identidad nueva está cancelada: sus dispositivos se desconectaron, usted vuelve a ser el principal"
        override val banTitle = "Prohibir «Empezar de cero»"
        override val banAbout =
            "Después nadie podrá entrar en la cuenta sin la frase secreta, ni siquiera con la SIM de este número. " +
                "La prohibición es para siempre: no se puede quitar."
        override val banSendCode = "Recibir código"
        override val banCodeHint = "Código del SMS"
        override val banConfirm = "Prohibir para siempre"
        override val bannedTitle = "«Empezar de cero» está prohibido"
        override val copyRotateTitle = "Cambiar la clave de la copia"
        override val copyRotateAbout =
            "Desconectaste un dispositivo: pudo llevarse la clave de la copia de tus mensajes. Escribe tu frase secreta: " +
                "la copia pasará a una clave nueva y el dispositivo desconectado ya no podrá abrirla."
        override val copyRotateSend = "Cambiar clave"
        override val copyRotated = "La copia pasó a una clave nueva"
        override val copyStartTitle = "Crear la copia de los mensajes"
        override val copyStartAbout =
            "La copia de claves guarda tus mensajes cifrados en el servidor: un teléfono nuevo con la frase secreta los recupera solo. " +
                "Escribe la frase una vez; después la copia se completa sola."
        override val copyStartSend = "Crear copia"
        override val copyStarted = "La copia de los mensajes está creada"
        override val reregTitle = "Re-registro"
        override val reregAbout =
            "Si alguien más tiene acceso a la cuenta: la frase anterior y un código SMS crean una clave de identidad nueva con una frase nueva. " +
                "Las certificaciones anteriores se retiran y luego se abre una ventana de confirmación."
        override val reregStart = "Re-registrar"
        override val reregEntryTitle = "Re-registro de la cuenta"
        override val reregEntryAbout = "Confirma el número de la cuenta con el código SMS: se creará una clave de identidad nueva con una frase secreta nueva."
        override val reregPhraseAbout =
            "La cuenta se re-registró a una clave de identidad nueva. Las certificaciones anteriores se retiraron: solo funcionan los teléfonos con la frase secreta. " +
                "En la ventana de confirmación —sus fechas están en «Frase secreta y dispositivos»— confirma el re-registro: la frase anterior, " +
                "la frase nueva y un código SMS. Sin confirmación la clave de identidad nueva se eliminará."
        override val reregOpen = "La cuenta se está re-registrando: no se puede crear otra clave de identidad hasta que se resuelva."
        override val reregStale = "La frase anterior no se confirmó: empieza el re-registro de nuevo en «Frase secreta y dispositivos»."
        override fun reregNewAbout(from: String, to: String) =
            "La cuenta se re-registró a una clave de identidad nueva. Las certificaciones anteriores se retiraron: solo funcionan los teléfonos con la frase secreta. " +
                "Entre el $from y el $to confirma el re-registro: la frase anterior, la frase nueva y un código SMS. " +
                "Sin confirmación la clave de identidad nueva se eliminará."
        override val reregOldAbout =
            "Se inició un re-registro de tu cuenta a una clave de identidad nueva. Si no fuiste tú, presenta «Cuenta robada»: " +
                "necesitas la frase y un código SMS. Si fuiste tú, no hace falta nada."
        override val reregClaim = "Cuenta robada"
        override val reregClaimed = "Solicitud aceptada"
        override fun reregClaimedAbout(from: String, to: String) =
            "Solicitud aceptada. La cuenta está en disputa hasta el $to: nadie puede certificar dispositivos ni crear una clave de identidad nueva. " +
                "Entre el $from y el $to confirma la solicitud con la frase y un código SMS."
        override fun reregDisputedNewAbout(from: String, to: String) =
            "La cuenta está en disputa hasta el $to: se presentó «Cuenta robada». Ahora no se pueden certificar dispositivos. " +
                "Entre el $from y el $to confirma el re-registro: dos frases y un código SMS."
        override fun reregWindowNew(to: String) =
            "Es hora de confirmar el re-registro: antes del $to introduce la frase anterior, la nueva y un código SMS. Sin ello la clave de identidad nueva se eliminará."
        override fun reregWindowOld(to: String) = "Es hora de confirmar «Cuenta robada»: antes del $to introduce la frase y un código SMS."
        override val reregConfirm = "Confirmar"
        override val reregConfirmed = "Confirmación aceptada"
        override val tooManyCodes = "Ya se enviaron varios códigos a este número: espera un poco y vuelve a intentarlo."
        override fun reregConfirmedWait(to: String) = "Tu confirmación está aceptada. El resultado llega después del $to, cuando se cierre la ventana de confirmación."
        override val reregOldPhrase = "frase anterior"
        override val reregNewPhrase = "frase nueva"
        override val reregNotInWindow = "La confirmación solo se acepta dentro de la ventana de confirmación."
        override val reregNewWon = "Re-registro confirmado. La clave de identidad anterior se eliminó, sin restricciones: certifica tus dispositivos como siempre."
        override val reregOldLost =
            "Tu clave de identidad se eliminó: el re-registro se confirmó. Puedes luchar por la cuenta —presentar de nuevo— o crear una cuenta nueva con otro número."
        override fun reregNewLost(date: String) =
            "El re-registro no se confirmó. Tu clave de identidad se eliminará el $date, la cuenta queda con la anterior. " +
                "Puedes repetir el procedimiento o crear una cuenta nueva con otro número."
        override val reregOldWon = "Re-registro cancelado: la cuenta vuelve a ser tuya, sin restricciones."
        override fun reregExtended(from: String, to: String) =
            "Confirmaron ambas partes. La disputa se prolonga: nueva ventana de confirmación del $from al $to."
        override val revokedReregistered = "Este dispositivo está desconectado: la cuenta se re-registró. Vuelve a certificarlo desde un teléfono con QR."
        override val revokedDisputed = "Este dispositivo está desconectado: se presentó «Cuenta robada». No se pueden certificar dispositivos hasta el fin de la disputa."
        override val reregFight = "Luchar por la cuenta"
        override val reregNewAccount = "Cuenta nueva"
        override val peerReregistered =
            "El contacto re-registró la cuenta: la clave de identidad cambió. Tus mensajes anteriores siguen aquí. Si dudas de que sea él, compruébalo."
        override fun peerDisputed(to: String) = "La cuenta del contacto está en disputa hasta el $to: se presentó una denuncia de robo."
        override val peerDisputeOver = "La disputa por la cuenta del contacto terminó."
        override val peerBackToOld = "El contacto volvió a la clave de identidad anterior: el chat vuelve a ser con ella."
        override val deviceAddedTitle = "Se añadió un dispositivo nuevo"
        override fun deviceAddedText(platform: String): String {
            val what = when (platform) {
                "android" -> "un teléfono Android"
                "ios" -> "un iPhone"
                "desktop" -> "un ordenador"
                else -> "un dispositivo"
            }
            return "Se conectó $what a tu cuenta. Normalmente es un teléfono nuevo. Si no fuiste tú, desconéctalo en «Frase de recuperación y dispositivos»."
        }
        override val deviceAddedOpen = "Abrir dispositivos"
        override val bannedAbout = "Solo se puede entrar en la cuenta con la frase secreta. La prohibición no se puede quitar."
        override val banDone = "La prohibición está puesta"
        override val startAnewBanned = "El propietario prohibió «Empezar de cero» en esta cuenta. Solo se puede entrar con la frase secreta."
        override val nothingToCancel = "No hay nada que cancelar"
        override val showCertifyCode = "Mostrar código de verificación"
        override val identityChangedLine = "Su contacto cambió la clave de identidad: empezó de nuevo con el mismo número. Los mensajes anteriores siguen con usted, no con él. Si duda de que sea él, pregúntele."
        override val identityRestoredLine = "El propietario canceló la clave de identidad nueva del contacto: la conversación vuelve a ser con la anterior."
        override val identityCancelledMark = "clave de identidad cancelada por el propietario"
        override val certifyCodeAbout = "En su teléfono abra Frase secreta y dispositivos → Escanear código y apunte la cámara a este código"
        override val certifyTitle = "Verificar dispositivo"
        override val certifyAsk = "El dispositivo frente a la cámara pasará a ser de confianza: los contactos también le escribirán. Verifique solo los suyos."
        override val certifyYes = "Verificar"
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
        override val goToCreated = "Abrir el grupo"
        override val shelf = "Sección"
        override val shelfAbout = "dónde ponerlo: se puede mover después con «•••»"
        override val shelfCommon = "General"
        override val newShelfHint = "nueva sección"
        override val createShelf = "Crear"
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
        override val chats = "Chats"
        override val contacts = "Contactos"
        override val calls = "Llamadas"
        override val view = "Vista"
        override val all = "Todas"
        override val fromBook = "De contactos"
        override val unknown = "Desconocidas"
        override val missed = "Perdidas"
        override val common = "Común"
        override val friends = "Amigos"
        override val catalogue = "Catálogo"
        override val feed = "Novedades"
        override val slides = "Diapositivas"
        override val answers = "Respuestas"
        override val reactions = "Reacciones"
        override val collections = "Colecciones"
        override val comments = "Comentarios"
        override val marks = "Valoraciones"
        override val subscribed = "Suscrito"
        override val groups = "Grupos"
        override val media = "Media"
        override val messages = "Mensajes"
        override val open = "Abierto"
        override val personal = "Privado"
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
