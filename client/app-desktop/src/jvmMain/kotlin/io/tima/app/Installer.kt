package io.tima.app

import io.tima.feature.shell.InstallOutcome
import io.tima.feature.shell.UpdateInstaller
import io.tima.feature.shell.UpdateOffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/**
 * Установка обновления на ПК (ПЛАН-ОБНОВЛЕНИЯ.md, О3).
 *
 * Три шага и ни одного лишнего: скачать, сверить, запустить `msiexec`. Всё платформенное
 * — здесь; оболочка знает только порт [UpdateInstaller].
 *
 * **Почему проверяется хэш, а не подпись.** Сертификата подписи кода у нас нет — заказчик
 * решил 2026-09-06 не покупать его и мириться с предупреждением Windows. Поэтому
 * единственная проверка скачанного — `sha256`, объявленный сервером по TLS. Она слабее
 * подписи и названа слабее: подменивший файл на сервере подменит и объявление. От чужого
 * в дороге она защищает, от захваченного сервера — нет.
 *
 * **Ktor здесь не используется намеренно.** Модуль `app-desktop` сетевого слоя не знает и
 * не должен: скачивание файла — это платформенная работа, и `HttpURLConnection` из JDK
 * делает её без единой новой зависимости.
 */
class DesktopInstaller : UpdateInstaller {

    override suspend fun install(offer: UpdateOffer, onProgress: (Int) -> Unit): InstallOutcome =
        withContext(Dispatchers.IO) {
            if (offer.sha256.isBlank()) return@withContext InstallOutcome.NoHash

            // Имя файла содержит номер сборки. Урок v1 (инвентарь поведения, пункт 11):
            // при общем имени однажды ставится вчерашний файл, оставшийся от прошлой
            // попытки, и понять это по экрану невозможно.
            val target = File(System.getProperty("java.io.tmpdir"), "TIMA-${offer.versionCode}.msi")

            val downloaded = try {
                download(offer.url, target, onProgress)
            } catch (e: Throwable) {
                return@withContext InstallOutcome.NoConnection
            }
            if (!downloaded) return@withContext InstallOutcome.NoConnection

            if (!hexDigest(target).equals(offer.sha256, ignoreCase = true)) {
                // Битый или подменённый файл не остаётся лежать: следующая попытка
                // должна начинаться с чистого места, а не с сомнительного файла.
                target.delete()
                return@withContext InstallOutcome.BadPackage
            }

            try {
                // /qb — «тихий с полосой»: человеку видно, что идёт установка, но
                // вопросов ему не задают. Их всё равно некому задавать: приложение,
                // из которого он нажал «Поставить», к этому моменту закрывается.
                ProcessBuilder("msiexec", "/i", target.absolutePath, "/qb")
                    .redirectErrorStream(true)
                    .start()
            } catch (e: Throwable) {
                return@withContext InstallOutcome.Refused("Windows не запустил установщик")
            }
            InstallOutcome.Started
        }

    /** Качает в файл, отдавая проценты. `false` — сервер ответил не тем, чего мы ждём. */
    private fun download(url: String, target: File, onProgress: (Int) -> Unit): Boolean {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT
        connection.instanceFollowRedirects = true
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return false
            val total = connection.contentLengthLong
            var read = 0L
            var lastPercent = -1
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        read += count
                        // Проценты отдаются только когда меняются: сотня мегабайт это
                        // тысячи чтений, и обновлять экран на каждое незачем.
                        if (total > 0) {
                            val percent = (read * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            return true
        } finally {
            connection.disconnect()
        }
    }

    private fun hexDigest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte ->
            val value = byte.toInt() and 0xFF
            HEX[value ushr 4].toString() + HEX[value and 0x0F]
        }
    }

    private companion object {
        const val BUFFER = 64 * 1024
        const val CONNECT_TIMEOUT = 15_000
        // Полторы минуты на кусок, а не на файл: в v1 простой поток на 31 МБ подвисал,
        // и лечилось это не терпением, а тем, что обрыв становится виден.
        const val READ_TIMEOUT = 90_000
        const val HEX = "0123456789abcdef"
    }
}
