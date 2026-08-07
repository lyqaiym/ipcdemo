# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

A minimal single-module Android app (`:app`) that demonstrates the JNI (Java Native Interface) bridge between Kotlin and C++. It is the Android Studio "Native C++" template project. The app calls a native method implemented in C++ and displays the returned string in a `TextView`.

## Build system

- Gradle with **Kotlin DSL** (`*.gradle.kts`). Android Gradle Plugin 9.3.0, Gradle 9.5.0.
- Versions of all dependencies and the AGP plugin are managed in the version catalog `gradle/libs.versions.toml`.
- `gradle/wrapper/gradle-wrapper.properties` points the wrapper at a **Tencent mirror** (`mirrors.cloud.tencent.com`) instead of `services.gradle.org` — the wrapper distribution URL must stay that way for CI/local builds to work in this environment.
- SDK config in `app/build.gradle.kts`: `compileSdk` 37, `minSdk` 24, `targetSdk` 37, Java 11 compatibility, ViewBinding enabled.

## Common commands

From the repo root (use `./gradlew`, not `gradle`):

- Build the debug APK: `./gradlew assembleDebug`
- Install and run on a connected device/emulator: `./gradlew installDebug`
- Run all local unit tests (JVM, in `app/src/test`): `./gradlew test`
- Run a single local unit test: `./gradlew test --tests "com.example.ipcdemo.ExampleUnitTest"`
- Run instrumented tests on a device (in `app/src/androidTest`): `./gradlew connectedAndroidTest`
- Clean build outputs: `./gradlew clean`

## Code architecture

### The JNI bridge (the core of this app)

Kotlin → C++ flow spans three files, and changing any one requires updating the others:

1. `app/src/main/java/com/example/ipcdemo/MainActivity.kt` declares the native entry point as `external fun stringFromJNI(): String`, and its `companion object` `init` block loads the shared library via `System.loadLibrary("ipcdemo")`.
2. `app/src/main/cpp/native-lib.cpp` implements the JNI symbol whose name is derived from the fully-qualified Java/Kotlin class and method: `Java_com_example_ipcdemo_MainActivity_stringFromJNI`. It is an `extern "C"` JNIEXPORT function.
3. `app/src/main/cpp/CMakeLists.txt` declares the shared library target named `ipcdemo` (`add_library(${CMAKE_PROJECT_NAME} SHARED native-lib.cpp)`) and links `android` and `log` system libraries.

**Invariant:** the CMake library name (`ipcdemo`), the `System.loadLibrary("ipcdemo")` argument, and the JNI symbol prefix (`Java_com_example_ipcdemo_MainActivity_...`) must stay in sync. Adding a new native method means adding an `external` function in Kotlin and a matching `Java_<package-with-underscores>_<ClassName>_<methodName>` symbol in C++.

### Module layout

- `app/src/main/java/` — Kotlin sources (only `MainActivity.kt`).
- `app/src/main/cpp/` — native C++ sources and `CMakeLists.txt`, built via `externalNativeBuild { cmake }` in `app/build.gradle.kts`.
- `app/src/main/res/` — resources; the single layout `activity_main.xml` uses a ConstraintLayout with a `TextView` id `sample_text` (bound via ViewBinding as `ActivityMainBinding`).
- `app/src/test/` — local JVM unit tests (`ExampleUnitTest.kt`).
- `app/src/androidTest/` — instrumented tests (`ExampleInstrumentedTest.kt`).

Build artifacts for the native build land under `app/.cxx/` (git-ignored) — not a source directory; don't edit anything there.
