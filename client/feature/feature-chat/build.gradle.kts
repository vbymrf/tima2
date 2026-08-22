plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// feature-chat — окно переписки (Plan.md §2.2): Store и экран.
//
// Экран — чистый рендер состояния: он ничего не считает и никуда не обращается, всё
// решение принято в Store. Это признак готовности К5, и проверяется он снимками.
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
            // Дизайн-система: экран собирается из её деталей и своих цветов не имеет.
            implementation(projects.core.coreUi)
            implementation(compose.runtime)
            implementation(compose.foundation)
            // Время сообщения — kotlinx-datetime: java.time запрещён архитектурным
            // правилом, и не зря — на iOS его нет.
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        // Снимки экрана: те же, что у дизайн-системы, и тем же способом.
        jvmTest.dependencies {
            implementation(projects.test.testUi)
        }
    }
}
