plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// feature-chat — окно переписки (Plan.md §2.2). Пока только Store: интерфейс приезжает
// из дизайн-системы, а признак готовности К4 сформулирован именно через Store —
// «сценарий отправил-обрыв-повтор-доставлено проходит тестом Store без сервера».
//
// Зависимость одна и та же навсегда: слой представления говорит с Domain через случаи
// использования. Ktor, SQLDelight и крипто здесь запрещены архитектурным правилом.
kotlin {
    jvmToolchain(17)

    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(projects.domain.domainChat)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
