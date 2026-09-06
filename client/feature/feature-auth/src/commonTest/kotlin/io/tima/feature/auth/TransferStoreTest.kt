package io.tima.feature.auth

import io.tima.domain.account.DeviceKeyMaterial
import io.tima.domain.account.Session
import io.tima.domain.account.TransferAcceptStep
import io.tima.domain.account.TransferProver
import io.tima.domain.account.TransferStartStep
import io.tima.domain.account.TransferVirtual
import io.tima.domain.account.TransfersApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Передача виртуального аккаунта на стороне экрана — ПЛАН-КОНТАКТОВ.md, Д12.
 *
 * Отдельно проверяется путь **снаружи**: код приносит штатная камера телефона, а не
 * человек руками. До 2026-09-06 этот путь не работал вовсе — переход `tima://transfer/…`
 * не был объявлен в манифесте, и система не знала, кому отдать ссылку.
 */
class TransferStoreTest {

    private val принесено = "tima://transfer/v1?code=" + "A".repeat(43)

    private class Server(
        val accept: TransferAcceptStep = TransferAcceptStep.Taken(
            session = Session("u-9", "d-9", "jwt-9"),
            rotateNeeded = false,
        ),
    ) : TransfersApi {
        var предъявлено: String? = null

        override suspend fun start(virtualUserId: String) =
            TransferStartStep.Code("code-1", 1800, 3)

        override suspend fun cancel(virtualUserId: String) = true

        override suspend fun accept(
            code: String,
            proof: ByteArray,
            encryptionPub: ByteArray,
            signingPub: ByteArray,
            platform: String,
        ): TransferAcceptStep {
            предъявлено = code
            return accept
        }
    }

    private fun магазин(server: Server, scope: kotlinx.coroutines.CoroutineScope) = TransferStore(
        transfer = TransferVirtual(
            api = server,
            keys = {
                DeviceKeyMaterial(
                    encryptionPub = ByteArray(32) { 1 },
                    signingPub = ByteArray(32) { 2 },
                    secret = ByteArray(32) { 3 },
                )
            },
            prover = TransferProver { _, _ -> ByteArray(64) { 5 } },
            platform = "android",
        ),
        payloadOf = { "tima://transfer/v1?code=$it" },
        // Разбор настоящий: половина смысла этой проверки в том, что принесённое камерой
        // проходит его без правки руками.
        parse = { line ->
            line.removePrefix("tima://transfer/v1?code=").substringBefore('&').trim()
                .takeIf { it.length == 43 }
        },
        scope = scope,
    )

    @Test
    fun принесённый_камерой_код_уже_в_поле() = runTest {
        val store = магазин(Server(), backgroundScope)
        store.takingSide(принесено)

        val state = store.state.value
        assertEquals(TransferSide.Taking, state.side)
        assertEquals(принесено, state.brought)
        // Фраза не приходит вместе с кодом и прийти не может: её передают отдельным
        // путём, и в этом весь расчёт передачи.
        assertEquals("", state.phrase)
        assertNull(state.trouble)
    }

    @Test
    fun принесённое_доходит_до_сервера_разобранным() = runTest {
        val server = Server()
        val store = магазин(server, backgroundScope)
        store.takingSide(принесено)
        store.changedPhrase("слово ".repeat(12).trim())
        store.take()

        val state = store.state.first { it.taken != null }
        // На сервер уходит код, а не ссылка целиком: разбор один и живёт в магазине.
        assertEquals("A".repeat(43), server.предъявлено)
        assertEquals("u-9", state.taken?.session?.userId)
    }

    @Test
    fun вход_из_настроек_оставляет_поле_пустым() = runTest {
        val store = магазин(Server(), backgroundScope)
        store.takingSide()

        assertEquals("", store.state.value.brought)
    }
}
