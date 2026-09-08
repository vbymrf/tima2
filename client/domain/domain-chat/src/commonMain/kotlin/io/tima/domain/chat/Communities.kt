package io.tima.domain.chat

/**
 * Сообщества: контейнер, который связывает готовое (ПЛАН-СООБЩЕСТВ, С5…С7).
 *
 * **Внесение — это связывание, а не создание нового и не переезд.** Внесли группу —
 * поменялась одна ссылка; переписка, участники и ключи остались там, где лежали. Отсюда и
 * устройство мастера: последний шаг называется «что вносим» и показывает **уже
 * существующие** группы и каналы, а не предлагает завести новые.
 *
 * **Элемент не бывает в двух сообществах.** Это держит сервер, а здесь — отдельный ответ
 * [LinkStep.Busy]: человеку надо сказать, что группа занята, а не что ему не разрешено.
 */
class CreateCommunity(private val communities: Communities) {

    suspend fun create(title: String, description: String, items: List<CommunityItem>): CommunityStep {
        val name = title.trim()
        if (name.isEmpty()) return CommunityStep.BadTitle("Название обязательно")
        if (name.length > MAX_TITLE) return CommunityStep.BadTitle("Название длиннее $MAX_TITLE знаков")

        val created = when (val outcome = communities.create(name)) {
            is CommunityStep.Created -> outcome
            else -> return outcome
        }
        // Описание — сообщение уровня 0, а не поле сообщества: то же решение, что у
        // группы (ADR-0019 §4). Пустое не отправляется вовсе: пустое сообщение и есть
        // отсутствие описания.
        if (description.isNotBlank()) {
            communities.describe(created.communityId, description.trim())
        }
        // Внесение идёт ПОСЛЕ создания и по одному: каждое — отдельная ссылка, и отказ по
        // одному элементу не должен отменять остальные. Что не связалось, названо.
        val busy = mutableListOf<String>()
        for (item in items) {
            if (communities.link(created.communityId, item.kind, item.id) == LinkStep.Busy) {
                busy += item.title
            }
        }
        return created.copy(notLinked = busy)
    }

    companion object {
        const val MAX_TITLE: Int = 200
    }
}

/** Элемент состава: готовая группа или канал. */
data class CommunityItem(
    val kind: String,
    val id: String,
    val title: String,
    /** Личная группа: в списке чужой страницы её не показывают никогда (ADR-0018 п. 5). */
    val personal: Boolean = false,
)

/** Виды элементов, которые сообщество умеет связывать. Звукового чата здесь нет: он ждёт реализации. */
object CommunityKinds {
    const val GROUP: String = "group"
    const val CHANNEL: String = "channel"
}

/** Что вышло из создания сообщества. */
sealed interface CommunityStep {
    data class Created(
        val communityId: String,
        /** Что не удалось внести: элемент уже в другом сообществе. */
        val notLinked: List<String> = emptyList(),
    ) : CommunityStep

    data class BadTitle(val reason: String) : CommunityStep
    data class Offline(val retryAfterMs: Long) : CommunityStep
    data class Refused(val reason: String) : CommunityStep
}

/** Что вышло из связывания. */
enum class LinkStep {
    Linked,

    /** Элемент уже в другом сообществе. Не «нельзя», а «занято». */
    Busy,

    /** Связывать может владелец сообщества и владелец элемента одновременно. */
    NotAllowed,

    Failed,
}

/** Страница сообщества: чем оно является и что в нём лежит. */
data class CommunityPage(
    val communityId: String,
    val title: String,
    val owner: Boolean,
    val admin: Boolean,
    val subscribed: Boolean,
    /** Описание — сообщения уровня 0. */
    val description: List<String> = emptyList(),
    val items: List<CommunityItem> = emptyList(),
)

/** Порт к серверу: сообщества. */
interface Communities {
    suspend fun create(title: String): CommunityStep
    suspend fun describe(communityId: String, text: String): Boolean
    suspend fun link(communityId: String, kind: String, itemId: String): LinkStep
    suspend fun unlink(communityId: String, kind: String, itemId: String): LinkStep
    suspend fun page(communityId: String): CommunityPage?
    suspend fun mine(): List<CommunityPage>

    /**
     * Что можно внести: свои группы и каналы, ещё не связанные ни с чем.
     *
     * Спрашивается у сервера, а не собирается из списков экранов: «своё» здесь значит
     * «где я владелец», а это знает он.
     */
    suspend fun linkable(): List<CommunityItem>
    suspend fun subscribe(communityId: String, on: Boolean): Boolean
}
