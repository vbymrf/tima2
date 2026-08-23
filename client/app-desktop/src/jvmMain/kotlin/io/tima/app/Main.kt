package io.tima.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.tima.core.database.desktopDatabase
import io.tima.core.ui.TimaTheme
import io.tima.shared.Вход
import io.tima.shared.Платформа
import io.tima.shared.Корень
import java.io.File

/**
 * Вход для ПК.
 *
 * Здесь и только здесь разрешено знать о платформе как о платформе (Plan.md §1.3): окно,
 * тема системы, каталог данных, драйвер базы. **Всё остальное — в `shared`**, потому что
 * правила поведения платформенными не бывают: копия на платформу это ровно то, из-за чего
 * в v1 Android и Desktop разошлись молча.
 *
 * Файл этим и ценен: он короткий. Стало длинно — значит в него протекло общее.
 */
fun main() = application {
    // Переменная окружения читается ЗДЕСЬ: на ПК она есть, в общем коде её нет вовсе —
    // `System.getenv` отсутствует на iOS. Адрес по умолчанию — стенд.
    val вход = remember {
        Вход.создать(
            платформа = Платформа.ПК,
            host = System.getenv("TIMA_STAND_HOST")?.takeIf { it.isNotBlank() } ?: Вход.СТЕНД,
        )
    }
    val состояниеОкна = rememberWindowState(
        // Планшетный формат по умолчанию: три полосы влезают, и сразу видно, что раскладку
        // решает ширина окна, а не устройство. Окно можно сузить — станет телефонным.
        size = DpSize(1100.dp, 820.dp),
        position = WindowPosition.Aligned(Alignment.Center),
    )

    Window(
        onCloseRequest = ::exitApplication,
        state = состояниеОкна,
        title = "TIMA",
    ) {
        TimaTheme(dark = isSystemInDarkTheme()) {
            Корень(вход) { desktopDatabase(File(каталогДанных(), ИМЯ_БАЗЫ)) }
        }
    }
}

/** `%LOCALAPPDATA%\TIMA` — рядом с секретами, но не вместе с ними. */
private fun каталогДанных(): File {
    val base = System.getenv("LOCALAPPDATA")
        ?: System.getProperty("user.home")
        ?: error("непонятно, где держать данные: ни LOCALAPPDATA, ни user.home")
    return File(base, "TIMA")
}

private const val ИМЯ_БАЗЫ = "tima.db"
