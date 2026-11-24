#!/usr/bin/env python3
"""Package both extension contracts and prompts from one exact Git revision."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PREFIXES = ("docs/language-pack-api/v1/", "docs/python-library-api/v1/",
            "pack-sdk/", "sample-pack/", "packs/python-library-template/")
FILES = {
    "LICENSE", "docs/prompts/00-共同约束.md", "docs/prompts/10-第三方语言包制包模板.md",
    "docs/prompts/11-Python库扩展制包模板.md", "tools/verify-language-pack.py",
    "tools/verify-static-pack-index.py", "tools/verify-language-pack-device.py",
    "tools/package-python-library.py", "tools/test_python_libraries.py",
    "tools/test_python_library_verification.py", "tools/PythonLibraryVerificationSource.java",
    "tools/package-extension-apis.py",
    "packs/python/src/main/java/org/pocketide/python/PythonLibraries.java",
    "packs/python/src/main/java/org/pocketide/python/PythonLibraryVerification.java",
    "packs/python/src/main/assets/python_library_support.py",
}
REQUIRED = {
    "docs/language-pack-api/v1/README.md", "docs/language-pack-api/v1/AI-PROMPT.md",
    "docs/language-pack-api/v1/pack-manifest.schema.json",
    "docs/python-library-api/v1/README.md", "docs/python-library-api/v1/AI-PROMPT.md",
    "docs/python-library-api/v1/manifest.schema.json",
    "pack-sdk/src/main/aidl/org/pocketide/pack/v1/ILanguagePack.aidl",
    "pack-sdk/src/main/aidl/org/pocketide/pack/v1/ITaskCallback.aidl",
    "packs/python-library-template/library.json",
}


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", default="HEAD")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    revision = git("rev-parse", "--verify", args.ref + "^{commit}").decode().strip()
    paths = git("ls-tree", "-rz", "--name-only", revision).decode("utf-8").split("\0")
    selected = [path for path in paths if (path in FILES or path.startswith(PREFIXES))
                and not any(part in {"build", "dist", "__pycache__"} for part in Path(path).parts)
                and not path.endswith((".zip", ".aar", ".apk", ".pyc", ".keystore"))]
    if REQUIRED - set(selected):
        raise SystemExit("Missing API files in Git revision: " + str(sorted(REQUIRED - set(selected))))
    data = {path: git("show", f"{revision}:{path}") for path in sorted(selected)}
    data["START-HERE.md"] = f"""# Pocket Android IDE extension reference kit

Source: https://github.com/LotusXanderYang/PocketAndroidIDE/tree/{revision}

Two distinct contracts are included:

| Contract | Specification | Copyable packaging prompt |
| --- | --- | --- |
| Language Pack API v1 | docs/language-pack-api/v1/README.md | docs/language-pack-api/v1/AI-PROMPT.md |
| Python Library APK API v1 | docs/python-library-api/v1/README.md | docs/python-library-api/v1/AI-PROMPT.md |

Language packs provide executable language services via Binder/AIDL. Python library
APKs provide opt-in resources loaded by the Python pack. Their schemas and trust
boundaries are separate. Native Python library support is experimental ARM64 only.

Also included: schemas, debug contract, AIDL/SDK source, Echo sample, a pure Python
library template, validators and host-side library loading reference code.

Paths match the repository. Use this kit alongside a checkout at the source revision
above; it is not a standalone Gradle project or a complete Android runtime. Fill in
the target language/library, device and required capabilities in the chosen prompt.
Keep Android builds and on-device validation separate in your report. No signing
keys, generated payloads, toolchain or user workspaces are included.

中文：两个接口分别用于可执行语言包和 Python 库资源包。先读对应 README，再填写对应
AI-PROMPT。目录与仓库一致，请配合上述版本源码使用。原生库须完成 Android ARM64 移植
与实机验证；本包不含密钥、运行时或大型生成载荷。
""".encode("utf-8")
    data["BUNDLE-INFO.json"] = (json.dumps({"sourceCommit": revision,
        "repository": "https://github.com/LotusXanderYang/PocketAndroidIDE",
        "contracts": ["Language Pack API v1", "Python Library APK API v1"]}, indent=2) + "\n").encode()
    data["CONTENTS-SHA256.txt"] = "".join(
        hashlib.sha256(content).hexdigest() + "  " + path + "\n"
        for path, content in sorted(data.items())).encode()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, "x", compression=zipfile.ZIP_DEFLATED) as archive:
        for path, content in sorted(data.items()):
            info = zipfile.ZipInfo(path, (2026, 1, 1, 0, 0, 0))
            info.external_attr = 0o644 << 16
            archive.writestr(info, content, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    with zipfile.ZipFile(args.output) as archive:
        if archive.testzip() is not None:
            raise SystemExit("ZIP integrity check failed")
        for line in archive.read("CONTENTS-SHA256.txt").decode().splitlines():
            expected, path = line.split("  ", 1)
            assert hashlib.sha256(archive.read(path)).hexdigest() == expected, path
    print(json.dumps({"path": str(args.output), "files": len(data), "sourceCommit": revision,
        "bytes": args.output.stat().st_size,
        "sha256": hashlib.sha256(args.output.read_bytes()).hexdigest()}, indent=2))


if __name__ == "__main__":
    main()
