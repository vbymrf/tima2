package io.tima.core.words

/**
 * Редактор сообщений (doc_mig/РЕДАКТОР-СООБЩЕНИЙ.md §10, ПЛАН-(РС) РС6). Подписи кнопок — по
 * ключу: ключ латиницей живёт в коде и в метке `editor:<ключ>`, подпись — здесь.
 */
interface EditorWords {
    val title: String
    /** Подпись под названием, пока показ «глазами получателя». */
    val previewOn: String
    /** Разделы: `text`, `insert`, `layout`, `style`. */
    fun tab(key: String): String
    /** Подпись кнопки ленты по её ключу. */
    fun button(key: String): String
    /** Буква на кнопке оформления — `bold`, `italic`, `underline`, `strike`, `highlight`; у остальных `null`. */
    fun letter(key: String): String?
    /** «Абзац ▾»: `PARAGRAPH`, `HEADING1`…`HEADING3`, `QUOTE`, `LIST`, `LIST2`, `CODE`. */
    fun paragraph(kind: String): String
    /** Области «Символ»: `recent`, `signs`, `arrows`, `currency`. */
    fun symbols(group: String): String
    val recentEmpty: String
    val nbsp: String
    val levelTitle: String
    /** Строка в разделителе: что выбрано — `text`, `row`, `cell`, `widget`. */
    fun selected(what: String): String
    val cannotSend: String
    val marksOn: String
    val marksOff: String
    /** Раздел ещё не сделан — следующим срезом. */
    val soon: String

    // ── Вставка (§10.4) ──
    /** Вид ссылки: `plain`, `button`, `card`. */
    fun linkMode(key: String): String
    val linkAddress: String
    val linkTitle: String
    val cardFailed: String
    val pictureFailed: String
    val replyTitle: String
    val replyEmpty: String
    val picksTitle: String
    val picksEmpty: String
    /** Сообщение из подборки, которого нет на этом устройстве. */
    fun pickElsewhere(serverId: Long): String
    /** Пометка на кнопке ссылки без шифрования (`http://`). */
    val unsafeLink: String

    // ── Стиль (§10.2) ──
    /** Имя цвета палитры: `amber-soft` и прочие (`MarkupRules.PALETTE`). */
    fun paletteName(key: String): String
    /** Ступень свойства: `size` — `smaller`…, `align`, `valign`, `padding`, `divider`. */
    fun stepName(key: String, value: String): String
    val none: String
    val apply: String
    val jsonBad: String
    fun tableTooWide(max: Int): String
    /** Ряд не помещается (заказчик 2026-10-18): ячейке узко для её содержимого. */
    val rowTooNarrow: String
    /** «Разметка → Ряд»: сколько строк и столбцов (заказчик 2026-10-18). */
    val gridLines: String
    val gridColumns: String
    val gridInsert: String
    /** Слить нельзя: прямоугольник задел бы другое слитое лишь частью, или сливать не с чем. */
    val joinRefused: String
    /** Список (ADR-0049): пункт ступени без родителя — над ним этот; правится как любой текст. */
    val untitled: String
    /** Подсказка под пунктом: что можно положить в пункт. */
    val listContent: String
    /** Пункт списка не помещается: виджету узко с отступом пункта. */
    val listTooNarrow: String
    /** «Разметка → Список» (ADR-0049 §3): свойства всего списка. */
    val listOpen: String
    val listFlat: String
    /** «Список» нажат вне списка. */
    val listChoose: String
    /** «Разметка → Виджет» (ПЛАН-(ВД)): виджеты с особым действием. */
    /** «Абзац ▾», второй ряд: «Список: 1 2 3 уровень» (заказчик 2026-10-19). */
    val listRow: String
    val levelWord: String
    val widgetPoll: String
    val widgetEvent: String
    val widgetClips: String
    val widgetAudio: String
    val widgetSoon: String
    /** Вставленное из подборки: `full`, `cut`. */
    fun cutName(key: String): String
    val authorShown: String
    val nothingChosen: String
    /** Перетащили файл в редактор группы: файлы там — только ссылкой на облако (Решения §2). */
    val filesByLink: String
    /** Перетащенный файл ушёл отдельным сообщением вне пузыря (Решения §2). */
    fun fileSentApart(name: String): String

