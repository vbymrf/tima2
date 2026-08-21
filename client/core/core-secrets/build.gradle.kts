plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// core-secrets — хранилище ключевого материала (Plan.md §3.6, К3.8).
//
// Криптографии здесь нет намеренно: модуль умеет положить и достать байты в
// хранилище платформы, а вывод ключа покоя — дело core-encryption. Обратная связь
// была бы противоестественной: Keychain не знает про HKDF, а HKDF не знает про
// Keychain.
kotlin {
    jvmToolchain(17)

    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            // DPAPI (CryptProtectData) в JDK нет, и JNI-обвязка ради двух вызовов —
            // лишний двоичный артефакт в сборке. jna-platform несёт готовый Crypt32Util.
            implementation(libs.jna.platform)
        }
    }
}
