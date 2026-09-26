package io.tima.core.notify

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode

/**
 * Android: `NotificationManager` и два канала важности.
 *
 * ── ПОЧЕМУ КАНАЛОВ ДВА, А НЕ ОДИН ───────────────────────────────────────────
 *
 * Важность на Android задаётся **каналу**, а не строке, и человек правит её сам в
 * настройках. Один канал на всё означал бы, что, приглушив «привет» по ночам, человек
 * заодно приглушит звонок — и узнает об этом, когда ему не дозвонятся.
 *
 * Звонок всплывает поверх экрана (`IMPORTANCE_HIGH`), сообщение — нет. Раздавать
 * всплытие сообщениям нельзя: человек, у которого поверх экрана всплывает каждая строка,
 * выключит всё, и вместе с сообщениями пропадёт звонок.
 *
 * ── КОНТЕКСТ ПРИЛОЖЕНИЯ, А НЕ ОКНА ──────────────────────────────────────────
 *
 * В отличие от разрешения (там нужна активность), показывать умеет и контекст
 * приложения. Это важно: уведомления ставит служба, а окна у неё нет и быть не должно —
 * в том и смысл, что канал живёт без окна.
 */
class AndroidNotifier(
    private val context: Context,
    /** Что открыть по нажатию; `null` — строка не нажимается. */
    private val open: (() -> Intent)? = null,
) : Notifier {

    private val manager get() = context.getSystemService(NotificationManager::class.java)

    override fun show(notice: Notice) {
        val manager = manager ?: return
        ensureChannel(manager, notice.kind)
        val builder = Notification.Builder(context, channelOf(notice.kind))
            .setSmallIcon(android.R.drawable.sym_action_email)
            // Заголовок — имя, если оно есть и проверено; иначе название приложения
            // подставит система. Пустая строка вместо имени выглядела бы как поломка.
            .setContentTitle(notice.who ?: APP)
            .setContentText(notice.what)
            .setAutoCancel(true)
        // ── НА ЗАМКЕ — ТО ЖЕ САМОЕ ──────────────────────────────────────────
        //
        // Прятать на замке нечего: текста сообщения в строке нет вовсе, а «кто и что» —
        // это и есть всё, что мы показываем. Заводить «публичную версию» строки значило
        // бы прятать от человека ровно то, ради чего он уведомление и включил.
        builder.setVisibility(Notification.VISIBILITY_PRIVATE)
        open?.let { intent ->
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    intent(),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        // Тот же `key` — та же строка: второе сообщение из переписки заменяет первое, а
        // проверенное имя заменяет безымянную строку (У6). Идентификатор выводится из
        // ключа, а не раздаётся счётчиком: счётчик не переживает перезапуск службы.
        runCatching { manager.notify(notice.key, idOf(notice.key), builder.build()) }
            .onFailure { Journal.trouble(LogCode.PERM_DENIED, "уведомление не показано", "почему" to it.message.orEmpty()) }
    }

    override fun hide(key: String) {
        runCatching { manager?.cancel(key, idOf(key)) }
    }

    override fun hideAll() {
        runCatching { manager?.cancelAll() }
    }

    private fun ensureChannel(manager: NotificationManager, kind: NoticeKind) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val id = channelOf(kind)
        if (manager.getNotificationChannel(id) != null) return
        val importance = when (kind) {
            NoticeKind.Call -> NotificationManager.IMPORTANCE_HIGH
            NoticeKind.Message -> NotificationManager.IMPORTANCE_DEFAULT
        }
        manager.createNotificationChannel(NotificationChannel(id, nameOf(kind), importance))
    }

    internal companion object {
        const val APP = "TIMA"
        const val CHANNEL_MESSAGE = "tima.messages"
        const val CHANNEL_CALL = "tima.calls"

        fun channelOf(kind: NoticeKind) = when (kind) {
            NoticeKind.Call -> CHANNEL_CALL
            NoticeKind.Message -> CHANNEL_MESSAGE
        }

        // Имя канала человек видит в настройках телефона. Своего словаря у модуля нет и
        // заводить его не стоит: две строки, которые не меняются вместе с языком
        // приложения, потому что система запоминает их при заведении канала.
        fun nameOf(kind: NoticeKind) = when (kind) {
            NoticeKind.Call -> "Звонки"
            NoticeKind.Message -> "Сообщения"
        }

        /** Устойчивое число из ключа: счётчик не пережил бы перезапуск службы. */
        fun idOf(key: String): Int = key.hashCode()
    }
}

/**
 * Право показывать уведомления — `POST_NOTIFICATIONS`, Android 13 и новее.
 *
 * ── ЧЕМ ЭТО ХУЖЕ ОБЫЧНОГО РАЗРЕШЕНИЯ ────────────────────────────────────────
 *
 * Отказ здесь не виден никак. Служба переднего плана **работает**, канал держится,
 * сообщения приходят — просто человек не видит ни одной строки, включая постоянную
 * строку самой службы. Со стороны это выглядит как «приложение не работает», и искать
 * будут где угодно, только не в разрешении.
 *
 * Поэтому отказ пишется в журнал: половина «у меня ничего не приходит» на Android —
 * именно про это.
 */
