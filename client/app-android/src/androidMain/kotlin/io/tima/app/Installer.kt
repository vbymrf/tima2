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
                Journal.trouble("разрешение", "установка пакетов не разрешена — увожу в настройки")
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
                Journal.trouble("обновление", "пакет не докачался")
                return@withContext InstallOutcome.NoConnection
            }
            Journal.note("обновление", "пакет скачан, проверяю подпись")

            when (val checked = verify(target, offer)) {
                null -> Journal.note("обновление", "подпись сошлась, отдаю системе")
                else -> {
                    // Отчёт должен объяснять, а не сообщать код: «подпись не та» и «файл
                    // побит» человек не различит, а нам важно, что ставить это нельзя.
                    Journal.trouble("обновление", "скачанное не прошло проверку — не ставлю")
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

    /** Качает через `DownloadManager`, отдавая проценты. `false` — не докачали. */
    private suspend fun download(url: String, target: File, onProgress: (Int) -> Unit): Boolean {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Обновление TIMA")
            .setDestinationUri(Uri.fromFile(target))
            // Только прогресс. VISIBILITY_VISIBLE_NOTIFY_COMPLETED дал бы уведомление,
            // по которому APK открывает чужой обработчик, — урок 3 выше.
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        val id = manager.enqueue(request)

        var waited = 0
        while (waited < MAX_STEPS) {
            delay(STEP_MS)
            waited++
            manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (!cursor.moveToFirst()) return false
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                if (total > 0) onProgress((done * 100 / total).toInt())
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> return true
                    DownloadManager.STATUS_FAILED -> return false
                }
            }
        }
        // Время вышло. Загрузку снимаем: висящая в фоне, она однажды доедет и положит
        // рядом файл, которого никто не ждал.
        manager.remove(id)
        return false
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
        // Те же две минуты, что были у v1: `waited > 170` при шаге 700 мс. Число взято
        // не из головы — оно измерено на живых телефонах в мобильной сети.
        const val MAX_STEPS = 170
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
