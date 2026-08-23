package io.tima.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.remember
import io.tima.core.database.androidDatabase
import io.tima.core.ui.TimaTheme
import io.tima.shared.Вход
import io.tima.shared.Платформа
import io.tima.shared.Корень

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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val вход = remember { Вход.создать(Платформа.Андроид) }
            TimaTheme(dark = isSystemInDarkTheme()) {
                // База телефона: имя файла в песочнице приложения, а не путь. Каталог
                // выбирает Android, и это правильно — он же его и стирает при удалении.
                Корень(вход) { androidDatabase(applicationContext, ИМЯ_БАЗЫ) }
            }
        }
    }

    private companion object {
        const val ИМЯ_БАЗЫ = "tima.db"
    }
}
