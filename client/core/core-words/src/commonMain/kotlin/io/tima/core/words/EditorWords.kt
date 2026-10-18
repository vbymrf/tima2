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
        "table" -> "Таблица"
        "cellAdd" -> "+Ячейка"
        "cellRemove" -> "−Ячейка"
        "merge" -> "Объедин."
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
        "LIST" -> "Список"
        "LIST2" -> "Список, второй уровень"
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
        "table" -> "Table"
        "cellAdd" -> "+Cell"
        "cellRemove" -> "−Cell"
        "merge" -> "Merge"
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
        "LIST" -> "List"
        "LIST2" -> "List, second level"
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
        "table" -> "Tabla"
        "cellAdd" -> "+Celda"
        "cellRemove" -> "−Celda"
        "merge" -> "Unir"
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
        "LIST" -> "Lista"
        "LIST2" -> "Lista, segundo nivel"
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
}
