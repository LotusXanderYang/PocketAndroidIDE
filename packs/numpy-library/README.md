# NumPy Android library candidate

Run `python tools/package-numpy-library.py` to derive the optional library from the pinned ARM64 baseline runtime. The source archive is verified before use. The generator checks AArch64 ELF dependency closure, includes OpenBLAS/libc++, records the source archive hash, and rebases the equal-length application prefix.

Requires the new Python library runtime `cp314-native-aarch64-v1`. Install the APK, explicitly validate and enable NumPy through Python library management. On native selection changes use the process restart button before further tasks; native extension types remain loaded for process lifetime. Existing pure ZIP libraries remain supported.

Device validation: import/version, arrays, dot product, linalg.solve, repeated runs, debugging, disable/restart and uninstall. The tablet user confirmed NUMPY_ALL_OK, repeated runs, and debugging. Disable/restart and uninstall regression remain unverified for this exact APK.
