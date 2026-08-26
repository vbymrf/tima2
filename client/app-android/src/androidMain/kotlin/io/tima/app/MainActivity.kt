package io.tima.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import io.tima.core.database.androidDatabase
import io.tima.core.ui.TimaTheme
import io.tima.shared.Entry
import io.tima.shared.Platform
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        code.value = codeFrom(intent)
        setContent {
            val entry = remember { Entry.create(Platform.Android) }
            TimaTheme(dark = isSystemInDarkTheme()) {
                // База телефона: имя файла в песочнице приложения, а не путь. Каталог
                // выбирает Android, и это правильно — он же его и стирает при удалении.
                Root(
                    entry = entry,
                    deviceDatabase = { androidDatabase(applicationContext, DATABASE_NAME) },
                    linkCode = code.value,
                    // Имя и номер разом: имя говорит, что за версия, номер — что
                    // установка действительно сменилась. По одному имени обновление
                    // «2.0.0-dev → 2.0.0-dev» неотличимо от его отсутствия.
                    buildVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        codeFrom(intent)?.let { code.value = it }
    }

    /** Наш ли это переход. Чужие ссылки нас не касаются, даже если система их принесла. */
    private fun codeFrom(intent: Intent?): String? =
        intent?.data?.toString()?.takeIf { it.startsWith("tima://link/") }

    private companion object {
        const val DATABASE_NAME = "tima.db"
    }
}
