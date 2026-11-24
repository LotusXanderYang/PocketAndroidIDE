"""Compile real Java generator, then execute its Python verification source."""
import contextlib
import importlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import types
import unittest
from unittest.mock import patch
import warnings

ROOT = Path(__file__).resolve().parents[1]
JDK = ROOT / ".toolchain/zulu17.68.203-ca-jdk17.0.20.1-win_x64/bin"

class VerificationSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.folder = tempfile.TemporaryDirectory()
        subprocess.run([str(JDK / "javac.exe"), "-encoding", "UTF-8", "-d", cls.folder.name,
            str(ROOT / "packs/python/src/main/java/org/pocketide/python/PythonLibraryVerification.java"),
            str(ROOT / "tools/PythonLibraryVerificationSource.java")], check=True, capture_output=True)

    @classmethod
    def tearDownClass(cls):
        cls.folder.cleanup()

    def script(self, path, name="pocket_demo"):
        output = subprocess.run([str(JDK / "java.exe"), "-cp", self.folder.name,
            "org.pocketide.python.PythonLibraryVerificationSource", json.dumps([name]), path],
            check=True, capture_output=True).stdout.decode("utf-8")
        with warnings.catch_warnings():
            warnings.simplefilter("error", SyntaxWarning)
            return compile(output, "verify.py", "exec")

    def execute(self, source, module_path, namespace_paths=None):
        module = types.ModuleType("pocket_demo")
        module.__file__ = module_path
        if namespace_paths is not None:
            module.__path__ = namespace_paths
        output = io.StringIO()
        with patch.object(importlib, "import_module", return_value=module):
            with contextlib.redirect_stdout(output):
                exec(source, {})
        return output.getvalue()

    def test_android_paths_and_python_string_characters(self):
        for path in ["/data/user/0/com.example.pocketpythonstd/cache/python-libraries/demo.zip",
                     "/data/data/com.example.pocketpythonstd/cache/python-libraries/demo.zip",
                     '/tmp/a "quote" and \n newline/library.zip',
                     "/tmp/中文/library.zip"]:
            with self.subTest(path=path):
                self.assertEqual("LIBRARY_IMPORT_OK\n",
                    self.execute(self.script(path), path + "/pocket_demo/__init__.py"))

    def test_real_module_conflict_still_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "Module conflict: pocket_demo"):
            self.execute(self.script("/approved/demo.zip"), "/other/demo.zip/pocket_demo.py")
        with self.assertRaisesRegex(RuntimeError, "Module conflict: pocket_demo"):
            self.execute(self.script("/approved/demo.zip"), "/approved/demo.zip-impostor/pocket_demo.py")

    def test_namespace_package_sources(self):
        script = self.script("/approved/matplotlib")
        self.assertEqual("LIBRARY_IMPORT_OK\n",self.execute(script,None,
            ["/approved/matplotlib/mpl_toolkits"]))
        with self.assertRaisesRegex(RuntimeError,"Module conflict"):
            self.execute(script,None,["/approved/matplotlib/mpl_toolkits","/other/mpl_toolkits"])
        with self.assertRaisesRegex(RuntimeError,"Module conflict"):
            self.execute(script,None,[])

    def test_standard_library_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "Reserved module: json"):
            self.execute(self.script("/approved/demo.zip", "json"), "/approved/demo.zip/json.py")

if __name__ == "__main__":
    unittest.main()
