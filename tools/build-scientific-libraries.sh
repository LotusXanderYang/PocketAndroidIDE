#!/usr/bin/env bash
set -euo pipefail
log_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.toolchain" && pwd)"
checkout=/root/pocketpy-build/termux-packages
expected=6be420e7536d1e228f3ffee2eb74ca2736a73fc1
cd "$checkout"
[[ "$(git -c safe.directory="$checkout" rev-parse HEAD)" == "$expected" ]]
grep -q '^TERMUX_APP__PACKAGE_NAME="com.example.pocketpythonide"$' scripts/properties.sh
patch_file="$log_root/../packaging/termux/matplotlib-agg-pillow.patch"
if ! git -c safe.directory="$checkout" apply --reverse --check "$patch_file" 2>/dev/null; then
    git -c safe.directory="$checkout" apply --check "$patch_file"
    git -c safe.directory="$checkout" apply "$patch_file"
fi
# Migrate only the exact prior task-owned configuration, preserving other edits.
previous_hash=c58e840bfc9cfd7129e55eeba7bb3b59af18e3dec40753e76826fd5ae821b93b
if [[ "$(sha256sum packages/harfbuzz/build.sh | cut -d' ' -f1)" == "$previous_hash" ]]; then
    git -c safe.directory="$checkout" apply -R "$log_root/../packaging/termux/matplotlib-harfbuzz-no-cairo.patch"
    git -c safe.directory="$checkout" apply -R "$log_root/../packaging/termux/matplotlib-harfbuzz-no-gir.patch"
fi
patch_file="$log_root/../packaging/termux/matplotlib-harfbuzz-agg.patch"
if ! git -c safe.directory="$checkout" apply --reverse --check "$patch_file" 2>/dev/null; then
    git -c safe.directory="$checkout" apply --check "$patch_file"
    git -c safe.directory="$checkout" apply "$patch_file"
fi
patch_file="$log_root/../packaging/termux/matplotlib-pybind11-headers.patch"
if ! git -c safe.directory="$checkout" apply --reverse --check "$patch_file" 2>/dev/null; then
    git -c safe.directory="$checkout" apply --check "$patch_file"
    git -c safe.directory="$checkout" apply "$patch_file"
fi
export TERMUX_BUILDER_IMAGE_NAME=pocketpy-termux-builder:local
export TERMUX_PKG_MAKE_PROCESSES=2
export MAX_JOBS=2
./scripts/run-docker.sh env MAX_JOBS=2 ./build-package.sh -j2 -a aarch64 matplotlib 2>&1 | tee "$log_root/scientific-build.log"
