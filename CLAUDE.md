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

### POSIX signal IPC demo

`NativeSignal.kt` + `native-lib.cpp` implement a signal channel between the main process and the `:remote` process (`RemoteService`, declared with `android:process=":remote"` in the manifest). This only works because both processes share the app's UID — `kill`/`sigqueue` to another app's pid fails with `EPERM`.

- The signal used is bionic's `SIGRTMIN` (41 in practice, i.e. `__SIGRTMIN + 9`). Never hardcode a number: bionic reserves `__SIGRTMIN`..`__SIGRTMIN+8` for POSIX timers, debuggerd, profilers, ART and fdtrack, and `SIGRTMIN` already skips them. `SIGQUIT`, `SIGUSR1`, `SIGSEGV` and `SIGBUS` are also claimed by ART — don't reuse them.
- `OnSignal` in `native-lib.cpp` runs in signal context, so it does nothing but `write()` an 8-byte `Packet{sender_pid, value}` to a self-pipe (atomic because it is below `PIPE_BUF`). `nativeWaitOne()` blocks on the read end and is driven by a daemon thread in `NativeSignal.start()`; delivery to Kotlin happens via `NativeSignal.onPacket`.
- Peer discovery: `MainActivity` passes its pid in the bind Intent, and `RemoteService.onBind` announces itself back with `sigqueue`, so the main process learns the remote pid from `siginfo.si_pid`. The service is reached with `bindService` rather than `startService` because a background `startService` from `onCreate` throws `BackgroundServiceStartNotAllowedException`.

### Unix domain socket demo

`LocalIpc.kt` is the counterpart to the signal channel and shows what signals cannot do: arbitrary-length ordered payloads. `LocalIpc.EchoServer` is created in `RemoteService.onCreate` (so it lives in `:remote`) and `MainActivity` drives `LocalIpc.Client` from a `HandlerThread` — the read/write calls block and must never run on the UI thread. Protocol is newline-delimited UTF-8.

**Security:** the socket uses the Linux abstract namespace (`LocalSocketAddress.Namespace.ABSTRACT`), which has no filesystem permissions — any app that guesses the name can connect. `EchoServer.serve` therefore rejects connections whose `peerCredentials.uid` is not our own uid. Keep that check if you extend the protocol.

### Shared memory demo

`ShmIpc.kt` plus the `Messenger` in `RemoteService` share a 1 MiB buffer: `MainActivity.shareMemory` creates a `SharedMemory`, fills it, checksums it, then passes it in a `Message` `Bundle`. Only the fd crosses Binder, so the payload is neither copied nor bounded by the ~1 MB transaction limit. `RemoteService.readSharedMemory` maps it, checksums it and replies via `msg.replyTo` so both sides can be compared.

- `SharedMemory` requires API 27 while `minSdk` is 24, hence the `Build.VERSION.SDK_INT` guard and the disabled button on older devices. Do not switch to `MemoryFile`: getting its fd needs reflection over a blocked non-SDK interface.
- The sender calls `setProtect(OsConstants.PROT_READ)` after unmapping its own writable mapping (order matters), so the receiver can only map read-only — `readSharedMemory` asserts this by attempting `mapReadWrite()` and expecting `EPERM`.
- Every mapping needs `SharedMemory.unmap`, and both processes must `close()` their own `SharedMemory` instance; the fd is duplicated by the transaction.
- This is the only channel that needs a real Binder interface, which is why `onBind` returns `messenger.binder` rather than a bare `Binder`.

### Module layout

- `app/src/main/java/` — Kotlin sources: `MainActivity.kt`, `NativeSignal.kt`, `LocalIpc.kt`, `ShmIpc.kt`, `RemoteService.kt`.
- `app/src/main/cpp/` — native C++ sources and `CMakeLists.txt`, built via `externalNativeBuild { cmake }` in `app/build.gradle.kts`.
- `app/src/main/res/` — resources; the single layout `activity_main.xml` is a vertical `LinearLayout` (`sample_text`, `status`, `send`, `log_scroll`/`log`), bound via ViewBinding as `ActivityMainBinding`.
- `app/src/test/` — local JVM unit tests (`ExampleUnitTest.kt`).
- `app/src/androidTest/` — instrumented tests (`ExampleInstrumentedTest.kt`).

Build artifacts for the native build land under `app/.cxx/` (git-ignored) — not a source directory; don't edit anything there.
