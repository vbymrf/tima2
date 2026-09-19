package io.tima.core.call

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode

/**
 * Android: системный диалог про микрофон и камеру, а если система его больше не покажет —
 * страница приложения в настройках.
 *
 * **Написано по образцу `AndroidContactsAccess`** и по тем же двум причинам:
 *
 * 1. Нужна активность, а не контекст приложения: разрешение спрашивает окно.
 * 2. `requestPermissions` показывает диалог **не всегда** — если человек однажды отказал
 *    так, что система решила больше не спрашивать, вызов молча не делает ничего. Кнопка
 *    «позвонить» при этом выглядит сломанной, и в неё жмут повторно.
 *
 * Общего кода с контактами нет намеренно: общий потребовал бы модуля, который знает и про
 * книгу телефона, и про звонки, а это два разных разрешения с разной судьбой.
 *
 * ── ПОЧЕМУ МИКРОФОН И КАМЕРА СПРАШИВАЮТСЯ ОДНИМ ВЫЗОВОМ ─────────────────────
 *
 * Их два в одном `requestPermissions`, но это один диалог системы, а не два подряд. Два
 * подряд человек читает как один и жмёт наугад. Для голосового звонка в списке только
 * микрофон: камеру, которая не понадобится, просить нечем объяснить.
 *
 * ── ЧТО СЧИТАЕТСЯ УСПЕХОМ ───────────────────────────────────────────────────
 *
 * **Микрофон.** Отказ от камеры при выданном микрофоне — не провал: звонок пойдёт
 * голосом, и это лучше, чем не пойти вовсе. Отказ от микрофона — провал: комната
 * соединится, собеседник будет виден, а звука не будет ни в одну сторону.
 */
object AndroidCallAccess {

    private const val REQUEST = 4202
    private const val PREFS = "call"
    private const val ASKED = "asked"

    @Volatile
    private var activity: Activity? = null

    @Volatile
    private var waiting: ((Boolean) -> Unit)? = null

    /** Вызывается из `onCreate`. */
    fun attach(activity: Activity) {
        this.activity = activity
    }

    /**
     * Вызывается из `onDestroy` — **с той самой активностью**, которая умирает.
     *
     * Проверка `===` не перестраховка: Android умеет создать новое окно раньше, чем
     * доломает старое. Без неё уходящая активность обнуляла бы ссылку на живую, и дальше
     * кнопка «позвонить» не делала бы ничего — молча. Ровно это ловили на realme
     * 2026-09-05 с контактами.
     */
    fun detach(activity: Activity) {
        if (this.activity !== activity) return
        this.activity = null
        waiting = null
    }

    /** Вызывается из `onRequestPermissionsResult`. */
    fun answered(requestCode: Int, permissions: Array<String>, results: IntArray) {
        if (requestCode != REQUEST) return
        // По имени разрешения, а не по месту в массиве: порядок задаём мы, но система
        // вправе вернуть свой, и молчаливый звонок из-за перепутанных мест искался бы
        // как беда LiveKit.
        val microphone = results.indexOfPermission(permissions, Manifest.permission.RECORD_AUDIO)
        // Половина «не работает» на Android — это невыданное разрешение, и по отчёту это
        // должно быть видно сразу (правило журнала, группа «разрешение»).
        if (microphone) Journal.note(LogCode.PERM_GRANTED, "звонок", "что" to "RECORD_AUDIO")
        else Journal.trouble(LogCode.PERM_DENIED, "звонок", "что" to "RECORD_AUDIO")
        waiting?.invoke(microphone)
        waiting = null
    }

    internal fun ask(video: Boolean, onResult: (Boolean) -> Unit) {
        val current = activity ?: return onResult(false)
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (video) add(Manifest.permission.CAMERA)
        }.filter { current.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isEmpty()) return onResult(true)

        // Система больше не спросит, если человек уже отказывал и «объяснять» ей нечего.
        // Тогда единственный оставшийся путь — настройки приложения.
        val prefs = current.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val asked = prefs.getBoolean(ASKED, false)
        if (asked && needed.none { current.shouldShowRequestPermissionRationale(it) }) {
            openSettings(current)
            return onResult(false)
        }

        prefs.edit().putBoolean(ASKED, true).apply()
        waiting = onResult
        current.requestPermissions(needed.toTypedArray(), REQUEST)
    }

    /**
     * Страница приложения в настройках.
     *
     * Не «настройки вообще»: человека, которому сказали «разрешите микрофон», нельзя
     * высаживать в корень настроек — он там ищет наугад.
     */
    private fun openSettings(activity: Activity) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", activity.packageName, null),
        )
        runCatching { activity.startActivity(intent) }
    }
}

/** Дано ли именно это разрешение — по имени, а не по месту в массиве ответов. */
private fun IntArray.indexOfPermission(permissions: Array<String>, name: String): Boolean {
    val at = permissions.indexOf(name)
    return at >= 0 && at < size && this[at] == PackageManager.PERMISSION_GRANTED
}

actual fun askCallAccess(video: Boolean, onResult: (Boolean) -> Unit) =
    AndroidCallAccess.ask(video, onResult)
