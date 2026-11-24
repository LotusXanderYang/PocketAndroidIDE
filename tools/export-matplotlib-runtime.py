#!/usr/bin/env python3
"""Run in the pinned Android builder after Matplotlib succeeds; export audited assets."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile

SOURCE_ID = b"com.example.pocketpythonide"
TARGET_ID = b"com.example.pocketpythonstd"
SYSTEM = {"libc.so","libm.so","libdl.so","liblog.so","libandroid.so","libz.so","libpython3.14.so"}
MODULES = ["numpy","matplotlib","mpl_toolkits","contourpy","kiwisolver","PIL",
           "cycler","fontTools","packaging","pyparsing","dateutil","six"]

def needed(blob):
    if blob[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H",blob,18)[0] != 183:
        raise ValueError("Not AArch64 ELF64")
    offset=struct.unpack_from("<Q",blob,40)[0]
    size,count=struct.unpack_from("<HH",blob,58)
    sections=[]
    for index in range(count):
        at=offset+index*size
        sections.append((struct.unpack_from("<I",blob,at+4)[0],
            struct.unpack_from("<Q",blob,at+24)[0],struct.unpack_from("<Q",blob,at+32)[0],
            struct.unpack_from("<I",blob,at+40)[0]))
    names=[]
    for kind,at,length,link in sections:
        if kind!=6:continue
        _,strings,string_size,_=sections[link]
        for pos in range(at,at+length,16):
            tag,value=struct.unpack_from("<QQ",blob,pos)
            if tag==1:
                start=strings+value
                end=blob.index(b"\0",start,strings+string_size)
                names.append(blob[start:end].decode("ascii"))
    return names

def export(prefix,output,pure_wheels=None):
    overlay={}; wheel_hashes={}
    pure_names={"cycler","fontTools","packaging","pyparsing","dateutil","six"}
    if pure_wheels:
        for wheel in sorted(pure_wheels.glob("*-none-any.whl")):
            wheel_hashes[wheel.name]=hashlib.sha256(wheel.read_bytes()).hexdigest()
            with zipfile.ZipFile(wheel) as z:
                for name in z.namelist():
                    if name.endswith("/"):continue
                    if name.startswith("/") or ".." in Path(name).parts or "\\" in name or name.endswith((".so",".pyd",".pth")):
                        raise ValueError("Unsafe pure wheel member: "+name)
                    if ".data/data/share/man/" in name or ".data/scripts/" in name:
                        continue  # CLI tools/manuals are outside the embedded runtime contract.
                    if ".data/" in name:raise ValueError("Wheel requires install layout: "+name)
                    if name in overlay:raise ValueError("Wheel file conflict: "+name)
                    overlay[name]=z.read(name)
    site=prefix/"lib/python3.14/site-packages"
    files={}
    for name in MODULES:
        if pure_wheels and name in pure_names:
            members={path:data for path,data in overlay.items() if path.startswith(name+"/") or path==name+".py"}
            if not members:raise ValueError("Pure wheel missing: "+name)
            files.update(members)
            continue
        item=site/name
        if not item.exists():item=site/(name+".py")
        if not item.exists():raise ValueError("Missing dependency: "+name)
        for path in sorted(item.rglob("*")) if item.is_dir() else [item]:
            if not path.is_file() or path.suffix in (".pyc",".a") or "__pycache__" in path.parts or "tests" in path.parts:
                continue
            if not path.resolve().is_relative_to(prefix.resolve()):raise ValueError("Symlink escape")
            files[path.relative_to(site).as_posix()]=path.read_bytes()
    for metadata in sorted(site.glob("*.dist-info")):
        if pure_wheels and metadata.name.lower().replace("-","_").startswith(("cycler_","fonttools_","packaging_","pyparsing_","python_dateutil_","six_")):
            continue
        normalized=metadata.name.lower().replace("-","_")
        if not any(normalized.startswith(name) for name in
                ("numpy_","matplotlib_","contourpy_","kiwisolver_","pillow_","cycler_","fonttools_","packaging_","pyparsing_","python_dateutil_","six_")):
            continue
        for path in metadata.rglob("*"):
            if path.is_file():files[path.relative_to(site).as_posix()]=path.read_bytes()
    for name,data in overlay.items():
        if ".dist-info/" in name:files[name]=data
    order=[];active=set();done=set()
    def closure(blob):
        for library in needed(blob):
            if library in SYSTEM or library in done:continue
            if library in active:raise ValueError("Cyclic preload dependency: "+library)
            active.add(library)
            data=(prefix/"lib"/library).read_bytes()
            closure(data)
            active.remove(library);done.add(library);order.append(library)
            files["native-libs/"+library]=data
    for name,data in list(files.items()):
        if name.endswith(".so"):closure(data)
    output.mkdir(parents=True,exist_ok=True)
    archive=output/"library.zip"
    with zipfile.ZipFile(archive,"w") as z:
        for name,data in sorted(files.items()):
            info=zipfile.ZipInfo(name,(2026,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
            z.writestr(info,data.replace(SOURCE_ID,TARGET_ID))
    if archive.stat().st_size>64*1024*1024 or sum(map(len,files.values()))>256*1024*1024 or len(files)>12000:
        raise ValueError("Native library API limits exceeded")
    manifest=dict(schemaVersion=1,packId="org.pocketide.library.matplotlib",displayName="Matplotlib + NumPy",
        version="3.11.2-candidate.1",runtime="cp314-native-aarch64-v1",kind="android-native",
        imports=MODULES,dependencies=[],source="https://matplotlib.org/",license="See bundled licenses",
        sha256=hashlib.sha256(archive.read_bytes()).hexdigest(),preload=order)
    (output/"python-library.json").write_text(json.dumps(manifest,indent=2)+"\n")
    (output/"pure-wheel-lock.json").write_text(json.dumps(wheel_hashes,indent=2)+"\n")
    (output/"payload-lock.json").write_text(json.dumps({name:hashlib.sha256(data).hexdigest()
        for name,data in sorted(files.items())},indent=2)+"\n")
    # Preserve original license material, following only links within the pinned prefix.
    licenses=output/"licenses";licenses.mkdir(exist_ok=True)
    for folder in (prefix/"share/LICENSES",prefix/"share/doc"):
        if not folder.is_dir():continue
        for path in folder.rglob("*"):
            if path.is_file() and path.resolve().is_relative_to(prefix.resolve()):
                relative=path.relative_to(prefix/"share")
                dest=licenses/relative;dest.parent.mkdir(parents=True,exist_ok=True)
                dest.write_bytes(path.read_bytes())
    print(archive,archive.stat().st_size,"preload",order)

if __name__=="__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("output",type=Path)
    parser.add_argument("--prefix",type=Path,default=Path("/data/data/com.example.pocketpythonide/files/usr"))
    parser.add_argument("--pure-wheels",type=Path)
    args=parser.parse_args();export(args.prefix,args.output,args.pure_wheels)
