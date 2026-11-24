"""Build a deterministic pure Python payload; no download or setup.py execution."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile


def package(root):
    root = Path(root)
    spec = json.loads((root / "library.json").read_text(encoding="utf-8"))
    if spec["schemaVersion"] != 1 or spec["runtime"] != "cp314-pure-v1" or spec["kind"] != "pure-python" or spec["dependencies"]:
        raise ValueError("Unsupported library contract")
    imports = spec["imports"]
    if not imports or len(set(imports)) != len(imports) or any(not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", x) for x in imports):
        raise ValueError("Invalid imports")
    output = root / "src/main/assets"
    output.mkdir(parents=True, exist_ok=True)
    paths = sorted((root / "payload").rglob("*"))
    files = []
    for path in paths:
        if path.is_symlink():
            raise ValueError("Symlink in payload")
        if not path.is_file():
            continue
        name = path.relative_to(root / "payload").as_posix()
        top = name.split("/")[0] if "/" in name else name.removesuffix(".py")
        if top not in imports or path.suffix.lower() in (".so", ".pyd", ".dll", ".pyc", ".pth"):
            raise ValueError("Unsupported payload: " + name)
        if path.stat().st_size > 4 * 1024 * 1024:
            raise ValueError("File too large")
        files.append((name, path.read_bytes()))
    if not files or len(files) > 4000 or sum(len(data) for _, data in files) > 32 * 1024 * 1024:
        raise ValueError("Payload limits exceeded")
    archive = output / "library.zip"
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as target:
        for name, data in files:
            info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            target.writestr(info, data)
    if archive.stat().st_size > 16 * 1024 * 1024:
        raise ValueError("Archive too large")
    spec["sha256"] = hashlib.sha256(archive.read_bytes()).hexdigest()
    (output / "python-library.json").write_text(json.dumps(spec, indent=2) + "\n", encoding="utf-8")
    print(archive, spec["sha256"])

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    package(parser.parse_args().root)
