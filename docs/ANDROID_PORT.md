# Porting Kinetic v0.1.0 to Android arm64

Source audited: `Veloresearch/kinetic` tag `v0.1.0` (commit `c1a11c2f`), Apache-2.0, 74k lines of Rust.
Vendored unmodified except for the patches below, in `third_party/kinetic`.

## Audit result

The brief expected an x86-only engine needing a new ARM64 implementation. The audit found something
more favourable, and I am reporting it as found:

* **Every kernel has a portable scalar definition.** `kinetic-cpu/src/kdot.rs` states that "the scalar
  functions are the definition; the SIMD versions must agree". The AVX2 files are `cfg(target_arch =
  "x86_64")`; on AArch64 the dispatch falls through to scalar code. Quantizations without an integer
  kernel fall back to dequantize-to-f32.
* **The workspace cross-compiles for `aarch64-linux-android` unchanged** (NDK r27, Rust 1.96.1), including
  its C dependency (blake3's NEON assembly).
* **Inference runs correctly on ARM64 with the scalar kernels**: a SmolLM2-135M GGUF produced a coherent,
  correct coding answer on emulated AArch64 before any patch was written.

So there was no hard blocker for CPU inference. The Android-specific engineering is the NEON kernels, the
JNI bridge, process/memory behaviour, and what the audit found about threads, mmap and the KV cache.

## Patches to Kinetic (all marked "Android patch" in source)

| File | Change |
|---|---|
| `crates/kinetic-android/` (new) | JNI bridge: `nativeLoad/Unload/Chat/Inspect/Capabilities/CpuFeatures/SetThreads`. Handles are opaque ids in a registry (a stale id is a Java exception, not a crash; an unload during generation cannot free a live model). No networking code. |
| `crates/kinetic-cpu/src/kdot/neon.rs` (new) | `sdot` (FEAT_DotProd) kernels for Q8_0, Q4_0, Q5_0, Q4_K, Q6_K. They compute the same exact integer block sums as the scalar code and fold in the same f32 order, so results are **bit-identical** to scalar. Runtime-detected; `KINETIC_NO_NEON=1` disables. Uses inline-asm `sdot` because `vdotq_s32` is still unstable in Rust 1.96. |
| `crates/kinetic-cpu/src/kdot.rs` | Dispatch to `neon` on aarch64; `is_neon()` accessor. |
| `crates/kinetic-cpu/src/lib.rs` | `kernel_tier(ty)` → `neon` / `integer` / `reference` / `float`, so the app classifies quantizations from the engine's real tables. |

Not touched: tokenizer, GGUF reader, model architectures, sampling, chat templates, KV handling.

## Findings that shape the app

* **The KV cache is f32** (`KvCache { k: Vec<f32>, v: Vec<f32> }`), 4 bytes per element: roughly twice
  what many llama.cpp setups use. For a 28-layer model with 256 KV dims that is 57 KB per token; for a 3B
  model with 1024 KV dims, ~230 KB per token. Context size therefore dominates memory on a phone, and the
  app's warnings use this exact formula (`kvBytes`). Quantizing the KV cache would be valuable upstream work.
* **Weights are memory-mapped** (`memmap2`), so they are page-cache backed and reclaimable. Unload frees
  the mapping (`Model::drop`).
* **Worker threads**: Kinetic sizes its spin-pool from rayon's global pool, which can be built once per
  process. The app therefore runs the engine in a separate process (`:engine`) and restarts that process to
  change the thread count. The spin-wait design (workers busy-wait ~2 ms between steps) is a battery
  consideration that has **not** been measured on a phone.
* **Cancellation granularity**: the engine checks the callback after each emitted token. A cancel during
  the prompt pass takes effect at the first token.
* **No network code in the library**: `ureq` is only used by Kinetic's tests; the shipped `.so` imports no
  socket/DNS/TLS symbols (`scripts/check-no-network.sh`, enforced in CI).

## Process model, memory pressure, recovery

* Inference runs in `com.kineai.local.engine.EngineService` in the `:engine` process, as a foreground
  service while loading/generating, so backgrounding does not stop it.
* If Android kills `:engine` (low memory), the UI process, saved conversations and files are untouched. The
  UI shows "Engine stopped" and **does not reload the model on its own**; the user decides.
* Assistant text is persisted while streaming (status `interrupted` until the answer completes), so a kill
  mid-answer keeps what was generated.
* The engine forwards `onTrimMemory` levels to the UI as a notice; it does not unload anything silently.
* A restarted UI process asks the engine what is loaded and adopts it rather than reloading.

## What cannot run (yet)

* **GPU/Vulkan.** `kinetic-vulkan` compiles for `aarch64-linux-android` (it loads `libvulkan.so` at
  runtime through `ash`), but it is described as tuned for AMD/Intel/NVIDIA GPUs, is not wired through the
  `kinetic` crate's backend selection in this port, and could not be validated against the Pixel 9's Mali
  driver. The app therefore reports "GPU unavailable" with this reason and uses the CPU. No Vulkan path is
  advertised.
* **Architectures** outside Kinetic's list (reported by the engine at runtime; at v0.1.0: llama, qwen2, qwen3,
  qwen35(moe), gemma3/4, lfm2/3, granite*, olmo2, ernie4_5, hunyuan-dense, exaone4, falcon-h1). Split
  (multi-part) GGUF files are not loadable and are labelled Unsupported.
* **Vision/mmproj**, embedding models and speculative decoding are not exposed in the UI.
* **NEON kernels for Q5_K, Q2_K, Q3_K, IQ\*** do not exist yet; those types use the scalar integer kernel or
  the f32 reference path, and the Models page labels such files "May run with limits".
* **Terminal**: commands run through `/system/bin/sh -c` with no PTY, so full-screen programs (vim, top) do
  not work. Android's toybox provides the commands; there is no package manager. AndCode's proot/Termux
  Linux guest was deliberately not carried over (see below).

## What was taken from AndCode v1.2.29 and what was removed

Kept (copied with attribution, package renamed): the dark theme and colour system, syntax themes, and the
ANSI terminal emulator/model. The code viewer, Git views and workspace/session screens were **re-implemented**
rather than ported, because AndCode's versions are entangled with its agent runtime (OpenCode REST types,
`LocalRuntimeManager`, remote profiles).

Not carried over at all, so there is nothing to hide or leave dead: OpenCode, Claude Code, Codex and
Antigravity runtimes, installers and sign-in flows; provider/API-key storage; remote server connections, LAN
discovery and QR pairing; the proot/Termux runtime installer and its asset pipeline; Firebase/Crashlytics;
wake-word (Vosk), schedules, widget, assistant overlay. The resulting app has no agent loop and no
automatic command execution or file changes: the model returns text, edit proposals are shown as diffs, and
only a user tap writes a file.
