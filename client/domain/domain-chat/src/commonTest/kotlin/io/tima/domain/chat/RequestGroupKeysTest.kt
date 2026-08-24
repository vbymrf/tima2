package io.tima.domain.chat

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Запрос недостающих ключей: три исхода, которые нельзя показать одинаково.
 *
 * «Просьба ушла», «просить некого» и «недостающих нет» означают для человека разное:
 * в первом случае надо ждать, во втором ждать бесполезно, в третьем дело вообще не в
 * ключе. Слепить их в «ошибку» — значит заставить человека жать кнопку снова.
 */
class RequestGroupKeysTest {

    private val группа = "gggggggg-0000-0000-0000-000000000001"

    @Test
    fun просьба_ушла_помощникам() = runTest {
        val шаг = RequestGroupKeys { _, _ -> RecoveryStep.Requested(versions = 3, helpers = 2) }
            .запросить(группа)
        assertEquals(RequestKeysStep.Asked(2), шаг)
    }

    @Test
    fun просить_некого_это_отдельный_исход() = runTest {
        // Ни у кого из участников этих версий нет. Ждать бесполезно, и «попробуйте
        // позже» здесь было бы неправдой.
        val шаг = RequestGroupKeys { _, _ -> RecoveryStep.Requested(versions = 3, helpers = 0) }
            .запросить(группа)
        assertIs<RequestKeysStep.NoHelpers>(шаг)
    }

    @Test
    fun недостающих_версий_нет() = runTest {
        // Значит, сообщение не читается по другой причине, и просить ключ бессмысленно.
        val шаг = RequestGroupKeys { _, _ -> RecoveryStep.Requested(versions = 0, helpers = 5) }
            .запросить(группа)
        assertIs<RequestKeysStep.NothingMissing>(шаг)
    }

    @Test
    fun нужна_секретная_фраза_а_не_ошибка() = runTest {
        // Заслон против угона номера: укравший SIM имеет доступ устройства, но без фразы
        // историю не получит. Показать это ошибкой значило бы спрятать единственное
        // действие, которое человеку доступно.
        val шаг = RequestGroupKeys { _, _ -> RecoveryStep.NeedsSecretPhrase }.запросить(группа)
        assertIs<RequestKeysStep.NeedsSecretPhrase>(шаг)
    }

    @Test
    fun обрыв_и_отказ_проходят_наружу() = runTest {
        assertIs<RequestKeysStep.Offline>(
            RequestGroupKeys { _, _ -> RecoveryStep.Offline(2_000) }.запросить(группа),
        )
        val отказ = RequestGroupKeys { _, _ -> RecoveryStep.Refused("internal") }.запросить(группа)
        assertEquals("internal", (отказ as RequestKeysStep.Refused).reason)
    }
}
