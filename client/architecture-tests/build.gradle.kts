plugins {
    alias(libs.plugins.kotlinJvm)
}

// Архитектурные правила Plan.md §2.2, проверяемые в CI.
//
// ── ПОЧЕМУ НЕ KONSIST ──────────────────────────────────────────────────────────
// Канон (ADR-0001) называет Konsist. Правила здесь написаны обычными тестами по
// двум причинам, и обе временные:
//
// 1. На машине разработки нет JDK и Gradle: каждая опечатка в незнакомом DSL
//    стоит цикл CI на две минуты. Обход правил по файлам не требует угадывания
//    чужого API — он делает ровно то, что написано.
// 2. Ценность здесь в самих правилах и в том, что они падают в CI. DSL, которым
//    они выражены, — вопрос удобства, а не гарантии.
//
// Задача на потом: перенести на Konsist, когда появится возможность
// прогонять Gradle локально. Правила при переносе не меняются.

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // Корень клиента передаётся явно: полагаться на рабочий каталог теста —
    // значит зависеть от того, как его запустили.
    systemProperty("client.root", rootProject.projectDir.absolutePath)
    systemProperty("fixtures.root", layout.projectDirectory.dir("src/test/fixtures").asFile.absolutePath)
    testLogging { showStandardStreams = true }
}