    // ── Личная страница (§10.2) ──
    val pageWrite: String
    val pageHasFiles: String
    val pagePublished: String
    val pageNoPictures: String

    // ── Подкладка и продолжение (§11.3) ──
    val underlayNew: String
    val underlayNewAbout: String
    fun continuationMarker(level: Int): String
    val continuationSent: String
    fun underlayLines(n: Int): String
}

object RussianEditorWords : EditorWords {
    override val title = "Редактор"
    override fun letter(key: String) = when (key) {
        "bold" -> "Ж"
        "italic" -> "К"
        "underline" -> "Ч"
        "strike" -> "З"
        "highlight" -> "М"
        else -> null
    }
    override val previewOn = "Так увидит получатель"
    override fun tab(key: String) = when (key) {
        "text" -> "Текст"
        "insert" -> "Вставка"
        "layout" -> "Разметка"
        else -> "Стиль"
    }
    override fun button(key: String) = when (key) {
        "paragraph" -> "Абзац"
        "bold" -> "Жирный"
        "italic" -> "Курсив"
        "underline" -> "Подчёрк."
        "strike" -> "Зачёрк."
        "highlight" -> "Маркер"
        "code" -> "Код"
        "symbol" -> "Символ"
        "paste" -> "С оформл."
        "marks" -> "Знаки md"
        "picture" -> "Картинка"
        "link" -> "Ссылка"
        "emoji" -> "Эмоджи"
        "picks" -> "Подборка"
        "underlay" -> "Подкладка"
        "cloud" -> "Облако"
        "place" -> "Карта"
        "contact" -> "Контакт"
        "reply" -> "Ответ"
        "row" -> "Ряд"
        "list" -> "Список"
        "props" -> "Свойства"
        "colAdd" -> "+Столбец"
        "colRemove" -> "−Столбец"
        "lineAdd" -> "+Строка"
        "lineRemove" -> "−Строка"
        "joinLeft" -> "С левой"
        "joinUp" -> "С верхней"
        "split" -> "Разъедин."
        "up" -> "Выше"
        "down" -> "Ниже"
        "delete" -> "Удалить"
        "widget" -> "Виджет"
        "background" -> "Фон"
        "color" -> "Цвет"
        "size" -> "Размер"
        "align" -> "Выровн."
        "padding" -> "Отступ"
        "divider" -> "Раздел."
        "reset" -> "Сброс"
        "json" -> "JSON"
        else -> key
    }
    override fun paragraph(kind: String) = when (kind) {
        "HEADING1" -> "Заголовок 1"
        "HEADING2" -> "Заголовок 2"
        "HEADING3" -> "Заголовок 3"
        "QUOTE" -> "Цитата"
        "LIST" -> "Список 1 уровень"
        "LIST2" -> "Список 2 уровень"
        "LIST3" -> "Список 3 уровень"
        "CODE" -> "Блок кода"
        else -> "Абзац"
    }
    override fun symbols(group: String) = when (group) {
        "recent" -> "Недавние"
        "signs" -> "Знаки"
        "arrows" -> "Стрелки"
        else -> "Валюта"
    }
    override val recentEmpty = "Здесь будут знаки, которые вы вставляли"
    override val nbsp = "Неразрывный пробел"
    override val levelTitle = "Уровень сообщения"
    override fun selected(what: String) = "Выбрано: " + when (what) {
        "row" -> "ряд"
        "cell" -> "ячейка"
        "widget" -> "виджет"
        "item" -> "пункт списка"
        else -> "текст"
    }
    override val cannotSend = "Отправить нельзя"
    override val marksOn = "Знаки md включены: знаки становятся оформлением"
    override val marksOff = "Знаки md выключены: текст уходит как набран"
    override val soon = "Этот раздел — следующим срезом редактора"
    override fun linkMode(key: String) = when (key) {
        "button" -> "Кнопкой"
        "card" -> "Карточкой"
        else -> "Текстом"
    }
    override val linkAddress = "Адрес"
    override val linkTitle = "Название (можно пусто)"
    override val cardFailed = "Карточку со страницы не собрать — вставлена кнопка"
    override val pictureFailed = "Картинка не загрузилась: нет связи или это не картинка"
    override val replyTitle = "На какое сообщение ответить"
    override val replyEmpty = "Отвечать пока не на что"
    override val picksTitle = "Подборка"
    override val picksEmpty = "В подборке пусто: добавьте сообщение из его меню"
    override fun pickElsewhere(serverId: Long) = "Сообщение № $serverId"
    override val unsafeLink = "без шифрования"
    override fun paletteName(key: String) = when (key.removeSuffix("-soft")) {
        "text" -> "текст"
        "surface" -> "фон пузыря"
        "accent" -> "акцент"
        "amber" -> "янтарь"
        "green" -> "зелёный"
        "blue" -> "синий"
        "red" -> "красный"
        "grey" -> "серый"
        else -> key
    } + if (key.endsWith("-soft")) ", мягкий" else ""
    override fun stepName(key: String, value: String) = when (key to value) {
        "size" to "smaller" -> "Мельче"
        "size" to "normal", "padding" to "normal" -> "Обычный"
        "size" to "larger" -> "Крупнее"
        "align" to "start" -> "Слева"
        "align" to "center", "valign" to "center" -> "По центру"
        "align" to "end" -> "Справа"
        "valign" to "top" -> "Сверху"
        "valign" to "bottom" -> "Снизу"
        "padding" to "none" -> "Нет"
        "padding" to "small" -> "Малый"
        "padding" to "large" -> "Большой"
        "divider" to "hidden" -> "Без линии"
        else -> if (key == "divider") "Линия $value" else value
    }
    override val none = "Нет"
    override val apply = "Применить"
    override val jsonBad = "Это не JSON из строк: {\"свойство\": \"значение\"}"
    override fun tableTooWide(max: Int) = "Таблица не помещается: больше $max столбцов — в ячейку не влезет «99». Измените таблицу"
    override val rowTooNarrow = "Ряд не помещается: ячейке узко для её содержимого — раздвиньте рамку или уберите столбец"
    override val gridLines = "Строк"
    override val gridColumns = "Столбцов"
    override val gridInsert = "Вставить ряд"
    override val joinRefused = "Слить нельзя: не с чем или слитое вышло бы не прямоугольником"
    override val untitled = "Без имени"
    override val listContent = "Под пунктом: текст, код, виджеты"
    override val listTooNarrow = "Пункт списка не помещается: виджету узко с отступом — включите «Без отступа» или уберите виджет"
    override val listOpen = "Развернуть по умолчанию"
    override val listFlat = "Без отступа"
    override val listChoose = "Встаньте в список, чтобы задать его свойства"
    override val listRow = "Список:"
    override val levelWord = "уровень"
    override val widgetPoll = "Опрос"
    override val widgetEvent = "Мероприятие"
    override val widgetClips = "Видеоклипы"
    override val widgetAudio = "Аудио"
    override val widgetSoon = "Этот виджет появится следующими срезами"
    override fun cutName(key: String) = if (key == "cut") "Обрезать" else "Полностью"
    override val authorShown = "Имя владельца"
    override val nothingChosen = "Выберите ряд, ячейку или виджет"
    override val filesByLink = "В группе файлы — только ссылкой на облако: «Вставка» → «Облако»"
    override fun fileSentApart(name: String) = "«$name» ушёл отдельным сообщением: файлы живут вне пузыря"
    override val pageWrite = "Написать"
    override val pageHasFiles = "На странице записи без картинок: уберите картинку или карточку с картинкой"
    override val pagePublished = "Запись на странице"
    override val pageNoPictures = "Картинок в записи страницы пока нет"
    override val underlayNew = "Новое продолжение своего уровня"
    override val underlayNewAbout = "Закрытая часть уйдёт отдельным сообщением и подложится в конец; в ленте сама не видна"
    override fun continuationMarker(level: Int) = "Продолжение · уровень $level — ниже пишется закрытая часть"
    override val continuationSent = "Продолжение ушло; основное уйдёт, как только сервер его примет"
    override fun underlayLines(n: Int) = when {
        n % 10 == 1 && n % 100 != 11 -> "$n строка"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "$n строки"
        else -> "$n строк"
    }
}

