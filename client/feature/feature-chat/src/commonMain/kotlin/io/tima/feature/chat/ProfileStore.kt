package io.tima.feature.chat

import io.tima.core.media.Media
import io.tima.core.words.CurrentWords
import io.tima.core.words.Words
import io.tima.core.words.RussianWords
import io.tima.domain.account.NickStep
import io.tima.domain.account.Profile
import io.tima.domain.account.nicknameFits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Свой профиль — ПЛАН-КОНТАКТОВ.md, Д8.
 *
 * **Отдельный экран, а не модалка** (решение заказчика 2026-09-05): ник требует проверки
 * занятости и показа ошибки, аватар — загрузки картинки, и в модалке для этого тесно.
 *
 * **Занятость спрашивается, пока человек печатает, но не на каждую букву:** сначала
 * границы (10…20 знаков, латиница, цифры, подчёркивание) — они проверяются на месте и
 * бесплатно, и только прошедший их ник уходит на сервер.
 */
class ProfileStore(
    private val profile: Profile,
    private val phone: String,
    private val scope: CoroutineScope,
    name: String = "",
    nickname: String = "",
    /**
     * Медиа-хранилище для аватара. `Media.None` — аватар не грузится и не скачивается:
     * харнесс и снимки живут без сети.
     */
    private val media: Media = Media.None,
    /**
     * Словарь надписей — **ссылкой, а не значением** (ПЛАН-ЯЗЫКА, Я2-беды).
     *
     * Store не `@Composable`, и `Tima.words` ему недоступен. Лямбда зовётся в момент
     * беды, поэтому язык всегда текущий.
     */
    private val words: () -> Words = { CurrentWords.value },
) {
    private val _state = MutableStateFlow(
        ProfileState(phone = phone, name = name, nickname = nickname, savedNickname = nickname),
    )
    val state: StateFlow<ProfileState> = _state.asStateFlow()

    /**
     * Перечитать себя с сервера. Зовётся при открытии экрана.
     *
     * До `GET /users/me` (0050) экран открывался с тем, что передали при создании, — то
     * есть ПУСТЫМ: сессия знает userId, а не имя и не телефон. Ответ сервера
     * перекрывает только незатронутое: если человек уже начал печатать, его буквы не
     * затираются пришедшими с задержкой старыми.
     */
    fun refresh() {
        scope.launch {
            val me = profile.me() ?: return@launch
            val now = _state.value
            _state.value = now.copy(
                phone = me.phone.ifBlank { now.phone },
                name = if (now.name == now.loadedName) me.name else now.name,
                loadedName = me.name,
                nickname = if (now.nickname == now.savedNickname) me.nickname else now.nickname,
                savedNickname = me.nickname,
                nickLocked = me.nickLocked,
                avatarMediaId = me.avatarMediaId,
            )
            // Картинка скачивается отдельно и позже: профиль обязан открыться сразу, а
            // байты аватара — второй запрос. Скачиваем, только если ещё не показано то же.
            if (me.avatarMediaId.isNotBlank() && _state.value.avatarPending == null) {
                val bytes = media.download(me.avatarMediaId) ?: return@launch
                if (_state.value.avatarMediaId == me.avatarMediaId) {
                    _state.value = _state.value.copy(avatarBytes = bytes)
                }
            }
        }
    }

    /** Обрезанный JPEG с экрана: показывается сразу, уходит на сервер по «Сохранить». */
    fun croppedAvatar(jpeg: ByteArray) {
        _state.value = _state.value.copy(avatarPending = jpeg, avatarBytes = jpeg, avatarRemove = false, saved = false)
    }

    /** Убрать аватар. Тоже до «Сохранить»: передумать можно. */
    fun removeAvatar() {
        _state.value = _state.value.copy(avatarPending = null, avatarBytes = null, avatarRemove = true, saved = false)
    }

    fun changedName(line: String) {
        _state.value = _state.value.copy(name = line, saved = false)
    }

    fun changedNickname(line: String) {
        val nick = line.trim()
        _state.value = _state.value.copy(nickname = nick, free = null, saved = false)
        // Свой же ник спрашивать не нужно: он занят самим человеком, и ответ «занят»
        // выглядел бы отказом там, где ничего не меняли.
        if (nick == _state.value.savedNickname || !nicknameFits(nick)) return
        scope.launch {
            val free = profile.freeNickname(nick)
            if (_state.value.nickname == nick) _state.value = _state.value.copy(free = free)
        }
    }

    fun save() {
        val state = _state.value
        scope.launch {
            _state.value = state.copy(working = true, trouble = null)

            val nameOk = if (state.name.isNotBlank()) profile.setName(state.name.trim()) else true

            // Аватар: сначала байты в медиа, потом ссылка в профиль. Неудача на любом
            // шаге — «не дошло», и картинка на экране остаётся ожидающей: человек
            // нажмёт «Сохранить» ещё раз, а не будет выбирать фото заново.
            var avatarId = state.avatarMediaId
            val avatarOk = when {
                state.avatarPending != null -> {
                    val id = media.upload(state.avatarPending, "image/jpeg")
                    if (id != null && profile.setAvatar(id)) { avatarId = id; true } else false
                }
                state.avatarRemove && state.avatarMediaId.isNotBlank() ->
                    profile.setAvatar("").also { if (it) avatarId = "" }
                else -> true
            }
            val nickStep = when {
                state.nickname.isBlank() -> NickStep.Taken
                state.nickname == state.savedNickname -> NickStep.Taken
                else -> profile.setNickname(state.nickname)
            }

            _state.value = when {
                nickStep == NickStep.Busy -> state.copy(working = false, free = false,
                    trouble = words().auth.nicknameTaken)
                // Сервер запер, а экран не знал: например, ник задали с другого
                // устройства минуту назад. Запираем и здесь — поле сменится текстом.
                nickStep == NickStep.Locked -> state.copy(working = false, nickLocked = true,
                    nickname = state.savedNickname, trouble = words().chat.nicknameLocked)
                nickStep == NickStep.OutOfBounds -> state.copy(working = false,
                    trouble = words().auth.nicknameRules)
                nickStep == NickStep.Offline || !nameOk || !avatarOk -> state.copy(working = false,
                    trouble = words().trouble.didNotReach)
                // Ник только что задан — и тем самым закреплён: второго раза у этой
                // личности не будет, экран обязан показать это сразу, а не после перезахода.
                else -> state.copy(
                    working = false, saved = true, loadedName = state.name,
                    savedNickname = state.nickname,
                    nickLocked = state.nickLocked || state.nickname.isNotBlank(),
                    avatarMediaId = avatarId, avatarPending = null, avatarRemove = false,
                )
            }
        }
    }
}

