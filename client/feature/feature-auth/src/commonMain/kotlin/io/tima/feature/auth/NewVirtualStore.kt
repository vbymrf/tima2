package io.tima.feature.auth

import io.tima.domain.account.CreateVirtual
import io.tima.domain.account.Profile
import io.tima.domain.account.VirtualStep
import io.tima.domain.account.nicknameFits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Заведение виртуального аккаунта с экрана — ПЛАН-КОНТАКТОВ.md, Д11.
 *
 * **Три шага, и порядок между ними не косметика.** Ник → фраза владельца → слова нового
 * аккаунта. Ник спрашивается первым, потому что занятость проверяется до всякой подписи:
 * узнать «занято» после того, как человек ввёл двенадцать слов, значит попросить его
 * ввести их снова. Слова нового аккаунта показываются последними и один раз — второго
 * раза не будет ни у нас, ни у сервера.
 *
 * **Фраза владельца не хранится в состоянии дольше одного вызова.** Она приходит из поля,
 * уходит в подпись и стирается: экран, помнящий фразу, отдаёт её всякому, кто откроет
 * приложение после хозяина.
 */
class NewVirtualStore(
    private val create: CreateVirtual,
    private val profile: Profile,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(NewVirtualState())
    val state: StateFlow<NewVirtualState> = _state.asStateFlow()

    fun changedNickname(line: String) {
        val nick = line.trim()
        _state.value = _state.value.copy(nickname = nick, free = null, trouble = null)
        if (!nicknameFits(nick)) return
        scope.launch {
            val free = profile.freeNickname(nick)
            if (_state.value.nickname == nick) _state.value = _state.value.copy(free = free)
        }
    }

    /** Ник принят — спрашиваем фразу владельца. */
    fun toPhrase() {
        val state = _state.value
        if (!state.nickFits || state.free == false) return
        _state.value = state.copy(step = NewVirtualStep.Phrase, trouble = null)
    }

    fun changedPhrase(line: String) {
        _state.value = _state.value.copy(phrase = line, trouble = null)
    }

    fun back() {
        _state.value = when (_state.value.step) {
            // С показа слов назад не уходят: аккаунт уже заведён, и «назад» вернуло бы к
            // форме, которая заведёт второй. Закрывает экран кнопка «Записал».
            NewVirtualStep.Words -> return
            NewVirtualStep.Phrase -> _state.value.copy(step = NewVirtualStep.Nickname, phrase = "")
            NewVirtualStep.Nickname -> return
        }
    }

    fun confirm() {
        val state = _state.value
        if (state.working || state.phrase.isBlank()) return
        val words = state.phrase.trim().split(' ', '\n', '\t').filter { it.isNotEmpty() }
        scope.launch {
            _state.value = state.copy(working = true, trouble = null)
            val step = create.create(state.nickname, words)
            _state.value = when (step) {
                is VirtualStep.Created -> _state.value.copy(
                    step = NewVirtualStep.Words,
                    working = false,
                    // Фраза владельца стирается сразу: дальше она не нужна ни для чего.
                    phrase = "",
                    words = step.words,
                    created = step,
                )
                VirtualStep.BadPhrase -> _state.value.copy(
                    working = false, phrase = "",
                    trouble = "Фраза не подошла. Это фраза вашего основного аккаунта — двенадцать слов через пробел",
                )
                VirtualStep.NicknameTaken -> _state.value.copy(
                    step = NewVirtualStep.Nickname, working = false, phrase = "", free = false,
                    trouble = "Этот ник уже занят — придумайте другой",
                )
                VirtualStep.BadNickname -> _state.value.copy(
                    step = NewVirtualStep.Nickname, working = false, phrase = "",
                    trouble = "Ник — от 10 до 20 знаков: латиница, цифры, подчёркивание",
                )
                VirtualStep.TooMany -> _state.value.copy(
                    working = false, phrase = "",
                    trouble = "Больше пяти виртуальных аккаунтов на номер нельзя",
                )
                VirtualStep.NotAllowed -> _state.value.copy(
                    working = false, phrase = "",
                    trouble = "Виртуальный аккаунт не заводит виртуальных",
                )
                VirtualStep.Offline -> _state.value.copy(
                    working = false, phrase = "",
                    trouble = "Не дошло до сервера. Попробуйте ещё раз",
                )
            }
        }
    }
}

/** Какой из трёх шагов открыт. */
enum class NewVirtualStep { Nickname, Phrase, Words }

data class NewVirtualState(
    val step: NewVirtualStep = NewVirtualStep.Nickname,
    val nickname: String = "",
    /** `null` — не спрашивали или не ответили. */
    val free: Boolean? = null,
    /** Фраза владельца. Живёт только пока её вводят: после вызова стирается. */
    val phrase: String = "",
    /** Двенадцать слов нового аккаунта. Пусто до успеха. */
    val words: List<String> = emptyList(),
    val working: Boolean = false,
    val trouble: String? = null,
    /**
     * Заведённый аккаунт: сессию и секрет записывает тот, кто собирает окружение, — здесь
     * они только проходят мимо. `null`, пока не заведён.
     */
    val created: VirtualStep.Created? = null,
) {
    val nickFits: Boolean get() = nicknameFits(nickname)

    /** Что сказать про ник. Молчание, пока не о чем говорить. */
    val aboutNick: String? get() = when {
        nickname.isBlank() -> null
        !nickFits -> "10…20 знаков: латиница, цифры, подчёркивание"
        free == true -> "Свободен"
        free == false -> "Занят"
        else -> null
    }

    val canGoOn: Boolean get() = nickFits && free != false
}
