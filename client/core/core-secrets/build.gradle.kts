plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

// core-secrets — хранилище ключевого материала (Plan.md §3.6, К3.8).
//
// Криптографии здесь нет намеренно: модуль умеет положить и достать байты в
// хранилище платформы, а вывод ключа покоя — дело core-encryption. Обратная связь
// была бы противоестественной: Keychain не знает про HKDF, а HKDF не знает про
// Keychain.
//
// Android-таргет заведён не для полноты схемы, а потому что поведение здесь ОБЯЗАНО
// отличаться: на JVM это DPAPI (то есть Windows), а на Android — AndroidKeyStore.
// Без своего таргета Android получил бы jvm-вариант, то есть громкий отказ DPAPI.
kotlin {
    jvmToolchain(17)

    jvm()
    androidTarget()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // Порт регистрации объявлен в domain-account, реализация здесь: слой данных
            // реализует объявленное выше — то же направление, что у core-database с OutboxStore.
            api(projects.domain.domainAccount)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidInstrumentedTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.core)
        }
        jvmMain.dependencies {
            // DPAPI (CryptProtectData) в JDK нет, и JNI-обвязка ради двух вызовов —
            // лишний двоичный артефакт в сборке. jna-platform несёт готовый Crypt32Util.
            implementation(libs.jna.platform)
        }
    }
}

android {
    namespace = "io.tima.core.secrets"
    compileSdk = 35

    defaultConfig {
        // API 26+ — из tech-stack.md, как и у приложения. KeyGenParameterSpec доступен
        // с 23, так что минимум здесь ничем не ограничен сверх общего.
        minSdk = 26
        // Проверки Keystore идут НА УСТРОЙСТВЕ: подменить AndroidKeyStore нечем, и
        // Robolectric его не реализует. Значит либо прогон, либо ничего.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Линт AGP выключен по той же причине, что и в app-android: у него свой встроенный
    // Kotlin, он отстаёт от проектного (2.3.10) и падает на метаданных, а не на коде.
    lint { abortOnError = false }
}

tasks.matching { it.name.startsWith("lint") }.configureEach { enabled = false }
