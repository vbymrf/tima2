plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// core-model — доменные типы без поведения. Зависимостей нет вовсе, и это правило,
// а не текущее состояние: как только сюда приезжает Ktor, SQL или Compose, модуль
// перестаёт быть тем, на что могут ссылаться все остальные (Plan.md §3.1).
kotlin {
    jvmToolchain(17)

    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
