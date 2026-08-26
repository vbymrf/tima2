package io.tima.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Обновляемый список маршрутов, подписанный ключом выпуска — К3.3.
 *
 * **Зачем это вообще.** Приложение выпускается с зашитым списком кандидатов, но
 * блокировки меняются быстрее, чем проходит обновление в магазине приложений. Значит
 * список обязан обновляться на живом устройстве. И ровно поэтому он обязан быть
 * подписан: канал доставки такого списка — самое привлекательное место для подмены,
 * какое есть в приложении. Подделав его, атакующий уводит весь трафик на свой хост.
 *
 * **Проверка идёт по сырым байтам, а не по разобранному документу.** Конверт несёт
 * полезную нагрузку строкой (base64), подпись покрывает именно эти байты, и разбор
 * идёт по ним же. Иначе появляется классическая щель: проверили одну форму JSON, а
 * применили другую — после пересборки порядок полей и пробелы уже не те, и «что
 * подписано» перестаёт совпадать с «что применено».
 *
 * ```
 * конверт:  {"payload":"<base64 байт документа>","signature":"<base64 64 байта>"}
 * документ: {"version":7,"issued_at_ms":…,"candidates":[{…}]}
 * ```
 */
@Serializable
data class SignedRouteEnvelope(
    /** Байты документа в base64 — **они и подписаны**. */
    val payload: String,
    /** Ed25519 detached, 64 байта, base64. */
    val signature: String,
)

/**
 * Документ маршрутов. Разбирается **с терпимостью к незнакомым полям**: то же правило,
 * что и у сервера («API только расширяется»), но с другой стороны — старый клиент
 * обязан принять документ, выпущенный после него.
 */
@Serializable
data class RouteConfigDocument(
    /**
     * Номер версии. Только растёт: по нему отвергается откат — подсунутый старый
     * подписанный документ, уводящий на выведенный из работы хост. Подпись у такого
     * настоящая, и без номера версии отличить его нечем.
     */
    val version: Int,
    @SerialName("issued_at_ms") val issuedAtMs: Long = 0,
    /**
     * Кандидаты в порядке предпочтения. Пустой список — отказ: подписанный документ,
     * оставляющий устройство без адресов, это выключатель связи, а не конфигурация.
     */
    val candidates: List<RouteConfig> = emptyList(),
)

/** Почему документ не принят. Причина нужна для диагностики, не для показа человеку. */
enum class RouteConfigRejection {
    /** Ключ выпуска не зашит: проверять нечем. См. [RouteConfigTrust]. */
    NO_TRUSTED_KEY,
    MALFORMED,
    BAD_SIGNATURE,

    /** Версия не больше уже принятой: откат к старому подписанному документу. */
    NOT_NEWER,
    NO_CANDIDATES,
}

class RouteConfigRejected(val reason: RouteConfigRejection, message: String) : Exception(message)

/** Проверка подписи. Вынесена наружу, чтобы `core-network` не зависел от криптографии. */
fun interface SignatureCheck {
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean
}

/**
 * Ключ, которым проверяется конфиг маршрутов.
 *
 * `null` означает «ключ выпуска ещё не выдан», и это **не заглушка, а состояние**:
 * пока ключа нет, обновление маршрутов отвергается целиком, а приложение ходит по
 * зашитому списку. Отказ закрытый — подставить сюда какие-нибудь байты, чтобы «пока
 * работало», значило бы завести проверку, которая ничего не проверяет.
 *
 * Ключ появится сборочной константой при выпуске. Механизма выдачи такого конфига на
 * сервере тоже пока нет — сверка Д3 показала, что такой ручки не существует.
 */
object BakedRouteKey {
    val publicKey: ByteArray? = null
}

/**
 * Принимает или отвергает обновление маршрутов.
 *
 * @param trustedKey ключ выпуска; `null` — обновления не принимаются вовсе.
 */
class RouteConfigTrust(
    private val trustedKey: ByteArray? = BakedRouteKey.publicKey,
    private val check: SignatureCheck,
) {

    /**
     * @param currentVersion версия документа, по которому устройство работает сейчас;
     *   `0`, если принимался только зашитый список.
     * @return документ, если он подписан ключом выпуска и новее текущего.
     */
    fun accept(envelopeJson: String, currentVersion: Int = 0): Result<RouteConfigDocument> {
        val key = trustedKey
            ?: return refusal(RouteConfigRejection.NO_TRUSTED_KEY, "ключ выпуска не зашит")

        val envelope = runCatching { json.decodeFromString<SignedRouteEnvelope>(envelopeJson) }
            .getOrElse { return refusal(RouteConfigRejection.MALFORMED, "конверт не разобран: ${it.message}") }

        val payload = decodeBase64(envelope.payload)
            ?: return refusal(RouteConfigRejection.MALFORMED, "payload не base64")
        val signature = decodeBase64(envelope.signature)
            ?: return refusal(RouteConfigRejection.MALFORMED, "signature не base64")

        // Подпись проверяется до разбора: разбирать чужой JSON, ещё не зная, наш ли он,
        // значит давать неизвестному входу работу до проверки.
        if (!check.verify(key, payload, signature)) {
            return refusal(RouteConfigRejection.BAD_SIGNATURE, "подпись не сошлась")
        }

        val document = runCatching {
            json.decodeFromString<RouteConfigDocument>(payload.decodeToString())
        }.getOrElse { return refusal(RouteConfigRejection.MALFORMED, "документ не разобран: ${it.message}") }

        if (document.version <= currentVersion) {
            return refusal(
                RouteConfigRejection.NOT_NEWER,
                "версия ${document.version} не новее текущей $currentVersion",
            )
        }
        if (document.candidates.isEmpty()) {
            return refusal(RouteConfigRejection.NO_CANDIDATES, "список кандидатов пуст")
        }
        // Кандидат обязан быть собираемым в маршрут: подписанный, новый и негодный
        // документ хуже отсутствующего — он вытеснил бы рабочий список.
        document.candidates.forEachIndexed { i, candidate ->
            runCatching { ServerRoute.from(candidate) }.onFailure {
                return refusal(RouteConfigRejection.MALFORMED, "кандидат $i негоден: ${it.message}")
            }
        }
        return Result.success(document)
    }

    private fun refusal(reason: RouteConfigRejection, message: String): Result<RouteConfigDocument> =
        Result.failure(RouteConfigRejected(reason, message))

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
