import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties  // в Gradle DSL `java` — расширение плагина, не пакет

plugins {
    kotlin("multiplatform")
    id("com.android.application")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(17)
    androidTarget()
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            // Kodium мультиплатформенный — генерация ключей устройства прямо в common-коде
            implementation("eu.livotov.labs:kodium:1.0.0")
            implementation("io.ktor:ktor-client-core:3.5.1")
            implementation("io.ktor:ktor-client-content-negotiation:3.5.1")
            implementation("io.ktor:ktor-client-websockets:3.5.1")
            implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.1")
        }
        // Общий JVM-код Android+Desktop: конвейер конверта поверх messenger-crypto (JVM-библиотека)
        val jvmCommon by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation("com.squareup.wire:wire-runtime:5.2.1") // классы Envelope/MessageBody из messenger-crypto
            }
        }
        val desktopMain by getting {
            dependsOn(jvmCommon)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("io.ktor:ktor-client-okhttp:3.5.1")
                implementation("io.tima:messenger-crypto:0.1.0") // подменяется composite build
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        androidMain {
            dependsOn(jvmCommon)
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.9.3")
            implementation("androidx.core:core-ktx:1.13.1") // FileProvider для установки обновления
            implementation("io.livekit:livekit-android:2.11.0") // живое медиа звонков/аудио-чатов (WebRTC)
            implementation("io.ktor:ktor-client-okhttp:3.5.1")
            implementation("io.tima:messenger-crypto:0.1.0") {
                // jar zstd-jni не содержит Android-нативов — ниже подключён его AAR
                exclude(group = "com.github.luben", module = "zstd-jni")
            }
            implementation("com.github.luben:zstd-jni:1.5.6-9@aar")
        }
    }
}

// Стабильная подпись APK.
//
// Раньше сборка подписывалась отладочным ключом, а он генерируется НА КАЖДОЙ МАШИНЕ
// свой. Приложение распространяется мимо магазина, поэтому смена машины сборки
// означала «Приложение конфликтует с другим пакетом» у всех, у кого стоит прежняя
// версия: Android не даёт обновить приложение с другой подписью.
//
// Ключ лежит в keystore/ вне гита. Потеря = все пользователи обязаны удалить и
// поставить приложение заново. Бэкапить отдельно.
//
// Файла нет (свежий клон) — собираем отладочной подписью, чтобы сборка не падала.
val keystoreProps = rootProject.file("../keystore/keystore.properties")
val stableSigning = keystoreProps.exists()
val ksProps = Properties().apply {
    if (stableSigning) keystoreProps.inputStream().use { load(it) }
}

android {
    namespace = "io.tima.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0" // установленная версия — AGP не должен докачивать свою

    defaultConfig {
        applicationId = "io.tima.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 13
        versionName = "0.5.6"
        // Только arm64-v8a: реальные телефоны с ~2017. Режет APK (WebRTC/zstd — нативные
        // либы под все ABI). ВНИМАНИЕ: x86_64-эмулятор перестанет работать — тест на телефоне.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }
    if (stableSigning) {
        signingConfigs {
            create("stable") {
                storeFile = rootProject.file("../keystore/tima-release.jks")
                storePassword = ksProps.getProperty("storePassword")
                keyAlias = ksProps.getProperty("keyAlias")
                keyPassword = ksProps.getProperty("keyPassword")
            }
        }
        buildTypes {
            // Распространяем отладочную сборку (release потребовал бы отдельной
            // проверки R8), но подписываем её стабильным ключом.
            getByName("debug") { signingConfig = signingConfigs.getByName("stable") }
            getByName("release") { signingConfig = signingConfigs.getByName("stable") }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        // дубликаты метаданных из bcprov/okhttp при слиянии ресурсов APK
        resources.excludes += setOf(
            "META-INF/versions/**",
            "META-INF/INDEX.LIST",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/DEPENDENCIES",
        )
    }
}

// bcprov подписан: при склейке в uber-jar его *.SF/*.RSA становятся невалидными и JVM
// не стартует (SecurityException: Invalid signature file digest) — подписи в fat-jar не нужны
tasks.withType<Jar>().matching { it.name == "packageUberJarForCurrentOS" }.configureEach {
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.EC")
}

compose.desktop {
    application {
        mainClass = "io.tima.app.MainKt"
        nativeDistributions {
            // MSI требует WiX с GitHub CDN (блокируется) — распространение пока uber-jar
            targetFormats(TargetFormat.AppImage)
            packageName = "TIMA"
            packageVersion = "0.1.0"
        }
    }
}
