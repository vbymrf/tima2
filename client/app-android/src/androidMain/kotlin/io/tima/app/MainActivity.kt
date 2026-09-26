package io.tima.app

import io.tima.core.notify.BackgroundWatch
import io.tima.shared.CallRequests
import io.tima.shared.CallOrder
import io.tima.core.notify.callRequestOf
import android.content.Intent
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import io.tima.core.contacts.AndroidContactsAccess
import io.tima.core.notify.AndroidNotifyAccess
import io.tima.shared.ChannelHost
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.tima.core.call.AndroidCallAccess
import io.tima.core.call.LiveKitCallEngine
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import io.tima.core.database.androidDatabase
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.feature.shell.ProblemFacts
import io.tima.feature.shell.UpdateMemory
import io.tima.core.ui.TimaTheme
import io.tima.shared.Entry
import io.tima.shared.Platform
import io.tima.shared.AppearanceStore
import io.tima.shared.Root

/**
 * Вход для Android.
 *
 * Здесь и только здесь разрешено знать о платформе как о платформе (Plan.md §1.3):
 * Activity, манифест, разрешения. **Всё остальное — в `shared`**, тот же самый код, что
 * работает на ПК: правила поведения платформенными не бывают, и копия на платформу это
 * ровно то, из-за чего в v1 Android и Desktop разошлись молча.
 *
 * Платформенного здесь два: драйвер базы (`AndroidSqliteDriver` вместо `sqlite-jdbc`) и
 * само окно. Хранилище секретов тоже платформенное — AndroidKeyStore вместо DPAPI, — но о
 * нём знает `core-secrets`, а не эта Activity: контекст ему отдаёт
 * [TimaApplication].
 *
 * Файл этим и ценен: он короткий. Стало длинно — значит в него протекло общее.
 */
@OptIn(ExperimentalComposeUiApi::class)
class MainActivity : ComponentActivity() {

    /**
     * Код привязки, пришедший **снаружи**.
     *
     * Своего сканера QR у приложения нет и не нужно: штатная камера телефона уже умеет
     * читать коды, видит в нашем `tima://link/v1?…` ссылку и открывает нас. Свой сканер
     * потребовал бы доступа к камере — то есть вопроса человеку, — и делал бы ровно то же
     * самое.
     *
     * Состояние, а не разовое чтение: приложение может быть уже запущено, и тогда код
     * приезжает в [onNewIntent], а не в [onCreate].
     */
    private val code = mutableStateOf<String?>(null)

    /**
     * Код передачи аккаунта, пришедший снаружи (Д12).
     *
     * Держится отдельно от [code] по той же причине, по какой их разделяет `Root`:
     * привязка добавляет устройство к своему аккаунту, передача забирает чужой. Общее
     * поле означало бы, что выбор экрана зависит от порядка двух переходов.
     */
    private val transfer = mutableStateOf<String?>(null)

    /**
     * Где Android хранит оформление: обычные настройки приложения.
     *
     * Не база и не хранилище секретов: выбранная тема не секрет и нужна раньше, чем
     * открывается база, — в неё уже завёрнут экран входа. `SharedPreferences` доступны
     * сразу и стираются вместе с приложением, что здесь и правильно.
     */
    private fun appearanceStore(): AppearanceStore {
        val prefs = getSharedPreferences("appearance", Context.MODE_PRIVATE)
        return AppearanceStore(
            load = { prefs.getString(KEY_APPEARANCE, null) },
            save = { prefs.edit().putString(KEY_APPEARANCE, it).apply() },
        )
    }

    /**
     * Где телефон помнит начатую установку: обычные настройки приложения.
     *
     * `SharedPreferences`, а не файл: строка короткая и читается один раз при запуске.
     * Не база — обновляются и до входа, а база открывается после.
     */
    private fun updateMemory(): UpdateMemory {
        val prefs = getSharedPreferences("update", Context.MODE_PRIVATE)
        return UpdateMemory(
            load = { prefs.getString(KEY_UPDATE, null) },
            save = { prefs.edit().putString(KEY_UPDATE, it).apply() },
        )
    }

