rootProject.name = "tima-client"

// ── Порядок репозиториев зависит от окружения, и это не прихоть ───────────────
//
// В сети разработки `dl.google.com` блокируется, поэтому там зеркало обязано идти
// первым — иначе сборка висит на недоступном хосте.
//
// На CI наоборот: зеркало — лишнее звено, и оно уже подводило. Прогон 2026-08-20
// упал с `502 Bad Gateway` от `maven.aliyun.com` при загрузке `apache-18.pom`, и
// это выглядело как поломка кода, хотя код был цел.
//
// Поэтому порядок выбирается по переменной `CI`, которую GitHub Actions задаёт сам.
// Список репозиториев один и тот же — меняется только очередь, то есть кто
// отвечает первым.

pluginManagement {
    repositories {
        if (System.getenv("CI") != null) {
            google()
            gradlePluginPortal()
            mavenCentral()
            maven("https://maven.aliyun.com/repository/google")
        } else {
            maven("https://maven.aliyun.com/repository/google")
            google()
            gradlePluginPortal()
            mavenCentral()
        }
    }
}

dependencyResolutionManagement {
    repositories {
        if (System.getenv("CI") != null) {
            google()
            mavenCentral()
            maven("https://maven.aliyun.com/repository/google")
        } else {
            maven("https://maven.aliyun.com/repository/google")
            google()
            mavenCentral()
        }
    }
}

// Структура модулей — Plan.md §3.1. Модули добавляются по мере надобности:
// правило проекта — «модуль появляется, когда у кода есть второй потребитель
// или другой цикл сборки; до этого — пакет» (Plan.md §2.2 п.5). Пустые модули
// ради полноты схемы дают минуты ожидания Gradle и ноль изоляции.

include(":core:core-model")

// Спайк К1.4 — временный: проверяет, собираются ли зависимости крипто под iOS.
// Ответ получен, блокер назван в модуле; удаляется в К2 (Plan.md К2).
include(":core:spike-ios-deps")

// Архитектурные правила Plan.md §2.2, проверяемые в CI.
include(":architecture-tests")

// Вход для ПК. К1.9: доказать, что тулчейн Compose собирается и окно открывается.
include(":app-desktop")

// Вход для Android. К1.9: доказать, что тулчейн AGP + Compose собирается.
include(":app-android")
