package io.tima.domain.chat

/**
 * Копия книги и разделов, которая едет между устройствами человека (ПЛАН-РАЗДЕЛОВ Р2а).
 *
 * ── ЧТО ЕДЕТ ────────────────────────────────────────────────────────────────
 *
 * Только то, что человек сделал сам: своё имя контакта, раздел, заведён ли руками, убран
 * ли; разделы с именем, значком, порядком и надгробием. Имя из телефонной книги не едет —
 * у второго устройства своя телефонная книга. Кто в TIMa (`userId`) не едет — второе
 * устройство сверит само, и это правда его момента, а не чужого.
 *
 * ── ШТАМП НА КАЖДОЙ ЗАПИСИ ──────────────────────────────────────────────────
 *
 * Решение заказчика 2026-09-18: слияние ПОСТРОЧНО. У каждой записи — когда менялась и
 * каким устройством. При слиянии двух копий побеждает поздняя запись; конфликт остаётся
 * только на одной и той же записи, правленной с двух сторон, — и там тоже побеждает
 * поздняя. Одна ревизия на весь блоб этого не умела бы: два пишущих устройства затирали
 * бы друг друга целиком.
 *
 * Надгробия (`hidden` у контакта, `deleted` у раздела) — записи, а не отсутствие: без них
 * убранное вернулось бы с устройства, которое об удалении не знало.
 */
data class BookCopy(
    /** Ревизия у сервера, из которой собрана копия. Внутри блоба — чтобы подмену блоба было видно. */
    val revision: Long,
    /** Устройство, собравшее копию. */
    val device: String,
    val contacts: List<CopyContact>,
    val sections: List<CopySection>,
) {
    companion object {
        val EMPTY = BookCopy(revision = 0, device = "", contacts = emptyList(), sections = emptyList())

        /**
         * Слить две копии: по записям, поздний штамп побеждает.
         *
         * Записи без пары берутся как есть. При равных штампах остаётся [ours] — это
         * детерминированно и не зависит от порядка аргументов у вызывающего только при
         * условии, что он всегда зовёт `merge(ours, theirs)`; так и есть.
         */
        fun merge(ours: BookCopy, theirs: BookCopy): BookCopy {
            val contacts = (ours.contacts.associateBy { it.phone } to theirs.contacts.associateBy { it.phone })
                .let { (a, b) -> (a.keys + b.keys).map { key -> newer(a[key], b[key]) { it.updatedAt } } }
            val sections = (ours.sections.associateBy { it.id } to theirs.sections.associateBy { it.id })
                .let { (a, b) -> (a.keys + b.keys).map { key -> newer(a[key], b[key]) { it.updatedAt } } }
            return BookCopy(
                revision = maxOf(ours.revision, theirs.revision),
                device = ours.device,
                contacts = contacts.sortedBy { it.phone },
                sections = sections.sortedWith(compareBy({ it.place }, { it.name })),
            )
        }

        private fun <T : Any> newer(a: T?, b: T?, stamp: (T) -> Long): T = when {
            a == null -> b!!
            b == null -> a
            stamp(b) > stamp(a) -> b
            else -> a
        }
    }
}

data class CopyContact(
    val phone: String,
    val nameOwn: String?,
    val sectionId: String,
    val manual: Boolean,
    /** Надгробие: человек убрал контакт. */
    val hidden: Boolean,
    val updatedAt: Long,
    val device: String,
)

data class CopySection(
    val id: String,
    val name: String,
    val icon: Int,
    val place: Int,
    /** Надгробие: раздел убран. */
    val deleted: Boolean,
    val updatedAt: Long,
    val device: String,
)

/** Порт к своей копии: собрать снимок и применить чужой. Реализуется `core-database`. */
interface BookCopyPort {
    suspend fun snapshot(): BookCopy

    /**
     * Применить записи, которые моложе наших. Уже слитый результат применять целиком
     * нельзя: между снимком и применением человек мог что-то поправить, и его правка
     * моложе всего, что пришло.
     */
    suspend fun apply(theirs: BookCopy)
}