object EnglishEditorWords : EditorWords {
    override val title = "Editor"
    override val previewOn = "As the recipient sees it"
    override fun letter(key: String) = when (key) {
        "bold" -> "B"
        "italic" -> "I"
        "underline" -> "U"
        "strike" -> "S"
        "highlight" -> "H"
        else -> null
    }
    override fun tab(key: String) = when (key) {
        "text" -> "Text"
        "insert" -> "Insert"
        "layout" -> "Layout"
        else -> "Style"
    }
    override fun button(key: String) = when (key) {
        "paragraph" -> "Paragraph"
        "bold" -> "Bold"
        "italic" -> "Italic"
        "underline" -> "Underline"
        "strike" -> "Strike"
        "highlight" -> "Marker"
        "code" -> "Code"
        "symbol" -> "Symbol"
        "paste" -> "Formatted"
        "marks" -> "md signs"
        "picture" -> "Picture"
        "link" -> "Link"
        "emoji" -> "Emoji"
        "picks" -> "Picks"
        "underlay" -> "Underlay"
        "cloud" -> "Cloud"
        "place" -> "Map"
        "contact" -> "Contact"
        "reply" -> "Reply"
        "row" -> "Row"
        "list" -> "List"
        "props" -> "Properties"
        "colAdd" -> "+Column"
        "colRemove" -> "−Column"
        "lineAdd" -> "+Line"
        "lineRemove" -> "−Line"
        "joinLeft" -> "With left"
        "joinUp" -> "With above"
        "split" -> "Split"
        "up" -> "Up"
        "down" -> "Down"
        "delete" -> "Delete"
        "widget" -> "Widget"
        "background" -> "Fill"
        "color" -> "Color"
        "size" -> "Size"
        "align" -> "Align"
        "padding" -> "Padding"
        "divider" -> "Divider"
        "reset" -> "Reset"
        "json" -> "JSON"
        else -> key
    }
    override fun paragraph(kind: String) = when (kind) {
        "HEADING1" -> "Heading 1"
        "HEADING2" -> "Heading 2"
        "HEADING3" -> "Heading 3"
        "QUOTE" -> "Quote"
        "LIST" -> "List level 1"
        "LIST2" -> "List level 2"
        "LIST3" -> "List level 3"
        "CODE" -> "Code block"
        else -> "Paragraph"
    }
    override fun symbols(group: String) = when (group) {
        "recent" -> "Recent"
        "signs" -> "Signs"
        "arrows" -> "Arrows"
        else -> "Currency"
    }
    override val recentEmpty = "Symbols you insert will appear here"
    override val nbsp = "Non-breaking space"
    override val levelTitle = "Message level"
    override fun selected(what: String) = "Selected: " + when (what) {
        "row" -> "row"
        "cell" -> "cell"
        "widget" -> "widget"
        "item" -> "list item"
        else -> "text"
    }
    override val cannotSend = "Cannot send"
    override val marksOn = "md signs on: signs become formatting"
    override val marksOff = "md signs off: text is sent as typed"
    override val soon = "This section comes with the next editor slice"
    override fun linkMode(key: String) = when (key) {
        "button" -> "Button"
        "card" -> "Card"
        else -> "Text"
    }
    override val linkAddress = "Address"
    override val linkTitle = "Title (optional)"
    override val cardFailed = "Could not build a card from the page — a button was inserted"
    override val pictureFailed = "The picture did not upload: no connection or not a picture"
    override val replyTitle = "Which message to reply to"
    override val replyEmpty = "Nothing to reply to yet"
    override val picksTitle = "Picks"
    override val picksEmpty = "Picks are empty: add a message from its menu"
    override fun pickElsewhere(serverId: Long) = "Message #$serverId"
    override val unsafeLink = "not encrypted"
    override fun paletteName(key: String) = when (key.removeSuffix("-soft")) {
        "text" -> "text"
        "surface" -> "bubble"
        "accent" -> "accent"
        "amber" -> "amber"
        "green" -> "green"
        "blue" -> "blue"
        "red" -> "red"
        "grey" -> "grey"
        else -> key
    } + if (key.endsWith("-soft")) ", soft" else ""
    override fun stepName(key: String, value: String) = when (key to value) {
        "size" to "smaller" -> "Smaller"
        "size" to "normal", "padding" to "normal" -> "Normal"
        "size" to "larger" -> "Larger"
        "align" to "start" -> "Left"
        "align" to "center", "valign" to "center" -> "Center"
        "align" to "end" -> "Right"
        "valign" to "top" -> "Top"
        "valign" to "bottom" -> "Bottom"
        "padding" to "none" -> "None"
        "padding" to "small" -> "Small"
        "padding" to "large" -> "Large"
        "divider" to "hidden" -> "No line"
        else -> if (key == "divider") "Line $value" else value
    }
    override val none = "None"
    override val apply = "Apply"
    override val jsonBad = "Not a JSON of strings: {\"property\": \"value\"}"
    override fun tableTooWide(max: Int) = "The table does not fit: more than $max columns — \"99\" will not fit in a cell. Change the table"
    override val rowTooNarrow = "The row does not fit: a cell is too narrow for its content — move the border or remove a column"
    override val gridLines = "Lines"
    override val gridColumns = "Columns"
    override val gridInsert = "Insert row"
    override val joinRefused = "Can't merge: nothing to merge with, or the merged area would not be a rectangle"
    override val untitled = "Untitled"
    override val listContent = "Under the item: text, code, widgets"
    override val listTooNarrow = "A list item does not fit: the widget is too narrow with the indent — turn on \"No indent\" or remove the widget"
    override val listOpen = "Expanded by default"
    override val listFlat = "No indent"
    override val listChoose = "Put the cursor in a list to set its properties"
    override val listRow = "List:"
    override val levelWord = "level"
    override val widgetPoll = "Poll"
    override val widgetEvent = "Event"
    override val widgetClips = "Video clips"
    override val widgetAudio = "Audio"
    override val widgetSoon = "This widget comes in the next slices"
    override fun cutName(key: String) = if (key == "cut") "Trim" else "Full"
    override val authorShown = "Owner's name"
    override val nothingChosen = "Choose a row, cell or widget"
    override val filesByLink = "In a group, files go only as a cloud link: Insert → Cloud"
    override fun fileSentApart(name: String) = "\"$name\" was sent as a separate message: files live outside the bubble"
    override val pageWrite = "Write"
    override val pageHasFiles = "Page posts have no pictures: remove the picture or the card with a picture"
    override val pagePublished = "Posted on the page"
    override val pageNoPictures = "Page posts cannot have pictures yet"
    override val underlayNew = "New continuation at its own level"
    override val underlayNewAbout = "The closed part goes as a separate message attached at the end; it does not show in the feed by itself"
    override fun continuationMarker(level: Int) = "Continuation · level $level — write the closed part below"
    override val continuationSent = "The continuation is sent; the main part follows once the server accepts it"
    override fun underlayLines(n: Int) = if (n == 1) "1 line" else "$n lines"
}

