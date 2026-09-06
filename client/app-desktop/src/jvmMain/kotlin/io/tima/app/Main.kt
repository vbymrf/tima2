package io.tima.app

import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.tima.core.database.desktopDatabase
import io.tima.core.diag.Diary
import io.tima.core.diag.DiaryFiles
import io.tima.core.diag.DiaryPolicy
import io.tima.core.diag.Journal
import io.tima.feature.shell.ProblemFacts
import io.tima.feature.shell.UpdateMemory
import io.tima.shared.Build
import io.tima.shared.ReportsStore
import io.tima.shared.rememberCrash
import io.tima.shared.Entry
import io.tima.shared.Platform
import io.tima.shared.AppearanceStore
import io.tima.shared.Root
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
fun main() {
    // Журнал поднимается первым — раньше окна и раньше обработчика падений. Всё, что
    // случится дальше, обязано в него попасть, а прошлые запуски — приехать с диска:
    // жалуются обычно после перезапуска, и журнал, начинающийся с этого запуска,
    // рассказывает про всё, кроме поломки.
    Journal.replace(
        Diary(
            now = { System.currentTimeMillis() },
            files = diaryFiles(),
            policy = DiaryPolicy.read(runCatching { File(dataCatalog(), DIARY_POLICY).readText() }.getOrNull()),
        ),
    )

    // Падения ловятся ДО того, как поднято окно: упасть можно и на сборке окружения, и
    // такой отчёт ценнее прочих — человек в этот момент видит только исчезнувшее окно
    // (ПЛАН-ОТЛАДКИ.md, Б7).
    val store = reportsStore()
    Thread.setDefaultUncaughtExceptionHandler { _, error ->
        runCatching {
            rememberCrash(
                store = store,
                platform = "windows",
                model = System.getProperty("os.name").orEmpty(),
                os = System.getProperty("os.name").orEmpty() + " " + System.getProperty("os.version").orEmpty(),
                build = BUILD_NAME,
                stream = BUILD_STREAM,
                log = Journal.diary.dump(),
                error = error,
            )
        }
        error.printStackTrace()
    }
    window(store)
}

private fun window(store: ReportsStore) = application {
    // Переменная окружения читается ЗДЕСЬ: на ПК она есть, в общем коде её нет вовсе —
    // `System.getenv` отсутствует на iOS. Адрес по умолчанию — стенд.
    val entry = remember {
        Entry.create(
            platform = Platform.DESKTOP,
            host = System.getenv("TIMA_STAND_HOST")?.takeIf { it.isNotBlank() } ?: Entry.STAND,
        )
    }
    val windowState = rememberWindowState(
        // Планшетный формат по умолчанию: три полосы влезают, и сразу видно, что раскладку
        // решает ширина окна, а не устройство. Окно можно сузить — станет телефонным.
        size = DpSize(1100.dp, 820.dp),
        position = WindowPosition.Aligned(Alignment.Center),
    )

    Window(
        // Закрытие — последний надёжный повод сбросить журнал: дальше процесса не будет,
        // а записи, не дожившие до сброса пачкой, пропали бы вместе с ним.
        onCloseRequest = { Journal.diary.flush(); exitApplication() },
        state = windowState,
        title = "TIMA",
    ) {
        // Тема здесь больше не решается: её выбирает человек в настройках, и держит
        // выбор `Root`. Платформе осталось только место для хранения строки.
        Root(
            entry = entry,
            // Имя файла приходит готовым: правило именования — общее (Д11), каталог —
            // платформенный, и это единственное, что здесь платформенного.
            deviceDatabase = { name -> desktopDatabase(File(dataCatalog(), name)) },
            appearanceStore = appearanceStore(),
            // Что ПК знает о себе для отчёта о проблеме. Модель здесь — имя системы:
            // «производителя» у ПК нет, а различать сборки Windows иногда приходится.
            facts = ProblemFacts(
                platform = "windows",
                model = System.getProperty("os.name").orEmpty(),
                os = System.getProperty("os.name").orEmpty() + " " + System.getProperty("os.version").orEmpty(),
            ),
            reportsStore = store,
            diaryPolicy = diaryPolicyStore(),
            // Начатая установка помнится файлом рядом с базой: MSI закрывает приложение,
            // и спросить у самих себя, чем всё кончилось, потом будет некого.
            updateMemory = updateMemory(),
            // Обновление ставит платформа: скачать, сверить хэш, позвать msiexec.
            // Приложение при этом закрывается — MSI не заменит файлы работающей
            // программы, и Windows вместо установки предложила бы перезагрузку.
            installer = DesktopInstaller(),
            onLeaving = { Journal.diary.flush(); exitApplication() },
            // Версия порождается сборкой из gradle.properties — одна на Android и ПК.
            // До 2026-08-26 десктоп её не знал и показывал «Установлена —»: вопрос
            // «какая версия стоит» задают, когда что-то пошло не так, и остаться без
            // ответа именно в этот момент — худшее время.
            build = Build(name = BUILD_NAME, code = BUILD_CODE, stream = BUILD_STREAM),
        )
    }
}

