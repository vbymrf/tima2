package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.tima.domain.chat.Friends
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Друзья по HTTP — `/api/v1/users/me/feed/subscribers` (ПЛАН-КОНТАКТОВ.md, Д1б).
 *
 * **Слова «друзья» сервер не знает.** Он хранит одно: кому открыта моя лента. Сущность
 * «друзья» живёт у клиента, а сюда уходит её след — подписка. Отдельная таблица друзей
 * на сервере дублировала бы это отношение вторым способом (снята миграцией 0040).
 *
 * **Подписывает владелец, а не читатель.** Открытие чужой страницы не подписывает ни на
 * что: иначе своим становился бы любой прохожий.
 *
 * **Отказ возвращается, а не бросается.** Добавление друга — часть добавления контакта, и
 * контакт обязан сохраниться даже тогда, когда сервер промолчал: без сети человек всё
 * равно записал номер себе в книгу.
 */
class FriendsOverHttp(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) : Friends {

    override suspend fun set(userId: String, friend: Boolean): Boolean = try {
        val response = if (friend) {
            client.post(route.api("/api/v1/users/me/feed/subscribers")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("user_id", JsonPrimitive(userId)) }.toString())
            }
        } else {
            client.delete(route.api("/api/v1/users/me/feed/subscribers/$userId")) {
                header("Authorization", "Bearer ${token()}")
            }
        }
        // 201 при открытии ленты, 204 при закрытии. Повторное открытие тоже 201:
        // сервер не считает его ошибкой, и клиенту незачем считать иначе.
        response.status == HttpStatusCode.Created || response.status == HttpStatusCode.NoContent
    } catch (_: Throwable) {
        false
    }
}
