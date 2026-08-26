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
    suspend fun create(title: String, number: List<String> = emptyList()): CreateGroupStep {
        val name = title.trim()
        if (name.isEmpty()) return CreateGroupStep.BadTitle("Без названия группу не найти в списке")
        if (name.encodeToByteArray().size > ПРЕДЕЛ_НАЗВАНИЯ) {
            return CreateGroupStep.BadTitle("Название длиннее $ПРЕДЕЛ_НАЗВАНИЯ байт сервер не примет")
        }

        val creation = groups.create(name)
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

        return CreateGroupStep.Created(groupId = groupId, notInvited = notInvited)
    }

    private companion object {
        /** Предел сервера на название — в БАЙТАХ, а не знаках: кириллица занимает по два. */
        const val ПРЕДЕЛ_НАЗВАНИЯ = 200
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
    suspend fun create(title: String): GroupCreateStep
    suspend fun mine(): GroupsStep
    suspend fun members(groupId: String): MembersStep
    suspend fun addMember(groupId: String, userId: String): MemberStep
    suspend fun removeMember(groupId: String, userId: String): MemberStep
}

/** Группа, как её знает сервер. */
class GroupInfo(
    val groupId: String,
    val title: String,
    /** Моя роль. От неё зависит, что мне можно: звать, исключать, менять название. */
    val myRole: GroupRole,
)

/** Участник группы. */
class GroupMember(val userId: String, val role: GroupRole, val bannedUntil: String?)

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
    data class Created(val groupId: String, val notInvited: List<String>) : CreateGroupStep

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
