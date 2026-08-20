# Enable Apple targets (iosArm64, iosX64, iosSimulatorArm64)

## What

Uncomments the three Apple targets in `build.gradle.kts`. No source changes.

## Prerequisite

This needs a release of `asia.hombre:keccak` that includes Apple artifacts — see the
companion PR in `KeccakKotlin`. Until that release is on Maven Central, building this
library for Apple targets fails with *"no matching variant"* on the `keccak`
dependency.

`org.kotlincrypto.random:crypto-rand` already publishes Apple artifacts, so no other
dependency is in the way.

## Why

The library already builds for `linuxX64`, `mingwX64` and the three `androidNative*`
targets, so the code is Kotlin/Native-ready. Without Apple artifacts, ML-KEM-768 is
unavailable to any Kotlin Multiplatform project that targets iOS — which in practice
means such projects cannot ship the algorithm at all, since there is currently no
other KMP ML-KEM implementation with Apple support.

## About needing a Mac

Publishing Apple **klibs** does not require macOS: `kotlin.native.enableKlibsCrossCompilation`
defaults to `true` in the Kotlin Gradle Plugin. macOS is needed for *linking* final
Apple binaries and for *running* tests on a simulator.

## Verification

Tests, including the KAT vectors, were run on an iOS simulator using a GitHub-hosted
macOS runner, with a locally published `keccak` that has Apple targets enabled:

```
./gradlew iosSimulatorArm64Test
```

Result: **pass**. (Run log: <вставьте ссылку на прогон>)

The workflow used is included as `verify-apple-targets.yml` if you want the same check
on every push — macOS runners are free for public repositories.
