package io.tima.domain.chat

/**
 * Группы: создать и знать о тех, куда позвали.
 *
 * **Чем группа отличается от личной переписки — не экраном, а тем, откуда берётся её
 * идентификатор.** `chat_id` личной выводится из пары участников и сервером не назначается
 * (см. [StartPersonalChat]); `group_id` **выдаёт сервер**, потому что состав группы меняется
 * и вывести её идентификатор из состава нельзя — он перестал бы совпадать после первого же
 * входа участника.
 *
 * Отсюда и порядок: сначала группа появляется на сервере, потом запоминается у нас. Обратный
 * порядок оставил бы местную строку без группы — переписку, которой нигде нет.
 */
class CreateGroupChat(
    private val groups: GroupRegistry,
    private val directory: UserDirectory,
    private val chats: ChatBook,
    /**
     * Выпуск группового ключа. `null` — без ключа (так собирают тесты состава).
     *
     * ── ПОЧЕМУ ОН ЗДЕСЬ, И ЧТО БЫЛО БЕЗ НЕГО ────────────────────────────────────
     *
     * Группа без ключа — тупик, и в нём побывал стенд 2026-09-16…18. Создание группы
     * ключа не выпускало; отправка в группу без ключа отвечает `NoKey`; экран говорил
     * «ключа этой группы на устройстве нет — попросите у участников». У владельца свежей
     * группы участников нет, а выпустить ключ мог только он — и не мог: ни одно действие
     * к выпуску не вело. На сервере у таких групп пусты и история ключей, и обёртки, и
     * сообщения.
     *
     * В v1 (`ref/client-v1`, `TimaChatService.createGroup`) сразу после добавления
     * участников шёл `rotateGroup(groupId, currentVersion = 0)` — группа рождалась с
     * ключом версии 1. При переписывании на `CreateGroupChat` этот вызов потерялся.
     */
    private val rotator: GroupKeyRotator? = null,
) {

    /**
     * Создать группу и позвать людей по номерам.
     *
     * **Незарегистрированный номер не отменяет создание группы.** Из десяти приглашённых
     * один может не пользоваться TIMA, и терять из-за него всю группу человек не согласится.
     * Поэтому такие номера возвращаются списком — их надо показать и предложить позвать
     * человека, а не молча выбросить.
     *
     * @param название до 200 байт: предел сервера. Проверяется здесь, чтобы отказ пришёл до
     *   сети и словами, а не как `bad_title`.
     */
    suspend fun create(
        title: String,
        number: List<String> = emptyList(),
        kind: GroupKind = GroupKind.Personal,
        description: String = "",
    ): CreateGroupStep {
        val name = title.trim()
        if (name.isEmpty()) return CreateGroupStep.BadTitle("Без названия группу не найти в списке")
        if (name.encodeToByteArray().size > ПРЕДЕЛ_НАЗВАНИЯ) {
            return CreateGroupStep.BadTitle("Название длиннее $ПРЕДЕЛ_НАЗВАНИЯ байт сервер не примет")
        }
        // Предел описания — общий предел сообщения (ADR-0019 §3): описание и есть
        // сообщение уровня 0, и особых правил у него нет.
        if (description.length > ПРЕДЕЛ_ОПИСАНИЯ) {
            return CreateGroupStep.BadTitle("Описание длиннее $ПРЕДЕЛ_ОПИСАНИЯ знаков")
        }

        val creation = groups.create(name, kind, description.trim())
        val groupId = when (creation) {
            is GroupCreateStep.Created -> creation.groupId
            is GroupCreateStep.Offline -> return CreateGroupStep.Offline(creation.retryAfterMs)
            is GroupCreateStep.Refused -> return CreateGroupStep.Refused(creation.reason)
        }

        // Запоминаем сразу после создания, до приглашений: группа уже есть, и потеряй мы её
        // здесь — человек остался бы с группой, о которой знает только сервер.
        chats.remember(chatId = groupId, kind = ChatKind.Group, title = name, peerId = null)

        val notInvited = mutableListOf<String>()
        for (number in number.map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
            when (val found = directory.byPhone(number)) {
                is UserLookup.Found ->
                    if (groups.addMember(groupId, found.userId) !is MemberStep.Done) {
                        notInvited += number
                    }
                else -> notInvited += number
            }
        }

        // Первый ключ — сразу, ПОСЛЕ добавления участников: ротация заворачивает ключ
        // на устройства всех, кто в группе на этот момент, и добавленные до неё получат
        // его без второй ротации. Провал ротации группу не отменяет — она уже есть, и ключ
        // выпустит первое же открытие (см. `HealGroupKey`); но сказать о нём наружу стоит.
        // Только личной: у публичной группы ключа нет по замыслу, сервер ответит not_e2e.
        val keyed = if (kind == GroupKind.Personal) {
            rotator?.rotate(groupId, RotationReason.MemberJoin).let { it == null || it is RotateStep.Rotated || it is RotateStep.VersionConflict }
        } else {
            true
        }

        return CreateGroupStep.Created(groupId = groupId, notInvited = notInvited, keyIssued = keyed)
    }

    private companion object {
        /** Предел сервера на название — в БАЙТАХ, а не знаках: кириллица занимает по два. */
        const val ПРЕДЕЛ_НАЗВАНИЯ = 200

        /** Предел описания — в ЗНАКАХ UTF-16, как у любого сообщения (ADR-0019 §3). */
        const val ПРЕДЕЛ_ОПИСАНИЯ = 4096
    }
}

