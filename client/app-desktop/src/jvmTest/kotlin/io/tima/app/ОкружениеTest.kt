package io.tima.app

import io.tima.core.secrets.SecretAlias
import io.tima.core.secrets.SecretVault
import io.tima.domain.chat.SendMessageResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Сборка приложения проверяется **без окна**.
 *
 * Первый живой запуск ломается не в отрисовке: он ломается на секрете, которого ещё нет,
 * на базе, которую нельзя открыть второй раз, и на ключе покоя, привязанном к устройству.
 * Всё это обычный код, и проверить его можно здесь, а не глазами на чужой машине.
 *
 * Хранилище секретов подменено — не чтобы обойти DPAPI, а чтобы не писать в хранилище
 * живой машины: сам DPAPI проверен там, где ему место, по файлу.
 */
class ОкружениеTest {

    private val каталог: File = File.createTempFile("tima-app", "").let {
        it.delete()
        it.mkdirs()
        it
    }

    private val хранилище = ПамятноеХранилище()

    @AfterTest
    fun убрать() {
        каталог.deleteRecursively()
    }

    /**
     * Приложение открывается дважды, и написанное остаётся.
     *
     * Второй запуск — главная проверка: до боевого драйвера ПК схема создавалась
     * безусловно, и второе открытие того же файла упало бы на «table messages already
     * exists». Такое находит человек, у которого уже есть переписка.
     */
    @Test
    fun второй_запуск_видит_написанное_в_первый() = runTest {
        val первое = Окружение.открыть(каталог, хранилище)
        assertTrue(первое.отправка.send("chat-1", "привет") is SendMessageResult.Queued)

        val второе = Окружение.открыть(каталог, хранилище)
        val список = второе.переписки.list().first()

        assertEquals(1, список.size)
        assertEquals("привет", список.single().preview, "написанное обязано читаться после перезапуска")
    }

    /**
     * Сообщение честно висит в очереди: сети нет, и очередь это не скрывает.
     *
     * «Ждёт» — правда о состоянии, а не недоделка. Показать «отправлено» без сервера
     * значило бы соврать человеку в единственном месте, где он на нас полагается.
     */
    @Test
    fun без_сети_сообщение_ждёт_в_очереди_а_не_исчезает() = runTest {
        val окружение = Окружение.открыть(каталог, хранилище)
        окружение.отправка.send("chat-1", "привет")

        val строка = окружение.переписка.page("chat-1").first().single()

        assertEquals(io.tima.domain.chat.MessageDisplay.PENDING, строка.display)
        assertEquals("привет", строка.text)
        assertEquals(1, окружение.очередь.pending().size)
    }

    /**
     * **База привязана к секрету устройства.**
     *
     * Чужой секрет — чужой ключ покоя, и переписка не читается. Это и есть смысл
     * шифрования покоя: файл базы без секрета из хранилища платформы бесполезен.
     * Проверка заодно доказывает, что ключ действительно выводится из секрета, а не
     * взялся откуда-нибудь ещё.
     */
    @Test
    fun с_чужим_секретом_переписка_не_читается() = runTest {
        Окружение.открыть(каталог, хранилище).отправка.send("chat-1", "привет")

        val чужое = Окружение.открыть(каталог, ПамятноеХранилище())
        val список = чужое.переписки.list().first()

        assertEquals(1, список.size, "строка остаётся: метаданные вариант A не закрывает")
        assertNull(список.single().preview, "а содержимое чужим ключом не открывается")
    }

    /** Хранилище секретов в памяти: проверке не нужен DPAPI, ей нужен секрет. */
    private class ПамятноеХранилище : SecretVault {
        private val значения = mutableMapOf<String, ByteArray>()
        override fun put(alias: SecretAlias, secret: ByteArray) {
            значения[alias.value] = secret
        }

        override fun get(alias: SecretAlias): ByteArray? = значения[alias.value]

        override fun remove(alias: SecretAlias): Boolean = значения.remove(alias.value) != null
    }
}