object AndroidNotifyAccess {

    private const val REQUEST = 4202
    private const val PREFS = "notifications"
    private const val ASKED = "asked"
    private const val PERMISSION = "android.permission.POST_NOTIFICATIONS"

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
     * Проверка `===` не перестраховка, и это уже стоило дня на realme у книги контактов:
     * Android умеет создать новое окно раньше, чем доломать старое, и без проверки
     * уходящая активность обнуляла бы ссылку на живую.
     */
    fun detach(activity: Activity) {
        if (this.activity !== activity) return
        this.activity = null
        waiting = null
    }

    /** Вызывается из `onRequestPermissionsResult`. */
    fun answered(requestCode: Int, results: IntArray) {
        if (requestCode != REQUEST) return
        val granted = results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED
        if (granted) Journal.note(LogCode.PERM_GRANTED, "уведомления", "что" to PERMISSION)
        else Journal.trouble(LogCode.PERM_DENIED, "уведомления", "что" to PERMISSION)
        waiting?.invoke(granted)
        waiting = null
    }

    internal fun way(): NotifyAccessWay {
        // До Android 13 разрешения не существует вовсе: право есть у всех.
        if (Build.VERSION.SDK_INT < 33) return NotifyAccessWay.Given
        val current = activity ?: return NotifyAccessWay.Given
        if (current.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            return NotifyAccessWay.Given
        }
        val askedBefore = current.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ASKED, false)
        return if (askedBefore && !current.shouldShowRequestPermissionRationale(PERMISSION)) {
            NotifyAccessWay.Settings
        } else {
            NotifyAccessWay.Ask
        }
    }

    internal fun ask(onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < 33) return onResult(true)
        val current = activity ?: return onResult(false)
        if (current.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            return onResult(true)
        }
        val prefs = current.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val askedBefore = prefs.getBoolean(ASKED, false)
        if (askedBefore && !current.shouldShowRequestPermissionRationale(PERMISSION)) {
            // Система спрашивать больше не станет, и `requestPermissions` молча ничего
            // не сделает. Кнопка при этом выглядит сломанной, и в неё жмут повторно.
            openSettings(current)
            return onResult(false)
        }
        prefs.edit().putBoolean(ASKED, true).apply()
        waiting = onResult
        current.requestPermissions(arrayOf(PERMISSION), REQUEST)
    }

    /** Страница приложения в настройках, а не корень: в корне ищут наугад. */
    private fun openSettings(activity: Activity) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", activity.packageName, null),
        )
        runCatching { activity.startActivity(intent) }
    }
}

actual fun notifyAccessWay(): NotifyAccessWay = AndroidNotifyAccess.way()

actual fun askNotifyAccess(onResult: (Boolean) -> Unit) = AndroidNotifyAccess.ask(onResult)

/**
 * Контекст приложения для показа.
 *
 * Тот же приём, что у `AndroidContacts`, и по той же причине. Но здесь он важнее:
 * уведомления ставит **служба**, у которой окна нет и не будет, — контекст окна тут не
 * подошёл бы даже при желании.
 */
object AndroidNotices {

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var open: (() -> Intent)? = null

    /** Вызывается из `Application.onCreate`. */
    fun install(context: Context, open: (() -> Intent)? = null) {
        this.appContext = context.applicationContext
        this.open = open
    }

    internal fun notifier(): Notifier =
        appContext?.let { AndroidNotifier(it, open) } ?: Notifier.NONE

    internal fun contextOrNull(): Context? = appContext
}

actual fun platformNotifier(): Notifier = AndroidNotices.notifier()

/**
 * Белый список энергосбережения — У14.
 *
 * ── ЧЕГО ЭТО НЕ ДЕЛАЕТ ──────────────────────────────────────────────────────
 *
 * Не спасает от оболочек. У realme и Xiaomi поверх Android стоят **свои** списки
 * автозапуска и «заморозки», которым всё равно, что мы в системном белом списке, — и
 * открыть их программно документированного способа нет. Поэтому экран, зовущий сюда,
 * обязан сказать про них словами.
 *
 * Google Play это разрешение ограничивает. Нас не связывает: раздаём мимо Play
 * (ADR-0022).
 */
actual fun awakeAllowed(): Boolean {
    val context = AndroidNotices.contextOrNull() ?: return true
    val power = context.getSystemService(android.os.PowerManager::class.java) ?: return true
    return runCatching { power.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(true)
}

actual fun askAwake() {
    val context = AndroidNotices.contextOrNull() ?: return
    // Диалог поднимается намерением приложения: активность для него не обязательна, но
    // флаг нового стека — обязателен, иначе система откажет молча.
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:" + context.packageName))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        // Отказ здесь законен: часть прошивок этот экран не показывает вовсе. Падать не
        // за что, а запись объяснит, почему нажатие ничего не дало.
        Journal.trouble(LogCode.PERM_DENIED, "белый список не открылся", "почему" to it.message.orEmpty())
    }
}