/**
 * Узнать о группах, куда меня позвали.
 *
 * Нужно потому, что **в группу добавляет кто-то другой**, и никакого местного следа это не
 * оставляет: сообщений в ней ещё нет, строку `chats` заводить было некому. Без этой сверки
 * человек узнал бы о группе только с первым сообщением — то есть узнал бы о ней последним.
 */
class SyncGroupChats(
    private val groups: GroupRegistry,
    private val chats: ChatBook,
) {

    /** @return сколько групп известно серверу, либо отказ. */
    suspend fun refresh(): SyncGroupsStep = when (val answer = groups.mine()) {
        is GroupsStep.Groups -> {
            for (group in answer.groups) {
                // Название берём серверное: у группы оно общее, в отличие от имени личной
                // переписки, которое каждый видит своё.
                chats.remember(
                    chatId = group.groupId,
                    kind = ChatKind.Group,
                    title = group.title.ifBlank { "Группа" },
                    peerId = null,
                )
            }
            SyncGroupsStep.Synced(answer.groups.size)
        }
        is GroupsStep.Offline -> SyncGroupsStep.Offline(answer.retryAfterMs)
        is GroupsStep.Refused -> SyncGroupsStep.Refused(answer.reason)
    }
}

// ── порт ────────────────────────────────────────────────────────────────────

/** Порт к группам на сервере. Реализуется `core-network`. */
interface GroupRegistry {
    suspend fun create(
        title: String,
        kind: GroupKind = GroupKind.Personal,
        description: String = "",
    ): GroupCreateStep
    suspend fun mine(): GroupsStep
    suspend fun members(groupId: String): MembersStep
    suspend fun addMember(groupId: String, userId: String): MemberStep
    suspend fun removeMember(groupId: String, userId: String): MemberStep

    /**
     * Карточки, которые мне открыли, — вкладка «Друзья» окна 2.
     *
     * Своих групп здесь нет: они в «Каталоге». Одна группа в двух списках заставила бы
     * гадать, чем списки различаются.
     */
    suspend fun cards(): CardsStep = CardsStep.Cards(emptyList())

    /** Попроситься в чужую личную группу — единственное действие с ней (ADR-0018 п. 7). */
    suspend fun askToJoin(groupId: String): AskStep = AskStep.Refused("не реализовано")
}

/** Карточка чужой группы: чем она себя называет. */
class GroupCard(
    val groupId: String,
    val title: String,
    val description: String,
    val kind: GroupKind,
)

sealed interface CardsStep {
    data class Cards(val cards: List<GroupCard>) : CardsStep
    data class Offline(val retryAfterMs: Long) : CardsStep
    data class Refused(val reason: String) : CardsStep
}

sealed interface AskStep {
    /** Просьба ушла. [state] — «pending» либо «accepted», если уже приняли раньше. */
    data class Asked(val state: String) : AskStep
    data class Offline(val retryAfterMs: Long) : AskStep
    data class Refused(val reason: String) : AskStep
}

/**
 * Вид группы — ось, от которой зависит шифрование (ADR-0019 §1, `doc_UI/33` шаг 2).
 *
 * **Не меняется после создания.** Перешифровать «на месте» нельзя: сменить вид значит
 * создать другую группу, и мастер говорит это словами на самом шаге.
 */
enum class GroupKind(val wire: String) {
    /** Личная: сообщения зашифрованы групповым ключом, поиском не находится. */
    Personal("private"),

