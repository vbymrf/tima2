package io.tima.domain.chat


/**
 * Поле человека, которым его можно назвать. Порядок перечисления — порядок по умолчанию в
 * «Виде»; человек его переставляет.
 *
 * `wire` — как поле пишется в настройки; менять нельзя: строки лежат на телефонах.
 */
enum class PersonField(val wire: String) {
    Name("name"),
    UserName("user"),
    Nick("nick"),
    Phone("phone"),
    ;

    companion object {
        fun byWire(wire: String): PersonField? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * Как называть человека — решение заказчика 2026-09-18: **список с порядком и галками**.
 *
 * Что отмечено галкой — показывается через запятую, в порядке списка, из того, что у
 * человека есть. Ничего отмеченного нет — показывается первое сверху, что есть. Нет и
 * этого — вызывающий подставляет своё («Без имени», «Участник»).
 *
 * @param order все поля сверху вниз.
 * @param checked отмеченные галкой.
 */
data class PersonLook(
    val order: List<PersonField> = PersonField.entries,
    val checked: Set<PersonField> = setOf(PersonField.Name, PersonField.Phone),
) {
    companion object {
        val DEFAULT = PersonLook()
    }
}

fun ChatPerson.field(field: PersonField): String? = when (field) {
    PersonField.Name -> name
    PersonField.UserName -> userName
    // Ник — всегда с «@» (заказчик 2026-09-18): так его отличают от имени.
    PersonField.Nick -> nick?.takeIf { it.isNotBlank() }?.let { "@" + it.removePrefix("@") }
    PersonField.Phone -> phone
}?.takeIf { it.isNotBlank() }

/**
 * Строка о человеке по «Виду». [among] — какие поля вообще допускаются в этой строке:
 * у контакта телефон стоит второй строкой всегда, и в первую его не берут.
 *
 * `null` — сказать нечего: ни одного поля у человека нет.
 */
fun ChatPerson.line(look: PersonLook, among: Set<PersonField> = PersonField.entries.toSet()): String? {
    val allowed = look.order.filter { it in among }
    val chosen = allowed.filter { it in look.checked }.mapNotNull { field(it) }
    if (chosen.isNotEmpty()) return chosen.joinToString(", ")
    return allowed.firstNotNullOfOrNull { field(it) }
}

/**
 * Буква аватара, когда картинки нет: первая буква ника, иначе имени, иначе имени
 * пользователя, иначе «+» (решение заказчика 2026-09-18: не «?»).
 */
fun ChatPerson.letter(): String =
    listOf(nick, name, userName)
        .firstNotNullOfOrNull { text -> text?.firstOrNull { it.isLetterOrDigit() } }
        ?.uppercase()
        ?: "+"
