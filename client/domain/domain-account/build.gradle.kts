plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// domain-account — личность, устройства, цепочка идентификаторов (Plan.md §2.2).
//
// Зависимостей на технику здесь нет и быть не может: правило «domain ничего не знает
// о транспорте и платформе» проверяется в CI (architecture-tests). Сервер, крипто и
// хранилище приходят портами, а реализуют их модули, владеющие своей техникой, —
// то же направление, что у core-database, реализующего OutboxStore.
kotlin {
    jvmToolchain(17)

    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // Единственная зависимость: suspend-функции портов. Ни Ktor, ни SQLDelight.
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
