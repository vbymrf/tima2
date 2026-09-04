package io.tima.feature.chat

import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.MessageCircle
import io.tima.domain.chat.NarrowMessageLevel
import io.tima.domain.chat.NarrowStep
import io.tima.domain.chat.MarkRead
import io.tima.domain.chat.ObserveChat
import io.tima.domain.chat.ChatNames
import io.tima.domain.chat.RequestGroupKeys
import io.tima.domain.chat.RequestKeysStep
import io.tima.domain.chat.SendMessage
import io.tima.domain.chat.SendMessageResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Состояние окна переписки — К4.4.
 *
 * **Что здесь есть и чего нет.** Есть правила поведения экрана: что делать с набранным
 * текстом при удаче и при отказе, что показывать человеку. Нет ни сети, ни базы, ни
 * криптографии — только два случая использования из `domain-chat`. Это проверяется
 * архитектурным правилом, а не договорённостью.
 *
 * **Главное правило здесь одно: набранное человеком не теряется.** При удаче поле ввода
 * очищается — сообщение уже видно в списке, и оставленный текст выглядел бы как второе.
 * При **любом** отказе текст остаётся в поле: он написан человеком, а не нами, и
 * восстановить его нам нечем. В v1 поле очищалось до подтверждения, и сообщение,
 * отвергнутое по размеру, исчезало вместе с набранным.
 */
