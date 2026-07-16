rootProject.name = "tima-client"

pluginManagement {
    repositories {
        // dl.google.com (Google Maven) в этой сети блокируется — берём полный зеркальный репозиторий
        maven("https://maven.aliyun.com/repository/google")
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        mavenCentral()
        maven("https://jitpack.io") // audioswitch (форк davidliu) — транзитив livekit-android
    }
}

// Крипто-SDK фазы 1: io.tima:messenger-crypto подменяется на живой проект (composite build)
includeBuild("../messenger-crypto")

include(":composeApp")
