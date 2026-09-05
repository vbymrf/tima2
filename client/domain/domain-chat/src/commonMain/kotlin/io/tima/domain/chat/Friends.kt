package io.tima.domain.chat

/**
 * Друзья — ПЛАН-КОНТАКТОВ.md, Д1б и Д7.
 *
 * **Друзья и книга — разные вещи, и это решение заказчика 2026-09-05.** Книга живёт на
 * устройстве и есть только у аккаунта с телефоном; друзья нужны каждому — в том числе
 * виртуальному, у которого телефонной книги нет и не будет.
 *
 * Связь односторонняя: «друг» означает «я на него подписан», а не «мы договорились».
 */
fun interface Friends {

    /**
     * Добавить или убрать.
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
    suspend fun add(raw: String, name: String?, section: String = ""): AddStep {
        val phone = normalizePhone(raw) ?: return AddStep.BadPhone

        val userId = try {
            discovery.discover(listOf(phone))[phone]
        } catch (_: Exception) {
            // Без сети сверить нельзя. Контакт всё равно заводится: следующая
            // синхронизация спросит о нём снова (SyncBook сверяет всю книгу).
            null
        }

        book.addManually(phone, name, section)
        if (!userId.isNullOrBlank()) {
            book.matched(mapOf(phone to userId))
            val подписан = friends.set(userId, friend = true)
            return AddStep.InTima(phone, userId, subscribed = подписан)
        }
        return AddStep.OnlyPhone(phone)
    }
}

/**
 * Убрать человека из книги.
 *
 * **И отписаться от его ленты** (решение заказчика 2026-09-05): «есть в контактах — друг»
 * читается в обе стороны, иначе лента копит тех, кого человек уже убрал, и он не
 * понимает, откуда они там.
 */
class RemoveContact(
    private val book: Book,
    private val friends: Friends,
) {

    suspend fun remove(entry: BookEntry) {
        book.hide(entry.phone)
        entry.userId?.let { friends.set(it, friend = false) }
    }
}

/** Чем кончилось добавление. */
sealed interface AddStep {

    /** Номер найден в TIMa: заведён контакт и оформлена подписка на его ленту. */
    data class InTima(val phone: String, val userId: String, val subscribed: Boolean) : AddStep

    /** В TIMa его нет. Контакт сохранён — позвонить можно телефоном. */
    data class OnlyPhone(val phone: String) : AddStep

    /** Из строки номера не выходит. Единственный случай, когда ничего не сохраняется. */
    data object BadPhone : AddStep
}
