plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// domain-chat — личные и групповые переписки (Plan.md §2.2).
//
// Правила отправки живут здесь, потому что от них зависит поведение продукта, а не
// работа техники: ключ идемпотентности назначается до первой попытки (иначе повтор
// после обрыва даёт дубль у собеседника), тело собирается один раз и одним кодеком.
// Очередь, упаковка и ключи приходят портами.
kotlin {
    jvmToolchain(17)

    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // Поток, а не список: экран обязан обновляться сам, когда приходит
            // сообщение. Опрос по таймеру давал в v1 и задержку, и лишние пробуждения.
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            // Начало переписки спрашивает справочник по сети — то есть случай
            // использования стал suspend, и проверкам нужен runTest.
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
