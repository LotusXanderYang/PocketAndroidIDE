"""Task-scoped, explicitly approved pure-Python ZIP libraries."""
import contextlib
import importlib
import json
import os
import sys
import tempfile

@contextlib.contextmanager
def library_paths(paths, cache_dir=None):
    if cache_dir:
        os.makedirs(cache_dir, exist_ok=True)
        os.environ["TMPDIR"] = cache_dir
        tempfile.tempdir = cache_dir
        if any(os.path.isdir(os.path.join(path, "matplotlib")) for path in paths):
            config = os.path.join(cache_dir, "matplotlib")
            os.makedirs(config, exist_ok=True)
            os.environ.setdefault("MPLCONFIGDIR", config)
            os.environ.setdefault("MPLBACKEND", "Agg")
    if getattr(sys, "_pocket_native_failure", False):
        raise RuntimeError("Native library initialization failed; restart the Python language pack")
    native = sorted(path for path in paths if os.path.isdir(path))
    previous_native = getattr(sys, "_pocket_native_selection", None)
    if previous_native is not None and previous_native != native:
        raise RuntimeError("Native library selection changed; restart the Python language pack")
    if native and previous_native is None:
        # Native extension types cannot safely be unloaded/re-imported in CPython.
        sys._pocket_native_failure = True
        sys._pocket_native_selection = native
        sys._pocket_native_handles = []
        import ctypes
        for path in native:
            with open(os.path.join(path, ".pocket-native.json"), encoding="utf-8") as source:
                preload = json.load(source)
            for name in preload:
                sys._pocket_native_handles.append(ctypes.CDLL(
                    os.path.join(path, "native-libs", name), mode=ctypes.RTLD_GLOBAL))
        sys._pocket_native_failure = False
    pure = [path for path in paths if path not in native]
    previous = list(sys.path)
    def belongs(module):
        location = getattr(module, "__file__", "") or ""
        return any(os.path.normcase(os.path.normpath(location)).startswith(
            os.path.normcase(os.path.normpath(path)) + os.sep) for path in pure)
    try:
        for name, module in list(sys.modules.items()):
            if belongs(module):
                sys.modules.pop(name, None)
        # Standard library retains priority; projects may shadow libraries as usual.
        sys.path.extend(paths)
        importlib.invalidate_caches()
        yield
    finally:
        for name, module in list(sys.modules.items()):
            if belongs(module):
                sys.modules.pop(name, None)
        sys.path[:] = previous
        for path in paths:
            sys.path_importer_cache.pop(path, None)
        importlib.invalidate_caches()
