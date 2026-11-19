package io.tima.feature.group

import io.tima.core.words.CurrentWords
import io.tima.core.words.Words
import io.tima.core.words.RussianWords
import io.tima.domain.chat.AskStep
import io.tima.domain.chat.Communities
import io.tima.domain.chat.CommunityPage
import io.tima.domain.chat.CardsStep
import io.tima.domain.chat.GroupCard
import io.tima.domain.chat.GroupInfo
import io.tima.domain.chat.GroupRegistry
import io.tima.domain.chat.GroupsStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Окно 2 «Социум»: каталог своих групп и карточки, которые открыли контакты.
 *
 * **Два списка, и они не пересекаются.** «Каталог» — где я состою; «Друзья» — то, что
 * положили себе люди из книги и куда я ещё не вступил. Группа, в которую я вступил,
 * уходит из «Друзей» сама — сервер её оттуда не отдаёт.
 *
 * **Пустой список и несостоявшаяся загрузка — разные состояния.** «Групп нет» и «не
 * дошли до сервера» выглядят одинаково, если хранить только список; поэтому есть
 * [SocialState.trouble] и [SocialState.loaded].
 */
class SocialStore(
    private val groups: GroupRegistry,
    private val scope: CoroutineScope,
    /**
     * Свои сообщества. `null` — списка нет вовсе: так работают проверки, которым
     * интересны только группы.
     */
    private val communities: Communities? = null,
    /**
     * Словарь надписей — **ссылкой, а не значением** (ПЛАН-(Я)-ЯЗЫКА, Я2-беды).
     *
     * Store не `@Composable`, и `Tima.words` ему недоступен. Лямбда зовётся в момент
     * беды, поэтому язык всегда текущий: переданный значением, он запомнился бы на всю
     * жизнь store, и после смены языка беда пришла бы на прежнем.
     */
    private val words: () -> Words = { CurrentWords.value },
    /** Свои каналы (ПЛАН-(СЦ) СЦ3); `null` — каналов в «Каталоге» нет; ответ `null` — нет связи. */
    private val channels: (suspend () -> List<io.tima.domain.chat.ChannelInfo>?)? = null,
    /**
     * Снимок каталога с устройства (заказчик 2026-11-19): прошлый удачный ответ — сразу при создании, до сети.
     * `null` — снимков нет (проверки).
     */
    private val restore: (suspend () -> SocialState?)? = null,
    /** Сохранить удачный ответ снимком; зовётся после каждого обновления, где свои группы доехали. */
    private val keep: (suspend (SocialState) -> Unit)? = null,
) {

    private val _state = MutableStateFlow(SocialState())
    val state: StateFlow<SocialState> = _state.asStateFlow()

    init {
        // Снимок не перебивает уже пришедший ответ сети: строки сети новее.
        if (restore != null) scope.launch {
            val snap = runCatching { restore.invoke() }.getOrNull() ?: return@launch
            _state.update { s -> if (s.loaded) s else s.copy(mine = snap.mine, cards = snap.cards, communities = snap.communities, channels = snap.channels, loaded = true) }
        }
    }

    /** Обновить оба списка. Вызывается при открытии окна и по возвращении в него. */
    fun refresh() {
        val current = _state.value
        if (current.expect) return
        _state.value = current.copy(expect = true, trouble = null)

        scope.launch {
            // Четыре списка — разом, а не подряд (заказчик 2026-11-19): ожидание — как у самого долгого, не сумма.
            // Каждый ложится в строки, как только пришёл: свои группы не ждут карточек и каналов. Прежде по
            // журналам телефонов сумма была 0,35–0,57 с медианой и до 2,1 с на Redmi — по Wi-Fi.
            val (mine, cards) = coroutineScope {
                val mine = async {
                    groups.mine().also { m ->
                        if (m is GroupsStep.Groups) _state.update { it.copy(mine = m.groups, loaded = true) }
                    }
                }
                val cards = async {
                    groups.cards().also { c -> if (c is CardsStep.Cards) _state.update { it.copy(cards = c.cards) } }
                }
                // Сообщества и каналы — тот же «Каталог». Нет связи — прежний список остаётся.
                val communitiesJob = async {
                    communities?.mine()?.let { list -> _state.update { it.copy(communities = list) } }
                }
                val channelsJob = async {
                    channels?.invoke()?.let { list -> _state.update { it.copy(channels = list) } }
                }
                communitiesJob.await()
                channelsJob.await()
                mine.await() to cards.await()
            }
            _state.update { it.copy(expect = false, loaded = true, trouble = troubleOf(mine, cards)) }
            if (mine is GroupsStep.Groups) runCatching { keep?.invoke(_state.value) }
        }
    }

    /**
     * Попроситься в чужую группу.
     *
     * Состояние просьбы держится по идентификатору группы: человек может попроситься в
     * несколько, и «попрошено» относится к строке, а не ко всему экрану.
     */
    fun ask(groupId: String) {
        if (_state.value.asking.contains(groupId)) return
        _state.value = _state.value.copy(asking = _state.value.asking + groupId, trouble = null)

        scope.launch {
            val answer = groups.askToJoin(groupId)
            val current = _state.value
            _state.value = when (answer) {
                is AskStep.Asked -> current.copy(
                    asking = current.asking - groupId,
                    asked = current.asked + groupId,
                )
                is AskStep.Offline -> current.copy(
                    asking = current.asking - groupId,
                    trouble = words().trouble.retryIn(
                        (answer.retryAfterMs / 1000).coerceAtLeast(1).toInt(),
                    ),
                )
                is AskStep.Refused -> current.copy(
                    asking = current.asking - groupId,
                    trouble = words().social.couldNotAsk(answer.reason),
                )
            }
        }
    }

    private fun troubleOf(mine: GroupsStep, cards: CardsStep): String? = when {
        mine is GroupsStep.Offline -> words().social.groupsMayBeIncomplete
        mine is GroupsStep.Refused -> words().trouble.refused(mine.reason)
        cards is CardsStep.Offline -> words().social.cardsMayBeIncomplete
        cards is CardsStep.Refused -> words().trouble.refused(cards.reason)
        else -> null
    }
}

/** Что видно в окне «Социум». */
data class SocialState(
    /** Где я состою — вкладка «Каталог». */
    val mine: List<GroupInfo> = emptyList(),
    /** Что открыли контакты — вкладка «Друзья». */
    val cards: List<GroupCard> = emptyList(),
    /**
     * Свои сообщества — там же, в «Каталоге».
     *
     * Отдельной вкладки им не заводится: сообщество это контейнер того же каталога, а не
     * другой вид отношений. Четвёртая вкладка заставила бы человека помнить, где что
     * лежит, вместо того чтобы видеть.
     */
    val communities: List<CommunityPage> = emptyList(),
    /** Свои каналы — тоже в «Каталоге» (ПЛАН-(СЦ) СЦ3), без лент людей. */
    val channels: List<io.tima.domain.chat.ChannelInfo> = emptyList(),
    /** Идёт запрос: списки могли ещё не приехать. */
    val expect: Boolean = false,
    /** Списки хоть раз доехали. До этого «пусто» означает «не знаем», а не «нет». */
    val loaded: Boolean = false,
    /** По каким группам просьба уже ушла — строка показывает это словами. */
    val asked: Set<String> = emptySet(),
    /** По каким идёт запрос прямо сейчас. */
    val asking: Set<String> = emptySet(),
    val trouble: String? = null,
)
