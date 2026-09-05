package io.tima.feature.chat

import io.tima.domain.chat.AddContact
import io.tima.domain.chat.AddStep
import io.tima.domain.chat.Book
import io.tima.domain.chat.ContactDiscovery
import io.tima.domain.chat.normalizePhone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Новый контакт и новый раздел — ПЛАН-КОНТАКТОВ.md, Д6.
 *
 * **Исход сверки виден до нажатия.** Человек должен знать заранее, кого он добавляет: в
 * TIMa этот номер или только в телефоне. Узнать это после нажатия значит узнать поздно —
 * кнопка уже пообещала «Написать».
 *
 * Сверка идёт по готовому номеру и не на каждую цифру: спрашивать сервер о «+7 9», «+7
 * 91», «+7 916» — это три запроса про несуществующих людей.
 */
class NewContactStore(
    private val add: AddContact,
    private val book: Book,
    private val discovery: ContactDiscovery,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(NewContactState())
    val state: StateFlow<NewContactState> = _state.asStateFlow()

    fun changedPhone(line: String) {
        val phone = normalizePhone(line)
        _state.value = _state.value.copy(phone = line, normalized = phone, checked = null)
        // Сверяем, только когда номер сложился целиком: до этого спрашивать не о ком.
        if (phone != null) check(phone)
    }

    fun changedName(line: String) {
        _state.value = _state.value.copy(name = line)
    }

    fun changedSection(name: String) {
        _state.value = _state.value.copy(section = name)
    }

    private fun check(phone: String) {
        scope.launch {
            val found = try {
                discovery.discover(listOf(phone))[phone]
            } catch (_: Exception) {
                // Без сети исход неизвестен, и врать о нём нельзя: кнопка останется
                // нейтральной «Добавить в контакты».
                null
            }
            // Ответ мог опоздать: пока ходили, человек дописал номер.
            if (_state.value.normalized == phone) {
                _state.value = _state.value.copy(checked = !found.isNullOrBlank())
            }
        }
    }

    fun save(onDone: (AddStep) -> Unit) {
        val state = _state.value
        if (state.normalized == null) {
            _state.value = state.copy(trouble = "Из этого номера не выходит телефона")
            return
        }
        scope.launch {
            _state.value = state.copy(working = true, trouble = null)
            val step = add.add(state.phone, state.name.ifBlank { null }, state.section)
            _state.value = _state.value.copy(working = false)
            onDone(step)
        }
    }

    /** Новый раздел книги. Раздел с тем же именем не заводится дважды. */
    fun addSection(name: String) {
        if (name.isBlank()) return
        scope.launch { book.addSection(name.trim()) }
    }
}

data class NewContactState(
    val phone: String = "",
    /** Номер в E.164 либо `null` — тогда сохранять нечего. */
    val normalized: String? = null,
    val name: String = "",
    val section: String = "",
    /** `null` — не сверяли или не смогли; иначе — нашёлся ли номер в TIMa. */
    val checked: Boolean? = null,
    val working: Boolean = false,
    val trouble: String? = null,
) {
    val canSave: Boolean get() = normalized != null && !working

    /**
     * Слово на кнопке.
     *
     * «Написать» обещает переписку, и обещать её тому, кого в TIMa нет, нельзя — писать
     * ещё некому. Пока исход неизвестен, слово нейтральное.
     */
    val saveWord: String get() = if (checked == true) "Добавить и написать" else "Добавить в контакты"

    /** Что сказать об исходе сверки до нажатия. */
    val about: String? get() = when (checked) {
        true -> "Найден в TIMa — подписка на его ленту оформится сама"
        false -> "В TIMa его нет. Контакт сохранится — позвонить можно телефоном"
        null -> null
    }
}
