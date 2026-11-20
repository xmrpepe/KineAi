#!/usr/bin/env bash
# Fails if the packaged inference library imports any networking function.
# Usage: scripts/check-no-network.sh path/to/libkinetic_android.so
set -euo pipefail
so="${1:?path to libkinetic_android.so}"
nm="${LLVM_NM:-$(ls "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME}"/toolchains/llvm/prebuilt/*/bin/llvm-nm | head -1)}"
bad=$("$nm" -D --undefined-only "$so" | grep -iE 'socket|connect|getaddrinfo|gethostby|sendto|recvfrom|sendmsg|recvmsg|curl|ssl' || true)
if [ -n "$bad" ]; then echo "FAIL: networking imports found:"; echo "$bad"; exit 1; fi
echo "OK: $(basename "$so") imports no networking functions ($("$nm" -D --undefined-only "$so" | wc -l) undefined symbols checked)"
