#!/usr/bin/env bash
# Exercises the real JNI glue (load, inspect, streaming, cancel, stale handles, errors) on a desktop
# JVM using the x86-64 build of the same Rust crate. Needs a JDK and a small .gguf path.
set -euo pipefail
model="${1:?path to a small .gguf}"
root="$(cd "$(dirname "$0")/.." && pwd)"
(cd "$root/third_party/kinetic" && cargo build --release --locked -p kinetic-android)
out="$(mktemp -d)"
javac -d "$out" "$root/native/jvm-harness/com/kineai/local/engine/NativeEngine.java"
java -cp "$out" com.kineai.local.engine.NativeEngine "$root/third_party/kinetic/target/release/libkinetic_android.so" "$model" | tee "$out/log.txt"
grep -q '"finish":"cancelled"' "$out/log.txt"
grep -q 'post-unload use: java.lang.RuntimeException' "$out/log.txt"
grep -q 'load(bad) threw' "$out/log.txt"
echo "OK: JNI glue behaves as specified"
