@file:OptIn(ExperimentalEncodingApi::class)

package io.tima.app

import io.kodium.Kodium
import io.tima.app.api.TimaApi
import io.tima.app.chat.createChatClient
import io.tima.app.session.Session
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * E2E клиентского конвейера против живого dev-сервера (как интеграционные тесты Go):
 * регистрация двух пользователей → конверт (слои 1+2+4 + подпись) → приём по WS
 * с расшифровкой → история обеих сторон. Пропускается, если сервер не поднят.
 *
 * Нужны: tima serve с REDIS_URL и ESCROW_URL (+ escrow-stub) — см. server/README.md.
 */
class ChatEndToEndTest {

    private val base = "http://127.0.0.1:8080"
    private val b64url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    private suspend fun register(api: TimaApi, phone: String): Session {
        val sms = api.smsRequest(phone)
        val code = requireNotNull(sms.devCode) { "сервер должен работать с TIMA_DEV_SMS=1" }
        val token = api.smsVerify(sms.requestId, code).registrationToken
        val key = Kodium.generateKeyPair()
        val pub = key.getPublicKey()
        val reg = api.register(token, b64url.encode(pub.encryptionKey), b64url.encode(pub.signingKey))
        return Session(
            serverUrl = base,
            userId = reg.userId,
            deviceId = reg.deviceId,
            accessToken = reg.accessToken,
            deviceSecretB64 = b64url.encode(key.secretKey),
        )
    }

    @Test
    fun `две стороны обмениваются шифрованными сообщениями`() = runBlocking {
        val api = TimaApi(base)
        try {
            api.smsRequest("+79990000001")
        } catch (e: Throwable) {
            println("сервер недоступен ($e) — тест пропущен; подними tima serve по server/README.md")
            return@runBlocking
        }

        // Случайные телефоны: повторные прогоны не упираются в дедуп и rate limit телефона
        val suffix = Random.nextInt(1_000_000)
        val alice = register(api, "+7999%07d".format(suffix))
        val bob = register(api, "+7998%07d".format(suffix))

        val aliceChat = createChatClient(alice)
        val bobChat = createChatClient(bob)
        assertEquals(
            aliceChat.chatIdWith(bob.userId), bobChat.chatIdWith(alice.userId),
            "детерминированный chat_id обязан совпасть",
        )

        try {
            bobChat.start() // Боб онлайн до отправки — проверяем live-доставку
            val text = "Привет, Боб! 🔐 Это конверт TIMA."
            aliceChat.send(bob.userId, text)

            val live = withTimeout(20_000) { bobChat.messages.first { !it.mine } }
            assertEquals(text, live.text)
            assertEquals(alice.userId, live.senderId)
            assertEquals(bobChat.chatIdWith(alice.userId), live.chatId)

            // История: обе стороны читают (у отправителя — обёртка своего устройства)
            val bobHistory = bobChat.history(alice.userId)
            assertEquals(listOf(text), bobHistory.map { it.text })
            val aliceHistory = aliceChat.history(bob.userId)
            assertEquals(listOf(text), aliceHistory.map { it.text })
            assertTrue(aliceHistory.single().mine)

            // Ответ в обратную сторону тем же чатом; Боб получает эхо своего сообщения по WS
            bobChat.send(alice.userId, "И тебе привет, Алиса!")
            val echo = withTimeout(20_000) { bobChat.messages.first { it.mine } }
            assertEquals("И тебе привет, Алиса!", echo.text)
            val aliceAfter = withTimeout(20_000) {
                var h = aliceChat.history(bob.userId)
                while (h.size < 2) {
                    kotlinx.coroutines.delay(300)
                    h = aliceChat.history(bob.userId)
                }
                h
            }
            assertEquals(2, aliceAfter.size)
            assertEquals("И тебе привет, Алиса!", aliceAfter.last().text)
        } finally {
            aliceChat.close()
            bobChat.close()
        }
    }
}
