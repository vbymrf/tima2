import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.dokka")  //KDocs
    id("maven-publish")
    id("signing")
}

group = "asia.hombre"
version = "2.1.1"
description = "SHA-3 Hash Functions in Kotlin Multiplatform"

val projectName = "keccak"

val mavenDir = projectDir.resolve("maven")


repositories {
    mavenCentral()
}

kotlin {
    jvm()
    js(IR) {
        nodejs()
        browser {

        }
        binaries.executable()
    }
    linuxX64()
    //linuxArm64()
    mingwX64()
    iosArm64()
    iosX64()
    iosSimulatorArm64()
    androidNativeArm32()
    androidNativeArm64()
    androidNativeX64()
    sourceSets {
        val commonTest by getting {
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-test")
            }
        }
    }
}

signing {
    if (project.hasProperty("signing.gnupg.keyName")) {
        useGpgCmd()
        sign(publishing.publications)
    }
}

publishing {
    repositories {
        maven {
            url = mavenDir.toURI()
        }
    }
    publications {
        //Dynamically rename all artifacts
        this.forEach {
            val mavenPublication = it as MavenPublication
            mavenPublication.artifactId = projectName +
                    if(mavenPublication.artifactId.contains("-"))
                        "-" + mavenPublication.artifactId.split("-").last()
                    else
                        ""
        }
    }
    publications.withType<MavenPublication> {
        // Stub javadoc.jar artifact
        artifact(tasks.register("${name}JavadocJar", Jar::class) {
            archiveClassifier.set("javadoc")
            archiveAppendix.set(this@withType.name)
        })

        // Provide artifacts information required by Maven Central
        pom {
            name.set("Keccak Kotlin Multiplatform Library")
            description.set(project.description)
            url.set("https://github.com/ronhombre/KeccakKotlin")

            licenses {
                license {
                    name.set("The Apache Software License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    name.set("Ron Lauren Hombre")
                    email.set("ronlauren@hombre.asia")
                }
            }
            scm {
                url.set("https://github.com/ronhombre/KeccakKotlin")
            }
        }
    }
}

fun parseArtifactId(artifactId: String): String {
    val list = artifactId.splitToSequence("-").map { it.replaceFirstChar(Char::uppercase) }

    return list.joinToString("")
}

fun parseArtifactArchiveName(artifact: MavenPublication): String {
    return artifact.artifactId + "-" + artifact.version + "-bundle.zip"
}

for (publication in publishing.publications.asMap) {
    val artifact = publication.value as MavenPublication
    val parsedArtifactId = parseArtifactId(artifact.artifactId)

    tasks.register<Zip>("bundle$parsedArtifactId") {
        group = "Bundle"
        from(mavenDir)
        val mavenDeepDir = artifact.groupId.replace(".", "/") + "/" + artifact.artifactId
        include("$mavenDeepDir/*/*")
        destinationDirectory = mavenDir
        archiveFileName = parseArtifactArchiveName(artifact)
    }
}

tasks.register("bundleAll") {
    group = "Bundle"
    dependsOn("publish")

    for (publication in publishing.publications.asMap) {
        val artifact = publication.value as MavenPublication

        dependsOn("bundle" + parseArtifactId(artifact.artifactId))
    }
}

// ── Задача публикации на central.sonatype.com удалена в форке TIMA ────────────
//
// В апстриме здесь регистрировалась Exec-задача `publish<Artifact>ToMavenCentral`,
// запускавшая `curl -X POST https://central.sonatype.com/...` с токеном из
// переменной окружения SONATYPE_TOKEN, и задача `publishAllToMavenCentral`,
// которая их все вызывала.
//
// Форк никуда не публикуется: он собирается только `publishToMavenLocal` в ~/.m2
// (см. client/settings.gradle.kts — mavenLocal первым). Правило проекта: в сборке
// не может быть исходящих обращений к чужим серверам. Разбор —
// doc_vnedren/ОТЧЁТ-ПРОВЕРКИ-ВНЕДРЕНИЙ.md, решение заказчика 2026-09-03.
//
// Локальная упаковка (`bundle<Artifact>`, `bundleAll`) оставлена: она пишет zip в
// ./maven и наружу не ходит.

dokka {
    pluginsConfiguration.html {
        footerMessage = "Copyright (c) 2025 Ron Lauren Hombre"
    }

    dokkaPublications.html {
        dokkaSourceSets {
            named("commonMain") {
                perPackageOption {
                    matchingRegex.set(".*")
                }
                reportUndocumented.set(true)
                documentedVisibilities(
                    VisibilityModifier.Public,
                    VisibilityModifier.Protected,
                )
            }
        }
    }
}