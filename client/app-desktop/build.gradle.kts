import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// Вход для ПК. Здесь и только здесь разрешено знать о платформе как о платформе
// (Plan.md §1.3): окно, трей, автозапуск, fileовые диалоги.
//
// Здесь же — единственное место, где всё соединяется: секрет устройства из хранилища
// платформы, база с диска, очередь, экраны. Ни один модуль ниже не знает, кто его собрал.
kotlin {
    jvmToolchain(17)
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            // material3 больше не нужен и убран: у нас своя система форм и цветов, и
            // единственным его потребителем был пустой каркас К1.9. Заодно ушло
            // предупреждение об устаревшем аксессоре, которое приходилось терпеть.
            // Всё общее — одним модулем. Платформенного здесь два: драйвер базы и
            // окно.
            implementation(project(":shared"))
            implementation(project(":core:core-database"))
            // Уведомления: значок в трее отдаётся показу отсюда (У4).
            implementation(project(":core:core-notify"))
            implementation(project(":core:core-ui"))
            // Оболочка объявляет порт установщика обновлений, а реализация платформенная
            // и живёт здесь (О3). Ребро явное: приложение поставляет оболочке то, чего
            // она сама не умеет, — так же, как драйвер базы.
            implementation(project(":feature:feature-shell"))
            // Журнал: точка входа ловит падения и кладёт их в очередь отчётов вместе с
            // тем, что человек успел сделать (ПЛАН-(Б)-ОТЛАДКИ.md, Б7).
            implementation(project(":core:core-diag"))
            // Звонки на ПК: движок на livekit-ffi (ПЛАН-(ПК)-ЗВОНКОВ-ПК, маршрут A).
            implementation(project(":core:core-call-desktop"))
            // Движок отдаётся в Root типом контракта — контракт объявлен явно, а не
            // получен переэкспортом (architecture-tests, DependenciesTest).
            implementation(project(":core:core-call"))
            // Наблюдатель сети ПК отдаётся каналу через `NetworkWatches` (core-network).
            implementation(project(":core:core-network"))
            // Маршрут до сервера спрашивается у Windows (iphlpapi) — через JNA.
            implementation(libs.jna)
            // Автозагрузка — значения в реестре Windows (Advapi32Util).
            implementation(libs.jna.platform)
        }

    }
}