data class ProfileState(
    /** Номер не правится: по нему заведён аккаунт. Показан, чтобы человек его видел. */
    val phone: String = "",
    val name: String = "",
    val nickname: String = "",
    /** Ник, который уже стоит на сервере: с ним сравнивают, чтобы не спрашивать зря. */
    val savedNickname: String = "",
    /** Имя, каким его прислал сервер: чтобы перечитывание не затирало набранное. */
    val loadedName: String = "",
    /**
     * Ник закреплён за этой личностью — поле сменяется текстом (0050).
     *
     * Правило заказчика 2026-09-15: ник задаётся один раз на секретную фразу. Новая
     * фраза («Начать заново») даёт право сменить или оставить; имя же меняется сколько
     * угодно.
     */
    val nickLocked: Boolean = false,
    /** Аватар — медиа-объект на сервере. Пусто — картинки нет. */
    val avatarMediaId: String = "",
    /** Что показывать: скачанное или только что обрезанное. `null` — буквы. */
    val avatarBytes: ByteArray? = null,
    /** Обрезанное, но ещё не отправленное: уйдёт по «Сохранить». */
    val avatarPending: ByteArray? = null,
    /** Человек убрал картинку, но ещё не сохранил. */
    val avatarRemove: Boolean = false,
    /** `null` — не спрашивали или не ответили. */
    val free: Boolean? = null,
    val working: Boolean = false,
    val saved: Boolean = false,
    val trouble: String? = null,
) {
    /** Границы ника: проверяются на месте, до всякой сети. */
    val nickFits: Boolean get() = nickname.isBlank() || nicknameFits(nickname)

    /**
     * Что сказать про ник.
     *
     * Молчание, пока не о чем говорить: подсказка на каждую букву мешает печатать.
     */
    fun aboutNick(words: Words): String? = when {
        nickname.isBlank() -> null
        !nickFits -> words.auth.nicknameRulesShort
        nickname == savedNickname -> words.chat.yourNickname
        free == true -> words.auth.nicknameFree
        free == false -> words.auth.nicknameBusy
        else -> null
    }

    /**
     * Пустое имя не прячется: пока имя не задано, собеседники видят номер, и человек
     * узнаёт об этом здесь, а не от собеседника.
     */
    val nameless: Boolean get() = name.isBlank()

    val canSave: Boolean get() = !working && nickFits && free != false

    /**
     * Почему «Сохранить» не нажимается — заказчик 2026-09-26: погашенная кнопка без
     * объяснения читается поломкой. `null` — нажимается, или идёт запись (тогда кнопка
     * погашена не из-за человека, и винить его нечем).
     */
    fun whyNoSave(words: Words): String? = when {
        working -> null
        !nickFits -> words.chat.saveNeedsNick(words.auth.nicknameRulesShort)
        free == false -> words.chat.saveNickTaken
        else -> null
    }

    /** Ник ещё можно задать: не заперт. */
    val nickEditable: Boolean get() = !nickLocked
}
