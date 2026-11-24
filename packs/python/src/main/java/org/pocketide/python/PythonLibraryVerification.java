package org.pocketide.python;

/** Generates Python source with Python string syntax, independently of JSON escaping. */
final class PythonLibraryVerification {
    static String script(String importsJson, String archivePath) {
        return "import importlib, sys, json\n"
            + "names = json.loads(" + literal(importsJson) + ")\n"
            + "for name in names:\n"
            + "    if name in sys.stdlib_module_names or name.startswith('_pocket') or name in ('python_pack_bridge', 'pocket_debug_adapter', 'sitecustomize', 'usercustomize'):\n"
            + "        raise RuntimeError('Reserved module: ' + name)\n"
            + "    module = importlib.import_module(name)\n"
            + "    filename = getattr(module, '__file__', None)\n"
            + "    locations = [filename] if filename else list(getattr(module, '__path__', []))\n"
            + "    if not locations or any(not location.startswith("
            + literal(archivePath + "/") + ") for location in locations):\n"
            + "        raise RuntimeError('Module conflict: ' + name)\n"
            + "print('LIBRARY_IMPORT_OK')\n";
    }

    private static String literal(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '"') out.append('\\').append(c);
            else if (c < 32 || c == 127)
                out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int)c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
}
