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
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
