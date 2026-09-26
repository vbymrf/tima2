package io.tima.feature.chat

import io.tima.core.ui.fullPhone
import io.tima.core.words.CurrentWords
import io.tima.core.words.Words
import io.tima.core.words.RussianWords
import io.tima.core.words.ChatWords
import io.tima.domain.chat.AddContact
import io.tima.domain.chat.AddStep
import io.tima.domain.chat.Book
import io.tima.domain.chat.BookEntry
import io.tima.domain.chat.BookList
import io.tima.domain.chat.ContactDiscovery
import io.tima.domain.chat.MIN_NICKNAME_QUERY
import io.tima.domain.chat.NicknameDirectory
import io.tima.domain.chat.NicknameHit
import io.tima.domain.chat.Section
import io.tima.domain.chat.normalizePhone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
     * Поиск по нику — Л10, Л11. `null` — искать нечем (так собирают проверки без сети):
     * поле ника тогда не показывается вовсе, а не стоит неработающим.
     */
    private val nicknames: NicknameDirectory? = null,
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

    init {
        // Разделы читаются один раз при открытии подокна: пока оно открыто, завести новый
        // можно только отсюда, и этот случай дописывает список сам.
        scope.launch {
            _state.value = _state.value.copy(sections = book.sections().first())
        }
        // Кто у нас в списках — для пометок в выдаче поиска (Л18), и кто уже в контактах —
        // чтобы не завести его второй раз. Потоком: store живёт всё время приложения, а не
        // минуту подокна, и снимок при запуске не знал бы о добавленных после.
        scope.launch {
            book.everyone().collect { rows ->
                _state.value = _state.value.copy(
                    inLists = rows
                        .filter { !it.inContacts }
                        .mapNotNull { row -> row.userId?.let { it to row.list } }
                        .toMap(),
                    contacts = rows.filter { it.inContacts },
                )
            }
        }
    }

    /**
     * Чистая форма — заказчик 2026-09-26: «щас она работает как изменить».
     *
     * Store один на приложение, и после «Добавить» в полях оставался прошлый человек:
     * следующее открытие подокна показывало его, и новое нажатие переписывало его строку.
     * Зовётся при закрытии подокна — сохранили или ушли назад. Разделы и книга остаются:
     * это не ввод, а то, что уже есть.
     */
    fun reset() {
        val was = _state.value
        _state.value = NewContactState(sections = was.sections, inLists = was.inLists, contacts = was.contacts)
    }

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

    // ── Поиск по нику (Л10, Л11) ────────────────────────────────────────────
    //
    // Второй вход в подокно, равноправный номеру: у виртуальных аккаунтов номера нет
    // вовсе, и добавить их сейчас было бы нечем.

    fun changedNick(line: String) {
        // Набрали заново — прежняя выдача и прежний выбор недействительны: оставить их
        // значило бы показать ответ на другой вопрос.
        _state.value = _state.value.copy(nick = line, found = null, picked = null, trouble = null)
    }

    /**
     * Найти.
     *
     * По нажатию, а не на каждую букву: это перебор каталога, и три знака с пределом
     * частоты на сервере заведены ровно против того, чтобы он шёл сам собой.
     */
    fun searchNick() {
        val q = _state.value.nick.trim()
        if (q.length < MIN_NICKNAME_QUERY || nicknames == null) return
        scope.launch {
            _state.value = _state.value.copy(searching = true, found = null, trouble = null)
            val hits = nicknames.searchNicknames(q)
            // Ответ мог опоздать: пока ходили, человек набрал другое.
            if (_state.value.nick.trim() != q) return@launch
            _state.value = _state.value.copy(
                searching = false,
                found = hits.orEmpty(),
                // `null` — сервер не ответил. Это не «никого нет», и говорить об этом
                // надо разное: пустая выдача значит «такого ника ни у кого».
                trouble = if (hits == null) words().chat.searchFailed else null,
            )
        }
    }

    /** Выбрать из найденного. Второе нажатие по той же строке снимает выбор. */
    fun pickNick(userId: String) {
        val was = _state.value.picked
        _state.value = _state.value.copy(picked = if (was == userId) null else userId, trouble = null)
    }

    fun changedName(line: String) {
        _state.value = _state.value.copy(name = line)
    }

    fun changedSection(name: String) {
        _state.value = _state.value.copy(section = name, trouble = null)
    }

    /**
     * Завести набранный раздел прямо отсюда.
     *
     * Здесь, а не «потом в настройках»: человек уже назвал раздел, и отсылать его в
     * другое место за тем же именем значит заставить набрать его дважды.
     */
    fun createTypedSection() {
        val name = _state.value.section.trim()
        if (name.isBlank()) return
        scope.launch {
            val id = book.addSection(name)
            _state.value = _state.value.copy(
                sections = _state.value.sections + Section(id = id, name = name),
                trouble = null,
            )
        }
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
        // Уже в контактах — не сохраняем: AddContact.add переписал бы его имя и раздел, то
        // есть «добавить» молча стало бы «изменить». Экран в этом случае показывает, кто
        // это, и ведёт на его страницу.
        if (state.already != null) return
        // Выбранный из поиска идёт своим путём: номера у него может не быть вовсе, и
        // требовать его значило бы отказать полноправному человеку (Л11).
        val picked = state.pickedHit
        if (picked != null) {
            if (state.sectionMissing) {
                _state.value = state.copy(trouble = words().chat.noSuchSection(state.section.trim()))
                return
            }
            scope.launch {
                _state.value = state.copy(working = true, trouble = null)
                val step = add.addByUser(picked.userId, state.name.ifBlank { null }, state.sectionId)
                _state.value = _state.value.copy(working = false)
                onDone(step)
            }
            return
        }
        if (state.normalized == null) {
            _state.value = state.copy(trouble = words().chat.notAPhone)
            return
        }
        // Раздела нет — контакт не сохраняется. Прежде он сохранялся и пропадал с экрана:
        // строка в книге была, а показать её было негде. Молчаливая пропажа хуже отказа.
        if (state.sectionMissing) {
            _state.value = state.copy(trouble = words().chat.noSuchSection(state.section.trim()))
            return
        }
        scope.launch {
            _state.value = state.copy(working = true, trouble = null)
            val step = add.add(state.normalized ?: state.phone, state.name.ifBlank { null }, state.sectionId)
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
    /** Набранная часть ника — Л11. */
    val nick: String = "",
    val searching: Boolean = false,
    /**
     * Выдача поиска; `null` — ещё не искали.
     *
     * Пустой список и `null` — разные вещи: первое значит «ответил и не нашёл», второе —
     * «не спрашивали». Экран говорит о них разное.
     */
    val found: List<NicknameHit>? = null,
    /** `user_id` выбранного из выдачи. */
    val picked: String? = null,
    /**
     * Кто у нас в «Убранных» и «Заблокированных» — Л18.
     *
     * Пометка рисуется НА КЛИЕНТЕ: сервер о списках не знает и отбирать по ним не может
     * (решение заказчика 2026-09-24). Спрятать заблокированного из выдачи всё равно не
     * вышло бы — значит, честнее показать и сказать, что он в списке.
     */
    val inLists: Map<String, BookList> = emptyMap(),
    val phone: String = "",
    /** Номер в E.164 либо `null` — тогда сохранять нечего. */
    val normalized: String? = null,
    val name: String = "",
    /** Имя раздела, как его набрал или выбрал человек. Пустое — «Общий». */
    val section: String = "",
    /**
     * Разделы, которые уже заведены. Нужны здесь, а не только на экране выбора: по ним
     * решается, существует ли набранный, — а от этого зависит, можно ли сохранять.
     */
    val sections: List<Section> = emptyList(),
    /** `null` — не сверяли или не смогли; иначе — нашёлся ли номер в TIMa. */
    val checked: Boolean? = null,
    /** Кто сейчас в контактах — для проверки «уже есть». */
    val contacts: List<BookEntry> = emptyList(),
    val working: Boolean = false,
    val trouble: String? = null,
) {
    /**
     * Раздел набран, но такого нет.
     *
     * Пустой раздел — это «Общий», он существует всегда и в списке разделов не значится.
     */
    val sectionMissing: Boolean
        get() = section.isNotBlank() && chosenSection == null

    /** Заведённый раздел, чьё имя набрано; `null` — такого нет либо набрано пустое («Общий»). */
    val chosenSection: Section?
        get() = sections.firstOrNull { it.name.equals(section.trim(), ignoreCase = true) }

    /** Что уходит в книгу: идентификатор раздела, пустой — «Общий». */
    val sectionId: String get() = chosenSection?.id ?: ""

    /** Выбранная строка выдачи. */
    val pickedHit: NicknameHit? get() = found?.firstOrNull { it.userId == picked }

    /** Искать пока не о чем: по одной-двум буквам выдачи не бывает (Л10). */
    val canSearch: Boolean get() = nick.trim().length >= MIN_NICKNAME_QUERY && !searching

    /** Ответили и не нашли — это не то же, что «ещё не искали». */
    val nobodyFound: Boolean get() = found?.isEmpty() == true

    val canSave: Boolean get() =
        (normalized != null || picked != null) && !working && !sectionMissing && already == null

    /**
     * Набранный номер или выбранный по нику уже в контактах. `null` — новый.
     *
     * По номеру — точное совпадение E.164; по нику — `user_id`. Убранных и
     * заблокированных здесь нет: их показывает пометка в выдаче (Л18).
     */
    val already: BookEntry? get() {
        if (picked != null) return contacts.firstOrNull { it.userId == picked }
        return normalized?.let { phone -> contacts.firstOrNull { it.phone == phone } }
    }

    /**
     * Слово на кнопке — всегда «Добавить в контакты».
     *
     * Было «Добавить и написать» у найденного в TIMa, но после нажатия переписка не
     * открывается: подокно просто закрывается. Кнопка обещала то, чего не делает, —
     * слово убрано (заказчик 2026-09-26).
     */
    fun saveWord(words: ChatWords): String = words.addToContacts

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
