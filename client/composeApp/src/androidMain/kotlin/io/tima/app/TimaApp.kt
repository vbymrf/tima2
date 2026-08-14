package io.tima.app

import android.app.Application
import io.tima.app.platform.AndroidAppContext
import io.tima.app.session.initSessionDir

/**
 * Единственное место, где процесс получает контекст и каталог файлов.
 *
 * Точка входа в процесс — не всегда экран: систему устраивает поднять один
 * TimaService после того, как она сама его прибила, или BootReceiver после
 * перезагрузки. Раньше это делала MainActivity, и в процессе без экрана
 * SessionStorage обращался к неинициализированному каталогу.
 */
class TimaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidAppContext.app = applicationContext
        initSessionDir(applicationContext.filesDir)
    }
}
