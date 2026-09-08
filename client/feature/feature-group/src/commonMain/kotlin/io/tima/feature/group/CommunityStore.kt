package io.tima.feature.group

import io.tima.domain.chat.Communities
import io.tima.domain.chat.CommunityItem
import io.tima.domain.chat.LinkStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Страница сообщества: состав, описание, подписка (ПЛАН-СООБЩЕСТВ С6, С7).
 *
 * **Отписка не отвязывает элементы, а отвязывание не отписывает.** Это разные действия с
 * разными последствиями: подписка про то, что человек читает, связывание — про то, чем
 * распоряжается владелец. Держать их одной кнопкой значило бы, что отписавшийся ломает
 * чужое сообщество.
 */
class CommunityStore(
    private val communities: Communities,
    private val scope: CoroutineScope,
    private val communityId: String,
) {

    private val _state = MutableStateFlow(CommunityState())
    val state: StateFlow<CommunityState> = _state.asStateFlow()

    fun refresh() {
        scope.launch {
            val page = communities.page(communityId)
            // Что ещё можно внести — спрашивается только владельцем и только здесь:
            // постороннему этот список не нужен, а лишний запрос на каждую страницу
            // означал бы поход в сеть ради кнопки, которой он не увидит.
            val free = if (page?.owner == true) communities.linkable() else emptyList()
            _state.value = if (page == null) {
                // Сообщества нет или оно нам не показано — различать эти случаи мы не
                // должны, и потому текст один.
                _state.value.copy(loaded = true, trouble = "Сообщество не открылось")
            } else {
                CommunityState(
                    title = page.title,
                    description = page.description,
                    items = page.items,
                    linkable = free,
                    owner = page.owner,
                    admin = page.admin,
                    subscribed = page.subscribed,
                    loaded = true,
                )
            }
        }
    }

    /** Подписаться или отписаться: одно действие на весь контейнер. */
    fun subscribe(on: Boolean) {
        scope.launch {
            if (communities.subscribe(communityId, on)) {
                refresh()
            } else {
                _state.value = _state.value.copy(trouble = "Не удалось изменить подписку")
            }
        }
    }

    /**
     * Внести свой элемент в это сообщество (С7).
     *
     * Переписка и участники не меняются — меняется одна ссылка. Экран говорит это словами,
     * а здесь после ответа перечитывается страница: состав приходит с сервера, и второй
     * его источник разошёлся бы с первым при первом же отказе.
     */
    fun link(item: CommunityItem) {
        scope.launch {
            _state.value = when (communities.link(communityId, item.kind, item.id)) {
                LinkStep.Linked -> {
                    refresh()
                    _state.value.copy(trouble = null)
                }

                LinkStep.Busy -> _state.value.copy(trouble = "«${item.title}» уже в другом сообществе")
                LinkStep.NotAllowed ->
                    _state.value.copy(trouble = "Вносить может владелец сообщества и владелец элемента")

                LinkStep.Failed -> _state.value.copy(trouble = "Не удалось внести")
            }
        }
    }

    /** Вынуть элемент обратно: он снова становится отдельным. */
    fun unlink(item: CommunityItem) {
        scope.launch {
            _state.value = when (communities.unlink(communityId, item.kind, item.id)) {
                LinkStep.Linked -> {
                    refresh()
                    _state.value.copy(trouble = null)
                }

                LinkStep.NotAllowed ->
                    _state.value.copy(trouble = "Вынимать может владелец сообщества и владелец элемента")

                else -> _state.value.copy(trouble = "Не удалось вынуть")
            }
        }
    }

    fun closeTrouble() {
        _state.value = _state.value.copy(trouble = null)
    }
}

/** Что видно на странице сообщества. */
data class CommunityState(
    val title: String = "",
    /** Описание — сообщения уровня 0 (ADR-0019 §4). */
    val description: List<String> = emptyList(),
    val items: List<CommunityItem> = emptyList(),
    /**
     * Что ещё можно внести: свои группы и каналы, не связанные ни с чем (С7).
     *
     * Пусто у всех, кроме владельца: вносить может владелец сообщества и владелец
     * элемента одновременно, и показывать список тому, кто не может, значит предлагать
     * действие, которое отвергнут.
     */
    val linkable: List<CommunityItem> = emptyList(),
    val owner: Boolean = false,
    val admin: Boolean = false,
    val subscribed: Boolean = false,
    /** Страница хоть раз доехала. До этого «пусто» значит «не знаем». */
    val loaded: Boolean = false,
    val trouble: String? = null,
)
