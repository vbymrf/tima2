package io.tima.core.call

import android.os.Build
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import java.io.File

/**
 * Android: отчёт ложится во **внутренний** каталог приложения, в папку `test`.
 *
 * ── ПОЧЕМУ ВНУТРЕННИЙ, А НЕ ОБЩАЯ ПАМЯТЬ ────────────────────────────────────
 *
 * Внешний `Android/data/<пакет>/files` с Android 11 закрыт для `adb pull` на части
 * прошивок — забирать оттуда пришлось бы по-разному на разных телефонах стенда. Из
 * внутреннего забирается одинаково на всех трёх, отладочной сборкой:
 *
 * ```
 * adb -s <телефон> shell run-as io.tima.app.v2 ls files/test
 * adb -s <телефон> exec-out run-as io.tima.app.v2 cat files/test/<файл> > <куда>
 * ```
 *
 * Имя папки латиницей — решение заказчика 2026-09-06 про всё, что приложение кладёт на
 * диск: кириллический путь ломается о консоль Windows и о разбор вывода `adb`.
 */
actual fun saveBenchReport(fileName: String, text: String): String? {
    val context = AndroidPhoneMeter.context() ?: run {
        Journal.trouble(LogCode.CALL, "отчёт о прогоне некуда положить — приложение не представилось")
        return null
    }
    return runCatching {
        val folder = File(context.filesDir, "test")
        folder.mkdirs()
        val file = File(folder, fileName)
        file.writeText(text)
        Journal.note(LogCode.CALL, "отчёт о прогоне записан", "файл" to fileName)
        file.absolutePath
    }.getOrElse { e ->
        Journal.trouble(
            LogCode.CALL,
            "отчёт о прогоне не записался",
            "причина" to (e.message ?: e::class.simpleName ?: "—"),
        )
        null
    }
}

actual fun phoneModel(): String = Build.MODEL ?: "android"

/** Имя файла наборов — одно на все телефоны, чтобы скрипт с ПК не спрашивал, куда класть. */
private const val PRESETS = "presets.json"

actual fun readPresetsFile(): String? {
    val context = AndroidPhoneMeter.context() ?: return null
    return runCatching {
        val file = File(File(context.filesDir, "test"), PRESETS)
        if (file.exists()) file.readText() else null
    }.getOrNull()
}

actual fun writePresetsFile(text: String): String? {
    val context = AndroidPhoneMeter.context() ?: return null
    return runCatching {
        val folder = File(context.filesDir, "test")
        folder.mkdirs()
        val file = File(folder, PRESETS)
        file.writeText(text)
        file.absolutePath
    }.getOrElse { e ->
        Journal.trouble(
            LogCode.CALL,
            "наборы не записались",
            "причина" to (e.message ?: e::class.simpleName ?: "—"),
        )
        null
    }
}