class ChatStore(
    private val chatId: String,
    observe: ObserveChat,
    private val send: SendMessage,
    private val scope: CoroutineScope,
    /**
     * Отметить прочитанным. `null` — не отмечать: так делают проверки, которым интересна
     * только отправка.
     *
     * Правило: **открытая переписка прочитана**. Отмечается не только при открытии, но и
     * на каждом обновлении списка реплик — пока переписка на экране, приходящее человек
     * видит, и держать при этом янтарную точку значит врать ему.
     */
    private val markRead: MarkRead? = null,
    /**
     * Запрос недостающих версий группового ключа. `null` — переписка личная: там нечего
     * запрашивать, и кнопки на экране быть не должно.
     */
    private val requestKeys: RequestGroupKeys? = null,
    /**
     * Имена авторов. `null` — переписка личная: там собеседник один, и подписывать
     * каждую его реплику именем значит шуметь.
     */
    private val names: ChatNames? = null,
    /**
     * Сужение круга у уже отправленного сообщения. `null` — переписка личная: там у
     * сообщений кругов нет, и предлагать сужение нечему.
     */
    private val narrow: NarrowMessageLevel? = null,
    /** Сколько строк держать на экране. Столько же просит и запрос к базе. */
    pageSize: Int = ObserveChat.DEFAULT_PAGE,
) {

    // Признак берётся из наличия случая, а не задаётся отдельно: два источника одной
    // правды разошлись бы, и кнопка появилась бы там, где нажимать её нечем.
    private val _state = MutableStateFlow(
        ChatState(keyAskMay = requestKeys != null, group = names != null),
    )
    val state: StateFlow<ChatState> = _state.asStateFlow()

    init {
        // Список приходит потоком: обновление от самой базы, а не по нажатию. Значит
        // пришедшее сообщение и смена состояния отправки появляются на экране сами.
        observe.page(chatId, pageSize)
            .onEach { lines ->
                _state.value = _state.value.copy(lines = lines)
                // Имена спрашиваются по одному разу на автора и только в группе: список
                // обновляется на каждое сообщение, и поход за именем на каждой строке
                // означал бы запрос к серверу на каждую реплику.
                names?.let { directory ->
                    val new = lines.mapNotNull { it.senderId }
                        .distinct()
                        .filter { it !in _state.value.names }
                    if (new.isNotEmpty()) {
                        val padded = _state.value.names.toMutableMap()
                        for (who in new) padded[who] = directory.name(who)
                        _state.value = _state.value.copy(names = padded)
                    }
                }
                // Переписка на экране — значит прочитана. Порядок именно такой: сначала
                // показать, потом отметить; иначе отметка обгонит то, что человек увидит.
                markRead?.chat(chatId)
            }
            .launchIn(scope)
    }

    /** Человек набирает текст. */
    fun draftChanged(text: String) {
        _state.value = _state.value.copy(draft = text, notice = null)
    }

    /**
     * Человек нажал «отправить».
     *
     * Возвращает исход, потому что вызывающему бывает нужно знать, состоялось ли
     * действие — например чтобы убрать клавиатуру. Состояние при этом уже обновлено.
     */
    /** Человек включил или выключил показ меток круга. */
    fun circlesShown(show: Boolean) {
        _state.value = _state.value.copy(showCircles = show)
    }

    /** Человек выбрал круг для следующего сообщения (ADR-0019). */
    fun circleChosen(level: Int) {
        _state.value = _state.value.copy(level = level)
    }

    /**
     * Человек выбрал, до какого круга сузить уже отправленное сообщение.
     *
     * **Сначала предупреждение, потом действие, и разделено это намеренно.** Сужение
     * необратимо и неполно: у тех, кто уже унёс сообщение к себе, запись останется —
     * сервер чужие переносы не отзывает и отозвать не может. Человек обязан узнать об
     * этом до нажатия, а не после.
     *
     * Расширение сюда не доходит: экран его не предлагает, а доменный случай отвергает.
     */
    fun narrowAsked(messageId: Long, was: Int, to: Int) {
        if (narrow == null) return
        _state.value = _state.value.copy(
            pendingNarrow = PendingNarrow(messageId, was, to),
            notice = ChatNotice.NarrowWarning(MessageCircle.of(to).title),
        )
    }

    /** Человек прочитал предупреждение и подтвердил сужение. */
    fun narrowConfirmed() {
        val case = narrow ?: return
        val asked = _state.value.pendingNarrow ?: return
        _state.value = _state.value.copy(pendingNarrow = null, notice = null)
        scope.launch {
            val outcome = case.narrow(chatId, asked.messageId, asked.was, asked.to)
            _state.value = _state.value.copy(
                notice = when (outcome) {
                    // Метку у реплики поменяет не экран, а событие сервера: оно приходит
                    // всем устройствам, включая наше, и второй источник правды здесь
                    // означал бы расхождение между тем, что видим мы, и что видят другие.
                    is NarrowStep.Narrowed -> ChatNotice.Narrowed(MessageCircle.of(outcome.level).title)
                    NarrowStep.Wider -> ChatNotice.NarrowRefused("Круг можно только сузить — расширить нельзя")
                    NarrowStep.AlreadySecret ->
                        ChatNotice.NarrowRefused("Зашифрованное сообщение читают только участники — сужать нечего")
                    NarrowStep.CannotEncryptLater ->
                        ChatNotice.NarrowRefused("Открытое сообщение уже разошлось — зашифровать его задним числом нельзя")
                    NarrowStep.NotAllowed -> ChatNotice.NarrowRefused("Чужое сообщение сужает админ группы")
                    NarrowStep.NotFound -> ChatNotice.NarrowRefused("Сообщения больше нет в группе")
                    is NarrowStep.Offline -> ChatNotice.NarrowRefused("Нет связи с сервером — повторите позже")
                    is NarrowStep.Refused -> ChatNotice.NarrowRefused("Сервер отказал: ${outcome.reason}")
                },
            )
        }
    }

    /** Человек передумал сужать. */
    fun narrowDropped() {
        _state.value = _state.value.copy(pendingNarrow = null, notice = null)
    }

    fun sendPressed(): SendMessageResult {
        val text = _state.value.draft
        // Круг едет в очередь вместе с сообщением: решение «нужен ли ключ» принимается
        // при отправке, а она может случиться после перезапуска.
        val outcome = send.send(chatId, text, _state.value.level)

        _state.value = when (outcome) {
            // Принято: поле чистим — сообщение уже в списке.
            is SendMessageResult.Queued,
            is SendMessageResult.AlreadyQueued,
            -> _state.value.copy(draft = "", notice = null)

            // Нажатие мимо. Ни сообщения, ни жалобы: человек и сам видит, что поле пусто.
            SendMessageResult.Empty -> _state.value.copy(notice = null)

            // Текст ОСТАЁТСЯ. Сообщить надо, а отобрать написанное — нельзя.
            is SendMessageResult.TooLarge -> _state.value.copy(
                notice = ChatNotice.TooLarge(outcome.bytes, outcome.limit),
            )
        }
        return outcome
    }

    /** Человек закрыл сообщение о беде. */
    /**
     * Человек нажал «запросить ключ» на нечитаемом сообщении.
     *
     * **Делает это человек, а не приложение фоном.** Просьба уходит чужим устройствам и
     * означает «дайте мне историю до моего прихода»: решать за человека, что он этого
     * хочет, и будить ради этого чужие устройства — не наше дело.
     *
     * `null` в [requestKeys] означает, что переписка не групповая: у личной такой
     * возможности нет, и кнопки на экране тоже не будет.
     */
    /** Человек набирает секретную фразу — её просят только после отказа по подписи. */
    fun changedPhrase(text: String) {
        _state.value = _state.value.copy(phrase = text)
    }

    fun requestKey() {
        val case = requestKeys ?: return
        if (_state.value.expectKey) return
        // Слова берутся из поля и дальше нигде не сохраняются: держать их значило бы
        // отдать вместе с устройством и тот заслон, ради которого фразу спрашивают.
        val words = _state.value.phrase.trim()
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }
        _state.value = _state.value.copy(expectKey = true, notice = null)

        scope.launch {
            val outcome = case.request(chatId, words)
            _state.value = _state.value.copy(
                expectKey = false,
                // Набранная фраза живёт до успеха и стирается сразу после него: держать
                // её на экране дольше нужного незачем.
                phrase = if (outcome is RequestKeysStep.Asked) "" else _state.value.phrase,
                notice = when (outcome) {
                    is RequestKeysStep.Asked -> ChatNotice.KeysAsked(outcome.devices)
                    RequestKeysStep.NoHelpers -> ChatNotice.KeysNoHelpers
                    RequestKeysStep.NothingMissing -> ChatNotice.KeysNothingMissing
                    RequestKeysStep.NeedsSecretPhrase -> ChatNotice.KeysNeedPhrase
                    RequestKeysStep.NotMember -> ChatNotice.KeysRefused("Вы больше не участник этой группы")
                    is RequestKeysStep.Offline -> ChatNotice.KeysRefused(
                        "Нет связи с сервером — повторите через ${(outcome.retryAfterMs / 1000).coerceAtLeast(1)} с",
                    )
                    is RequestKeysStep.Refused -> ChatNotice.KeysRefused(outcome.reason)
                },
            )
        }
    }

    fun noticeDismissed() {
        _state.value = _state.value.copy(notice = null)
    }
}

