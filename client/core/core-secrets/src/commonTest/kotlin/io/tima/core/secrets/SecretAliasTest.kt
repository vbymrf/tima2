package io.tima.core.secrets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Имя секрета. Проверка формальная только на вид: на ПК секреты лежат файлами, и имя
 * решает, куда именно попадёт запись.
 */
class SecretAliasTest {

    @Test
    fun выход_за_свой_каталог_невозможен() {
        // Главная причина, по которой проверка вообще есть.
        for (плохое in listOf("../../ключ", "..", "a/b", "a\\b", "a:b", "с/../д")) {
            assertFailsWith<IllegalArgumentException>("имя «$плохое» обязано быть отвергнуто") {
                SecretAlias(плохое)
            }
        }
    }

    @Test
    fun пустое_и_слишком_длинное_отвергаются() {
        assertFailsWith<IllegalArgumentException> { SecretAlias("") }
        assertFailsWith<IllegalArgumentException> { SecretAlias("   ") }
        assertFailsWith<IllegalArgumentException> { SecretAlias("a".repeat(65)) }
    }

    @Test
    fun скрытые_файлы_и_чужие_алфавиты_отвергаются() {
        // Точка в начале — скрытый файл: имя, которое не видно в каталоге, найти труднее
        // всего, а секрет обязан быть на виду у своего же приложения.
        assertFailsWith<IllegalArgumentException> { SecretAlias(".секрет") }
        // Кириллица, заглавные, пробелы: на разных файловых системах ведут себя по-разному.
        assertFailsWith<IllegalArgumentException> { SecretAlias("ключ") }
        assertFailsWith<IllegalArgumentException> { SecretAlias("Device-Secret") }
        assertFailsWith<IllegalArgumentException> { SecretAlias("device secret") }
    }

    @Test
    fun обычное_имя_принимается_и_остаётся_собой() {
        assertEquals("device-secret.v1", SecretAlias("device-secret.v1").value)
        assertEquals("device-secret.v1", Secrets.DEVICE_SECRET.toString())
    }
}
