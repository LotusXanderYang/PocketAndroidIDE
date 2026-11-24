"""Derive Android NumPy from the already pinned, audited ARM64 runtime."""
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("base", ROOT / "packs/python/prepare_runtime.py")
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
assets = ROOT / "packs/numpy-library/src/main/assets"
source = ROOT / "app/src/main/assets/runtime.zip"
manifest = json.loads((ROOT / "app/src/main/assets/runtime.json").read_text())
assert base.sha256(source) == manifest["asset_sha256"]
site = "usr/lib/python3.14/site-packages/"
with zipfile.ZipFile(source) as archive, zipfile.ZipFile(ROOT / "packs/python/src/main/assets/runtime.zip") as baseline:
    baseline_libs = {Path(n).name for n in baseline.namelist() if n.startswith("usr/lib/") and n.count("/")==2}
    names = [n for n in archive.namelist() if n.startswith(site+"numpy/") or n.startswith(site+"numpy-2.5.4.dist-info/")]
    names = [n for n in names if not n.endswith("/") and not n.endswith((".pyc", ".a")) and "/__pycache__/" not in n and "/tests/" not in n]
    root_libs = {Path(n).name:n for n in archive.namelist() if n.startswith("usr/lib/") and n.count("/")==2}
    extra = {}; order = []
    def dependencies(blob):
        if blob[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H",blob,18)[0]!=183:
            raise ValueError("Not an Android AArch64 ELF")
        for dependency in base.needed_libraries(blob):
            if dependency in base.SYSTEM_LIBRARIES or dependency in baseline_libs or dependency in extra:
                continue
            path = root_libs[dependency]
            extra[dependency] = archive.read(path)
            dependencies(extra[dependency])
            order.append(dependency)
    for name in names:
        if name.endswith(".so"): dependencies(archive.read(name))
    target = assets / "library.zip"
    with zipfile.ZipFile(target,"w",compression=zipfile.ZIP_DEFLATED) as out:
        for name in sorted(names):
            content = archive.read(name).replace(base.SOURCE_ID,base.PACK_ID)
            out.writestr(name[len(site):],content)
        for name,content in extra.items():
            out.writestr("native-libs/"+name,content.replace(base.SOURCE_ID,base.PACK_ID))
    config = dict(schemaVersion=1,packId="org.pocketide.library.numpy",displayName="NumPy",
        version="2.5.4-candidate.1", runtime="cp314-native-aarch64-v1",kind="android-native",
        imports=["numpy"],dependencies=[],source="https://numpy.org/",license="BSD-3-Clause and bundled dependency licenses",
        sha256=base.sha256(target),preload=order)
    (assets / "python-library.json").write_text(json.dumps(config,indent=2)+"\n")
    (assets / "source-lock.json").write_text(json.dumps(dict(source_sha256=manifest["asset_sha256"],
        termux_commit="6be420e7536d1e228f3ffee2eb74ca2736a73fc1",preload=order,
        dependencies={name:hashlib.sha256(blob).hexdigest() for name,blob in extra.items()}),indent=2)+"\n")
    print(target, target.stat().st_size,config["sha256"],order)
