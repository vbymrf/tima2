package io.tima.app

import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.feature.shell.InstallOutcome
import io.tima.feature.shell.UpdateInstaller
import io.tima.feature.shell.UpdateOffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Установка обновления на телефоне (ПЛАН-ОБНОВЛЕНИЯ.md, О4).
 *
 * Четыре решения ниже — не выдумка, а уроки первого приложения
 * ([инвентарь-поведения.md](../../../../../../../doc_mig/инвентарь-поведения.md), пункты
 * 11 и 12). Каждый записан там со словами «тест в v2», то есть они наши обязательства.
 *
 * 1. **`DownloadManager`, а не поток.** Простой поток на 31 МБ подвисал.
 * 2. **Имя файла содержит `versionCode`.** При общем имени `DownloadManager`, увидев
 *    существующий файл, пишет рядом «-1», а установщику уходил старый.
 * 3. **Уведомление системы — только прогресс, не кнопка.** По нажатию на него APK
 *    открывает система своим обработчиком; на Samsung им оказывался посторонний
 *    компонент, и разрешение на установку система просила для него, а не для нас.
 * 4. **Проверка до установки.** Имя пакета, номер сборки и отпечаток подписи читаются из
 *    скачанного файла и сверяются **с нашей собственной подписью**: ставим только то, что
 *    подписано тем же ключом, что и мы. Зашитая в код константа была бы хуже — её
 *    пришлось бы менять при смене ключа, и тогда обновление ломало бы себя само.
 */
class AndroidInstaller(private val context: Context) : UpdateInstaller {

    override suspend fun install(offer: UpdateOffer, onProgress: (Int) -> Unit): InstallOutcome =
        withContext(Dispatchers.IO) {
            // Разрешение спрашивается здесь, а не при запуске: объяснить его можно только
            // тогда, когда человек уже нажал «Обновить».
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !context.packageManager.canRequestPackageInstalls()
            ) {
                Journal.trouble(LogCode.PERM_DENIED, "установка пакетов не разрешена — увожу в настройки", "что" to "REQUEST_INSTALL_PACKAGES")
                openInstallSettings()
                return@withContext InstallOutcome.Refused(
                    "Android спрашивает разрешение отдельно: разрешите TIMA ставить приложения и нажмите ещё раз",
                )
            }

            val target = File(context.getExternalFilesDir(null) ?: context.filesDir, "TIMA-${offer.versionCode}.apk")
            target.delete() // остаток прошлой попытки — не файл, а ловушка

            val downloaded = try {
                download(offer.url, target, onProgress)
            } catch (e: Throwable) {
                false
            }
            if (!downloaded) {
                Journal.trouble(LogCode.UPD_NO_CONNECTION, "пакет не докачался", "версия" to offer.versionCode)
                return@withContext InstallOutcome.NoConnection
            }
            Journal.note(LogCode.UPD_DOWNLOADED, "пакет скачан, проверяю подпись", "версия" to offer.versionCode)

            when (val checked = verify(target, offer)) {
                null -> Journal.note(LogCode.UPD_VERIFIED, "подпись сошлась, отдаю системе")
                else -> {
                    // Отчёт должен объяснять, а не сообщать код: «подпись не та» и «файл
                    // побит» человек не различит, а нам важно, что ставить это нельзя.
                    Journal.trouble(LogCode.UPD_BAD_PACKAGE, "скачанное не прошло проверку — не ставлю")
                    target.delete()
                    return@withContext checked
                }
            }

