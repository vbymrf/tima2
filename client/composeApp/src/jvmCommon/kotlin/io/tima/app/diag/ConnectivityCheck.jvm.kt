package io.tima.app.diag

import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.tima.app.api.createHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

private const val STEP_TIMEOUT_MS = 20_000

actual suspend fun checkConnectivity(baseUrl: String, report: (String) -> Unit) = withContext(Dispatchers.IO) {
    val base = baseUrl.trimEnd('/')
    val uri = runCatching { URI(base) }.getOrNull()
    val host = uri?.host
    if (host.isNullOrEmpty()) {
        report("Адрес сервера «$baseUrl» не разбирается — проверьте поле «Сервер»")
        return@withContext
    }
    val secure = uri.scheme == "https"
    val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
    report("Проверяю $host:$port")

    // 1. Имя. Отдельно от всего: «Unable to resolve host» и «Read timed out» — это
    // совершенно разные беды, а в общем сообщении об ошибке они сливаются.
    val addresses = timed("1. Имя $host", report) { InetAddress.getAllByName(host).toList() }
        ?.also { report("   адреса: " + it.joinToString { a -> a.hostAddress }) }
        ?: run {
            report("→ ВЫВОД: не работает разбор имени. Это DNS оператора, до нашего сервера дело не дошло.")
            return@withContext
        }

    // 2. TCP до каждого адреса: у имени их может быть несколько, и недостижимым
    // бывает один. «Сервер недоступен» при живом втором адресе — ложный вывод.
    var reachable = false
    for (a in addresses) {
        val ok = timed("2. TCP ${a.hostAddress}:$port", report) {
            Socket().use { it.connect(InetSocketAddress(a, port), STEP_TIMEOUT_MS) }
        } != null
        reachable = reachable || ok
    }
    if (!reachable) {
        report("→ ВЫВОД: имя разбирается, но соединение не устанавливается. Адрес недостижим из этой сети.")
        return@withContext
    }

    if (secure) {
        // 3. TLS. Отдельным шагом, потому что рукопожатие ломается там, где обычный
        // TCP проходит: посредник в середине или урезанный размер пакета.
        val tls = timed("3. TLS", report) {
            (SSLSocketFactory.getDefault().createSocket(host, port) as SSLSocket).use { s ->
                s.soTimeout = STEP_TIMEOUT_MS
                s.startHandshake()
                "${s.session.protocol} / ${s.session.cipherSuite}"
            }
        }
        if (tls == null) {
            report("→ ВЫВОД: TCP проходит, а TLS — нет. Похоже на посредника в середине.")
            return@withContext
        }
        report("   $tls")
    }

    val client = createHttpClient(Json { ignoreUnknownKeys = true })
    try {
        // 4. Собственно наш сервер.
        val code = timed("4. HTTP /healthz", report) { client.get("$base/healthz").status.value }
        if (code == null) {
            report("→ ВЫВОД: TLS есть, ответа нет. Дело в сервере или в том, что запрос не доходит целиком.")
            return@withContext
        }
        report("   код $code")

        // 5. WebSocket. Переход на другой протокол посредники ломают охотнее всего,
        // а без него не приходят ни сообщения, ни звонки — при живом HTTP.
        val wsUrl = base.replaceFirst("http", "ws") + "/ws"
        val ws = withTimeoutOrNull(STEP_TIMEOUT_MS.toLong()) {
            runCatching { client.webSocket(wsUrl) { } }.exceptionOrNull()
        }
        when {
            ws == null -> report("5. WebSocket — не ответил за ${STEP_TIMEOUT_MS / 1000} с")
            else -> report("5. WebSocket — ок")
        }
        report("→ ВЫВОД: связь с сервером есть на всех шагах.")
    } finally {
        client.close()
    }
}

/** Выполняет шаг, засекает время и докладывает исход. null — шаг не прошёл. */
private inline fun <T> timed(name: String, report: (String) -> Unit, block: () -> T): T? {
    val t0 = System.currentTimeMillis()
    return try {
        val r = block()
        report("$name — ок (${System.currentTimeMillis() - t0} мс)")
        r
    } catch (e: Throwable) {
        report("$name — НЕ ПРОШЁЛ за ${System.currentTimeMillis() - t0} мс: ${e::class.simpleName}: ${e.message}")
        null
    }
}
