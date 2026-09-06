plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

// core-diag — журнал приложения (ПЛАН-ОТЛАДКИ.md, срез Б1).
//
// Отдельный модуль, а не пакет в core-model: у журнала потребителей сразу трое —
// сеть пишет вызовы, оболочка пишет переходы, точки входа пишут падения, — и все
// три лежат в разных слоях. В core-model ему не место: там доменные типы без
// поведения, а журнал — инфраструктура с состоянием.
//
// Зависимость ровно одна — время. Часы при этом инъектируются: журнал, который сам
// зовёт «сейчас», нельзя проверить на вытеснение по времени, не подождав сутки.
kotlin {
    jvmToolchain(17)

    jvm()
    androidTarget()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "io.tima.core.diag"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
