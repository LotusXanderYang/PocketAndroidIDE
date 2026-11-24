package com.example.pocketpythonide;

import android.content.Context;

import org.json.JSONObject;
import org.pocketide.python.RuntimeInstaller;
import org.pocketide.python.PythonLibraries;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** JNI symbols match the already audited Lab bridge, but run in this pack's UID. */
public final class NativePythonBridge {
    static { System.loadLibrary("nativepythonbridge"); }

    private NativePythonBridge() { }

    private static native String initialize(String pythonHome);
    private static native int run(byte[] utf8Code);

    public static JSONObject execute(Context context, JSONObject command) throws Exception {
        File home = RuntimeInstaller.ensure(context);
        String failure = initialize(home.getAbsolutePath());
        if (failure != null) throw new IOException(failure);
        File input = File.createTempFile("python-pack-", ".json", context.getCacheDir());
        File output = new File(input.getAbsolutePath() + ".result");
        File diagnostic = new File(input.getAbsolutePath() + ".diagnostic");
        try {
            Files.write(input.toPath(), command.toString().getBytes(StandardCharsets.UTF_8));
            String script = "try:\n"
                    + "    import python_pack_bridge\n"
                    + "    python_pack_bridge.main(" + quote(input.getAbsolutePath())
                    + ", " + quote(output.getAbsolutePath()) + ")\n"
                    + "except BaseException:\n"
                    + "    import traceback\n"
                    + "    with open(" + quote(diagnostic.getAbsolutePath())
                    + ", 'w', encoding='utf-8') as log:\n"
                    + "        log.write(traceback.format_exc())\n";
            script = "with _pocket_library_scope['library_paths'](_pocket_library_paths, _pocket_library_cache):\n"
                    + "    " + script.replace("\n", "\n    ");
            int code = run((libraryBootstrap(context, command) + script).getBytes(StandardCharsets.UTF_8));
            if (code != 0 || !output.isFile()) {
                String detail = diagnostic.isFile()
                        ? new String(Files.readAllBytes(diagnostic.toPath()), StandardCharsets.UTF_8)
                        : "CPython command failed before producing a result (native=" + code + ")";
                throw new IOException(detail);
            }
            if (output.length() > 4L * 1024 * 1024)
                throw new IOException("Python command result exceeds 4 MiB");
            return new JSONObject(new String(Files.readAllBytes(output.toPath()),
                    StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(input.toPath());
            Files.deleteIfExists(output.toPath());
            Files.deleteIfExists(diagnostic.toPath());
        }
    }

    /** Run the optional DAP adapter on the interpreter's process-lifetime thread. */
    public static JSONObject debug(Context context, JSONObject command, int channelFd,
            File ready) throws Exception {
        File home = RuntimeInstaller.ensure(context);
        String failure = initialize(home.getAbsolutePath());
        if (failure != null) throw new IOException(failure);
        File support = new File(context.getFilesDir(), "debug-support");
        if (!support.mkdirs() && !support.isDirectory())
            throw new IOException("Cannot create Python debugger support directory");
        File module = new File(support, "pocket_debug_adapter.py");
        File staging = File.createTempFile("debug-adapter-", ".py", support);
        try {
            try (InputStream source = context.getAssets().open("pocket_debug_adapter.py");
                    FileOutputStream target = new FileOutputStream(staging)) {
                byte[] block = new byte[8192];
                int n;
                while ((n = source.read(block)) != -1) target.write(block, 0, n);
                target.getFD().sync();
            }
            Files.move(staging.toPath(), module.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(staging.toPath()); }
        File input = File.createTempFile("python-debug-", ".json", context.getCacheDir());
        File output = new File(input.getAbsolutePath() + ".result");
        File diagnostic = new File(input.getAbsolutePath() + ".diagnostic");
        try {
            Files.write(input.toPath(), command.toString().getBytes(StandardCharsets.UTF_8));
            String script = "try:\n"
                    + "    import sys\n"
                    + "    sys.path.insert(0, " + quote(support.getAbsolutePath()) + ")\n"
                    + "    try:\n"
                    + "        import pocket_debug_adapter\n"
                    + "        pocket_debug_adapter.main(" + quote(input.getAbsolutePath())
                    + ", " + quote(output.getAbsolutePath()) + ", "
                    + channelFd + ", " + quote(ready.getAbsolutePath()) + ")\n"
                    + "    finally:\n"
                    + "        sys.path.remove(" + quote(support.getAbsolutePath()) + ")\n"
                    + "except BaseException:\n"
                    + "    import traceback\n"
                    + "    with open(" + quote(diagnostic.getAbsolutePath())
                    + ", 'w', encoding='utf-8') as log:\n"
                    + "        log.write(traceback.format_exc())\n";
            script = "with _pocket_library_scope['library_paths'](_pocket_library_paths, _pocket_library_cache):\n"
                    + "    " + script.replace("\n", "\n    ");
            int code = run((libraryBootstrap(context, command) + script).getBytes(StandardCharsets.UTF_8));
            if (code != 0 || !output.isFile()) {
                String detail = diagnostic.isFile()
                        ? new String(Files.readAllBytes(diagnostic.toPath()), StandardCharsets.UTF_8)
                        : "Embedded Python debugger failed before producing a result (native="
                            + code + ")";
                throw new IOException(detail);
            }
            return new JSONObject(new String(Files.readAllBytes(output.toPath()),
                    StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(input.toPath());
            Files.deleteIfExists(output.toPath());
            Files.deleteIfExists(diagnostic.toPath());
        }
    }

    private static String libraryBootstrap(Context context, JSONObject command) throws Exception {
        String support;
        try (InputStream input = context.getAssets().open("python_library_support.py")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int n;
            while ((n = input.read(buffer)) != -1) bytes.write(buffer, 0, n);
            support = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
        org.json.JSONArray paths = command.optJSONArray("libraryPaths");
        if (paths == null) paths = PythonLibraries.enabledPaths(context);
        return "import json\n_pocket_library_cache = " + quote(context.getCacheDir().getAbsolutePath())
                + "\n_pocket_library_scope = {}\nexec(" + quote(support)
                + ", _pocket_library_scope)\n_pocket_library_paths = json.loads("
                + quote(paths.toString()) + ")\n";
    }

    private static String quote(String text) {
        return JSONObject.quote(text).replace("\\/", "/");
    }
}
