package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Группы. Форма ответов — из `internal/api/group_service.go` дословно.
 *
 * Главное здесь — **название группы приходит от человека**, и это единственное место в
 * модуле, где в тело JSON попадает его текст. Кавычка в названии без экранирования ломала бы
 * запрос, и выглядело бы это как отказ сервера на пустом месте.
 */
class GroupsApiTest {

    private val route = ServerRoute.from(RouteConfig(host = "example.com"))
    private lateinit var engine: MockEngine

    private fun api(responds: MockRequestHandler): GroupsApi {
        engine = MockEngine(responds)
        return GroupsApi(route, HttpClient(engine) { timaDefaults() }, token = { "t-1" })
    }

    private fun json(body: String, status: HttpStatusCode = HttpStatusCode.OK): MockRequestHandler =
        { respond(body, status, headersOf("Content-Type", "application/json")) }

    private fun body(request: HttpRequestData): String = (request.body as TextContent).text

    @Test
    fun создание_уходит_с_видом_и_названием() = runTest {
        val api = api(json("""{"group_id":"g-1"}""", HttpStatusCode.Created))

        val outcome = api.create("Поход")

        val request = engine.requestHistory.single()
        assertTrue(request.url.encodedPath.endsWith("/api/v1/groups"), "не тот путь: ${request.url}")
        assertEquals("Bearer t-1", request.headers["Authorization"])
        assertTrue(body(request).contains(""""kind":"private""""), "kind stated explicitly: ${body(request)}")
        assertTrue(body(request).contains(""""title":"Поход""""), "not that body: ${body(request)}")
        assertIs<GroupCreateResult.Created>(outcome)
        assertEquals("g-1", outcome.groupId)
    }

    /**
     * Кавычка в названии не ломает запрос.
     *
     * Тело собирается строкой, а название набирает человек. Без экранирования кавычка
     * превратила бы JSON в мусор, и сервер ответил бы `bad_json` — то есть человек увидел бы
     * отказ там, где просто назвал группу «Поход "North"».
     */
    @Test
    fun кавычка_в_названии_экранируется() = runTest {
        val api = api(json("""{"group_id":"g-1"}""", HttpStatusCode.Created))

        api.create("""Hike "Север"" + перенос""")

        val text = body(engine.requestHistory.single())
        assertTrue(text.contains("""\"Север\""""), "quotes not escaped: $text")
    }

    @Test
    fun список_групп_разбирается_с_ролью() = runTest {
        val api = api(
            json(
                """{"groups":[""" +
                    """{"group_id":"g-1","title":"Поход","kind":"private","owner_id":"u-1","my_role":"owner"},""" +
                    """{"group_id":"g-2","title":"Работа","kind":"public","owner_id":"u-9","my_role":"member"}""" +
                    """]}""",
            ),
        )

        val outcome = api.mine()

        assertIs<GroupsResult.Groups>(outcome)
        assertEquals(2, outcome.groups.size)
        assertEquals("Поход", outcome.groups[0].title)
        assertEquals("owner", outcome.groups[0].myRole)
        assertEquals("member", outcome.groups[1].myRole)
    }

    @Test
    fun участники_разбираются_с_баном() = runTest {
        val api = api(
            json(
                """{"members":[""" +
                    """{"user_id":"u-1","role":"owner","joined_at":"2026-08-23T10:00:00Z"},""" +
                    """{"user_id":"u-2","role":"member","joined_at":"2026-08-23T11:00:00Z",""" +
                    """"banned_until":"2026-08-30T11:00:00Z"}""" +
                    """]}""",
            ),
        )

        val outcome = api.members("g-1")

        assertIs<MembersResult.Members>(outcome)
        assertEquals(2, outcome.members.size)
        assertEquals(null, outcome.members[0].bannedUntil)
        assertEquals("2026-08-30T11:00:00Z", outcome.members[1].bannedUntil)
    }

    /**
     * «Прав не хватает» и «человека нет» — разные исходы.
     *
     * Первое означает «попросите админа», второе — «позовите человека в TIMA». Слипнись они
     * в «не получилось», человек будет пробовать одно и то же.
     */
    @Test
    fun отказы_добавления_не_слипаются() = runTest {
        assertEquals(
            MemberResult.Forbidden,
            api(json("""{"code":"forbidden"}""", HttpStatusCode.Forbidden)).addMember("g-1", "u-2"),
        )
        assertEquals(
            MemberResult.NoSuchUser,
            api(json("""{"code":"user_not_found"}""", HttpStatusCode.NotFound)).addMember("g-1", "u-9"),
        )
    }

    @Test
    fun исключение_идёт_delete_по_пользователю() = runTest {
        val api = api(json("""{"removed":true}"""))

        assertEquals(MemberResult.Done, api.removeMember("g-1", "u-2"))

        val request = engine.requestHistory.single()
        assertEquals("DELETE", request.method.value)
        assertTrue(
            request.url.encodedPath.endsWith("/api/v1/groups/g-1/members/u-2"),
            "не тот путь: ${request.url}",
        )
    }
}
