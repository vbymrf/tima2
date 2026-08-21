plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// core-outbox — жизненный цикл исходящих (Plan.md §3.1, выход этапа К3).
//
// Зависимостей нет намеренно, и это не аскеза. Свойства «не теряет» и «не
// дублирует» проверяются убийством процесса в каждом состоянии; с базой такой тест
// превращается в тест на SQLite, а с корутинами — в тест на планировщик. Машина
// синхронная, часы инъектируются, хранилище — интерфейс.
kotlin {
    jvmToolchain(17)

    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // Порт отправки объявлен в domain-chat, и Outbox его реализует: подпись
            // enqueue совпала дословно — правило продукта и было списано с очереди.
            api(projects.domain.domainChat)
            // Корутины нужны ТОЛЬКО насосу: сами машины состояний синхронные и
            // остаются такими. Ограничение одновременных отправок и паузы между
            // попытками без планировщика не сделать.
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
