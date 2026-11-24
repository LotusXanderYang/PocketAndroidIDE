# Matplotlib + NumPy ARM64 candidate

Matplotlib 3.11.2 and NumPy 2.5.4, CPython 3.14, Android ARM64. Includes the complete required Python/native dependency closure, metadata, bundled fonts/data and original license texts. Target: non-interactive Agg PNG rendering.

Build: tools/build-scientific-libraries.sh uses the pinned Termux checkout, with scoped patches for Pillow optional codecs, HarfBuzz optional introspection/Cairo/ICU, and pybind11 header-only installation. tools/export-matplotlib-runtime.py exports the completed prefix plus the six locked pure wheels. source-lock.json records the inputs. Large generated library.zip is ignored by Git. Run :packs:matplotlib-library:assembleDebug after preparing assets.

Install the updated Python pack, disable standalone NumPy, restart the Python process, then verify/enable this self-contained scientific bundle. It includes NumPy; do not enable both overlapping packages. Native selection changes require a process restart.

Compilation, ELF checks, payload/schema/hash checks passed. The tablet user confirmed PNG generation after disabling standalone NumPy and rebooting the device. Full repeat/debug/uninstall regression remains pending. Interactive windows and optional AVIF/TIFF/WebP codecs are outside this candidate's acceptance scope.
