package io.tima.core.words

/**
 * Вид группы — где аватар автора (пробы `пробы-вид-группы.html`, названия приняты заказчиком
 * 2026-10-07). Переключатель в «Виде» «Социума», как «Экономичный режим» в «Уведомлениях».
 */
interface GroupLookWords {
    val title: String
    val about: String
    val free: String
    val freeAbout: String
    val freeShort: String
    val edge: String
    val edgeAbout: String
    val edgeShort: String
    val inside: String
    val insideAbout: String
    val insideShort: String
}

object RussianGroupLookWords : GroupLookWords {
    override val title = "Аватар в группе"
    override val about = "где стоит аватар автора сообщения"
    override val free = "Аватар свободно"
    override val freeAbout = "аватар над пузырём, имя рядом"
    override val freeShort = "свободно"
    override val edge = "Аватар у края"
    override val edgeAbout = "аватар у края экрана поверх полосы, имя рядом"
    override val edgeShort = "у края"
    override val inside = "Имя в аватаре"
    override val insideAbout = "аватары колонкой слева, имя — мелко в аватаре"
    override val insideShort = "имя в аватаре"
}

object EnglishGroupLookWords : GroupLookWords {
    override val title = "Avatar in a group"
    override val about = "where the author's avatar stands"
    override val free = "Avatar floating"
    override val freeAbout = "avatar above the bubble, name next to it"
    override val freeShort = "floating"
    override val edge = "Avatar at the edge"
    override val edgeAbout = "avatar at the screen edge over the stripe, name next to it"
    override val edgeShort = "at the edge"
    override val inside = "Name in the avatar"
    override val insideAbout = "avatars in a column on the left, name small inside the avatar"
    override val insideShort = "name in avatar"
}

object SpanishGroupLookWords : GroupLookWords {
    override val title = "Avatar en el grupo"
    override val about = "dónde va el avatar del autor"
    override val free = "Avatar libre"
    override val freeAbout = "avatar sobre la burbuja, nombre al lado"
    override val freeShort = "libre"
    override val edge = "Avatar en el borde"
    override val edgeAbout = "avatar en el borde de la pantalla sobre la franja, nombre al lado"
    override val edgeShort = "en el borde"
    override val inside = "Nombre en el avatar"
    override val insideAbout = "avatares en columna a la izquierda, nombre pequeño dentro"
    override val insideShort = "nombre en avatar"
}
