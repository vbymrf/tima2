plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// core-encryption — граница слоя Data с криптографией (Plan.md §3.6).
//
// Зачем модуль, если messenger-crypto и так библиотека: отправка одного сообщения
// это четыре шага в правильном порядке (содержимое → тело → конверт → protobuf), и
// повторять их в каждом месте — способ однажды сделать в другом порядке. Плюс
// управление ключами устройства: их форма не должна течь дальше этого модуля.
//
// api, а не implementation: типы messenger-crypto (MessageContent, EnvelopeMeta)
// стоят в подписях фасада, и слой Data обязан их видеть. Domain и feature их видеть
// НЕ должны — это проверяют архитектурные правила, а не договорённость.
kotlin {
    jvmToolchain(17)

    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api("io.tima:messenger-crypto")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
