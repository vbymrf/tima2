package io.tima.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Идентификатор личной переписки. Проверка **известным ответом**: значение перенесено
 * из v1 и обязано совпадать байт в байт, иначе клиенты v1 и v2 окажутся в разных
 * переписках при полностью работающей доставке.
 *
 * Упавший известный ответ означает, что неверен наш код, а не тест.
 */
class PersonalChatIdTest {

    private val А = "0f8fad5b-d9cb-469f-a165-70867728950e"
    private val Б = "7c9e6679-7425-40de-944b-e07fc1f90ae7"

    @Test
    fun известный_ответ_совпадает_с_v1() {
        // sha256("tima.personal.chat|<меньший>|<больший>") с выставленными битами UUID.
        assertEquals("ec3863e2-d7fc-4806-b1a5-19b0bf1cde1c", PersonalChatId.of(А, Б))
    }

    @Test
    fun порядок_сторон_не_важен() {
        // Ради этого и сортировка: обе стороны считают одинаково, ни о чём не
        // договариваясь. Без неё у каждого был бы свой chat_id, и сервер честно развёл
        // бы их по разным перепискам.
        assertEquals(PersonalChatId.of(А, Б), PersonalChatId.of(Б, А))
    }

    @Test
    fun переписка_с_собой_отличается_от_переписки_с_другим() {
        // «Заметки» — это переписка с собой, и она обязана быть отдельной.
        assertEquals("af2cf156-ee4d-49c0-8254-648e41ae977d", PersonalChatId.of(А, А))
        assertNotEquals(PersonalChatId.of(А, А), PersonalChatId.of(А, Б))
    }

    @Test
    fun это_валидный_uuid_версии_4() {
        // Он ложится в столбцы типа uuid на сервере: строка, похожая на UUID, но с чужими
        // битами версии, будет отвергнута базой, а не нами.
        val id = PersonalChatId.of(А, Б)

        assertEquals(36, id.length)
        assertEquals(listOf(8, 4, 4, 4, 12), id.split("-").map { it.length })
        assertEquals('4', id[14], "версия 4")
        assertTrue(id[19] in "89ab", "вариант RFC 4122, а получено ${id[19]}")
        assertTrue(id.all { it == '-' || it in "0123456789abcdef" }, "только строчные шестнадцатеричные: $id")
    }

    @Test
    fun разные_пары_дают_разные_переписки() {
        val третий = "11111111-2222-4333-8444-555555555555"
        val пары = setOf(
            PersonalChatId.of(А, Б),
            PersonalChatId.of(А, третий),
            PersonalChatId.of(Б, третий),
        )
        assertEquals(3, пары.size)
    }

    @Test
    fun пустой_идентификатор_отвергается() {
        assertFailsWith<IllegalArgumentException> { PersonalChatId.of("", Б) }
        assertFailsWith<IllegalArgumentException> { PersonalChatId.of(А, "  ") }
    }
}
