package io.tima.domain.chat

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Отметки «просмотрено до» между устройствами — ЖУ9. */
class SyncReadsCopyTest {

    /** Ячейка сервера в памяти: ревизия только следующая, иначе 409 с текущим. */
    private class Ячейка : AccountStorePort {
        var blob: AccountStoreStep.Blob? = null
        var puts = 0
        override suspend fun fetch(): AccountStoreStep = blob ?: AccountStoreStep.Empty
        override suspend fun put(revision: Long, blob: ByteArray): AccountStoreStep {
            puts++
            val current = this.blob
            if (revision != (current?.revision ?: 0) + 1) return AccountStoreStep.Conflict(current!!)
            this.blob = AccountStoreStep.Blob(revision, "d", blob)
            return AccountStoreStep.Stored
        }
    }

    /** Шифр для проверки — открытый текст: здесь проверяется слияние, а не шифрование. */
    private object Открыто : ReadsCopyCodec {
        override fun seal(key: ByteArray, copy: ReadsCopy): ByteArray =
            (listOf(copy.revision.toString(), copy.device) + copy.marks.map { it.key + "=" + it.value }).joinToString("\n").encodeToByteArray()

        override fun open(key: ByteArray, sealed: ByteArray): ReadsCopy {
            val lines = sealed.decodeToString().split("\n")
            val marks = lines.drop(2).filter { it.isNotEmpty() }.associate { it.substringBefore("=") to it.substringAfter("=").toLong() }
            return ReadsCopy(lines[0].toLong(), lines[1], marks)
        }
    }

    private class Отметки(start: Map<String, Long> = emptyMap(), var грязно: Boolean = false) : ReadMarksPort {
        val map = start.toMutableMap()
        fun своя(chat: String, upto: Long) {
            if (upto > (map[chat] ?: -1)) {
                map[chat] = upto
                грязно = true
            }
        }
        override fun marks() = map.toMap()
        override fun dirty() = грязно
        override fun sent() { грязно = false }
        override fun merge(remote: Map<String, Long>): Map<String, Long> {
            val moved = remote.filter { (chat, upto) -> upto > (map[chat] ?: -1) }
            map.putAll(moved)
            return moved
        }
    }

    private class Память : RevisionMemory {
        var value = 0L
        override fun last() = value
        override fun remember(revision: Long) { value = revision }
    }

    private fun sync(cell: Ячейка, marks: Отметки, memory: Память = Память(), device: String = "d") =
        SyncReadsCopy(cell, Открыто, { ByteArray(32) }, memory, { device }, marks)

    @Test
    fun прочитал_на_телефоне_на_пк_переписка_сдвинулась() = runTest {
        val cell = Ячейка()
        val телефон = Отметки()
        телефон.своя("redmi", 100)
        assertIs<ReadsStep.Pushed>(sync(cell, телефон).push())

        val пк = Отметки()
        val pulled = sync(cell, пк).pull()

        assertIs<ReadsStep.Pulled>(pulled)
        assertEquals(mapOf("redmi" to 100L), pulled.advanced)
    }

    @Test
    fun слияние_большего_в_любом_порядке() = runTest {
        val a = Отметки(mapOf("x" to 5L, "y" to 9L))
        val b = Отметки(mapOf("x" to 7L, "y" to 3L))

        a.merge(b.marks())
        b.merge(a.marks())

        assertEquals(mapOf("x" to 7L, "y" to 9L), a.marks())
        assertEquals(a.marks(), b.marks(), "порядок прихода неважен")
    }

    @Test
    fun ничего_не_менялось_не_отдаём() = runTest {
        val cell = Ячейка()
        assertEquals(ReadsStep.Unchanged, sync(cell, Отметки()).push())
        assertEquals(0, cell.puts, "без своих изменений — ни одного запроса")
    }

    @Test
    fun две_отправки_разом_сливаются() = runTest {
        // Телефон и ПК отдали каждый своё по одной и той же ревизии: второй получает 409,
        // сливает чужое и отправляет общее.
        val cell = Ячейка()
        val телефон = Отметки().apply { своя("redmi", 100) }
        val пк = Отметки().apply { своя("moi", 50) }
        sync(cell, телефон).push()

        val ответ = sync(cell, пк).push()

        assertIs<ReadsStep.Pushed>(ответ)
        assertEquals(mapOf("redmi" to 100L), ответ.advanced, "чужое сдвинуло у ПК redmi")
        assertEquals(mapOf("redmi" to 100L, "moi" to 50L), Открыто.open(ByteArray(0), cell.blob!!.bytes).marks)
    }
}