    /** Публичная: сервер видит переписку, находится поиском и каталогом. */
    Public("public"),
    ;

    /**
     * Какие круги сервер принимает у группы этого вида (`postGroupMessage`:
     * `level_in_private`, `secret_in_public`). Предлагать другие — получать 400 и крестик:
     * так и вышло с «nafig» на Redmi 2026-09-18 — «Своим» в личной группе.
     */
    val circles: List<MessageCircle>
        get() = when (this) {
            Personal -> listOf(MessageCircle.Secret, MessageCircle.Everyone)
            Public -> MessageCircle.entries.filter { it != MessageCircle.Secret }
        }

    companion object {
        fun fromWire(wire: String): GroupKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** Группа, как её знает сервер. */
class GroupInfo(
    val groupId: String,
    val title: String,
    /** Моя роль. От неё зависит, что мне можно: звать, исключать, менять название. */
    val myRole: GroupRole,
    /** Владелец: его реплики — салатовой полосой темы, остальных — своими цветами. */
    val ownerId: String = "",
    /**
     * Вид группы: от него зависят круги сообщений. У личной сервер принимает только
     * «Зашифровано» (−1) и «Всем и всегда» (0), у публичной — 0…3 без шифра. `null` —
     * сервер вида не назвал.
     */
    val kind: GroupKind? = null,
)

/** Участник группы. */
class GroupMember(
    val userId: String,
    val role: GroupRole,
    val bannedUntil: String?,
    /** Номер оттенка полосы 0…99, который участник выбрал себе в этой группе; `null` — автомат. */
    val hue: Int? = null,
)

/**
 * Роль в группе.
 *
 * Перечень, а не строка: от роли зависят права, и опечатка в строке означала бы молча
 * отобранное или молча выданное право. [Неизвестная] — роль, которой этот клиент не знает:
 * сервер новее нас, и делать вид, что это `member`, значит выдать права по ошибке.
 */
enum class GroupRole {
    Owner,
    Admin,
    Moderator,
    Member,
    Unknown,
    ;

    /** Может звать и исключать. Правило сервера: owner и admin. */
    val deliveryEdits: Boolean get() = this == Owner || this == Admin

    companion object {
        fun from(line: String): GroupRole = when (line) {
            "owner" -> Owner
            "admin" -> Admin
            "moderator" -> Moderator
            "member" -> Member
            else -> Unknown
        }
    }
}

// ── исходы ──────────────────────────────────────────────────────────────────

/** Чем закончилось создание группы для человека. */
sealed interface CreateGroupStep {
    /**
     * @param непозванные номера, которых нет в TIMA или которых не удалось добавить. Группа
     *   при этом создана: терять её из-за одного номера человек не согласится.
     */
    data class Created(
        val groupId: String,
        val notInvited: List<String>,
        /** Ключ выпущен. `false` — группа есть, ключа нет; его выпустит первое открытие. */
        val keyIssued: Boolean = true,
    ) : CreateGroupStep

    data class BadTitle(val reason: String) : CreateGroupStep
    data class Offline(val retryAfterMs: Long) : CreateGroupStep
    data class Refused(val reason: String) : CreateGroupStep
}

sealed interface SyncGroupsStep {
    data class Synced(val count: Int) : SyncGroupsStep
    data class Offline(val retryAfterMs: Long) : SyncGroupsStep
    data class Refused(val reason: String) : SyncGroupsStep
}

sealed interface GroupCreateStep {
    data class Created(val groupId: String) : GroupCreateStep
    data class Offline(val retryAfterMs: Long) : GroupCreateStep
    data class Refused(val reason: String) : GroupCreateStep
}

sealed interface GroupsStep {
    data class Groups(val groups: List<GroupInfo>) : GroupsStep
    data class Offline(val retryAfterMs: Long) : GroupsStep
    data class Refused(val reason: String) : GroupsStep
}

sealed interface MembersStep {
    data class Members(val members: List<GroupMember>) : MembersStep
    data class Offline(val retryAfterMs: Long) : MembersStep
    data class Refused(val reason: String) : MembersStep
}

/** Исход правки состава. */
sealed interface MemberStep {
    data object Done : MemberStep

    /** Такого человека в TIMA нет: его надо позвать в мессенджер, а не в группу. */
    data object NoSuchUser : MemberStep

    /** Прав не хватает. */
    data object Forbidden : MemberStep
    data class Offline(val retryAfterMs: Long) : MemberStep
    data class Refused(val reason: String) : MemberStep
}
