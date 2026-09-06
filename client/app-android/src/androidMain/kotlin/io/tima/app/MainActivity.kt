package io.tima.app

import android.content.Intent
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import io.tima.core.contacts.AndroidContactsAccess
import androidx.activity.compose.setContent
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
        Journal.note(LogCode.APP_BACKGROUND, "ушли в фон")
        Journal.diary.flush()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        // Только после настоящего ухода в фон: первый `onStart` идёт сразу за запуском, и
        // «вернулись» рядом с `APP-START` было бы неправдой и лишней строкой.
        if (wasBackground) Journal.note(LogCode.APP_FOREGROUND, "вернулись из фона")
    }

    /** Был ли уже уход в фон — см. [onStart]. */
    private var wasBackground = false

    override fun onDestroy() {
        // Удержанная активность — это утечка целого экрана. Отдаём именно себя:
        // новое окно Android умеет создать раньше, чем доломает старое, и без этого
        // уходящее обнуляло бы ссылку на живое.
        AndroidContactsAccess.detach(this)
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
        AndroidContactsAccess.answered(requestCode, grantResults)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Разрешение на контакты спрашивает ОКНО, а не приложение: контекст от
        // Application для системного диалога не годится (Д3).
        AndroidContactsAccess.attach(this)
        code.value = linkFrom(intent)
        transfer.value = transferFrom(intent)
        setContent {
            val entry = remember { Entry.create(Platform.Android) }
            // Тема здесь больше не решается: её выбирает человек в настройках, и
            // держит выбор `Root`. Платформе осталось только место для хранения строки.
            //
            // База телефона: имя файла в песочнице приложения, а не путь. Каталог
            // выбирает Android, и это правильно — он же его и стирает при удалении.
            Root(
                entry = entry,
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        linkFrom(intent)?.let { code.value = it }
        transferFrom(intent)?.let { transfer.value = it }
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

        /** Ключ памяти о начатой установке. */
        const val KEY_UPDATE = "started"
    }
}
