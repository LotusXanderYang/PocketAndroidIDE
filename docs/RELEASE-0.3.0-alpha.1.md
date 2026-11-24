# Pocket Android IDE 0.3.0 Alpha 1

This preview adds optional Python library APKs and image previews in the IDE. The
repository is now **PocketAndroidIDE**. Android package IDs and signing identities
are unchanged, so existing users can install the matching updates in place.

## Downloads

| Asset | Purpose |
| --- | --- |
| `PocketAndroidIDE-0.3.0-alpha.1.apk` | IDE with UI polish and read-only image preview |
| `PocketPythonPack-0.2.0-libraries.5.apk` | Updated CPython 3.14 pack with opt-in library loading |
| `PocketNumPy-2.5.4-candidate.1.apk` | Optional standalone NumPy library |
| `PocketMatplotlib-3.11.2-candidate.1.apk` | Optional Matplotlib bundle, including NumPy and its dependencies |
| `Extension-APIs-v1.zip` | Both extension interfaces, AI packaging prompts, schemas, SDK and templates |
| `SHA256SUMS.txt` | SHA-256 checksums for the assets above |

The existing C/C++ language pack remains compatible with Language Pack API v1 and
is available in [Alpha 1](https://github.com/LotusXanderYang/PocketAndroidIDE/releases/tag/v0.2.0-alpha.1).
[PyTorch Lab](https://github.com/LotusXanderYang/PocketPyTorchLab) stays independent.

## Install and use

Install the IDE and updated Python pack directly over your existing versions.
**Do not uninstall first** to preserve private files and workspace grants. Open
Language Packs, verify Python, and enable the passed capabilities.

For Python libraries, install either standalone NumPy or the Matplotlib + NumPy
bundle. Open **Language Packs → Python Library Extensions**, review the origin,
signature and permissions, then verify and enable your selection. The library APKs
use their own signing identity; they do not need the IDE's certificate.

**Do not enable both scientific library APKs at once.** When switching to
Matplotlib, disable standalone NumPy, restart the Python process, then verify the
bundle. If a previous NumPy module still causes `Module conflict: numpy`, keep
standalone NumPy disabled and reboot the device before retrying. A native-library
selection change requires a process restart because native modules stay loaded.

Matplotlib uses the Agg backend to save images. Tap a generated PNG in the IDE file
tree to preview it, pinch to zoom, drag, or use the fit, 1:1 and refresh buttons.
PNG, JPEG, WebP, BMP and the first GIF frame are supported. Files up to 32 MiB can
be previewed; large images are downsampled. The original image and current code
buffer are preserved. Interactive `plt.show()` windows are outside this preview.

## Extension reference kit

`Extension-APIs-v1.zip` contains two separate contracts:

- **Language Pack API v1:** executable language services through Binder/AIDL,
  optional debugging contract, schemas, SDK source, Echo example and AI prompt.
- **Python Library APK API v1:** opt-in resource APKs loaded by the Python pack,
  pure Python and experimental native ARM64 schema, template and AI prompt.

Start with `START-HERE.md` in the ZIP. Paths match the repository. Use it alongside
the source revision recorded in `BUNDLE-INFO.json`; it is not a standalone Gradle
project. Language Pack API v1's `minHostVersion` remains `0.2.0-alpha.1`.

## Verification and limits

The tablet user confirmed NumPy calculations, repeated runs and debugging, then
Matplotlib image generation after disabling standalone NumPy and rebooting. The
image-preview build was accepted by the user. Individual preview gestures and all
failure paths have not been separately reported as device-tested.

Host checks passed for library loading, verification-source generation, the Python
bridge, image decode bounds, pack/schema validation and IDE compilation. Android
Lint completed with the existing data-extraction-rules warning. Full Matplotlib
debugging/uninstall regression, Android API 26 and 16 KB memory-page devices remain
untested. This is an ARM64 preview, not general `pip install` support.

Library approval is tied to the installed APK identity and is invalidated by an
update or reinstall. Library code runs with the Python pack's permissions; verify
its source before enabling it. Native initialization can still report a generic
`native=-1` error; detailed bootstrap-error reporting remains follow-up work.

Source, dependency locks, patches and license provenance are in this tag and
[THIRD-PARTY-SOURCE.md](https://github.com/LotusXanderYang/PocketAndroidIDE/blob/v0.3.0-alpha.1/docs/THIRD-PARTY-SOURCE.md).
The IDE and Python pack retain certificate SHA-256
`f1fa1170a83b9ac5d8436b25d9c163a07b68ad9a5567492d568906edeaa888e6`.
The NumPy and Matplotlib library APKs retain their independently signed candidates.
Earlier releases remain available.