    /**
     * Ушли в фон — и это единственный надёжный момент сбросить журнал на диск.
     *
     * Дальше система вправе убить процесс без предупреждения, и `onDestroy` при этом не
     * зовётся. Записи, не дожившие до сброса пачкой, пропали бы вместе с процессом —
     * именно те, которые объясняют, чем кончился сеанс.
     *
     * Строка в журнале нужна не меньше сброса: без неё тишина фона неотличима от
     * зависания, а «приложение висело полчаса» — частая формулировка жалобы.
     */
    override fun onStop() {
        wasBackground = true
        // Окно ничего не показывает глазами — значит переписка, оставшаяся «открытой»,
        // обязана снова уведомлять (У10). Без этого человек, свернувший приложение на
        // переписке, перестал бы получать из неё уведомления до следующего захода.
        ChannelHost.notices()?.windowVisible(false)
        Journal.note(LogCode.APP_BACKGROUND, "ушли в фон")
        Journal.diary.flush()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        // Только после настоящего ухода в фон: первый `onStart` идёт сразу за запуском, и
        // «вернулись» рядом с `APP-START` было бы неправдой и лишней строкой.
        if (wasBackground) Journal.note(LogCode.APP_FOREGROUND, "вернулись из фона")
        ChannelHost.notices()?.windowVisible(true)
        // Человек мог сходить в настройки и включить уведомления или белый список — сверяем.
        // Не изменилось — строки нет.
        BackgroundWatch.check(if (wasBackground) "вернулись из фона" else "окно открыто")
    }

    /** Был ли уже уход в фон — см. [onStart]. */
    private var wasBackground = false

    override fun onDestroy() {
        // Удержанная активность — это утечка целого экрана. Отдаём именно себя:
        // новое окно Android умеет создать раньше, чем доломает старое, и без этого
        // уходящее обнуляло бы ссылку на живое.
        AndroidContactsAccess.detach(this)
        AndroidCallAccess.detach(this)
        AndroidNotifyAccess.detach(this)
        super.onDestroy()
    }

