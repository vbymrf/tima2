package io.tima.core.network

import io.kodium.Kodium
import io.kodium.KodiumPrivateKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Подписанный конфиг маршрутов (К3.3). Подпись здесь **настоящая Ed25519**, а не
 * заглушка: смысл проверки в том, что подделку отвергает криптография, и проверять это
 * фальшивой «подписью» значило бы проверять свой же тест.
 *
 * Ради этого `core-network` в тестах — и только в тестах — видит `core-encryption`.
 * Боевой код криптографии не знает: проверка приходит в [SignatureCheck], потому что
 * сетевой слой не должен тянуть за собой крипто-библиотеку.
 */
class SignedRouteConfigTest {

    private val выпуск = Kodium.generateKeyPair()
    private val ключВыпуска = выпуск.getPublicKey().signingKey
    private val проверка = SignatureCheck { pub, msg, sig -> Kodium.verifyDetached(pub, msg, sig) }

    private val доверие = RouteConfigTrust(trustedKey = ключВыпуска, check = проверка)

    private fun документ(version: Int = 1, хосты: List<String> = listOf("пацак.рф")) = """
        {"version":$version,"issued_at_ms":1771200000000,"candidates":[
        ${хосты.joinToString(",") { """{"host":"$it","api_subdomain":"api","secure":true}""" }}
        ]}
    """.trimIndent()

    /** Конверт, подписанный ключом выпуска. */
    private fun подписанный(payload: String, ключ: KodiumPrivateKey = выпуск): String {
        val байты = payload.encodeToByteArray()
        val подпись = Kodium.signDetached(ключ, байты).getOrThrow()
        return """{"payload":"${encodeBase64(байты)}","signature":"${encodeBase64(подпись)}"}"""
    }

    // ── что принимается ──────────────────────────────────────────────────────

    @Test
    fun подписанный_ключом_выпуска_принимается_и_собирается_в_маршрут() {
        val принят = доверие.accept(подписанный(документ())).getOrThrow()

        assertEquals(1, принят.version)
        assertEquals(1, принят.candidates.size)
        // Кириллическое имя доезжает до маршрута и переводится там же, где всегда.
        assertEquals("api.xn--80aa4ar0b.xn--p1ai", ServerRoute.from(принят.candidates[0]).serverHost)
    }

    @Test
    fun незнакомые_поля_не_ломают_приём() {
        // То же правило, что у сервера («API только расширяется»), но с другой стороны:
        // старый клиент обязан принять документ, выпущенный после него. Иначе выпуск
        // нового поля разом выключает обновление маршрутов у всех старых устройств.
        val сНовымПолем = """
            {"version":2,"issued_at_ms":1,"ttl_hours":48,
            "candidates":[{"host":"пацак.рф","weight":10}]}
        """.trimIndent()
        assertEquals(2, доверие.accept(подписанный(сНовымПолем)).getOrThrow().version)
    }

    // ── что отвергается ──────────────────────────────────────────────────────

    @Test
    fun подделанный_документ_отвергается() {
        // Главная проверка этапа. Подпись настоящая, но чужого ключа — так выглядит
        // подмена канала доставки конфига.
        val чужой = Kodium.generateKeyPair()
        val исход = доверие.accept(подписанный(документ(), ключ = чужой))

        val беда = исход.exceptionOrNull()
        assertIs<RouteConfigRejected>(беда)
        assertEquals(RouteConfigRejection.BAD_SIGNATURE, беда.reason)
    }

    @Test
    fun подмена_нагрузки_под_настоящей_подписью_отвергается() {
        // Подпись от честного документа, нагрузка — другая. Именно это ловит проверка
        // по сырым байтам: подписано и применено обязано быть одним и тем же.
        val честный = подписанный(документ(хосты = listOf("пацак.рф")))
        val подпись = честный.substringAfter(""""signature":"""").substringBefore("\"")
        val подменённый =
            """{"payload":"${encodeBase64(документ(хосты = listOf("злой.example")).encodeToByteArray())}","signature":"$подпись"}"""

        assertEquals(
            RouteConfigRejection.BAD_SIGNATURE,
            (доверие.accept(подменённый).exceptionOrNull() as RouteConfigRejected).reason,
        )
    }

    @Test
    fun откат_к_старому_подписанному_документу_отвергается() {
        // У старого документа подпись настоящая. Отличить его от свежего можно только
        // номером версии — иначе повтор старого конфига уводит устройство на хост,
        // выведенный из работы (или уже чужой).
        val исход = доверие.accept(подписанный(документ(version = 3)), currentVersion = 5)
        assertEquals(
            RouteConfigRejection.NOT_NEWER,
            (исход.exceptionOrNull() as RouteConfigRejected).reason,
        )
    }

    @Test
    fun та_же_версия_тоже_не_принимается() {
        val исход = доверие.accept(подписанный(документ(version = 5)), currentVersion = 5)
        assertEquals(
            RouteConfigRejection.NOT_NEWER,
            (исход.exceptionOrNull() as RouteConfigRejected).reason,
        )
    }

    @Test
    fun пустой_список_кандидатов_это_выключатель_связи_а_не_конфигурация() {
        val пустой = """{"version":9,"candidates":[]}"""
        assertEquals(
            RouteConfigRejection.NO_CANDIDATES,
            (доверие.accept(подписанный(пустой)).exceptionOrNull() as RouteConfigRejected).reason,
        )
    }

    @Test
    fun негодный_кандидат_отвергает_документ_целиком() {
        // Подписанный, свежий и негодный документ хуже отсутствующего: он вытеснил бы
        // рабочий список, и устройство осталось бы без связи с настоящей подписью в
        // руках.
        val сURL = """{"version":9,"candidates":[{"host":"https://пацак.рф/api"}]}"""
        assertEquals(
            RouteConfigRejection.MALFORMED,
            (доверие.accept(подписанный(сURL)).exceptionOrNull() as RouteConfigRejected).reason,
        )
    }

    @Test
    fun мусор_вместо_конверта_не_роняет_приложение() {
        for (мусор in listOf("", "не json", "{}", """{"payload":"!!!","signature":"!!!"}""")) {
            val беда = доверие.accept(мусор).exceptionOrNull()
            assertIs<RouteConfigRejected>(беда, "вход «$мусор» обязан быть отказом, а не исключением")
            assertEquals(RouteConfigRejection.MALFORMED, беда.reason)
        }
    }

    // ── отказ закрытый ───────────────────────────────────────────────────────

    @Test
    fun без_зашитого_ключа_не_принимается_ничего() {
        // Пока ключ выпуска не выдан, обновление маршрутов выключено целиком, а не
        // «принимается на веру». Проверка, которая ничего не проверяет, хуже
        // отсутствующей: она выглядит защитой.
        val безКлюча = RouteConfigTrust(trustedKey = null, check = проверка)
        val исход = безКлюча.accept(подписанный(документ()))
        assertEquals(
            RouteConfigRejection.NO_TRUSTED_KEY,
            (исход.exceptionOrNull() as RouteConfigRejected).reason,
        )
    }

    @Test
    fun ключ_выпуска_в_сборке_пока_не_выдан() {
        // Состояние, а не заглушка: подставленные «пока какие-нибудь» байты означали бы
        // проверку подписи неизвестно чьим ключом.
        assertTrue(BakedRouteKey.publicKey == null, "ключ появится сборочной константой при выпуске")
    }
}
