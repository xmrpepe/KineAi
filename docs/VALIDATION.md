# What has and has not been verified

**No physical Pixel 9 was available.** Nothing below was run on a Pixel 9, and no APK was installed on any
Android device or emulator (the build host has no KVM and no ARM64 Android emulator). Everything in the
"not verified" section is untested on hardware and should be treated as unproven until someone runs it.

## Verified (and where)

| Claim | How | Environment |
|---|---|---|
| Kinetic v0.1.0 cross-compiles for `aarch64-linux-android` | `cargo build --target aarch64-linux-android` | Linux x86-64 host, NDK r27c, Rust 1.96.1 |
| Real GGUF inference works on ARM64 CPU code | `kinetic run` of SmolLM2-135M Q4_K_M; coherent correct answer | qemu-user AArch64 (emulation) |
| NEON kernels equal the scalar reference bit for bit | `kdot::neon::tests::neon_matches_scalar` (random + worst-case blocks, several sizes) + all 38 `kinetic-cpu` tests | qemu AArch64 `-cpu max` |
| Dispatch falls back correctly without dotprod | same tests, `QEMU_CPU=cortex-a72` | qemu |
| NEON and scalar produce byte-identical text end to end | `scripts/e2e-qemu.sh` (greedy, 60 tokens) | qemu |
| JNI glue: load, inspect, streaming, cancel, recovery after cancel, error → exception, stale handle → exception | `scripts/jvm-jni-test.sh` | desktop JVM + **x86-64** build of the same crate (tests the glue, not ARM) |
| Packaged `.so` is arm64-v8a, 16 KB-aligned, imports no networking symbols | `readelf`, `scripts/check-no-network.sh` | built APK |
| App logic: edit parsing, path-escape refusal, line diff, compatibility classification, f32-KV memory formula, conversation persistence, backup round-trip, model deletion leaves chats | 10 JVM unit tests (`LogicTest`) | JVM |
| APK builds, R8 keeps the JNI class, v2 signature valid | `assembleRelease`, `apkanalyzer`, `apksigner` | Linux |
| Hugging Face search/tree endpoints return the fields the client parses | live `curl` | network |

## Measured numbers, and what they mean

Under qemu, NEON ran 8.3 tok/s vs 4.4 tok/s scalar on the 135M model (1.9×). **This is emulation on an x86
host. It shows NEON code is selected and executed; it is not a Pixel 9 speed and must not be quoted as one.**
The app's Diagnostics screen and `Run benchmark` produce the real numbers on a phone (fixed prompt, greedy,
3 runs, 128 tokens, prompt-cache defeated per run, plus load time, peak RSS, battery and thermal deltas).

## Reported from a real Pixel 9 (screenshots from the maintainer, not re-run by CI)

* Pixel 9 CPU features seen by the engine: NEON, dotprod, i8mm, fp16, bf16, SVE; engine selects the NEON dotprod path.
* `qwen2.5-coder-0.5b-instruct-q4_k_m` loaded in **236 ms**, 165 MB resident (peak 173 MB), context 4096, 11.3 GB RAM device.
* Two real bugs were found this way and fixed: recycled IPC `Message`s (first load reported "engine not reachable")
  and a status strip that grew taller than the screen. Neither could be seen without a device.
* No tok/s figure has been reported yet.

## The AndCode-UI build (latest APK)

Built, unit-tested (10 tests) and R8-minified on the build host; **not run on any device**. The port replaced the
app shell, DI, settings and workspace view models and ~60k lines of AndCode UI now sit on a new runtime target
whose only exercised code path so far is compilation. Expect first-run problems in places no test reaches:
event ordering between the target and AndCode's chat view model, drawer/session refresh, the permission card for
edits, the shell terminal, settings rows, and R8 reflection. The previous UI is kept as
`dist/...-classic-ui.apk` (same application id and signature, so either can be installed over the other without
losing models or chats).

## Not verified (needs a Pixel 9)

* Most screens' rendering, and the landscape split view.
* Loading a model in the `:engine` process on Android; foreground-service behaviour; backgrounding.
* Real prompt/generation speed, peak memory, load time, battery and thermal trends, and therefore the
  "safe defaults" (context 4096, 4 threads) - these are conservative engineering estimates, not measurements.
* Low-memory kill and recovery behaviour (designed and coded; never triggered on a device).
* Storage Access Framework import, resumable download over real mobile networks, JGit and the shell
  terminal on Android (JGit on Android's file system, `/system/bin/sh` availability and permissions).
* Vulkan: built in and selectable, never run on the Mali GPU (see ANDROID_PORT.md). Airplane-mode operation is by construction (no network use
  in inference; the offline chip reads the connectivity state) but was not exercised on a device.
* The release APK is signed with the **debug key** unless `KINEAI_*` signing secrets are provided.