            try {
                commit(target)
            } catch (e: Throwable) {
                return@withContext InstallOutcome.Refused("Android не принял пакет установки")
            }
            InstallOutcome.Started
        }

    /**
     * Качает через `DownloadManager`, отдавая проценты. `false` — не докачали.
     *
     * ── ЖДЁМ ПРОДВИЖЕНИЯ, А НЕ ЧАСОВ ────────────────────────────────────────
     *
     * До 2026-09-19 здесь стоял общий потолок в две минуты — `MAX_STEPS = 170` при шаге
     * 700 мс. Он был измерен на APK в 16 МБ и для него годился; APK со звонками весит
     * 64 МБ и в две минуты не укладывается **ни при какой скорости**, доступной телефону
     * в мобильной сети.
     *
     * Так и вышло: отчёт `MBRY` 2026-09-19, две попытки подряд, обе оборваны ровно через
     * 119 секунд. В журнале это выглядело как `UPD-NO-CONNECTION пакет не докачался` — то
     * есть обвиняло сеть в том, что сделали мы сами.
     *
     * **Потолок, посчитанный от размера, чинил бы только этот случай.** Следующий APK
     * вырастет опять, и число снова разойдётся с жизнью — молча. Поэтому спрашивается
     * другое: **идёт ли загрузка вообще.** Байты прибавляются — ждём сколько угодно;
     * перестали прибавляться на [STALL_MS] — сдаёмся. Это и есть то, что человек называет
     * «не качается», и от размера файла оно не зависит.
     *
     * Общий потолок [LIMIT_MS] остался страховкой от загрузки, которая ухитряется капать
     * по байту и потому застрявшей не считается, — а не мерой терпения.
     */
    private suspend fun download(url: String, target: File, onProgress: (Int) -> Unit): Boolean {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Обновление TIMA")
            .setDestinationUri(Uri.fromFile(target))
            // Только прогресс. VISIBILITY_VISIBLE_NOTIFY_COMPLETED дал бы уведомление,
            // по которому APK открывает чужой обработчик, — урок 3 выше.
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        val id = manager.enqueue(request)

        var spent = 0L
        var idle = 0L
        var best = -1L
        var seen = 0L
        var size = 0L
        while (spent < LIMIT_MS) {
            delay(STEP_MS)
            spent += STEP_MS
            val done = manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (!cursor.moveToFirst()) return false
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val got = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                seen = got
                size = total
                if (total > 0) onProgress((got * 100 / total).toInt())

                // Счётчик простоя сбрасывается каждым прибавившимся байтом.
                if (got > best) {
                    best = got
                    idle = 0
                } else {
                    idle += STEP_MS
                }

                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> true
                    DownloadManager.STATUS_FAILED -> {
                        // Отказ системы — не наше нетерпение, и в журнале он обязан
                        // выглядеть иначе: причина у него своя, и лежит она в `reason`.
                        note(id, manager, "система отказала", got, total)
                        manager.remove(id)
                        return false
                    }
                    else -> false
                }
            }
            if (done) return true
            if (idle >= STALL_MS) break
        }

        // Либо перестало качаться, либо упёрлись в общий потолок. Загрузку снимаем:
        // висящая в фоне, она однажды доедет и положит рядом файл, которого никто не ждал.
        note(id, manager, if (idle >= STALL_MS) "перестало качаться" else "общий потолок", seen, size)
        manager.remove(id)
        return false
    }

    /**
     * Запись о сорвавшейся загрузке — **с числами**.
     *
     * Прежняя строка говорила только «пакет не докачался», и по ней нельзя было отличить
     * «сеть пропала на первом байте» от «скачали 58 МБ из 64 и не дождались». Разбор
     * отчёта `MBRY` упёрся ровно в это: причину нашли по расстоянию между строками
     * журнала, а не по самой строке.
     */
    private fun note(id: Long, manager: DownloadManager, why: String, done: Long, total: Long) {
        val reason = manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            } else {
                0
            }
        }
        Journal.trouble(
            LogCode.UPD_NO_CONNECTION,
            "загрузка сорвалась: " + why,
            "скачано" to megabytes(done),
            "всего" to megabytes(total),
            "код" to reason,
        )
    }

    /** Байты в мегабайты с одной десятой: журнал читают глазами, а не считают в уме. */
    private fun megabytes(bytes: Long): String {
        if (bytes <= 0) return "неизвестно"
        val tenths = bytes * 10 / (1024 * 1024)
        return (tenths / 10).toString() + "," + (tenths % 10).toString() + " МБ"
    }

    /**
     * Что скачали. `null` — всё сошлось; иначе исход, который надо показать.
     *
     * Проверяются три вещи, и порядок неважен: любая несошедшаяся означает «не ставим».
     */
    private fun verify(file: File, offer: UpdateOffer): InstallOutcome? {
        val pm = context.packageManager
        // Оба флага сразу: у пакета бывает пуст либо новый источник подписи, либо старый.
        @Suppress("DEPRECATION")
        val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        val info = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: return InstallOutcome.BadPackage

        if (info.packageName != context.packageName) return InstallOutcome.BadPackage

        @Suppress("DEPRECATION")
        val version = info.versionCode
        if (version != offer.versionCode) return InstallOutcome.BadPackage

        val ourFingerprints = fingerprints(ownSignatures())
        val theirs = fingerprints(archiveSignatures(info))
        if (theirs.isEmpty() || ourFingerprints.isEmpty()) return InstallOutcome.BadPackage
        if (theirs.intersect(ourFingerprints).isEmpty()) return InstallOutcome.BadPackage

        // Хэш от сервера проверяем, только если он объявлен: на Android главная проверка —
        // подпись, и требовать хэш значило бы отказываться обновляться там, где всё цело.
        if (offer.sha256.isNotBlank() && !hexDigest(file).equals(offer.sha256, ignoreCase = true)) {
            return InstallOutcome.BadPackage
        }
        return null
    }

    /** Отдаёт APK системе. Диалог покажет [InstallResultReceiver] — так решает Android. */
    private fun commit(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("tima", 0, file.length()).use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
                session.fsync(output)
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    private fun openInstallSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    @Suppress("DEPRECATION")
    private fun ownSignatures(): Array<android.content.pm.Signature> {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        return archiveSignatures(info)
    }

    @Suppress("DEPRECATION")
    private fun archiveSignatures(info: android.content.pm.PackageInfo): Array<android.content.pm.Signature> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo
            if (signing != null) {
                val current = signing.apkContentsSigners
                if (current != null && current.isNotEmpty()) return current
            }
        }
        return info.signatures ?: emptyArray()
    }

    private fun fingerprints(signatures: Array<android.content.pm.Signature>): Set<String> =
        signatures.map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()

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
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { byte ->
        val value = byte.toInt() and 0xFF
        HEX[value ushr 4].toString() + HEX[value and 0x0F]
    }

    private companion object {
        const val BUFFER = 64 * 1024
        const val STEP_MS = 700L

        /**
         * Сколько ждём, когда байты **перестали** прибавляться.
         *
         * Сорок секунд, а не две минуты: столько занимает переключение сети телефона
         * (Wi-Fi на мобильную и обратно) с запасом. Больше — и человек смотрит в
         * замерший прогресс, не понимая, ждать ему или нажимать заново.
         */
        const val STALL_MS = 40_000L

        /**
         * Общий потолок — страховка, а не мера терпения.
         *
         * Полчаса хватает и на 64 МБ в медленной мобильной сети (это около 300 кбит/с), и
         * на любой обозримый рост APK. Упереться в него может только загрузка, которая
         * капает по байту и потому застрявшей не считается.
         *
         * **Прежний потолок стоил дня разбора.** Он был в две минуты, измерен на APK в
         * 16 МБ, и на 64 МБ оборвал загрузку дважды подряд — отчёт `MBRY` 2026-09-19.
         * Отсюда правило: срок ставится тому, что от размера не зависит.
         */
        const val LIMIT_MS = 30 * 60 * 1000L
        const val HEX = "0123456789abcdef"
    }
}

/**
 * Ответ системы на нашу установку.
 *
 * Нужен ровно для одного: `PackageInstaller` не показывает человеку диалог сам, а
 * присылает `STATUS_PENDING_USER_ACTION` с готовым интентом — и запустить его должны мы.
 * Без этого установка молча стоит на месте, и выглядит это как «ничего не произошло».
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) return
        @Suppress("DEPRECATION")
        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(confirm) }
    }
}
