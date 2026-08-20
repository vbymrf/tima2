rootProject.name = "tima-client"

pluginManagement {
    repositories {
        // dl.google.com блокируется в сети разработки, поэтому зеркало идёт первым.
        // google() оставлен запасом: на CI и в других сетях он доступен и свежее.
        maven("https://maven.aliyun.com/repository/google")
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
    }
}

// Структура модулей — Plan.md §3.1. Модули добавляются по мере надобности:
// правило проекта — «модуль появляется, когда у кода есть второй потребитель
// или другой цикл сборки; до этого — пакет» (Plan.md §2.2 п.5). Пустые модули
// ради полноты схемы дают минуты ожидания Gradle и ноль изоляции.

include(":core:core-model")

// Спайк К1.4 — временный: проверяет, собираются ли зависимости крипто под iOS.
// Удаляется в К2 вместе с получением ответа (Plan.md К2).
include(":core:spike-ios-deps")
