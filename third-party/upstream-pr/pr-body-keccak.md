# Enable Apple targets (iosArm64, iosX64, iosSimulatorArm64)

## What

Uncomments the three Apple targets in `build.gradle.kts`. No source changes.

## Why

The library already builds for `linuxX64`, `mingwX64` and the three `androidNative*`
targets, so the code is Kotlin/Native-ready — the Apple targets were simply switched
off. Consumers that target iOS currently cannot use `asia.hombre:keccak` at all:
resolution fails with *"no matching variant"* because no Apple artifacts are
published.

## About needing a Mac

Publishing Apple **klibs** does not require macOS: the Kotlin Gradle Plugin property
`kotlin.native.enableKlibsCrossCompilation` defaults to `true`, so the artifacts can
be built on Linux or Windows.

What does require macOS is *linking* final Apple binaries and *running* tests on a
simulator. I suspect that is why these targets (and `linuxArm64`) are commented out —
not because they fail to build, but because they could not be verified.

## Verification

I ran the test suite on an iOS simulator using a GitHub-hosted macOS runner:

```
./gradlew iosSimulatorArm64Test
```

Result: **pass**. (Run log: <вставьте ссылку на прогон>)

To keep that verification permanently, and for free, you can add the workflow from
`verify-apple-targets.yml` — GitHub-hosted macOS runners are free for public
repositories.

## Note for KyberKotlin

`KyberKotlin` depends on this library as a regular artifact, so its own Apple targets
can only be enabled after a release of `keccak` that includes them. A separate PR for
that repository is ready and will be opened once this one is released.
