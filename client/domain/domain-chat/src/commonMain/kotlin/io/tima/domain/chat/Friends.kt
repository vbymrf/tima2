package io.tima.domain.chat

/**
 * Друзья — ПЛАН-КОНТАКТОВ.md, Д1б и Д7.
 *
 * **Сущность живёт здесь, а не на сервере.** Сервер знает только, кому открыта моя
 * лента; слова «друзья» у него нет. Отдельная таблица друзей на сервере дублировала бы
 * подписку — снята в тот же день, миграцией 0040.
 *
 * **Дружба односторонняя и асимметричная**: я дружу с тобой — ты видишь мою ленту; ты со
 * мной не дружишь — я твою не вижу. Законны все четыре состояния пары.
 *
 * **Мой список открывает мою ленту.** Поэтому [set] — это «открыть ему свою ленту», а не
 * «подписаться на его».
 */
fun interface Friends {

    /**
     * Открыть человеку свою ленту или закрыть.
     *
     * @return `true`, если сервер согласился. Отказ здесь не поломка: человека могло не
     *   оказаться, сети могло не быть, — и книга от этого не меняется.
     */
    suspend fun set(userId: String, friend: Boolean): Boolean
}

/**
 * Добавить человека в контакты — и, если он в TIMa, в друзья.
 *
 * **Два действия, а не одно, но одно нажатие.** «Есть в контактах — друг» (решение
 * заказчика 2026-08-25), и заставлять человека отдельно «подписаться» значило бы
 * спрашивать дважды об одном.
 *
 * **Подписка не обязана удаться.** Контакт сохраняется в любом случае: без сети человек
 * всё равно добавил номер себе в книгу, и терять его из-за молчания сервера не за что.
 */
class AddContact(
    private val book: Book,
    private val friends: Friends,
    private val discovery: ContactDiscovery,
) {

    /**
     * @param raw номер как его набрал человек: «8 916…», «+7 (916)…» — приводится к E.164.
     * @return что вышло. Ненайденный номер — **не отказ**: контакт сохраняется, и кнопка
     *   до нажатия честно говорит «Добавить в контакты», а не «Написать».
     */
    suspend fun add(raw: String, name: String?, sectionId: String = ""): AddStep {
        val phone = normalizePhone(raw) ?: return AddStep.BadPhone

        val userId = try {
            discovery.discover(listOf(phone))[phone]
        } catch (_: Exception) {
            // Без сети сверить нельзя. Контакт всё равно заводится: следующая
            // синхронизация спросит о нём снова (SyncBook сверяет всю книгу).
            null
        }

        book.addManually(phone, name, sectionId)
        if (!userId.isNullOrBlank()) {
            book.matched(mapOf(phone to userId))
            val subscribed = friends.set(userId, friend = true)
            return AddStep.InTima(phone, userId, subscribed = subscribed)
        }
        return AddStep.OnlyPhone(phone)
    }
}

/**
 * Убрать человека из книги.
 *
 * **И закрыть ему свою ленту** (решение заказчика 2026-09-05): «есть в контактах — друг»
 * читается в обе стороны. Иначе убранный из книги продолжал бы видеть то, что человек
 * пишет для своих.
 */
class RemoveContact(
    private val book: Book,
    private val friends: Friends,
) {

    suspend fun remove(entry: BookEntry) = moveTo(entry, BookList.Removed)

    /**
     * Заблокировать — Л3, Л7, Л13.
     *
     * `known` ставится **здесь и один раз**: «был ли он в контактах до блокировки».
     * Проверять это потом поздно — строка книги к тому времени есть в любом случае,
     * блокировка её и заводит. Признак нужен автоответу (Л14): знакомому мы говорим,
     * что он заблокирован, незнакомец остаётся в неведении.
     */
    suspend fun block(entry: BookEntry) =
        moveTo(entry, BookList.Blocked, known = entry.inContacts)

    /** Вернуть в контакты: из «Убранных» или из «Заблокированных». */
    suspend fun restore(entry: BookEntry) = moveTo(entry, BookList.Usual)

    /**
     * Переложить в список — и привести подписку в соответствие (Л7).
     *
     * «Есть в контактах — друг» читается в обе стороны, поэтому лента открывается и
     * закрывается **тем же движением**, что перекладывание. Двумя отдельными вызовами
     * это рано или поздно разошлось бы: убрали в одном месте, а ленту закрыли только в
     * одном из трёх.
     */
    private suspend fun moveTo(entry: BookEntry, list: BookList, known: Boolean = false) {
        book.setList(entry.id, list, known)
        entry.userId?.let { friends.set(it, friend = list == BookList.Usual) }
    }
}

/** Чем кончилось добавление. */
sealed interface AddStep {

    /** Номер найден в TIMa: заведён контакт, и ему открыта своя лента. */
    data class InTima(val phone: String, val userId: String, val subscribed: Boolean) : AddStep

    /** В TIMa его нет. Контакт сохранён — позвонить можно телефоном. */
    data class OnlyPhone(val phone: String) : AddStep

    /** Из строки номера не выходит. Единственный случай, когда ничего не сохраняется. */
    data object BadPhone : AddStep
}
