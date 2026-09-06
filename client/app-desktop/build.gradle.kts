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
            implementation(project(":core:core-ui"))
            // Оболочка объявляет порт установщика обновлений, а реализация платформенная
            // и живёт здесь (О3). Ребро явное: приложение поставляет оболочке то, чего
            // она сама не умеет, — так же, как драйвер базы.
            implementation(project(":feature:feature-shell"))
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
// Правило одно на обе платформы (ПЛАН-ОБНОВЛЕНИЯ.md, Р4): у Android номер сборки — это
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

        nativeDistributions {
            // Только MSI. `Exe` от jpackage — это не самораспаковывающийся установщик, а
            // тот же WiX-пакет в обёртке; двух форматов у нас нет смысла раздавать —
            // раздавать надо один и знать его хэш.
            targetFormats(TargetFormat.Msi)
            packageName = "TIMA"
            packageVersion = msiVersion
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
