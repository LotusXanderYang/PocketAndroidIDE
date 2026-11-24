"""Real ZIP import/reload/disable tests and library packager rejection tests."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module
support = load("library_support", ROOT / "packs/python/src/main/assets/python_library_support.py")
packager = load("packager", ROOT / "tools/package-python-library.py")

class LibrariesTest(unittest.TestCase):
    def test_import_reload_disable_and_exception(self):
        before = list(sys.path)
        with tempfile.TemporaryDirectory() as folder:
            archives = []
            for version in (21, 42):
                path = Path(folder) / (str(version) + ".zip")
                with zipfile.ZipFile(path, "w") as archive:
                    archive.writestr("library_probe/__init__.py", "from .child import value")
                    archive.writestr("library_probe/child.py", "value = " + str(version))
                archives.append(str(path))
            for archive, expected in zip(archives, (21,42)):
                with support.library_paths([archive]):
                    import library_probe
                    self.assertEqual(expected, library_probe.value)
                self.assertNotIn("library_probe", sys.modules)
                self.assertNotIn("library_probe.child", sys.modules)
            with self.assertRaises(ModuleNotFoundError):
                __import__("library_probe")
            with self.assertRaises(RuntimeError):
                with support.library_paths(archives):
                    raise RuntimeError("task failed")
        self.assertEqual(before, sys.path)

    def test_real_bridge_uses_library_and_releases_it(self):
        bridge = load("pack_bridge", ROOT / "packs/python/python_pack_bridge.py")
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            archive = root / "library.zip"
            with zipfile.ZipFile(archive, "w") as payload:
                payload.writestr("bridge_probe.py", "value = 42")
            (root / "main.py").write_text("import bridge_probe; print(bridge_probe.value)")
            command = {"workspace": str(root), "entry": "main.py"}
            for _ in range(2):
                with support.library_paths([str(archive)]):
                    answer = bridge.run(command)
                self.assertEqual("ok", answer["status"], answer)
                self.assertEqual("42\n", answer["stdout"])
                self.assertNotIn("bridge_probe", sys.modules)
            answer = bridge.run(command)
            self.assertEqual("error", answer["status"])
            self.assertIn("ModuleNotFoundError", answer["stderr"])

    def test_native_selection_requires_restart_and_keeps_modules(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / ".pocket-native.json").write_text("[]")
            (root / "native_probe.py").write_text("value=42")
            try:
                for _ in range(2):
                    with support.library_paths([str(root)]):
                        import native_probe
                        self.assertEqual(42,native_probe.value)
                    self.assertIn("native_probe",sys.modules)
                with self.assertRaisesRegex(RuntimeError,"restart"):
                    with support.library_paths([]):
                        pass
            finally:
                sys.modules.pop("native_probe",None)
                for name in ("_pocket_native_selection","_pocket_native_failure","_pocket_native_handles"):
                    if hasattr(sys,name): delattr(sys,name)

    def test_temp_directory_survives_deleted_verification_workspace(self):
        import os
        old_dir = tempfile.tempdir
        old_env = os.environ.get("TMPDIR")
        try:
            with tempfile.TemporaryDirectory() as cache:
                with tempfile.TemporaryDirectory() as workspace:
                    with support.library_paths([], cache):
                        self.assertEqual(cache, tempfile.gettempdir())
                # Verification workspace is gone; subsequent commands can still create temp files.
                with tempfile.TemporaryFile() as output:
                    output.write(b"next-task")
        finally:
            tempfile.tempdir = old_dir
            if old_env is None: os.environ.pop("TMPDIR",None)
            else: os.environ["TMPDIR"] = old_env

    def test_packager_rejects_native_and_undeclared_files(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            spec = json.loads((ROOT / "packs/python-library-template/library.json").read_text())
            (root / "library.json").write_text(json.dumps(spec))
            payload = root / "payload/pocket_demo"
            payload.mkdir(parents=True)
            (payload / "__init__.py").write_text("value=42")
            native = payload / "native.so"
            native.write_bytes(b"ELF")
            with self.assertRaises(ValueError): packager.package(root)
            native.unlink()
            other = root / "payload/undeclared.py"
            other.write_text("value=0")
            with self.assertRaises(ValueError): packager.package(root)
            other.unlink()
            packager.package(root)
            first = (root / "src/main/assets/library.zip").read_bytes()
            packager.package(root)
            self.assertEqual(first, (root / "src/main/assets/library.zip").read_bytes())

if __name__ == "__main__": unittest.main()
