package io.tima.app

import android.app.Application
import android.os.Build
import io.tima.core.contacts.AndroidContacts
import io.tima.core.diag.Diary
import io.tima.core.diag.DiaryStore
import io.tima.core.diag.Journal
import io.tima.core.secrets.AndroidSecrets
import io.tima.shared.ReportsStore
import io.tima.shared.rememberCrash

/**
 * Точка, где Android отдаёт приложению контекст.
 *
 * Нужна ровно для одного: тем частям, что ходят в системные хранилища, контекст
 * необходим, а общие подписи `platformVault(scope)` и `platformPhoneBook()` о нём не
 * знают и знать не должны — на ПК и на Apple контекста нет. Протаскивать его через все
 * слои ради одной платформы значило бы впустить Android в общий код.
 *
 * Больше здесь ничего не заводится. Граф зависимостей (Koin) приезжает в `shared`
 * вместе с экранами — К5; собирать его здесь заранее значило бы делать из точки входа
 * второй композиционный корень.
 */
class TimaApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Журнал — раньше всего прочего: то, что случится при установке хранилищ, тоже
        // должно быть видно, а прошлые запуски обязаны приехать с диска. На телефоне это
        // важнее, чем на ПК: систему никто не спрашивает, когда она убивает процесс в
        // фоне, и журнал в памяти пропадал бы вместе с ним по нескольку раз в день.
        Journal.replace(Diary(now = { System.currentTimeMillis() }, store = androidDiaryStore(this)))

        // До первого обращения к хранилищу — то есть до всего остального.
        AndroidSecrets.install(this)
        // До первого открытия вкладки «Контакты». Разрешение при этом не спрашивается:
        // контекст нужен, чтобы было чем спросить, когда человек туда дойдёт.
        AndroidContacts.install(this)

        // Падения ловятся здесь, а не в Activity: упасть можно и до её создания, и такой
        // отчёт ценнее прочих — человек в этот момент видит только «приложение
        // остановлено» (ПЛАН-ОТЛАДКИ.md, Б7).
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                rememberCrash(
                    store = androidReportsStore(this),
                    platform = "android",
                    model = Build.MANUFACTURER + " " + Build.MODEL,
                    os = "Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")",
                    build = BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")",
                    stream = BuildConfig.TIMA_STREAM,
                    log = Journal.diary.dump(),
                    error = error,
                )
            }
            // Своего обработчика Android ставит сам, и он делает нужное: показывает
            // системное окно и завершает процесс. Съесть падение значило бы оставить
            // приложение в состоянии, из которого оно уже не выйдет.
            previous?.uncaughtException(thread, error)
        }
    }
}

/**
 * Где телефон держит неотправленные отчёты.
 *
 * `SharedPreferences`, а не база: отчёт нужен и тогда, когда база не открылась — а это как
 * раз тот случай, ради которого всё и делается. Стираются они вместе с приложением, и это
 * правильно: отчёт от удалённого приложения отправлять некому и незачем.
 */
/**
 * Где телефон держит журнал между запусками: файл в песочнице приложения.
 *
 * Файл, а не `SharedPreferences`: там строка на несколько сотен килобайт читается и
 * пишется целиком в основном потоке, и это заметно. Каталог выбирает Android — он же
 * стирает его при удалении приложения, и это правильно: журнал удалённого приложения
 * никому не нужен.
 */
internal fun androidDiaryStore(application: Application): DiaryStore {
    val file = java.io.File(application.filesDir, "журнал.txt")
    return DiaryStore(
        load = { runCatching { file.readText() }.getOrNull() },
        save = { text -> runCatching { file.writeText(text) } },
    )
}

internal fun androidReportsStore(application: Application): ReportsStore {
    val prefs = application.getSharedPreferences("отчёты", android.content.Context.MODE_PRIVATE)
    return ReportsStore(
        load = { prefs.getString("очередь", null) },
        save = { text -> prefs.edit().putString("очередь", text).apply() },
    )
}
