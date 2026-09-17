package io.tima.feature.chat

import io.tima.core.ui.fullPhone
import io.tima.core.words.CurrentWords
import io.tima.core.words.Words
import io.tima.core.words.RussianWords
import io.tima.core.words.ChatWords
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
    /**
     * Словарь надписей — **ссылкой, а не значением** (ПЛАН-ЯЗЫКА, Я2-беды).
     *
     * Store не `@Composable`, и `Tima.words` ему недоступен. Лямбда зовётся в момент
     * беды, поэтому язык всегда текущий: переданный значением, он запомнился бы на всю
     * жизнь store, и после смены языка беда пришла бы на прежнем.
     */
    private val words: () -> Words = { CurrentWords.value },
) {
    private val _state = MutableStateFlow(NewContactState())
    val state: StateFlow<NewContactState> = _state.asStateFlow()

    fun changedCountryCode(text: String) {
        _state.value = _state.value.copy(countryCode = text, checked = null)
        recompose()
    }

    fun changedPhone(line: String) {
        _state.value = _state.value.copy(phone = line, checked = null)
        recompose()
    }

    /**
     * Собрать номер из кода страны и набранного и, если сложился целиком, сверить.
     *
     * Два поля (2026-09-15): на цифровой клавиатуре нет плюса. Но контакт часто
     * ВСТАВЛЯЮТ из книги телефона — «+7 916…» или «8 916…». Первое берётся целиком
     * (правило [fullPhone]); второе — российская запись с восьмёркой — по-прежнему
     * распознаётся [normalizePhone]: приписать к ней код дало бы «+78916…», номер,
     * которого нет.
     */
    private fun recompose() {
        val s = _state.value
        val typed = s.phone.trim()
        val digits = typed.filter(Char::isDigit)
        val phone = when {
            typed.startsWith("+") -> normalizePhone(typed)
            digits.length == 11 && digits.startsWith("8") && s.countryCode.filter(Char::isDigit) == "7" -> normalizePhone(typed)
            else -> normalizePhone(fullPhone(s.countryCode, typed))
        }
        _state.value = s.copy(normalized = phone)
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
            _state.value = state.copy(trouble = words().chat.notAPhone)
            return
        }
        scope.launch {
            _state.value = state.copy(working = true, trouble = null)
            val step = add.add(state.normalized ?: state.phone, state.name.ifBlank { null }, state.section)
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
    /** Код страны отдельным полем: на цифровой клавиатуре нет плюса (2026-09-15). */
    val countryCode: String = "7",
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
    fun saveWord(words: ChatWords): String =
        if (checked == true) words.addAndWrite else words.addToContacts

    /**
     * Что сказать об исходе сверки до нажатия.
     *
     * Отрицательный исход называет ПРОВЕРЕННЫЙ номер, а не просто «его нет». Разница
     * найдена на стенде 2026-09-17: контакт с лишним нулём выглядел как человек без
     * приложения, и беду искали в определении, которое работало исправно.
     */
    fun about(words: ChatWords): String? = when (checked) {
        true -> words.foundInTima
        false -> normalized?.let { words.notInTimaChecked(it) } ?: words.notInTima
        null -> null
    }
}
