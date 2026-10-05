package io.tima.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import io.tima.core.network.NetworkWatches
import io.tima.core.call.desktop.DesktopCallEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.pointer.PointerIcon
import io.tima.core.ui.LocalSplitterIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.awt.Cursor
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.tima.core.database.desktopDatabase
import io.tima.core.diag.Diary
import io.tima.core.diag.DiaryFiles
import io.tima.core.diag.DiaryPolicy
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.feature.shell.ProblemFacts
import io.tima.feature.shell.UpdateMemory
import io.tima.shared.Build
import io.tima.shared.ChannelHost
import io.tima.shared.CallKeep
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
fun main(args: Array<String>) {
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
    // (ПЛАН-(Б)-ОТЛАДКИ.md, Б7).
    val store = reportsStore()
    // Наблюдатель сети — до первого канала: канал читает его при каждом подъёме. Без
    // него смена сети (включили VPN) на ПК не замечалась вовсе (ПЛАН-(ПК)-ЗВОНКОВ-ПК, 2026-09-26).
    // Следит за маршрутом именно до сервера — адрес тот же, что у входа ниже.
    NetworkWatches.current = DesktopNetworkWatch(standHost())
    Thread.setDefaultUncaughtExceptionHandler { _, error ->
        recordCrash(store, error)
    }
    // Звонок и стенд живут у процесса (`CallKeep`, заказчик 2026-09-30, 1а): окно, закрытое в
    // трей, разбирает композицию, а звонок идёт. Поток — окна (Swing), как раньше у `Root`.
    // Своим исполнителем, а не `Dispatchers.Main`: модуля с ним у ПК нет, и 2026-09-30 версия
    // 95 падала при первом окне («Module with the Main dispatcher is missing»).
    CallKeep.scope = CoroutineScope(
        SupervisorJob() + java.util.concurrent.Executor { javax.swing.SwingUtilities.invokeLater(it) }.asCoroutineDispatcher(),
    )
    // Включённый автозапуск — под эту установку: программа могла переехать.
    Autostart.refresh()
    window(store, hidden = Autostart.HIDDEN in args)
}

