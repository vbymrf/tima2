package io.tima.core.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Черновик (Решения §9): у переписки свой, на диске закрыт, пустой — убран. */
class SqlDraftsTest {
    private val db = testDatabase()
    private val drafts = SqlDrafts(db, testCipher(), nowMs = { 1 })

    @Test
    fun черновик_по_переписке_и_закрыт() {
        drafts.save("a", "секретный черновик")
        drafts.save("b", "другой")
        assertEquals("секретный черновик", drafts.load("a"))
        assertEquals("другой", drafts.load("b"))
        val raw = db.draftsQueries.draftOf("a").executeAsOne()
        assertFalse(raw.decodeToString().contains("секретный"), "черновик на диске открытым")
        drafts.save("a", "  ")
        assertNull(drafts.load("a"))
    }
}
