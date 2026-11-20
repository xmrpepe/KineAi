# KineAi - a fully local coding assistant for the Pixel 9

Chat with an LLM, review its suggested edits as diffs, browse code, use Git and a terminal - with the model
running **on the phone** through [Kinetic](https://github.com/Veloresearch/kinetic) v0.1.0, ported to Android
arm64. No remote inference, no agents, no API keys. The workspace look and terminal emulator derive from
[AndCode](https://github.com/yuga-hashimoto/and-code) v1.2.29.

> **Status: built and unit-tested; not yet run on a Pixel 9.** The engine is verified on emulated ARM64 and the
> app logic on a JVM; the APK has not been installed on any device. Read [docs/VALIDATION.md](docs/VALIDATION.md)
> before trusting any claim here, and [docs/ANDROID_PORT.md](docs/ANDROID_PORT.md) for the port audit, patches
> and limitations (notably: **CPU only, no Vulkan**).

## What it does

* **Chat** with a local GGUF model: streamed answers, cancellation, many saved conversations, live model /
  RAM / offline status. Context is trimmed to fit; you are told when older turns are left out.
* **Code help** as text only. When the model proposes a file change you see a diff and tap **Apply** or
  **Reject**; **Undo** restores the previous content. Paths that escape the project are refused. There are no
  tool loops, no auto-run commands and no unattended edits.
* **Workspace**: projects (new / import a folder you pick / clone by URL), syntax-highlighted file viewer,
  **Git** status/diff/staged diff/log/commit (JGit, offline), and a **terminal** that runs only what you type.
  Landscape/wide screens show chat and workspace side by side.
* **Models** page: installed models (load, unload, rename, inspect, benchmark, delete), Hugging Face search with
  GGUF/size/quantization/architecture/license filters, results grouped **Compatible / May run with limits /
  Unsupported** from the engine's real capability tables plus your free RAM and storage, per-file detail pages
  (exact file, size, memory estimate, hash), resumable downloads with pause/retry, space checks, SHA-256
  verification against Hugging Face's published hash, and cleanup of partial files.
* **First run**: explains storage, imports or downloads a model, and has the model generate on the device
  before chat opens.
* **Diagnostics**: compute device in use, CPU features, GPU status and reason, prompt tok/s, generation
  tok/s, load time, peak memory, battery and thermal trend, and a repeatable benchmark.
* **Settings**: model, context size (with a memory warning), CPU threads, compute device, sampling; backup and
  restore of conversations.

## Privacy

Inference, chats and project files never leave the device. The network is used only for Hugging Face search /
downloads and a clone you explicitly request. The shipped `libkinetic_android.so` imports no socket, DNS or TLS
functions (checked in CI). Models, conversations and projects live in three separate directories; deleting a
model deletes neither chats nor code. Backup includes conversations and projects, not models.

## Build

Requirements: JDK 17, Android SDK (platform 35, build-tools 35), **NDK r27c** (`27.2.12479018`), Rust via
rustup (the pinned 1.96.1 is selected automatically) with `rustup target add aarch64-linux-android`.

```sh
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018
./gradlew :app:testDebugUnitTest          # app logic tests
./gradlew :app:assembleRelease            # builds the Rust engine, then the APK
# -> app/build/outputs/apk/release/app-release.apk   (arm64-v8a only)
adb install -r app/build/outputs/apk/release/app-release.apk
```

Gradle runs `cargo build --release --target aarch64-linux-android -p kinetic-android` in
`third_party/kinetic` and packages `libkinetic_android.so`. Sign a real release by exporting
`KINEAI_STORE_FILE`, `KINEAI_STORE_PASSWORD`, `KINEAI_KEY_ALIAS`, `KINEAI_KEY_PASSWORD`; otherwise the release
APK is signed with the debug key. If a download proxy rate-limits you (HTTP 429), add `--no-parallel
--max-workers=1` and retry.

Engine checks without a phone (need `qemu-user gcc-aarch64-linux-gnu libc6-arm64-cross` and a small GGUF):

```sh
scripts/e2e-qemu.sh   /path/to/SmolLM2-135M-Instruct-Q4_K_M.gguf   # NEON vs scalar, byte-identical
scripts/jvm-jni-test.sh /path/to/SmolLM2-135M-Instruct-Q4_K_M.gguf  # JNI glue on a desktop JVM
(cd third_party/kinetic && QEMU_CPU=max cargo test --release --target aarch64-unknown-linux-gnu -p kinetic-cpu)
```

CI (`.github/workflows/build.yml`) runs all of the above, builds the APKs, and checks the no-network property.

## Layout

```
app/                     Kotlin + Compose app (package com.kineai.local)
third_party/kinetic/     Kinetic v0.1.0 + Android patches (crates/kinetic-android = JNI bridge)
docs/                    ANDROID_PORT.md, VALIDATION.md
licenses/, THIRD_PARTY_NOTICES.md
scripts/, native/jvm-harness/
dist/                    prebuilt release APK + SHA-256 (debug-signed)
```

## Roadmap / known gaps

Vulkan validation on Mali; NEON kernels for Q5_K/Q2_K/Q3_K/IQ; quantized KV cache (the f32 cache is the main
memory cost); a PTY terminal; on-device measurement to replace the conservative default settings.