    @Deprecated("вызывается системой; свой ActivityResultLauncher здесь не нужен")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Оба разбирают ответ по СВОЕМУ коду запроса и чужой пропускают. Поэтому их
        // здесь два подряд, а не развилка: развилка перестала бы работать молча,
        // когда появится третье разрешение.
        AndroidContactsAccess.answered(requestCode, grantResults)
        AndroidCallAccess.answered(requestCode, permissions, grantResults)
        AndroidNotifyAccess.answered(requestCode, grantResults)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Разрешение на контакты спрашивает ОКНО, а не приложение: контекст от
        // Application для системного диалога не годится (Д3).
        AndroidContactsAccess.attach(this)
        // То же и для микрофона со звонком: движок видит только контекст приложения, и
        // системный диалог через него не показать (К7, `core-call/Access.kt`).
        AndroidCallAccess.attach(this)
        // И для уведомлений: с Android 13 право на показ спрашивается так же, как
        // контакты, и без него не видно ни одной строки — включая строку службы (У1).
        AndroidNotifyAccess.attach(this)
        // Служба поднимается при первом же открытии окна и остаётся жить после него
        // (У3). Дважды поднятая — это одна служба: второй `onStartCommand` увидит, что
        // канал уже взят.
        ChannelService.start(this)
        code.value = linkFrom(intent)
        transfer.value = transferFrom(intent)
        takeCallOrder(intent)
        setContent {
            val entry = remember { Entry.create(Platform.Android) }
            // ── МЕТКИ СЦЕНАРИЕВ ПОПАДАЮТ В ДЕРЕВО ДОСТУПНОСТИ ────────────────
            //
            // `Modifier.testTag` сам по себе виден только тестам Compose. Прогонщику
            // живых сценариев (Maestro) нужен `resource-id` — как у обычного вида
            // Android. Эта строка и делает одно в другое, и работает она на весь
            // экран разом, поэтому стоит здесь, а не у каждой метки.
            //
            // Почему в точке входа Android, а не в теме: свойство платформенное, в
            // общем коде его нет. Настольной версии оно не нужно вовсе — там нет
            // ни дерева доступности Android, ни прогонщика.
            //
            // Зачем всё это: отбор в живых прогонах идёт либо по видимому тексту,
            // либо по метке. Текста у нас три языка, и сценарий с `tapOn: "Чаты"`
            // на английском не находит ничего.
            Box(
                Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
            ) {
            // Тема здесь больше не решается: её выбирает человек в настройках, и
            // держит выбор `Root`. Платформе осталось только место для хранения строки.
            //
            // База телефона: имя файла в песочнице приложения, а не путь. Каталог
            // выбирает Android, и это правильно — он же его и стирает при удалении.
            // Звонок исполняет livekit-android: ему нужен Context, а общий код его не
            // видит. Поэтому движок собирается здесь и передаётся вниз — так же, как
            // база и установщик. На ПК и iOS его нет, и там окна 0 не будет вовсе.
            val callScope = rememberCoroutineScope()
            val callEngine = remember { LiveKitCallEngine(applicationContext, callScope) }

            Root(
                entry = entry,
                callEngine = callEngine,
                // Имя файла приходит готовым: правило именования общее (Д11).
                deviceDatabase = { name -> androidDatabase(applicationContext, name) },
                appearanceStore = appearanceStore(),
                linkCode = code.value,
                transferCode = transfer.value,
                // Обновление ставит платформа. Закрывать приложение здесь, в отличие от
                // ПК, не нужно: Android сам покажет установщик поверх, а замену пакета
                // проведёт, когда сочтёт нужным.
                installer = AndroidInstaller(applicationContext),
                // Что телефон знает о себе для отчёта о проблеме. Производитель и модель
                // решают половину разбора: «на realme не работает, на Xiaomi работает».
                facts = ProblemFacts(
                    platform = "android",
                    model = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL,
                    os = "Android " + android.os.Build.VERSION.RELEASE +
                        " (SDK " + android.os.Build.VERSION.SDK_INT + ")",
                ),
                reportsStore = androidReportsStore(application),
                diaryPolicy = androidDiaryPolicy(application),
                // Начатая установка помнится между запусками: замена пакета убивает
                // процесс, и спросить у себя, чем всё кончилось, потом будет некого.
                updateMemory = updateMemory(),
                // Имя и номер разом: имя говорит, что за версия, номер — что
                // установка действительно сменилась. По одному имени обновление
                // «2.0.0-dev → 2.0.0-dev» неотличимо от его отсутствия.
                // Поток обязателен: номера версий сравнимы только внутри него, и
                // без него v2 приняла бы предложение v1 за более новую версию.
                build = io.tima.shared.Build(
                    name = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    code = BuildConfig.VERSION_CODE,
                    stream = BuildConfig.TIMA_STREAM,
                ),
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        linkFrom(intent)?.let { code.value = it }
        transferFrom(intent)?.let { transfer.value = it }
        takeCallOrder(intent)
    }

    /**
     * Окно подняла строка звонка — полноэкранный вызов или «Принять» (ВЗ1, ВЗ2).
     *
     * Поверх замка и с включением экрана — только ради звонка: обычное открытие окна замок
     * не обходит.
     */
    private fun takeCallOrder(intent: Intent?) {
        val request = callRequestOf(intent) ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        CallRequests.post(CallOrder(request.callId, request.accept))
    }

    /** Наш ли это переход. Чужие ссылки нас не касаются, даже если система их принесла. */
    private fun linkFrom(intent: Intent?): String? =
        intent?.data?.toString()?.takeIf { it.startsWith("tima://link/") }

    /** Код передачи аккаунта: та же схема, другой хост, другой экран. */
    private fun transferFrom(intent: Intent?): String? =
        intent?.data?.toString()?.takeIf { it.startsWith("tima://transfer/") }

    private companion object {
        const val DATABASE_NAME = "tima.db"

        /** Ключ строки оформления в настройках приложения. */
        const val KEY_APPEARANCE = "appearance"

        /** Ключ выбранного языка. Отдельный от темы: это разные решения человека. */
        const val KEY_LANGUAGE = "language"

        /** Ключ памяти о начатой установке. */
        const val KEY_UPDATE = "started"
    }
}
