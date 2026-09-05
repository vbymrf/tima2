package io.tima.core.contacts

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/**
 * Android: системный диалог разрешения, а если система его больше не покажет — настройки.
 *
 * **Нужна активность, а не контекст приложения.** Разрешение спрашивает окно, и `Context`
 * от `Application` для этого не годится. Поэтому активность отдаётся сюда на время своей
 * жизни — тем же приёмом, что и контекст в [AndroidContacts], и по той же причине: общий
 * код про Android знать не должен.
 *
 * ── ПОЧЕМУ КНОПКА ОБЯЗАНА ЧТО-ТО ДЕЛАТЬ ВСЕГДА ──────────────────────────────
 *
 * `requestPermissions` показывает диалог **не всегда**: если человек однажды отказал так,
 * что система решила больше не спрашивать, вызов молча ничего не делает — ни диалога, ни
 * ответа. Кнопка при этом выглядит сломанной, и в неё жмут повторно.
 *
 * Поэтому здесь два пути: пока система готова спросить — спрашиваем, а когда перестала —
 * открываем страницу приложения в настройках. Оба видимы человеку; молчания нет ни в
 * одном случае.
 */
object AndroidContactsAccess {

    private const val REQUEST = 4201
    private const val PREFS = "контакты"
    private const val ASKED = "спрашивали"

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
     * Проверка `===` здесь не перестраховка. Android умеет создать новое окно раньше, чем
     * доломает старое: без проверки уходящая активность обнуляла бы ссылку на живую, и
     * дальше кнопка «разрешить» не делала бы ничего — молча, потому что спрашивать стало
     * не через кого. Поймано на realme 2026-09-06: диалог не появлялся вовсе, а флаги
     * разрешения показывали, что человека ни разу не спросили.
     */
    fun detach(activity: Activity) {
        if (this.activity !== activity) return
        this.activity = null
        // Ожидающий обратный вызов тоже снимается: экрана, который его ждал, уже нет.
        waiting = null
    }

    /** Вызывается из `onRequestPermissionsResult`. */
    fun answered(requestCode: Int, results: IntArray) {
        if (requestCode != REQUEST) return
        val granted = results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED
        waiting?.invoke(granted)
        waiting = null
    }

    internal fun ask(onResult: (Boolean) -> Unit) {
        val current = activity ?: return onResult(false)
        if (current.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            // Уже дано: диалога быть не должно, а ответ нужен тот же.
            return onResult(true)
        }

        // Система больше не спросит, если человек уже отказывал и «объяснять» ей нечего.
        // Тогда единственный оставшийся путь — настройки приложения.
        val prefs = current.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val спрашивали = prefs.getBoolean(ASKED, false)
        if (спрашивали && !current.shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)) {
            openSettings(current)
            return onResult(false)
        }

        prefs.edit().putBoolean(ASKED, true).apply()
        waiting = onResult
        current.requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST)
    }

    /**
     * Страница приложения в настройках.
     *
     * Не «настройки вообще»: человека, которому сказали «разрешите доступ», нельзя
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

actual fun askContactsAccess(onResult: (Boolean) -> Unit) = AndroidContactsAccess.ask(onResult)