/** Что видно на экране переписки. */
data class ChatState(
    /** Новое сверху — так же, как отдаёт запрос к базе. */
    val lines: List<ChatLine> = emptyList(),
    /** Набранное, но не отправленное. Живёт до подтверждения постановки в очередь. */
    val draft: String = "",
    val notice: ChatNotice? = null,
    /** Запрос ключа в пути: второе нажатие не шлёт вторую просьбу чужим устройствам. */
    val expectKey: Boolean = false,
    /** Можно ли просить ключ: у личной переписки такой возможности нет. */
    val keyAskMay: Boolean = false,
    /** Набранная секретная фраза. Пусто — запрос уйдёт без подписи. */
    val phrase: String = "",
    /**
     * Групповая ли переписка. От этого зависит показ автора у каждой реплики: в личной
     * он лишний, в групповой без него сообщение теряет половину смысла.
     */
    val group: Boolean = false,
    /**
     * Круг следующего сообщения (ADR-0019): −1 шифр, 0…3 открытые.
     *
     * Умолчание — шифр: в личной переписке других не бывает, а в группе выбор человека
     * заменяет его при первом же касании.
     */
    val level: Int = -1,
    /**
     * Показывать ли метки круга у реплик.
     *
     * **По умолчанию выключено.** Метка отвечает на вопрос, которого у обычного участника
     * нет: он и так видит ровно то, что ему открыто. Включает её тот, кто распоряжается
     * содержимым, — админ или автор.
     */
    val showCircles: Boolean = false,
    /** Имена авторов по идентификатору. Пусто для личной переписки. */
    val names: Map<String, String> = emptyMap(),
    /**
     * Сужение, о котором человека предупредили и которого он ещё не подтвердил.
     *
     * Держится в состоянии, а не в замыкании кнопки: предупреждение — это отдельный шаг,
     * и между показом и подтверждением экран может пережить поворот.
     */
    val pendingNarrow: PendingNarrow? = null,
)

/** Задуманное сужение: до какого круга и от какого. */
data class PendingNarrow(val messageId: Long, val was: Int, val to: Int)

/**
 * Сообщение человеку. Список короткий намеренно: то, что очередь решает сама
 * (повторы, ожидание сети), человеку сообщать нечем и незачем — это видно по
 * состоянию строки.
 */
sealed interface ChatNotice {
    /** Слишком большое. Числа в сообщении нужны: «слишком большое» без размера бесполезно. */
    data class TooLarge(val bytes: Int, val limit: Int) : ChatNotice

    /** Просьба ушла: ключи приедут, когда ответит кто-то из этих устройств. */
    data class KeysAsked(val devices: Int) : ChatNotice

    /** Просить некого: нужных версий нет ни у кого из участников. Ждать бесполезно. */
    data object KeysNoHelpers : ChatNotice

    /** Недостающих версий нет — значит, сообщение не читается по другой причине. */
    data object KeysNothingMissing : ChatNotice

    /**
     * Нужна секретная фраза: аккаунт защищён ею от угона номера.
     *
     * **Для устройства, подключённого по QR-коду, это тупик**, и текст обязан назвать
     * второй выход. Фразы у такого устройства нет по построению; зато смену ключа в
     * группе может запустить любой участник — значит и сам человек, с другого своего
     * устройства. После смены группа читается вперёд, прежнее остаётся закрытым.
     */
    data object KeysNeedPhrase : ChatNotice

    data class KeysRefused(val text: String) : ChatNotice

    /**
     * Предупреждение перед сужением: у тех, кто унёс, запись останется.
     *
     * Это главное, чего человек не ожидает. Сужение выглядит как «спрятать», а прячет оно
     * только вперёд: перенос уже сделан, и отзывать чужие страницы никто не станет.
     */
    data class NarrowWarning(val circle: String) : ChatNotice

    /** Сузили. Круг назван словами: номера человек не видит нигде. */
    data class Narrowed(val circle: String) : ChatNotice

    data class NarrowRefused(val text: String) : ChatNotice
}
