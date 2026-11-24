# NumPy Android library candidate

NumPy 2.5.4, BSD-3-Clause, with bundled NumPy notices in numpy-2.5.4.dist-info/licenses inside library.zip. Source: https://github.com/numpy/numpy/releases/tag/v2.5.4 .

OpenBLAS 0.3.34 and its included LAPACK/Relapack/reference components: original licenses in assets/licenses/openblas-*. Source: https://github.com/OpenMathLib/OpenBLAS .

Android NDK r30 libc++_shared.so: NCSA / Apache-2.0 with LLVM exception; texts in assets/licenses. Original NDK SHA-256 and source recipe: .toolchain/termux-packages/packages/libc++/build.sh; the pinned Termux source commit is recorded in source-lock.json.

Native binaries are derived from the previously audited application-prefix ARM64 runtime. Paths are rebased from com.example.pocketpythonide to com.example.pocketpythonstd without altering the original archive. This is a device-validation candidate, not a new upstream source build or a claim of Android compatibility beyond the recorded test results.
