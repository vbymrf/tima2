@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJvmCompilation
import java.nio.file.Files

val kmm: String by properties
val keccak: String by properties
val random: String by properties

plugins {
    kotlin("multiplatform") //Kotlin Multiplatform
    id("org.jetbrains.dokka")  //KDocs
    id("maven-publish")
    id("signing") //GPG
}

group = "asia.hombre"
version = "2.0.1"
description = "ML-KEM (NIST FIPS 203) optimized implementation on 100% Kotlin."

val projectName = "kyber"
val baseProjectName = projectName.plus("-").plus(project.version)


val mavenDir = projectDir.resolve("maven")
val mavenBundlingDir = mavenDir.resolve("bundling")
val mavenDeep = "$mavenBundlingDir/" + (project.group.toString().replace(".", "/")) + "/" + version

val npmDir = "./npm"
val npmKotlinDir = "$npmDir/kotlin"

val jarFileName = baseProjectName.plus(".jar")
val jarFullFileName = baseProjectName.plus("-full.jar")
val javadocsFileName = baseProjectName.plus("-javadoc.jar")
val sourcesFileName = baseProjectName.plus("-sources.jar")
val mavenBundleFileName = baseProjectName.plus("-bundle.zip")

repositories {
    // ПРАВКА ФОРКА: mavenLocal первым.
    // В Maven Central у asia.hombre:keccak нет вариантов Apple, и Gradle,
    // найдя там модуль, дальше не пойдёт — падение «no matching variant».
    // Наш keccak с включёнными таргетами Apple лежит в mavenLocal.
    mavenLocal()
    mavenCentral()
}

kotlin {
    jvm {
        @Suppress("unused") val main by compilations.getting {
            compileTaskProvider.configure {
                //Set up the Kotlin compiler options for the 'main' compilation:
                compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
            }

            compileTaskProvider //Get the Kotlin task 'compileKotlinJvm'
            output //Get the main compilation output
        }

        @Suppress("unused") val jvmJar by tasks.getting(org.gradle.jvm.tasks.Jar::class) {
            archiveFileName.set(jarFileName)

            val jvmMainCompilation = kotlin.targets.getByName("jvm").compilations.getByName("main") as KotlinJvmCompilation

            from(jvmMainCompilation.output.allOutputs)
        }

        compilations["test"].runtimeDependencyFiles // get the test runtime classpath
    }
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
        @Suppress("unused") val commonMain by getting {
            dependencies {
                implementation("org.kotlincrypto.random:crypto-rand:$random")
                implementation("asia.hombre:keccak:$keccak")
            }
        }
        @Suppress("unused") val commonTest by getting {
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-test")
            }
        }
        @Suppress("unused") val jvmTest by getting {
            dependencies {
                implementation("org.bouncycastle:bcprov-jdk15to18:1.81")
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
            name.set("Kyber Kotlin Multiplatform Library")
            description.set(project.description)
            url.set("https://github.com/ronhombre/KyberKotlin")

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
                url.set("https://github.com/ronhombre/KyberKotlin")
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
        @Suppress("UnstableApiUsage")
        destinationDirectory = mavenDir
        @Suppress("UnstableApiUsage")
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

tasks.register("packageNPM") {
    val packageSourcePath = projectDir.toPath().resolve("npm.json")
    val packagePath = projectDir.toPath().resolve("npm").resolve("package.json")

    var packageFile = String(Files.readAllBytes(packageSourcePath))
    packageFile = packageFile.replace("<VERSION>", version.toString()).replace("<DESCRIPTION>", project.description.toString())
    Files.write(packagePath, packageFile.toByteArray())
}

tasks.register<Copy>("bundleNPM") {
    dependsOn("jsBrowserProductionWebpack", "packageNPM")

    from(buildDir.resolve("js").resolve("packages").resolve(project.name).resolve("kotlin"))
    into(npmKotlinDir)

    doFirst {
        delete(npmKotlinDir)
        mkdir(npmKotlinDir)
    }
}

dokka {
    pluginsConfiguration.html {
        @Suppress("UnstableApiUsage")
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