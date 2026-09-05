package io.tima.feature.chat

import io.tima.domain.chat.NickStep
import io.tima.domain.chat.Profile
import io.tima.domain.chat.nicknameFits
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
) {
    private val _state = MutableStateFlow(
        ProfileState(phone = phone, name = name, nickname = nickname, savedNickname = nickname),
    )
    val state: StateFlow<ProfileState> = _state.asStateFlow()

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

            val имя = if (state.name.isNotBlank()) profile.setName(state.name.trim()) else true
            val ник = when {
                state.nickname.isBlank() -> NickStep.Taken
                state.nickname == state.savedNickname -> NickStep.Taken
                else -> profile.setNickname(state.nickname)
            }

            _state.value = when {
                ник == NickStep.Busy -> state.copy(working = false, free = false,
                    trouble = "Этот ник уже занят — придумайте другой")
                ник == NickStep.OutOfBounds -> state.copy(working = false,
                    trouble = "Ник — от 10 до 20 знаков: латиница, цифры, подчёркивание")
                ник == NickStep.Offline || !имя -> state.copy(working = false,
                    trouble = "Не дошло до сервера. Попробуйте ещё раз")
                else -> state.copy(working = false, saved = true, savedNickname = state.nickname)
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
    val aboutNick: String? get() = when {
        nickname.isBlank() -> null
        !nickFits -> "10…20 знаков: латиница, цифры, подчёркивание"
        nickname == savedNickname -> "Ваш ник"
        free == true -> "Свободен"
        free == false -> "Занят"
        else -> null
    }

    /**
     * Пустое имя не прячется: пока имя не задано, собеседники видят номер, и человек
     * узнаёт об этом здесь, а не от собеседника.
     */
    val nameless: Boolean get() = name.isBlank()

    val canSave: Boolean get() = !working && nickFits && free != false
}
