#!/usr/bin/env bash
# ARM64 correctness without a phone: run Kinetic's real inference on emulated AArch64 with the
# NEON kernels and with the scalar kernels (KINETIC_NO_NEON=1), greedy decoding, and require
# byte-identical output. Needs qemu-user, gcc-aarch64-linux-gnu, libc6-arm64-cross, a GGUF path.
# This proves the kernels are correct. It says NOTHING about speed on a Pixel: qemu is emulation.
set -euo pipefail
model="${1:?path to a small .gguf (e.g. SmolLM2-135M-Instruct-Q4_K_M.gguf)}"
cd "$(dirname "$0")/../third_party/kinetic"
export CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER=aarch64-linux-gnu-gcc
cargo build --release --locked --target aarch64-unknown-linux-gnu -p kinetic-cli --no-default-features
K="qemu-aarch64 -cpu max -L /usr/aarch64-linux-gnu target/aarch64-unknown-linux-gnu/release/kinetic"
P="Write a Python function that reverses a string."
$K run "$model" "$P" --temp 0 -n 60 -t 4 >/tmp/neon.txt 2>/tmp/neon.err
KINETIC_NO_NEON=1 $K run "$model" "$P" --temp 0 -n 60 -t 4 >/tmp/scalar.txt 2>/tmp/scalar.err
cmp /tmp/neon.txt /tmp/scalar.txt && echo "OK: NEON and scalar outputs are byte-identical ($(wc -c </tmp/neon.txt) bytes)"
tail -1 /tmp/neon.err
