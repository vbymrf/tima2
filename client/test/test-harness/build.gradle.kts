plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// test-harness — фейки и сценарии без устройства (Plan.md §2.2, К4.6).
//
// Модуль тестовый, но исходники в commonMain: его потребители — тесты других
// модулей, а тестовые наборы никуда не публикуются.
kotlin {
    jvmToolchain(17)

    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(projects.core.coreOutbox)
            api(projects.core.coreDatabase)
            api(projects.core.coreEncryption)
            api(projects.domain.domainChat)
            implementation(libs.sqldelight.runtime)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.driver.jvm)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.driver.native)
        }
    }
}
