package org.pocketide.python;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Resource-only APK discovery. No third-party Android class is loaded. */
public final class PythonLibraries {
    public static final String ACTION = "org.pocketide.python.library.v1.LIBRARY";
    private static final int LIMIT = 64 * 1024 * 1024;
    public static final class Library {
        public String id, name, fingerprint, identity, certificate, permissions, error;
        public JSONObject manifest;
        public File archive;
        public String detail() {
            return error != null ? id + "\n" + error : name + " " + manifest.optString("version")
                + "\n" + id + "\n来源：" + manifest.optString("source")
                + "\n许可证：" + manifest.optString("license")
                + "\n模块：" + manifest.optJSONArray("imports")
                + "\n证书 SHA-256：" + certificate + "\nAPK SHA-256：" + fingerprint
                + "\nAndroid 权限：" + permissions
                + "\n\n启用后库代码将在 Python 语言包进程执行，拥有该进程权限。请核对来源。";
        }
    }
    public static List<Library> discover(Context context) {
        List<Library> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo info : context.getPackageManager().queryIntentActivities(
                new Intent(ACTION), 0)) {
            if (!seen.add(info.activityInfo.packageName)) continue;
            Library item = new Library(); item.id = info.activityInfo.packageName;
            try { inspect(context, item); }
            catch (Exception failure) { item.error = failure.toString(); }
            result.add(item);
        }
        result.sort(Comparator.comparing(item -> item.id));
        return result;
    }
    private static synchronized void inspect(Context context, Library item) throws Exception {
        PackageManager pm = context.getPackageManager();
        PackageInfo installed = pm.getPackageInfo(item.id, PackageManager.GET_PERMISSIONS
                | (Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES
                    : PackageManager.GET_SIGNATURES));
        if (installed.applicationInfo.splitSourceDirs != null)
            throw new IOException("库扩展 v1 只接受独立单 APK");
        android.content.pm.Signature[] signatures = Build.VERSION.SDK_INT >= 28
                ? installed.signingInfo.getApkContentsSigners() : installed.signatures;
        if (signatures.length != 1) throw new IOException("需要单签名 APK");
        item.certificate = digest(signatures[0].toByteArray());
        item.permissions = Arrays.toString(installed.requestedPermissions == null
                ? new String[0] : installed.requestedPermissions);
        File apk = new File(installed.applicationInfo.sourceDir);
        item.fingerprint = hash(apk);
        item.identity = item.fingerprint + ":" + installed.firstInstallTime + ":" + installed.lastUpdateTime;
        try (ZipFile zip = new ZipFile(apk)) {
            item.manifest = new JSONObject(new String(read(zip, "assets/python-library.json", 65536),
                    StandardCharsets.UTF_8));
            JSONObject m = item.manifest;
            boolean nativeMode = "cp314-native-aarch64-v1".equals(m.optString("runtime"))
                    && "android-native".equals(m.optString("kind"));
            if (nativeMode && !Arrays.asList(android.os.Build.SUPPORTED_ABIS).contains("arm64-v8a"))
                throw new IOException("需要 ARM64 Android");
            if (m.getInt("schemaVersion") != 1 || !item.id.equals(m.getString("packId"))
                    || !(nativeMode || ("cp314-pure-v1".equals(m.getString("runtime"))
                        && "pure-python".equals(m.getString("kind"))))
                    || !m.getString("source").startsWith("https://")
                    || m.getString("license").isEmpty() || m.getString("version").isEmpty()
                    || m.getJSONArray("dependencies").length() != 0)
                throw new IOException("不兼容的库清单；v1 要求依赖已内置的纯 Python 库");
            item.name = m.getString("displayName");
            byte[] payload = read(zip, "assets/library.zip", nativeMode ? LIMIT : 16 * 1024 * 1024);
            if (!digest(payload).equals(m.getString("sha256")))
                throw new IOException("库内容 SHA-256 不匹配");
            validate(payload, m.getJSONArray("imports"), nativeMode);
            File cache = new File(context.getCacheDir(), "python-libraries");
            if (!cache.mkdirs() && !cache.isDirectory()) throw new IOException("无法创建库缓存");
            item.archive = new File(cache, item.fingerprint + ".zip");
            if (!item.archive.isFile() || !hash(item.archive).equals(m.getString("sha256"))) {
                File temporary = File.createTempFile("library-", ".tmp", cache);
                try {
                    Files.write(temporary.toPath(), payload);
                    Files.move(temporary.toPath(), item.archive.toPath(),
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally { temporary.delete(); }
            }
            if (nativeMode) item.archive = extractNative(context, item, payload);
        }
    }
    private static File extractNative(Context context, Library item, byte[] payload) throws Exception {
        File root = new File(context.getFilesDir(), "native-libraries");
        if (!root.mkdirs() && !root.isDirectory()) throw new IOException("无法创建原生库目录");
        File target = new File(root, item.fingerprint);
        if (new File(target, ".complete").isFile()) return target;
        File stage = Files.createTempDirectory(root.toPath(), "stage-").toFile();
        try {
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(payload))) {
                ZipEntry member; byte[] block = new byte[8192];
                while ((member = zip.getNextEntry()) != null) {
                    File file = new File(stage, member.getName()).getCanonicalFile();
                    if (!file.getPath().startsWith(stage.getCanonicalPath() + File.separator))
                        throw new IOException("原生库路径越界");
                    if (member.isDirectory()) { file.mkdirs(); continue; }
                    file.getParentFile().mkdirs();
                    try (OutputStream output = Files.newOutputStream(file.toPath())) {
                        int n; while ((n=zip.read(block))!=-1) output.write(block,0,n);
                    }
                }
            }
            JSONArray preload = item.manifest.getJSONArray("preload");
            for (int i=0;i<preload.length();i++) {
                String name=preload.getString(i);
                if (!name.matches("[A-Za-z0-9_+.-]+[.]so([.][0-9]+)*")
                        || !new File(stage,"native-libs/" + name).isFile())
                    throw new IOException("无效 preload：" + name);
            }
            Files.write(new File(stage,".pocket-native.json").toPath(),
                preload.toString().getBytes(StandardCharsets.UTF_8));
            Files.write(new File(stage,".complete").toPath(), new byte[]{1});
            Files.move(stage.toPath(),target.toPath(), StandardCopyOption.ATOMIC_MOVE);
            return target;
        } finally {
            if (stage.exists()) {
                try (java.util.stream.Stream<java.nio.file.Path> files = Files.walk(stage.toPath())) {
                    for (java.nio.file.Path file : (Iterable<java.nio.file.Path>)files.sorted(Comparator.reverseOrder())::iterator)
                        Files.delete(file);
                }
            }
        }
    }
    private static void validate(byte[] bytes, JSONArray imports, boolean nativeMode) throws Exception {
        Set<String> roots = new HashSet<>(), paths = new HashSet<>(), modules = new HashSet<>();
        if (imports.length() == 0 || imports.length() > 64) throw new IOException("无效 imports");
        for (int i=0; i<imports.length(); i++) {
            String name = imports.getString(i);
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*") || !roots.add(name))
                throw new IOException("无效或重复模块名");
        }
        long total = 0; int count = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry; byte[] block = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                String path = entry.getName();
                if (path.startsWith("/") || path.contains("\\") || path.contains(":")
                        || Arrays.asList(path.split("/", -1)).contains("..")
                        || !paths.add(path) || ++count > (nativeMode ? 12000 : 4000)) throw new IOException("不安全的库路径");
                if (entry.isDirectory()) continue;
                String lower = path.toLowerCase(Locale.ROOT);
                boolean nativeFile = lower.matches(".*[.]so([.][0-9]+)*");
                if ((!nativeMode && nativeFile) || lower.endsWith(".pyd") || lower.endsWith(".pyc")
                        || lower.endsWith(".pth") || lower.endsWith(".dll"))
                    throw new IOException("v1 不接受原生扩展、字节码或启动钩子");
                String root = path.contains("/") ? path.substring(0, path.indexOf('/'))
                        : path.endsWith(".py") ? path.substring(0, path.length()-3) : "";
                if (!(roots.contains(root) || (nativeMode && root.equals("native-libs"))
                        || (nativeMode && root.endsWith(".dist-info")))) throw new IOException("未声明的顶层路径：" + path);
                if (path.endsWith(".py")) modules.add(root);
                int n; long size = 0;
                ByteArrayOutputStream header = new ByteArrayOutputStream();
                while ((n = zip.read(block)) != -1) {
                    header.write(block, 0, Math.min(n, 20 - header.size()));
                    size += n; total += n;
                    if (size > (nativeMode ? 64L : 4L)*1024*1024 || total > (nativeMode ? 256L : 32L)*1024*1024)
                        throw new IOException("库解压大小超限");
                }
                if (nativeFile) {
                    byte[] elf = header.toByteArray();
                    if (elf.length != 20 || elf[0] != 127 || elf[1] != 'E' || elf[2] != 'L'
                        || elf[3] != 'F' || elf[4] != 2 || elf[5] != 1
                        || (elf[18] & 255) != 183 || elf[19] != 0)
                        throw new IOException("不是 AArch64 ELF：" + path);
                }
            }
        }
        if (!modules.equals(roots)) throw new IOException("声明模块缺少源码");
    }
    private static byte[] read(ZipFile zip, String name, int max) throws Exception {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) throw new IOException("缺少 " + name);
        try (InputStream input = zip.getInputStream(entry);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] block = new byte[8192]; int n;
            while ((n=input.read(block)) != -1) {
                if (output.size()+n > max) throw new IOException("资源大小超限");
                output.write(block,0,n);
            }
            return output.toByteArray();
        }
    }
    private static JSONObject approvals(Context context) throws Exception {
        File path = new File(context.getFilesDir(), "python-library-approvals.json");
        if (!path.exists()) return new JSONObject();
        if (path.length() > 65536) throw new IOException("库批准记录过大");
        return new JSONObject(new String(Files.readAllBytes(path.toPath()), StandardCharsets.UTF_8));
    }
    public static boolean enabled(Context context, Library item) throws Exception {
        return item.error == null && item.identity.equals(approvals(context).optString(item.id));
    }
    public static synchronized void setEnabled(Context context, Library item, boolean enabled)
            throws Exception {
        JSONObject state = approvals(context);
        if (enabled) state.put(item.id, item.identity); else state.remove(item.id);
        File temporary = File.createTempFile("library-approval-", ".json", context.getFilesDir());
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(state.toString().getBytes(StandardCharsets.UTF_8)); output.getFD().sync();
            }
            Files.move(temporary.toPath(), new File(context.getFilesDir(),
                    "python-library-approvals.json").toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally { temporary.delete(); }
    }
    public static JSONArray enabledPaths(Context context) throws Exception {
        JSONArray paths = new JSONArray(); Set<String> roots = new HashSet<>();
        for (Library library : discover(context)) if (enabled(context, library)) {
            if (paths.length() >= 16) throw new IOException("最多启用 16 个库包");
            JSONArray names = library.manifest.getJSONArray("imports");
            for (int i=0;i<names.length();i++) if (!roots.add(names.getString(i)))
                throw new IOException("库模块冲突：" + names.getString(i));
            paths.put(library.archive.getAbsolutePath());
        }
        return paths;
    }
    public static String hash(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file.toPath())) {
            byte[] block=new byte[8192]; int n;
            while ((n=input.read(block))!=-1) md.update(block,0,n);
        }
        return hex(md.digest());
    }
    private static String digest(byte[] bytes) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static String hex(byte[] bytes) {
        StringBuilder out=new StringBuilder();
        for (byte b:bytes) out.append(String.format(Locale.ROOT,"%02x",b&255));
        return out.toString();
    }
}
