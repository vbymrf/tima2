rootProject.name = "messenger-crypto"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        // Только mavenCentral. До 2026-10-29 первым стоял mavenLocal — ради форков
        // KyberKotlin и KeccakKotlin с таргетами Apple; ML-KEM теперь из Kodium 1.1.0
        // (ПЛАН-(КМ)), и форков нет.
        mavenCentral()
    }
}
