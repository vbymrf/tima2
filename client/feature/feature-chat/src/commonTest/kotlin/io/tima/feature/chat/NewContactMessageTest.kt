package io.tima.feature.chat

import io.tima.core.words.RussianWords
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **Сообщение «в TIMa его нет» называет проверенный номер.**
 *
 * Заведено 2026-09-17 по случаю со стенда. Контакт был заведён с лишним нулём —
 * `+799900000101` вместо `+79990000101`, — и экран честно сказал «в TIMa его нет». Правду
 * сказал: такого номера в TIMa действительно не существует. Человек прочитал это как «у
 * него не стоит приложение» и стал искать беду в определении, которое работало исправно.
 *
 * Номер на экране набирают двумя полями, а серверу уходит собранный — это разные строки, и
 * расходятся они молча. Поэтому сообщение обязано показывать то, что проверялось.
 */
class NewContactMessageTest {

    private val words = RussianWords.chat

    @Test
    fun не_найденный_номер_назван_в_сообщении() {
        val state = NewContactState(
            countryCode = "7",
            phone = "99900000101",
            normalized = "+799900000101",
            checked = false,
        )
        val said = state.about(words)
        assertTrue(said != null, "об исходе сверки обязано быть сказано")
        assertTrue(
            said!!.contains("+799900000101"),
            "сообщение обязано называть проверенный номер, иначе опечатку не отличить " +
                "от отсутствия приложения: «$said»",
        )
    }

    @Test
    fun найденный_номер_не_требует_оговорок() {
        val state = NewContactState(normalized = "+79990000101", checked = true)
        assertTrue(
            state.about(words) == words.foundInTima,
            "у найденного исход один и оговорок не требует",
        )
    }

    @Test
    fun до_сверки_ничего_не_обещается() {
        val state = NewContactState(normalized = "+79990000101", checked = null)
        assertTrue(
            state.about(words) == null,
            "пока исход неизвестен, говорить нечего: любая фраза здесь была бы догадкой",
        )
    }
}
