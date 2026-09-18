package io.tima.shared

import io.tima.core.network.UsersApi
import io.tima.domain.chat.Book
import androidx.compose.ui.graphics.ImageBitmap
import io.tima.core.media.Media
import io.tima.core.media.decodeImage
import io.tima.domain.chat.ChatFaces
import io.tima.domain.chat.ChatPeople
import io.tima.domain.chat.ChatPerson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Люди за идентификаторами — одно место на всё приложение (решение заказчика 2026-09-18:
 * «меняем подход для них везде»).
 *
 * Карточка человека собирается из двух источников: **книга** даёт имя, которым его назвал
 * я (своё или из телефонной книги), **справочник** — имя пользователя, ник и номер (номер
 * сервер отдаёт только собеседникам). Что из этого показать — решает «Вид» соответствующего
 * набора, здесь только сбор.
 *
 * Справочник спрашивается **пачкой и по разу**: в книге сотня людей, и сто походов за
 * именами вместо одного — это сто запросов на каждое открытие вкладки.
 */
class People(
    private val directory: UsersApi,
    private val book: Book,
    private val scope: CoroutineScope,
    /** Медиа-хранилище — за картинками аватаров; `null` — только буквы. */
    private val media: Media? = null,
) : ChatPeople, ChatFaces {

    private val _cards = MutableStateFlow<Map<String, ChatPerson>>(emptyMap())
    private val _faces = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())

    /** Картинки аватаров по идентификатору — те, что уже доехали. */
    val faces: StateFlow<Map<String, ImageBitmap>> = _faces.asStateFlow()

    private val fetching = HashSet<String>()

    /** Попросить картинку аватара; приедет в [faces]. Без аватара у карточки — ничего. */
    fun wantFace(userId: String) {
        val mediaId = _cards.value[userId]?.avatar ?: return
        if (media == null || userId in _faces.value || !fetching.add(userId)) return
        scope.launch {
            val picture = media.download(mediaId)?.let(::decodeImage)
            if (picture != null) _faces.value = _faces.value + (userId to picture) else fetching.remove(userId)
        }
    }

    /** Байты аватара для переписки — тот же порт, что раньше собирали в Root. */
    override suspend fun face(userId: String): ByteArray? {
        val card = _cards.value[userId] ?: directory.cards(listOf(userId))?.get(userId) ?: return null
        return card.avatar?.let { media?.download(it) }
    }

    /** Что справочник рассказал о людях — по идентификатору. Без имени из книги. */
    val cards: StateFlow<Map<String, ChatPerson>> = _cards.asStateFlow()

    private val asked = HashSet<String>()

    /** Чья карточка сменилась — для открытых экранов, которые держат свой снимок. */
    private val _changed = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val changed: SharedFlow<String> = _changed.asSharedFlow()

    private val _hues = MutableStateFlow<Map<String, Map<String, Int>>>(emptyMap())

    /** Выбранные цвета полос: группа → (человек → номер оттенка). Из событий и из списка участников. */
    val hues: StateFlow<Map<String, Map<String, Int>>> = _hues.asStateFlow()

    /**
     * Штамп отправителя из события о сообщении (сервер 0052/0053): счётчик его профиля и
     * цвет в группе. Счётчик отличается от запомненного — карточка переспрашивается одним
     * запросом; совпадает — ничего. Так смена аватара или имени доезжает с первым же
     * сообщением автора, а пока никто ничего не менял, запросов нет вовсе.
     */
    fun stamp(userId: String, profileRev: Int?, groupId: String?, hue: Int?) {
        if (groupId != null) setHue(groupId, userId, hue)
        if (profileRev == null) return
        val known = _cards.value[userId]
        if (known != null && known.rev == profileRev) return
        if (known == null && userId in asked) return // ответ ещё в пути
        scope.launch { refresh(userId) }
    }

    /** Переспросить карточку человека и сказать об этом тем, кто её показывает. */
    suspend fun refresh(userId: String) {
        val card = directory.cards(listOf(userId))?.get(userId) ?: return
        asked.add(userId)
        val before = _cards.value[userId]
        _cards.value = _cards.value + (userId to card)
        if (before?.avatar != card.avatar) {
            // Новый аватар — старую картинку забыть, следующий показ скачает новую.
            _faces.value = _faces.value - userId
            fetching.remove(userId)
        }
        if (before != card) _changed.tryEmit(userId)
    }

    /** Цвета участников группы — из списка участников (`hue`) или события (`sender_hue`). */
    fun setHue(groupId: String, userId: String, hue: Int?) {
        val group = _hues.value[groupId].orEmpty()
        val next = if (hue == null) group - userId else group + (userId to hue)
        if (next != group) _hues.value = _hues.value + (groupId to next)
    }

    fun setHues(groupId: String, hues: Map<String, Int>) {
        if (_hues.value[groupId] != hues) _hues.value = _hues.value + (groupId to hues)
    }

    /** Спросить справочник о тех, кого ещё не спрашивали. Ответ приедет в [cards]. */
    fun want(ids: Collection<String>) {
        val fresh = ids.filter { it.isNotBlank() && asked.add(it) }
        if (fresh.isEmpty()) return
        scope.launch {
            val answer = directory.cards(fresh)
            if (answer == null) {
                // Не дошли — спросим в следующий раз, а не будем считать людей безымянными.
                asked.removeAll(fresh.toSet())
                return@launch
            }
            _cards.value = _cards.value + answer
        }
    }

    /** Карточка одного человека: справочник плюс имя из книги. Для реплик и состава. */
    override suspend fun person(userId: String): ChatPerson {
        val card = _cards.value[userId]
            ?: directory.cards(listOf(userId))?.get(userId)?.also { _cards.value = _cards.value + (userId to it) }
            ?: ChatPerson()
        return card.withBookName(book.list().first().firstOrNull { it.userId == userId || (card.phone != null && it.phone == card.phone) }?.name)
    }
}

/** Имя из книги поверх карточки справочника; телефон из книги — если справочник промолчал. */
fun ChatPerson.withBookName(name: String?, phone: String? = null): ChatPerson =
    copy(name = name?.ifBlank { null } ?: this.name, phone = this.phone ?: phone)
