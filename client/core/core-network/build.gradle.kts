plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// core-network. Пока здесь только состояние связи, перенесённое из v1: это
// единственная часть сетевого слоя, которая измерена в живой мобильной сети, а не
// спроектирована. Транспорт, стратегии маршрутов и Outbox — К3 (Plan.md §3.3).
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
