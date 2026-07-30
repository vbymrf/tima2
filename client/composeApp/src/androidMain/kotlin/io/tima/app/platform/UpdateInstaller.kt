package io.tima.app.platform

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import io.tima.app.diag.AppDiagnostics
import java.io.File
import java.security.MessageDigest

/**
 * Установка обновления с ОБРАТНОЙ СВЯЗЬЮ.
 *
 * Раньше APK отдавался системе через `ACTION_VIEW`, и приложение о судьбе установки
 * не узнавало ничего: пользователь видел «не ставится», а в диагностике была лишь
 * строка «запускаю установщик». Именно так выглядела история с несовпавшей подписью —
 * симптом был, причины не было нигде.
 *
 * Здесь два изменения:
 *  1. Перед установкой сверяем подпись скачанного файла с подписью установленного
 *     приложения. Несовпадение = обновиться поверх НЕВОЗМОЖНО (так устроен Android),
 *     и об этом надо сказать прямо, а не отправлять человека к установщику, который
 *     молча откажет.
 *  2. Ставим через PackageInstaller с получателем статуса: код и текст ошибки от
 *     системы попадают в диагностику.
 */
object UpdateInstaller {

    /** Что удалось узнать о скачанном файле до установки. */
    data class ApkInfo(
        val versionCode: Long,
        val versionName: String,
        val packageName: String,
        val certSha256: String,
    )

    private const val ACTION_STATUS = "io.tima.app.UPDATE_STATUS"

    /** Сведения об APK-файле: версия и отпечаток подписи. */
    fun inspect(ctx: Context, apk: File): ApkInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info = ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, flags) ?: return null
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return ApkInfo(
            versionCode = code,
            versionName = info.versionName.orEmpty(),
            packageName = info.packageName.orEmpty(),
            certSha256 = certDigest(signaturesOf(info)),
        )
    }

    /** Отпечаток подписи УСТАНОВЛЕННОГО приложения. */
    fun installedCertSha256(ctx: Context): String {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, flags) }.getOrNull()
            ?: return ""
        return certDigest(signaturesOf(info))
    }

    private fun signaturesOf(info: android.content.pm.PackageInfo): Array<Signature>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }

    private fun certDigest(sigs: Array<Signature>?): String {
        val first = sigs?.firstOrNull() ?: return ""
        val d = MessageDigest.getInstance("SHA-256").digest(first.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    /** Экран удаления приложения — единственный путь, когда подпись разошлась. */
    fun openUninstall(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { AppDiagnostics.add("update: не открылся экран удаления — ${it.message}") }
    }

    /**
     * Ставит APK через PackageInstaller. Результат приходит широковещательно и
     * попадает в диагностику — в отличие от ACTION_VIEW, который ничего не сообщает.
     */
    fun install(ctx: Context, apk: File) {
        registerStatusReceiver(ctx)
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        AppDiagnostics.add("update: сессия установки $sessionId, файл ${apk.length()} байт")

        installer.openSession(sessionId).use { session ->
            session.openWrite("tima", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val pi = PendingIntent.getBroadcast(
                ctx, sessionId, Intent(ACTION_STATUS).setPackage(ctx.packageName), flags,
            )
            session.commit(pi.intentSender)
        }
        AppDiagnostics.add("update: сессия передана системе, жду ответа установщика")
    }

    private var receiverRegistered = false

    private fun registerStatusReceiver(ctx: Context) {
        if (receiverRegistered) return
        receiverRegistered = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        AppDiagnostics.add("update: система просит подтверждение установки")
                        val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        }
                        confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { c.startActivity(confirm) }
                            .onFailure { AppDiagnostics.add("update: не открылось подтверждение — ${it.message}") }
                    }
                    PackageInstaller.STATUS_SUCCESS ->
                        AppDiagnostics.add("update: установлено успешно")
                    else ->
                        // ВОТ РАДИ ЧЕГО ВСЁ ЭТО: до сих пор причина отказа не была видна нигде.
                        AppDiagnostics.add("update: установка отклонена, код $status — ${explain(status)}${if (msg.isNotEmpty()) " ($msg)" else ""}")
                }
            }
        }
        val filter = IntentFilter(ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(receiver, filter)
        }
    }

    private fun explain(status: Int): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "отменено пользователем"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "заблокировано системой или защитой"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "конфликт с установленным (обычно другая подпись — нужно удалить приложение)"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "несовместимо с устройством"
        PackageInstaller.STATUS_FAILURE_INVALID -> "файл повреждён или неверный"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "не хватает места"
        else -> "неизвестная причина"
    }
}