/**
 * Где ПК держит неотправленные отчёты: файл рядом с базой.
 *
 * Не база: отчёт нужен и тогда, когда база не открылась, — а это как раз тот случай,
 * ради которого всё и делается.
 */
private fun reportsStore(): ReportsStore {
    val file = File(dataCatalog(), REPORTS_NAME)
    return ReportsStore(
        load = { runCatching { file.readText() }.getOrNull() },
        save = { text ->
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(text)
            }
        },
    )
}

/**
 * Где ПК помнит начатую установку: файл рядом с базой.
 *
 * Одна строка: номер версии, имя и примечание. Читается один раз при запуске и сразу
 * стирается — см. `UpdateStore.rememberedOutcome`.
 */
private fun updateMemory(): UpdateMemory {
    val file = File(dataCatalog(), UPDATE_NAME)
    return UpdateMemory(
        load = { runCatching { file.readText() }.getOrNull() },
        save = { text ->
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(text)
            }
        },
    )
}

/**
 * Где ПК держит журнал: каталог рядом с базой, **файл на день**.
 *
 * Обычные текстовые файлы, а не база и не сжатый формат: их открывают, когда всё
 * остальное уже не работает, и открывать их должно быть нечем — блокнотом.
 *
 * Ошибки чтения и записи гасятся: журнал — не то, ради чего стоит не пускать человека в
 * переписку. Не прочиталось — начнём с пустого; не записалось — часть строк не переживёт
 * перезапуск.
 */
private fun diaryFiles(): DiaryFiles {
    val catalog = File(dataCatalog(), DIARY_CATALOG)
    fun day(name: String) = File(catalog, name + ".txt")
    return DiaryFiles(
        // Имена дней достаются из имён файлов: отдельный указатель разошёлся бы с тем,
        // что на диске, ровно в тот раз, когда запись оборвалась.
        days = {
            catalog.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".txt") }
                ?.map { it.name.removeSuffix(".txt") }
                .orEmpty()
        },
        append = { name, text ->
            catalog.mkdirs()
            day(name).appendText(text)
        },
        read = { name -> runCatching { day(name).readText() }.getOrNull() },
        remove = { name -> day(name).delete() },
        size = { name -> day(name).length() },
    )
}

/** Где ПК держит выбранный срок хранения журнала: строка рядом с оформлением. */
private fun diaryPolicyStore(): AppearanceStore {
    val file = File(dataCatalog(), DIARY_POLICY)
    return AppearanceStore(
        load = { runCatching { file.readText() }.getOrNull() },
        save = { text ->
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(text)
            }
        },
    )
}

/**
 * Где ПК хранит оформление: файл рядом с базой.
 *
 * Не база: выбранная тема нужна раньше, чем база открывается, — в неё уже завёрнут
 * экран входа. Не `java.util.prefs`: тот кладёт значения в реестр Windows, то есть в
 * место, которое не уносится вместе с каталогом данных и переживает переустановку —
 * а «переустановил, а тема прежняя» человек читает как поломку.
 *
 * Ошибки чтения и записи гасятся: оформление — не то, ради чего стоит не пускать
 * человека в переписку. Не прочиталось — тема по умолчанию; не записалось — выбор
 * доживёт до перезапуска.
 */
private fun appearanceStore(): AppearanceStore {
    val file = File(dataCatalog(), APPEARANCE_NAME)
    return AppearanceStore(
        load = { runCatching { file.readText() }.getOrNull() },
        save = { text ->
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(text)
            }
        },
    )
}

/** `%LOCALAPPDATA%\TIMA` — рядом с секретами, но не вместе с ними. */
private fun dataCatalog(): File {
    val base = System.getenv("LOCALAPPDATA")
        ?: System.getProperty("user.home")
        ?: error("непонятно, где держать данные: ни LOCALAPPDATA, ни user.home")
    return File(base, "TIMA")
}

/**
 * Имена того, что приложение кладёт на диск, — **латиницей**.
 *
 * Решение заказчика 2026-09-06, и это продолжение правила про имена в коде: кириллица в
 * пути ломается о кодировку консоли, о `git status` с восьмеричными кодами и о инструменты,
 * которым этот путь приходится передавать при разборе. Читает эти файлы не человек с
 * улицы, а тот, кто чинит.
 */
private const val DATABASE_NAME = "tima.db"
private const val APPEARANCE_NAME = "appearance.txt"
private const val REPORTS_NAME = "reports.json"
private const val DIARY_CATALOG = "logs"
private const val DIARY_POLICY = "logs-policy.txt"
private const val UPDATE_NAME = "update.txt"
