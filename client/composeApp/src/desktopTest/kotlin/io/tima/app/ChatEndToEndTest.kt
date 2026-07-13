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

    /** Регистрация устройства; при phrase != null — с ключом личности из этой фразы. */
    private suspend fun register(api: TimaApi, phone: String, phrase: List<String>? = null): Session {
        val sms = api.smsRequest(phone)
        val code = requireNotNull(sms.devCode) { "сервер должен работать с TIMA_DEV_SMS=1" }
        val token = api.smsVerify(sms.requestId, code).registrationToken
        val key = Kodium.generateKeyPair()
        val pub = key.getPublicKey()
        val identity = phrase?.let { io.tima.crypto.AccountMnemonic.identityFromMnemonic(it) }
        val reg = api.register(
            token, b64url.encode(pub.encryptionKey), b64url.encode(pub.signingKey),
            identityPub = identity?.let { b64url.encode(it.getPublicKey().signingKey) } ?: "",
        )
        return Session(
            serverUrl = base,
            userId = reg.userId,
            deviceId = reg.deviceId,
            accessToken = reg.accessToken,
            deviceSecretB64 = b64url.encode(key.secretKey),
            identitySecretB64 = identity?.let { b64url.encode(it.secretKey) } ?: "",
            backupSecretB64 = phrase?.let { b64url.encode(io.tima.crypto.AccountMnemonic.backupKeyFromMnemonic(it)) } ?: "",
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
        val alicePhone = "+7999%07d".format(suffix)
        val bobPhone = "+7998%07d".format(suffix)
        val alice = register(api, alicePhone)
        val bob = register(api, bobPhone)

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

            // Фото: MediaCipher → MinIO по presigned PUT → CK_IMAGE с MediaRef → приём и расшифровка
            val image = ByteArray(2048).also { java.security.SecureRandom().nextBytes(it) }
            aliceChat.sendImage(bob.userId, image, "image/png", "подпись к фото")
            val photo = withTimeout(20_000) { bobChat.messages.first { it.media != null } }
            assertEquals("подпись к фото", photo.text)
            assertEquals("image/png", photo.media!!.mime)
            val loaded = bobChat.loadMedia(photo.media!!)
            assertTrue(image.contentEquals(loaded), "расшифрованное медиа обязано совпасть с исходником")

            // Группа: создание + ротация GK Алисой, приём и расшифровка Бобом (GK по wrapped_GK)
            val group = aliceChat.createGroup("Тест-группа", listOf(bobPhone))
            val bobGroups = bobChat.myGroups()
            assertTrue(bobGroups.any { it.groupId == group.groupId }, "Боб обязан видеть группу в своём списке")
            aliceChat.sendGroup(group.groupId, "Привет, группа! 🔐")
            val groupMsg = withTimeout(20_000) { bobChat.messages.first { it.group && it.chatId == group.groupId } }
            assertEquals("Привет, группа! 🔐", groupMsg.text)
            assertEquals(alice.userId, groupMsg.senderId)
            // Ответ Боба (member): GK берётся догоном с сервера, подпись своя
            bobChat.sendGroup(group.groupId, "Принято!")
            val aliceGroupHistory = withTimeout(20_000) {
                var h = aliceChat.groupHistory(group.groupId)
                while (h.size < 2) {
                    kotlinx.coroutines.delay(300)
                    h = aliceChat.groupHistory(group.groupId)
                }
                h
            }
            assertEquals(listOf("Привет, группа! 🔐", "Принято!"), aliceGroupHistory.map { it.text })
        } finally {
            aliceChat.close()
            bobChat.close()
        }
    }

    @Test
    fun `заметки себе восстанавливаются из бэкапа по фразе без онлайн-источников`() = runBlocking {
        val api = TimaApi(base)
        try {
            api.smsRequest("+79990000001")
        } catch (e: Throwable) {
            println("сервер недоступен ($e) — тест пропущен")
            return@runBlocking
        }
        val suffix = Random.nextInt(1_000_000)
        val phone = "+7993%07d".format(suffix)
        val phrase = io.tima.crypto.AccountMnemonic.generate()
        val dev1 = register(api, phone, phrase)

        val dev1Chat = createChatClient(dev1)
        dev1Chat.start()
        dev1Chat.send(dev1.userId, "Заметка самому себе") // self-чат → сохраняет бэкап под фразу
        assertEquals(listOf("Заметка самому себе"), dev1Chat.history(dev1.userId).map { it.text })
        dev1Chat.close() // первое устройство ОФЛАЙН — бэкап не зависит от него

        // Новое устройство с той же фразой: своих онлайн-источников нет, только бэкап
        val dev2 = register(api, phone, phrase)
        val dev2Chat = createChatClient(dev2)
        try {
            dev2Chat.start()
            assertTrue(dev2Chat.history(dev2.userId).isEmpty(), "новое устройство не видит заметки без ключей")
            val recovered = dev2Chat.recoverChatHistory(dev2.userId).map { it.text }
            assertEquals(
                listOf("Заметка самому себе"), recovered,
                "заметки восстановлены из бэкапа по фразе, было: $recovered",
            )
        } finally {
            dev2Chat.close()
        }
    }

    @Test
    fun `новое устройство восстанавливает личный чат у своего старого устройства`() = runBlocking {
        val api = TimaApi(base)
        try {
            api.smsRequest("+79990000001")
        } catch (e: Throwable) {
            println("сервер недоступен ($e) — тест пропущен")
            return@runBlocking
        }
        val suffix = Random.nextInt(1_000_000)
        val alicePhone = "+7995%07d".format(suffix)
        val alice1 = register(api, alicePhone)
        val bob = register(api, "+7994%07d".format(suffix))

        val alice1Chat = createChatClient(alice1)
        val bobChat = createChatClient(bob)
        try {
            alice1Chat.start() // первое устройство Алисы — помощник, должно быть онлайн
            bobChat.start()
            alice1Chat.send(bob.userId, "Личное сообщение до второго устройства")
            bobChat.send(alice1.userId, "Ответ Боба")

            // Второе устройство Алисы: тот же телефон → тот же аккаунт, НОВЫЙ device без ключей
            val alice2 = register(api, alicePhone)
            val alice2Chat = createChatClient(alice2)
            try {
                alice2Chat.start()
                assertTrue(
                    alice2Chat.history(bob.userId).isEmpty(),
                    "новое устройство не должно видеть личную переписку без ключей",
                )
                // Восстановление у своего первого устройства (свои устройства — авто-помощь)
                val recovered = alice2Chat.recoverChatHistory(bob.userId).map { it.text }
                assertTrue(
                    recovered.contains("Личное сообщение до второго устройства") &&
                        recovered.contains("Ответ Боба"),
                    "после восстановления второе устройство читает личную переписку, было: $recovered",
                )
            } finally {
                alice2Chat.close()
            }
        } finally {
            alice1Chat.close()
            bobChat.close()
        }
    }

    @Test
    fun `новое устройство участника восстанавливает историю группы`() = runBlocking {
        val api = TimaApi(base)
        try {
            api.smsRequest("+79990000001")
        } catch (e: Throwable) {
            println("сервер недоступен ($e) — тест пропущен")
            return@runBlocking
        }
        val suffix = Random.nextInt(1_000_000)
        // Боб — с ключом личности (recovery-фраза): восстановление требует подписи ею (этап 3)
        val bobPhrase = io.tima.crypto.AccountMnemonic.generate()
        val alice = register(api, "+7997%07d".format(suffix))
        val bobPhone = "+7996%07d".format(suffix)
        val bob1 = register(api, bobPhone, bobPhrase)

        val aliceChat = createChatClient(alice)
        val bob1Chat = createChatClient(bob1)
        try {
            aliceChat.start()
            bob1Chat.start() // помощник обязан быть онлайн, чтобы поделиться ключом
            val group = aliceChat.createGroup("История группы", listOf(bobPhone))
            aliceChat.sendGroup(group.groupId, "Сообщение до входа второго устройства")

            // Первое устройство Боба читает (GK v1 получен при ротации)
            val h1 = withTimeout(20_000) {
                var h = bob1Chat.groupHistory(group.groupId)
                while (h.isEmpty()) { kotlinx.coroutines.delay(300); h = bob1Chat.groupHistory(group.groupId) }
                h
            }
            assertEquals(listOf("Сообщение до входа второго устройства"), h1.map { it.text })

            // Второе устройство Боба: тот же телефон → тот же аккаунт, ТА ЖЕ фраза → тот же
            // ключ личности; НОВЫЙ device без GK v1. Восстановление подписывается фразой.
            val bob2 = register(api, bobPhone, bobPhrase)
            val bob2Chat = createChatClient(bob2)
            try {
                bob2Chat.start()
                // Без ключа история недоступна
                assertTrue(
                    bob2Chat.groupHistory(group.groupId).isEmpty(),
                    "новое устройство не должно читать историю без GK",
                )
                // Восстановление у участников (bob1 онлайн помогает), запрос подписан фразой
                val recovered = bob2Chat.recoverGroupHistory(group.groupId)
                assertEquals(
                    listOf("Сообщение до входа второго устройства"), recovered.map { it.text },
                    "после восстановления второе устройство читает историю",
                )
                // Барьер этапа 3 (чужая фраза → отказ) проверяется в Go: TestAccountIdentity
            } finally {
                bob2Chat.close()
            }
        } finally {
            aliceChat.close()
            bob1Chat.close()
        }
    }
}
