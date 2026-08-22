package io.tima.harness

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import io.tima.core.database.androidDatabaseDriver

/**
 * Контекст приложения для харнесса на Android.
 *
 * Тот же приём, что у `AndroidSecrets`: общая подпись [harnessDriver] контекста не
 * знает и знать не должна — на ПК и Apple его нет. Инструментальная проверка отдаёт
 * контекст один раз, до первого обращения.
 */
object AndroidHarness {

    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    internal fun context(): Context = appContext
        ?: error("AndroidHarness.install(context) не вызван: харнессу нужен контекст приложения")
}

/**
 * База в памяти на устройстве: `name = null` — файла не остаётся, как и на других
 * платформах. Настройки соединения задаёт драйвер платформы, общий код их проверяет.
 */
actual fun harnessDriver(): SqlDriver = androidDatabaseDriver(AndroidHarness.context(), name = null)
