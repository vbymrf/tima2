plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// СПАЙК К1.4, а не модуль продукта. Единственная задача: выяснить, собираются ли
// зависимости крипто-ядра под iOS — ДО того, как от этого зависит гейт К2.
//
// В v1 крипто было `kotlin("jvm")`, и вопрос «а поедет ли это на iOS» никогда не
// задавался коду. Если ответ «нет», это меняет план, и узнать надо на первой
// неделе, а не через два месяца.
//
// Модуль удаляется, как только ответ получен: смысл спайка в ответе, а не в коде.
kotlin {
    jvmToolchain(17)

    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // NaCl-слой: SecretBox, Box, Ed25519, HKDF. Единственная криптобиблиотека
            // проекта (crypto-invariants). Заявлен мультиплатформенным — проверяем.
            implementation("eu.livotov.labs:kodium:1.0.0")
            // ML-KEM-768 для escrow. Взят вместо сломанной реализации в Kodium
            // (ADR-0005 Поправка-1), заявлена поддержка KMP — проверяем.
            implementation("asia.hombre:kyber:2.0.1")
            // Конверт и тело сообщения: protobuf через Wire. Кодоген в К2, здесь
            // проверяется только рантайм.
            implementation("com.squareup.wire:wire-runtime:5.2.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