object SpanishEditorWords : EditorWords {
    override val title = "Editor"
    override val previewOn = "Así lo verá el destinatario"
    override fun letter(key: String) = when (key) {
        "bold" -> "N"
        "italic" -> "C"
        "underline" -> "S"
        "strike" -> "T"
        "highlight" -> "M"
        else -> null
    }
    override fun tab(key: String) = when (key) {
        "text" -> "Texto"
        "insert" -> "Insertar"
        "layout" -> "Diseño"
        else -> "Estilo"
    }
    override fun button(key: String) = when (key) {
        "paragraph" -> "Párrafo"
        "bold" -> "Negrita"
        "italic" -> "Cursiva"
        "underline" -> "Subray."
        "strike" -> "Tachado"
        "highlight" -> "Marcador"
        "code" -> "Código"
        "symbol" -> "Símbolo"
        "paste" -> "Con formato"
        "marks" -> "Signos md"
        "picture" -> "Imagen"
        "link" -> "Enlace"
        "emoji" -> "Emoji"
        "picks" -> "Selección"
        "underlay" -> "Fondo"
        "cloud" -> "Nube"
        "place" -> "Mapa"
        "contact" -> "Contacto"
        "reply" -> "Respuesta"
        "row" -> "Fila"
        "list" -> "Lista"
        "props" -> "Propiedades"
        "colAdd" -> "+Columna"
        "colRemove" -> "−Columna"
        "lineAdd" -> "+Línea"
        "lineRemove" -> "−Línea"
        "joinLeft" -> "Con izquierda"
        "joinUp" -> "Con superior"
        "split" -> "Separar"
        "up" -> "Arriba"
        "down" -> "Abajo"
        "delete" -> "Borrar"
        "widget" -> "Widget"
        "background" -> "Relleno"
        "color" -> "Color"
        "size" -> "Tamaño"
        "align" -> "Alinear"
        "padding" -> "Margen"
        "divider" -> "Divisor"
        "reset" -> "Restablecer"
        "json" -> "JSON"
        else -> key
    }
    override fun paragraph(kind: String) = when (kind) {
        "HEADING1" -> "Título 1"
        "HEADING2" -> "Título 2"
        "HEADING3" -> "Título 3"
        "QUOTE" -> "Cita"
        "LIST" -> "Lista nivel 1"
        "LIST2" -> "Lista nivel 2"
        "LIST3" -> "Lista nivel 3"
        "CODE" -> "Bloque de código"
        else -> "Párrafo"
    }
    override fun symbols(group: String) = when (group) {
        "recent" -> "Recientes"
        "signs" -> "Signos"
        "arrows" -> "Flechas"
        else -> "Moneda"
    }
    override val recentEmpty = "Aquí aparecerán los símbolos que insertes"
    override val nbsp = "Espacio de no separación"
    override val levelTitle = "Nivel del mensaje"
    override fun selected(what: String) = "Seleccionado: " + when (what) {
        "row" -> "fila"
        "cell" -> "celda"
        "widget" -> "widget"
        "item" -> "punto de la lista"
        else -> "texto"
    }
    override val cannotSend = "No se puede enviar"
    override val marksOn = "Signos md activados: los signos se convierten en formato"
    override val marksOff = "Signos md desactivados: el texto se envía tal cual"
    override val soon = "Esta sección llega con la siguiente parte del editor"
    override fun linkMode(key: String) = when (key) {
        "button" -> "Botón"
        "card" -> "Tarjeta"
        else -> "Texto"
    }
    override val linkAddress = "Dirección"
    override val linkTitle = "Título (opcional)"
    override val cardFailed = "No se pudo crear la tarjeta de la página: se insertó un botón"
    override val pictureFailed = "La imagen no se subió: sin conexión o no es una imagen"
    override val replyTitle = "A qué mensaje responder"
    override val replyEmpty = "Aún no hay nada a qué responder"
    override val picksTitle = "Selección"
    override val picksEmpty = "La selección está vacía: añade un mensaje desde su menú"
    override fun pickElsewhere(serverId: Long) = "Mensaje n.º $serverId"
    override val unsafeLink = "sin cifrado"
    override fun paletteName(key: String) = when (key.removeSuffix("-soft")) {
        "text" -> "texto"
        "surface" -> "burbuja"
        "accent" -> "acento"
        "amber" -> "ámbar"
        "green" -> "verde"
        "blue" -> "azul"
        "red" -> "rojo"
        "grey" -> "gris"
        else -> key
    } + if (key.endsWith("-soft")) ", suave" else ""
    override fun stepName(key: String, value: String) = when (key to value) {
        "size" to "smaller" -> "Menor"
        "size" to "normal", "padding" to "normal" -> "Normal"
        "size" to "larger" -> "Mayor"
        "align" to "start" -> "Izquierda"
        "align" to "center", "valign" to "center" -> "Centro"
        "align" to "end" -> "Derecha"
        "valign" to "top" -> "Arriba"
        "valign" to "bottom" -> "Abajo"
        "padding" to "none" -> "Ninguno"
        "padding" to "small" -> "Pequeño"
        "padding" to "large" -> "Grande"
        "divider" to "hidden" -> "Sin línea"
        else -> if (key == "divider") "Línea $value" else value
    }
    override val none = "Ninguno"
    override val apply = "Aplicar"
    override val jsonBad = "No es un JSON de cadenas: {\"propiedad\": \"valor\"}"
    override fun tableTooWide(max: Int) = "La tabla no cabe: más de $max columnas, \"99\" no cabe en una celda. Cambia la tabla"
    override val rowTooNarrow = "La fila no cabe: una celda es estrecha para su contenido; mueve el borde o quita una columna"
    override val gridLines = "Líneas"
    override val gridColumns = "Columnas"
    override val gridInsert = "Insertar fila"
    override val joinRefused = "No se puede unir: no hay con qué o lo unido no sería un rectángulo"
    override val untitled = "Sin nombre"
    override val listContent = "Bajo el punto: texto, código, widgets"
    override val listTooNarrow = "Un punto de la lista no cabe: el widget es estrecho con la sangría; activa «Sin sangría» o quita el widget"
    override val listOpen = "Desplegada por defecto"
    override val listFlat = "Sin sangría"
    override val listChoose = "Sitúate en una lista para ajustar sus propiedades"
    override val listRow = "Lista:"
    override val levelWord = "nivel"
    override val widgetPoll = "Encuesta"
    override val widgetEvent = "Evento"
    override val widgetClips = "Videoclips"
    override val widgetAudio = "Audio"
    override val widgetSoon = "Este widget llegará en las próximas partes"
    override fun cutName(key: String) = if (key == "cut") "Recortar" else "Completo"
    override val authorShown = "Nombre del dueño"
    override val nothingChosen = "Elige una fila, celda o widget"
    override val filesByLink = "En un grupo, los archivos solo van como enlace a la nube: Insertar → Nube"
    override fun fileSentApart(name: String) = "\"$name\" se envió como mensaje aparte: los archivos van fuera de la burbuja"
    override val pageWrite = "Escribir"
    override val pageHasFiles = "Las publicaciones de la página no llevan imágenes: quita la imagen o la tarjeta con imagen"
    override val pagePublished = "Publicado en la página"
    override val pageNoPictures = "Las publicaciones de la página aún no admiten imágenes"
    override val underlayNew = "Nueva continuación con su propio nivel"
    override val underlayNewAbout = "La parte cerrada se envía como mensaje aparte y se adjunta al final; sola no aparece en el feed"
    override fun continuationMarker(level: Int) = "Continuación · nivel $level — escribe abajo la parte cerrada"
    override val continuationSent = "La continuación se envió; la parte principal irá en cuanto el servidor la acepte"
    override fun underlayLines(n: Int) = if (n == 1) "1 línea" else "$n líneas"
}
