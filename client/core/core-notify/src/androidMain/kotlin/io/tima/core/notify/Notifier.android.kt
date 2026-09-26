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
        notice.call?.let { call -> dressAsCall(builder, notice, call) }
        // Тот же `key` — та же строка: второе сообщение из переписки заменяет первое, а
        // проверенное имя заменяет безымянную строку (У6). Идентификатор выводится из
        // ключа, а не раздаётся счётчиком: счётчик не переживает перезапуск службы.
        runCatching { manager.notify(notice.key, idOf(notice.key), builder.build()) }
            .onFailure {
                Journal.trouble(LogCode.PERM_DENIED, "уведомление не показано", "почему" to it.message.orEmpty())
                return
            }
        // Звук — после показа: строки нет, звенеть незачем. Каналы беззвучные — звучит
        // приложение само (см. AndroidRinger).
        val call = notice.call
        if (call != null) {
            ringingKey = notice.key
            AndroidRinger.ring(context, call.ring)
        } else {
            AndroidRinger.once(context, notice.sound)
        }
    }

    /**
     * Строка звонит — ВЗ1, ВЗ2.
     *
     * **Во весь экран на замке и при погашенном экране** (`fullScreenIntent`): система
     * включает экран и показывает окно входящего. При открытом другом приложении та же
     * строка всплывает поверх — с кнопками, чтобы ответить, не переходя в TIMA.
     *
     * «Принять» открывает окно (разговору нужен экран), «Отклонить» кладёт трубку без
     * окна — приёмником [CallActionReceiver].
     */
    private fun dressAsCall(builder: Notification.Builder, notice: Notice, call: CallAlert) {
        builder.setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            // На замке звонок виден целиком: кто звонит — ровно то, что нужно решить, не
            // отпирая телефон.
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        val show = callIntent(ACTION_SHOW, call.callId)
        val accept = callIntent(ACTION_ACCEPT, call.callId)
        if (show != null) builder.setFullScreenIntent(show, true)
        val decline = PendingIntent.getBroadcast(
            context,
            idOf("decline:" + call.callId),
            Intent(context, CallActionReceiver::class.java)
                .setAction(ACTION_DECLINE)
                .putExtra(EXTRA_CALL_ID, call.callId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && accept != null) {
            val person = android.app.Person.Builder().setName(notice.who ?: APP).setImportant(true).build()
            builder.setStyle(Notification.CallStyle.forIncomingCall(person, decline, accept))
        } else {
            builder.addAction(Notification.Action.Builder(null, CALL_DECLINE, decline).build())
            if (accept != null) builder.addAction(Notification.Action.Builder(null, CALL_ACCEPT, accept).build())
        }
    }

    /** Окно приложения с поручением про звонок: показать или принять. */
    private fun callIntent(action: String, callId: String): PendingIntent? {
        val base = open?.invoke() ?: return null
        return PendingIntent.getActivity(
            context,
            idOf(action + callId),
            base.setAction(action).putExtra(EXTRA_CALL_ID, callId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    override fun hide(key: String) {
        // Строка звонка снята — звонок кончился чем бы то ни было: мелодия молчит.
        if (key == ringingKey) {
            AndroidRinger.stop()
            ringingKey = null
        }
        runCatching { manager?.cancel(key, idOf(key)) }
    }

    override fun hideAll() {
        runCatching { manager?.cancelAll() }
    }

    /**
     * Завести каналы сразу, при запуске (ВЗ0г). Иначе канал «Звонки» появлялся только с
     * первым входящим: до него в настройках телефона выключать было нечего, а приложение не
     * знало, включён ли он, — и полоса «звонки не дойдут» молчала о канале вовсе.
     */
    internal fun prepareChannels() {
        val manager = manager ?: return
        NoticeKind.entries.forEach { ensureChannel(manager, it) }
    }

    private fun ensureChannel(manager: NotificationManager, kind: NoticeKind) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // Прежние каналы звучали сами; звук канала не меняется, поэтому они заменены
        // беззвучными с новыми именами, а старые убираются (ВЗ5).
        OLD_CHANNELS.forEach { old -> if (manager.getNotificationChannel(old) != null) manager.deleteNotificationChannel(old) }
        val id = channelOf(kind)
        if (manager.getNotificationChannel(id) != null) return
        val importance = when (kind) {
            NoticeKind.Call -> NotificationManager.IMPORTANCE_HIGH
            NoticeKind.Message -> NotificationManager.IMPORTANCE_DEFAULT
        }
        val channel = NotificationChannel(id, nameOf(kind), importance).apply {
            // Звучит приложение (AndroidRinger): своя мелодия, по кругу, свой файл.
            setSound(null, null)
            enableVibration(false)
            if (kind == NoticeKind.Call) lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    internal companion object {
        const val APP = "TIMA"
        const val CHANNEL_MESSAGE = "tima.messages.2"
        const val CHANNEL_CALL = "tima.calls.2"

        /** Прежние, звучавшие сами, — убираются при запуске (ВЗ5). */
        val OLD_CHANNELS = listOf("tima.messages", "tima.calls")

        /** Какой ключ сейчас звонит: снятие его строки глушит мелодию. */
        @Volatile
        var ringingKey: String? = null

        /** Поручения окну и приёмнику про звонок (ВЗ1, ВЗ2). */
        const val ACTION_SHOW = "io.tima.call.SHOW"
        const val ACTION_ACCEPT = "io.tima.call.ACCEPT"
        const val ACTION_DECLINE = "io.tima.call.DECLINE"
        const val EXTRA_CALL_ID = "io.tima.call.ID"

        // Кнопки старых Android (до 12): своего словаря у модуля нет — как у имён каналов.
        const val CALL_ACCEPT = "Принять"
        const val CALL_DECLINE = "Отклонить"

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
/**
 * «Отклонить» из строки звонка — без окна (ВЗ2).
 *
 * Кладёт трубку тем, кто держит канал ([AndroidNotices.onDecline]), и глушит мелодию
 * сразу, не дожидаясь, пока сервер разошлёт конец звонка.
 */
class CallActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidNotifier.ACTION_DECLINE) return
        val callId = intent.getStringExtra(AndroidNotifier.EXTRA_CALL_ID) ?: return
        Journal.note(LogCode.CALL, "отклонили из строки уведомления", "звонок" to callId.take(8))
        AndroidRinger.stop()
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(CALL_KEY_PREFIX + callId, AndroidNotifier.idOf(CALL_KEY_PREFIX + callId))
        AndroidNotices.onDecline?.invoke(callId)
    }

    private companion object {
        /** Тот же ключ, что у `Notices.calling`: строку снимаем ту самую. */
        const val CALL_KEY_PREFIX = "call:"
    }
}

/**
 * Что окно получило из строки звонка: показать его или принять (ВЗ1).
 *
 * `null` — поручения нет. Окно разбирает намерение и кладёт сюда; общий код забирает.
 */
data class CallRequest(val callId: String, val accept: Boolean)

/** Разобрать намерение окна; не наше — `null`. */
fun callRequestOf(intent: Intent?): CallRequest? {
    val callId = intent?.getStringExtra(AndroidNotifier.EXTRA_CALL_ID) ?: return null
    return when (intent.action) {
        AndroidNotifier.ACTION_ACCEPT -> CallRequest(callId, accept = true)
        AndroidNotifier.ACTION_SHOW -> CallRequest(callId, accept = false)
        else -> null
    }
}

object AndroidNotices {

    /** Положить трубку по «Отклонить» из строки; ставит тот, кто держит канал. */
    @Volatile
    var onDecline: ((String) -> Unit)? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var open: (() -> Intent)? = null

    /** Вызывается из `Application.onCreate`. */
    fun install(context: Context, open: (() -> Intent)? = null) {
        this.appContext = context.applicationContext
        this.open = open
        runCatching { AndroidNotifier(context.applicationContext, open).prepareChannels() }
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