// Версия для десктопа: BuildConfig есть только у Android, поэтому крошечный file
// порождается сборкой. Без него десктоп показывал «Установлена —», то есть не мог
// ответить на вопрос, который задают, когда что-то пошло не так.
val versionDir = layout.buildDirectory.dir("generated/tima-version")
// Свойства читаются ЗДЕСЬ, а не внутри задачи: внутри `registering` получатель — сама
// задача, и `property()` ищет у неё, а не у проекта. Ловится только при сборке.
val timaCode = property("tima.versionCode") as String
val timaName = property("tima.versionName") as String
val timaStream = property("tima.stream") as String
val timaVersion by tasks.registering {
    val into = versionDir
    val code = timaCode
    val name = timaName
    val stream = timaStream
    inputs.property("code", code)
    inputs.property("name", name)
    inputs.property("stream", stream)
    outputs.dir(into)
    doLast {
        val file = into.get().file("io/tima/app/Version.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package io.tima.app

            /** Порождается сборкой из gradle.properties. Правится там, а не здесь. */
            internal const val BUILD_NAME: String = "$name"
            internal const val BUILD_CODE: Int = $code
            internal const val BUILD_STREAM: String = "$stream"
            """.trimIndent() + System.lineSeparator(),
        )
    }
}

kotlin.sourceSets.named("jvmMain") { kotlin.srcDir(timaVersion) }

// ── Номер версии для Windows Installer ────────────────────────────────────────
//
// Windows сравнивает ТОЛЬКО три числа `MAJOR.MINOR.BUILD` и суффиксов не понимает:
// «2.0.1-dev» jpackage отвергает при сборке. Поэтому номер выводится из
// `tima.versionCode` — того же счётчика, что растёт при каждой раздаваемой сборке, — а
// человек по-прежнему видит `tima.versionName` на вкладке «Обновление».
//
// Правило одно на обе платформы (ПЛАН-(О)-ОБНОВЛЕНИЯ.md, Р4): у Android номер сборки — это
// `versionCode`, и у ПК он же. Разойдясь, они дали бы «на телефоне 7, на ПК 2» про одну
// и ту же сборку.
val msiVersion = "2.0.$timaCode"

check(timaCode.toInt() in 1..65535) {
    "tima.versionCode=$timaCode не годится для MSI: Windows Installer читает третье число " +
        "как 0…65535, а нулевая версия не ставится вовсе"
}

compose.desktop {
    application {
        mainClass = "io.tima.app.MainKt"

        // ── livekit_ffi.dll — ресурсом приложения ───────────────────────────────
        //
        // Каталог раскладки по ОС (`windows/`) собирает задача `callNatives` модуля
        // core-call-desktop: скачивает библиотеку и сверяет sha256. Compose кладёт её и
        // в MSI, и в запуск `run`, а путь сообщает свойством
        // `compose.application.resources.dir` — его и читает движок.
        nativeDistributions {
            appResourcesRootDir.set(project(":core:core-call-desktop").layout.buildDirectory.dir("livekit-ffi"))
            // Только MSI. `Exe` от jpackage — это не самораспаковывающийся установщик, а
            // тот же WiX-пакет в обёртке; двух форматов у нас нет смысла раздавать —
            // раздавать надо один и знать его хэш.
            targetFormats(TargetFormat.Msi)
            packageName = "TIMA"
            packageVersion = msiVersion

            // ── Модули JVM: без них пакет собирается и падает ────────────────
            //
            // Внутрь пакета кладётся не весь JDK, а обрезанный `jlink`-образ, и режет он
            // по тому, что видит в байт-коде. Отражения он не видит: JDBC-драйвер SQLite
            // достаётся через `DriverManager`, и в первой раздаваемой сборке (версия 3,
            // 2026-09-06) `java.sql` в образ не попал. Приложение вставало, рисовало окно
            // и падало на открытии базы:
            //
            //     java.lang.NoClassDefFoundError: java/sql/DriverManager
            //         at …JdbcSqliteDriver.getConnection
            //         at io.tima.core.database.DesktopDriverKt.desktopDatabase
            //
            // Список — из `gradlew :app-desktop:suggestRuntimeModules`, а не из головы.
            // `jdk.crypto.ec` анализатор находит сам (без него не работает TLS), поэтому
            // здесь его нет; проверять состав образа — `runtime/release`, строка MODULES.
            modules("java.instrument", "java.management", "java.sql", "jdk.unsupported")
            // ── Описание ЛАТИНИЦЕЙ, и это не небрежность ────────────────────
            //
            // Первая сборка упала так: `light.exe … exited with 311 code`. Код 311 у WiX
            // означает строку со знаками, которых нет в кодовой странице базы MSI (1252):
            // кириллица и длинное тире. Задать кодовую страницу через jpackage нельзя —
            // он зовёт WiX сам и своими аргументами.
            //
            // Эта строка видна в свойствах файла и в списке программ Windows; надписи
            // приложения она не касается — там всё по-русски, как и было.
            description = "TIMA secure messenger"
            vendor = "TIMA"
            // Копирайт и лицензия сюда не вписаны намеренно: пустая строка честнее
            // выдуманной. Появятся вместе с решением о лицензии.

            windows {
                // ── Идентификатор продукта. Заводится РАЗ и не меняется никогда ──
                //
                // По нему Windows Installer узнаёт, что новый пакет — это обновление
                // прежнего, а не второе приложение. Смена этого GUID означает две копии
                // TIMA в списке программ, каждая со своим ярлыком.
                //
                // Сгенерирован 2026-09-06 для потока v2. Поток v1 своего не имел —
                // установщика у него не было вовсе.
                upgradeUuid = "6f3d2a41-9e57-4c0b-9b1e-2a7c5d84f012"

                // Установка «для текущего пользователя»: в %LOCALAPPDATA%, без прав
                // администратора. С правами машины обновление просило бы UAC каждый раз,
                // а у части людей их нет вовсе (решение заказчика 2026-09-06, Р1).
                perUserInstall = true

                // Ярлыки: и меню Пуск, и рабочий стол — решение заказчика 2026-09-06.
                menu = true
                menuGroup = "TIMA"
                shortcut = true

                // Выбор каталога у человека не спрашиваем: при установке для
                // пользователя выбирать нечего, а лишний шаг мастера — это лишний шаг,
                // на котором останавливаются.
                dirChooser = false

                // ── Программа ставится НЕ в каталог данных ───────────────────────
                //
                // Поймано проверкой 2026-09-06, а не рассуждением. Без этой строки
                // jpackage кладёт программу в `%LOCALAPPDATA%\<packageName>` — то есть
                // ровно туда, где лежат `tima.db` и `secrets\`. Проверка на живой машине:
                // установка прошла, а `msiexec /x` унёс каталог целиком — **вместе с
                // базой и секретом устройства**. На человеческом языке это значит:
                // «удалил приложение — потерял аккаунт», и узнал бы он об этом после.
                //
                // Каталог данных менять нельзя: он уже у людей (`%LOCALAPPDATA%\TIMA`,
                // `Main.kt`). Поэтому отходит программа.
                installationPath = "TIMA-app"

                iconFile.set(project.file("icons/tima.ico"))
            }
        }
    }
}

// Ресурсы приложения готовятся из каталога, который наполняет чужая задача: без явной
// связи Gradle собрал бы MSI без библиотеки звонков, и ПК молча остался бы без них.
tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(":core:core-call-desktop:callNatives")
}

// ── Установщик с окнами (заказчик 2026-09-26) ─────────────────────────────────
//
// Без выбора папки и без лицензии jpackage собирает MSI без единого окна: он ставит молча
// за секунду и ничего не запускает, и человек видит в этом сбой. Свой интерфейс лежит в
// `msi/main.wxs` (приветствие, ход, «Установка завершена» с «Запустить TIMA») и
// `msi/MsiInstallerStrings_ru.wxl`.
//
// Подсунуть их плагину нельзя: перед вызовом jpackage он очищает свой каталог ресурсов, а
// после — рабочий каталог. Поэтому после `packageMsi` MSI собирается ещё раз, **из готового
// образа приложения** (`createDistributable`, `--app-image`) и со своим каталогом ресурсов:
// ресурсы плагина плюс наш шаблон. Настройки установщика (папка, ярлыки, UUID обновления,
// версия) берутся из аргументов, которые записал плагин, — они так и остаются в одном
// месте, в `nativeDistributions` выше.
val msiTemplates = layout.projectDirectory.dir("msi")
tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>()
    .matching { it.name == "packageMsi" }
    .configureEach {
        dependsOn("createDistributable")
        val templates = msiTemplates.asFile
        val tmp = layout.buildDirectory.dir("compose/tmp").get().asFile
        val image = layout.buildDirectory.dir("compose/binaries/main/app/TIMA").get().asFile
        val wix = wixToolsetDir
        inputs.dir(templates)
        doLast {
            // Аргументы плагина: по строке на слово, значения в кавычках с удвоенной косой.
            val words = File(tmp, "packageMsi.args.txt").readLines()
                .filter { it.isNotBlank() }
                .map { it.trim().removeSurrounding("\"").replace("\\\\", "\\") }
            // Из них — только то, что описывает установщик, а не приложение: приложение уже
            // собрано в образе.
            val keep = setOf(
                "--install-dir", "--win-per-user-install", "--win-shortcut", "--win-menu",
                "--win-menu-group", "--win-upgrade-uuid", "--dest", "--name", "--description",
                "--app-version", "--vendor",
            )
            val installer = mutableListOf<String>()
            var i = 0
            while (i < words.size) {
                val word = words[i]
                val value = words.getOrNull(i + 1)?.takeIf { !it.startsWith("--") }
                if (word in keep) {
                    installer += word
                    if (value != null) installer += value
                }
                i += if (value != null) 2 else 1
            }
            val resources = File(tmp, "msi-ui-resources")
            resources.deleteRecursively()
            File(tmp, "resources").takeIf { it.isDirectory }?.copyRecursively(resources)
            templates.copyRecursively(resources, overwrite = true)
            val dest = File(installer[installer.indexOf("--dest") + 1])
            // Прежний MSI — долой: jpackage не пишет поверх существующего файла.
            dest.listFiles { f -> f.extension == "msi" }?.forEach { it.delete() }
            val jpackage = File(System.getProperty("java.home"), "bin/jpackage.exe")
            val command = listOf(jpackage.absolutePath, "--type", "msi", "--app-image", image.absolutePath,
                "--resource-dir", resources.absolutePath,
                // Ради одного: с ним jpackage подключает к WiX библиотеку окон
                // (WixUIExtension), без которой нашим окнам не на чем стоять. Само окно
                // выбора ярлыков не появится — наш main.wxs на него не ссылается.
                "--win-shortcut-prompt") + installer
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .apply {
                    wix.orNull?.asFile?.let { dir ->
                        environment()["PATH"] = dir.absolutePath + File.pathSeparator + environment()["PATH"].orEmpty()
                    }
                }
                .start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "jpackage с окнами установщика не собрал MSI:\n$output" }
            logger.lifecycle("MSI с окнами установщика: $dest")
        }
    }
