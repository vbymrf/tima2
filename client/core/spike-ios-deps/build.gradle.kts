plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// СПАЙК К1.4, а не модуль продукта. Задача: выяснить, собираются ли зависимости
// крипто-ядра под iOS — ДО того, как от этого зависит гейт К2.
//
// ── ОТВЕТ ПОЛУЧЕН 2026-08-20 ──────────────────────────────────────────────────
//
//   Kodium (NaCl: SecretBox, Box, Ed25519, HKDF)  — iOS ЕСТЬ
//   Wire runtime (protobuf)                        — iOS ЕСТЬ
//   KyberKotlin (ML-KEM-768, escrow)               — iOS НЕТ
//   keccak (SHAKE, зависимость KyberKotlin)        — iOS НЕТ
//
// Проверено листингом Maven Central, а не предположено: у `asia.hombre:kyber`
// опубликованы androidNative, js, jvm, linuxX64, mingwX64, windows — и ни одного
// артефакта Apple. У `keccak` то же самое. Сборка падала на
// «Unresolved platforms: [iosArm64, iosSimulatorArm64]».
//
// Поэтому здесь KyberKotlin вынесен в jvmMain: спайк доказывает, что две из трёх
// зависимостей работают на iOS, а третья названа блокером явно, а не оставлена
// красной сборкой без объяснения. Решение по ML-KEM на iOS — за заказчиком,
// варианты в отчёте doc_mig/отчёты/2026-08-20-К0-К1.md.
kotlin {
    jvmToolchain(17)

    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // NaCl-слой: единственная криптобиблиотека проекта (crypto-invariants).
            // Артефакты iosArm64 / iosSimulatorArm64 / iosX64 опубликованы.
            implementation("eu.livotov.labs:kodium:1.0.0")
            // Конверт и тело сообщения. Артефакты Apple опубликованы.
            implementation("com.squareup.wire:wire-runtime:5.2.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            // ML-KEM-768 для escrow. Только JVM: артефактов Apple у библиотеки нет.
            implementation("asia.hombre:kyber:2.0.1")
        }
    }
}
