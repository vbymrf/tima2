package io.tima.core.contacts

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager

/**
 * Android: системный диалог разрешения.
 *
 * **Нужна активность, а не контекст приложения.** Разрешение спрашивает окно, и
 * `Context` от `Application` для этого не годится. Поэтому активность отдаётся сюда на
 * время своей жизни — тем же приёмом, что и контекст в [AndroidContacts], и по той же
 * причине: общий код про Android знать не должен.
 *
 * Ссылка снимается в `onDestroy`: удержанная активность — это утечка целого экрана.
 */
object AndroidContactsAccess {

    private const val REQUEST = 4201

    @Volatile
    private var activity: Activity? = null

    @Volatile
    private var waiting: ((Boolean) -> Unit)? = null

    /** Вызывается из `onCreate`. */
    fun attach(activity: Activity) {
        this.activity = activity
    }

    /** Вызывается из `onDestroy`: иначе удерживается весь экран. */
    fun detach() {
        activity = null
        // Ожидающий обратный вызов тоже снимается: экрана, который его ждал, уже нет,
        // и ответ пришёл бы в никуда.
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
        waiting = onResult
        current.requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST)
    }
}

actual fun askContactsAccess(onResult: (Boolean) -> Unit) = AndroidContactsAccess.ask(onResult)
