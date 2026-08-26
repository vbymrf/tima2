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

    private val issue = Kodium.generateKeyPair()
    private val issueKey = issue.getPublicKey().signingKey
    private val check = SignatureCheck { pub, msg, sig -> Kodium.verifyDetached(pub, msg, sig) }

    private val trust = RouteConfigTrust(trustedKey = issueKey, check = check)

    private fun document(version: Int = 1, hosts: List<String> = listOf("пацак.рф")) = """
        {"version":$version,"issued_at_ms":1771200000000,"candidates":[
        ${hosts.joinToString(",") { """{"host":"$it","api_subdomain":"api","secure":true}""" }}
        ]}
    """.trimIndent()

    /** Конверт, подписанный ключом выпуска. */
    private fun signed(payload: String, key: KodiumPrivateKey = issue): String {
        val bytes = payload.encodeToByteArray()
        val caption = Kodium.signDetached(key, bytes).getOrThrow()
        return """{"payload":"${encodeBase64(bytes)}","signature":"${encodeBase64(caption)}"}"""
    }

    // ── что принимается ──────────────────────────────────────────────────────

    @Test
    fun подписанный_ключом_выпуска_принимается_и_собирается_в_маршрут() {
        val accepted = trust.accept(signed(document())).getOrThrow()

        assertEquals(1, accepted.version)
        assertEquals(1, accepted.candidates.size)
        // Кириллическое имя доезжает до маршрута и переводится там же, где всегда.
        assertEquals("api.xn--80aa4ar0b.xn--p1ai", ServerRoute.from(accepted.candidates[0]).serverHost)
    }

    @Test
    fun незнакомые_поля_не_ломают_приём() {
        // То же правило, что у сервера («API только расширяется»), но с другой стороны:
        // старый клиент обязан принять документ, выпущенный после него. Иначе выпуск
        // нового поля разом выключает обновление маршрутов у всех старых устройств.
        val withNewField = """
            {"version":2,"issued_at_ms":1,"ttl_hours":48,
            "candidates":[{"host":"пацак.рф","weight":10}]}
        """.trimIndent()
        assertEquals(2, trust.accept(signed(withNewField)).getOrThrow().version)
    }

    // ── что отвергается ──────────────────────────────────────────────────────

    @Test
    fun подделанный_документ_отвергается() {
        // Главная проверка этапа. Подпись настоящая, но чужого ключа — так выглядит
        // подмена канала доставки конфига.
        val foreign = Kodium.generateKeyPair()
        val outcome = trust.accept(signed(document(), key = foreign))

        val trouble = outcome.exceptionOrNull()
        assertIs<RouteConfigRejected>(trouble)
        assertEquals(RouteConfigRejection.BAD_SIGNATURE, trouble.reason)
    }

    @Test
    fun подмена_нагрузки_под_настоящей_подписью_отвергается() {
        // Подпись от честного документа, нагрузка — другая. Именно это ловит проверка
        // по сырым байтам: подписано и применено обязано быть одним и тем же.
        val honest = signed(document(hosts = listOf("пацак.рф")))
        val caption = honest.substringAfter(""""signature":"""").substringBefore("\"")
        val substituted =
            """{"payload":"${encodeBase64(document(hosts = listOf("злой.example")).encodeToByteArray())}","signature":"$caption"}"""

        assertEquals(
            RouteConfigRejection.BAD_SIGNATURE,
            (trust.accept(substituted).exceptionOrNull() as RouteConfigRejected).reason,
        )
    }

    @Test
    fun откат_к_старому_подписанному_документу_отвергается() {
        // У старого документа подпись настоящая. Отличить его от свежего можно только
        // номером версии — иначе повтор старого конфига уводит устройство на хост,
        // выведенный из работы (или уже чужой).
        val outcome = trust.accept(signed(document(version = 3)), currentVersion = 5)
        assertEquals(
            RouteConfigRejection.NOT_NEWER,
            (outcome.exceptionOrNull() as RouteConfigRejected).reason,
        )
    }

    @Test
    fun та_же_версия_тоже_не_принимается() {
        val outcome = trust.accept(signed(document(version = 5)), currentVersion = 5)
        assertEquals(
            RouteConfigRejection.NOT_NEWER,
            (outcome.exceptionOrNull() as RouteConfigRejected).reason,
        )
    }

    @Test
    fun пустой_список_кандидатов_это_выключатель_связи_а_не_конфигурация() {
        val empty = """{"version":9,"candidates":[]}"""
        assertEquals(
            RouteConfigRejection.NO_CANDIDATES,
            (trust.accept(signed(empty)).exceptionOrNull() as RouteConfigRejected).reason,
        )
    }

    @Test
    fun негодный_кандидат_отвергает_документ_целиком() {
        // Подписанный, свежий и негодный документ хуже отсутствующего: он вытеснил бы
        // рабочий список, и устройство осталось бы без связи с настоящей подписью в
        // руках.
        val withURL = """{"version":9,"candidates":[{"host":"https://пацак.рф/api"}]}"""
        assertEquals(
            RouteConfigRejection.MALFORMED,
            (trust.accept(signed(withURL)).exceptionOrNull() as RouteConfigRejected).reason,
        )
    }

    @Test
    fun мусор_вместо_конверта_не_роняет_приложение() {
        for (garbage in listOf("", "не json", "{}", """{"payload":"!!!","signature":"!!!"}""")) {
            val trouble = trust.accept(garbage).exceptionOrNull()
            assertIs<RouteConfigRejected>(trouble, "вход «$garbage» обязан быть отказом, а не исключением")
            assertEquals(RouteConfigRejection.MALFORMED, trouble.reason)
        }
    }

    // ── отказ закрытый ───────────────────────────────────────────────────────

    @Test
    fun без_зашитого_ключа_не_принимается_ничего() {
        // Пока ключ выпуска не выдан, обновление маршрутов выключено целиком, а не
        // «принимается на веру». Проверка, которая ничего не проверяет, хуже
        // отсутствующей: она выглядит защитой.
        val keyWithout = RouteConfigTrust(trustedKey = null, check = check)
        val outcome = keyWithout.accept(signed(document()))
        assertEquals(
            RouteConfigRejection.NO_TRUSTED_KEY,
            (outcome.exceptionOrNull() as RouteConfigRejected).reason,
        )
    }

    @Test
    fun ключ_выпуска_в_сборке_пока_не_выдан() {
        // Состояние, а не заглушка: подставленные «пока какие-нибудь» байты означали бы
        // проверку подписи неизвестно чьим ключом.
        assertTrue(BakedRouteKey.publicKey == null, "ключ появится сборочной константой при выпуске")
    }
}