/**
 * @param hidden запущены автозагрузкой при входе в Windows: окно не показывать, TIMA сразу
 *   в трее — нужен канал, а не окно поверх рабочего стола.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun window(store: ReportsStore, hidden: Boolean) = application {
    // Переменная окружения читается ЗДЕСЬ: на ПК она есть, в общем коде её нет вовсе —
    // `System.getenv` отсутствует на iOS. Адрес по умолчанию — стенд.
    val entry = remember {
        Entry.create(
            platform = Platform.DESKTOP,
            host = standHost(),
        )
    }
    // Окно открывается таким, каким его оставили (заказчик 2026-09-27): размер, место,
    // развёрнуто ли. Ширины полос внутри помнит сам `Root` — в настройках устройства.
    val remembered = remember { WindowMemory.load(File(dataCatalog(), WINDOW_NAME)) }
    val windowState = rememberWindowState(
        placement = if (remembered?.maximized == true) WindowPlacement.Maximized else WindowPlacement.Floating,
        // Планшетный формат по умолчанию: три полосы влезают, и сразу видно, что раскладку
        // решает ширина окна, а не устройство. Окно можно сузить — станет телефонным.
        size = remembered?.size ?: WINDOW_DEFAULT,
        position = remembered?.position ?: WindowPosition.Aligned(Alignment.Center),
    )
    LaunchedEffect(windowState) {
        snapshotFlow { Triple(windowState.size, windowState.position, windowState.placement) }
            // Тянут мышью — событий десятки в секунду, писать файл на каждое незачем.
            .collectLatest {
                delay(WINDOW_SAVE_DELAY_MS)
                WindowMemory.save(File(dataCatalog(), WINDOW_NAME), windowState, WINDOW_DEFAULT)
            }
    }

    // ── ОКНО — ВИД НА ПРОЦЕСС, А НЕ САМ ПРОЦЕСС (У4) ────────────────────────
    //
    // Раньше закрытие окна звало `exitApplication()`: процесса больше нет, канала нет,
    // уведомлений нет. Пока уведомлений не было вовсе, это было честно.
    //
    // Теперь закрытие ПРЯЧЕТ окно, а выход живёт в меню значка. Канал при этом держит
    // `ChannelHost`, а не композиция (У2), — и то, что окно при закрытии разбирается
    // целиком, не беда, а проверка: собранное отдаётся из процесса, и повторное открытие
    // берёт то же самое.
    var windowShown by remember { mutableStateOf(!hidden) }
    var hasTray by remember { mutableStateOf(false) }

    // ── ЗВОНКИ (ПЛАН-(ПК)-ЗВОНКОВ-ПК, маршрут A) ─────────────────────────────────
    //
    // Движок один на процесс, как и сервер FFI внутри библиотеки: создаётся здесь, у
    // окна, а не в `Root`, чтобы пережить закрытие окна вместе с каналом. Библиотеки
    // нет — `null`, и ПК остаётся без звонков честно: «Принять» не показывается (ПК0).
    val callEngine = remember {
        DesktopCallEngine.createOrNull(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }
    DisposableEffect(Unit) {
        hasTray = Tray.install(
            onOpen = { windowShown = true },
            onExit = { Journal.diary.flush(); exitApplication() },
        )
        // Трея нет — спрятанное окно вернуть было бы нечем.
        if (!hasTray) windowShown = true
        onDispose { Tray.remove() }
    }

    // Убранное окно ничего не показывает глазами — значит открытая в нём переписка
    // снова обязана уведомлять (У10). Без этого человек, закрывший окно на переписке,
    // перестал бы получать из неё уведомления до следующего открытия.
    LaunchedEffect(windowShown) { ChannelHost.notices()?.windowVisible(windowShown) }
    // Окно ПК одно на процесс, но прячется в трей и возвращается: для отчёта это разные
    // состояния — «не пришло уведомление» при спрятанном окне и при открытом читаются
    // по-разному (APP-WINDOW, 2026-09-27).
    LaunchedEffect(windowShown) {
        Journal.note(LogCode.APP_WINDOW, if (windowShown) "окно показано" else "окно спрятано в трей")
    }

    // ── ПАДЕНИЕ ВНУТРИ ОКНА ─────────────────────────────────────────────────
    //
    // Исключение в окне Compose забирает сам: окно исчезает, процесс остаётся без окна, в
    // журнале — ни строки, отчёта нет (2026-09-26: так «упала» проверка звука). Теперь оно
    // ложится туда же, куда любое падение, и приложение закрывается честно — висящий
    // процесс без окна к тому же держал замок запускалки, и второй запуск отказывал.
    CompositionLocalProvider(
        // Над разделителем полос — стрелка «влево-вправо», как у любой программы Windows.
        LocalSplitterIcon provides PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)),
        LocalWindowExceptionHandlerFactory provides WindowExceptionHandlerFactory {
            WindowExceptionHandler { error ->
                recordCrash(store, error)
                Journal.diary.flush()
                exitApplication()
            }
        },
    ) {
        Window(
            // Окно ПРЯЧЕТСЯ, а не разбирается: composition-у `application` без единого окна
            // доверять нельзя — он вправе счесть, что показывать больше нечего.
            visible = windowShown,
            onCloseRequest = {
                if (hasTray) {
                    windowShown = false
                } else {
                    // Сброс журнала — последний надёжный повод: дальше процесса не будет, а
                    // записи, не дожившие до сброса пачкой, пропали бы вместе с ним.
                    Journal.diary.flush()
                    exitApplication()
                }
            },
            state = windowState,
            title = "TIMA",
        ) {
            // Число на значке — и в трее, и в панели задач (ПЛАН-(ЖУ)-ЖУРНАЛА-УВЕДОМЛЕНИЙ.md, ЖУ4).
            // Считает общий код по журналу уведомлений, рисует приложение: окно есть только
            // здесь. В панели задач число видно, пока окно открыто или свёрнуто; спрятанное в
            // трей окно кнопки в панели не имеет — там число остаётся на значке трея.
            val frame = window
            androidx.compose.runtime.DisposableEffect(frame) {
                io.tima.core.notify.DesktopBadge.listen { total ->
                    java.awt.EventQueue.invokeLater {
                        Tray.number(total)
                        taskbarBadge(frame, total)
                    }
                }
                onDispose { }
            }
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
                // «Закрыть приложение» в рейке (заказчик 2026-09-26, имя — 2026-09-30):
                // крестик окна прячет в трей, а закрыть совсем раньше можно было только из
                // меню значка.
                onExit = {
                    Journal.note(LogCode.APP_BACKGROUND, "закрыто кнопкой «Закрыть приложение» — фон остановлен")
                    Journal.diary.flush()
                    exitApplication()
                },
                // «Выйти» — то же, что крестик окна: окно в трей, канал и звонки живут.
                // Трея нет — спрятанное окно вернуть нечем, и кнопки нет.
                onLeave = if (hasTray) {
                    {
                        Journal.note(LogCode.APP_BACKGROUND, "выход по кнопке «Выйти» — фон работает")
                        windowShown = false
                    }
                } else {
                    null
                },
                // Версия порождается сборкой из gradle.properties — одна на Android и ПК.
                // До 2026-08-26 десктоп её не знал и показывал «Установлена —»: вопрос
                // «какая версия стоит» задают, когда что-то пошло не так, и остаться без
                // ответа именно в этот момент — худшее время.
                build = Build(name = BUILD_NAME, code = BUILD_CODE, stream = BUILD_STREAM),
                callEngine = callEngine,
                loginStart = Autostart,
            )
        }
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
private const val WINDOW_NAME = "window.txt"
private const val WINDOW_SAVE_DELAY_MS = 500L
private val WINDOW_DEFAULT = DpSize(1100.dp, 820.dp)

/**
 * Адрес стенда: из переменной окружения или по умолчанию. Одним местом — его читают и
 * вход, и наблюдатель сети, и разойтись они не должны.
 */
private fun standHost(): String = System.getenv("TIMA_STAND_HOST")?.takeIf { it.isNotBlank() } ?: Entry.STAND

/**
 * Падение — в журнал и в очередь отчётов, а стек — в консоль запускалки. Одно место для
 * падений процесса и падений внутри окна: разойдясь, они записывали бы разное.
 */
private fun recordCrash(store: ReportsStore, error: Throwable) {
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

/**
 * Число на кнопке окна в панели задач Windows — ЖУ4. Рисуется картинкой поверх значка:
 * текстового числа Windows у своих кнопок не показывает. Ноль — снять.
 */
private fun taskbarBadge(window: java.awt.Window, total: Int) {
    runCatching {
        if (!java.awt.Taskbar.isTaskbarSupported()) return
        val taskbar = java.awt.Taskbar.getTaskbar()
        if (!taskbar.isSupported(java.awt.Taskbar.Feature.ICON_BADGE_IMAGE_WINDOW)) return
        taskbar.setWindowIconBadge(window, if (total <= 0) null else Tray.counted(total, size = 16))
    }
}
