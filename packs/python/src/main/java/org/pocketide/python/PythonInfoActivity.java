package org.pocketide.python;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.*;

/** Installed library management; discovery and interpreter work stay off the UI thread. */
public final class PythonInfoActivity extends Activity {
    private LinearLayout content;
    @Override protected void onCreate(Bundle state) { super.onCreate(state); refresh(); }
    private void refresh() {
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(24,24,24,24); scroll.addView(content); setContentView(scroll);
        TextView title = new TextView(this); title.setTextSize(20);
        title.setText("Python · 库扩展\nCPython 3.14 · 纯 Python ZIP 库\n"
            + "安装库 APK 后在这里验证并启用。更新或卸载后原验证失效。\n"
            + "库与用户脚本在 Python 包内执行；安装前请核对来源。\n");
        content.addView(title);
        Button refresh = new Button(this); refresh.setText("刷新已安装库");
        refresh.setOnClickListener(v -> refresh()); content.addView(refresh);
        Button restart = new Button(this); restart.setText("重启 Python 进程（原生库变更后）");
        restart.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("关闭 Python 进程")
            .setMessage("将结束运行或调试任务并关闭此页。已保存的库选择保留，随后从 IDE 重新运行。")
            .setNegativeButton("取消", null).setPositiveButton("关闭进程", (d,w) ->
                android.os.Process.killProcess(android.os.Process.myPid())).show());
        content.addView(restart);
        TextView progress = new TextView(this); progress.setText("正在读取…"); content.addView(progress);
        new Thread(() -> {
            java.util.List<PythonLibraries.Library> items = PythonLibraries.discover(this);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                content.removeView(progress);
                if (items.isEmpty()) { progress.setText("尚未安装库扩展 APK"); content.addView(progress); }
                for (PythonLibraries.Library item : items) {
                    Button button = new Button(this);
                    boolean enabled = false;
                    try { enabled = PythonLibraries.enabled(this, item); } catch (Exception ignored) { }
                    button.setText((item.name == null ? item.id : item.name)
                        + (item.error != null ? " · 不兼容" : enabled ? " · 已启用" : " · 未启用"));
                    final boolean active = enabled;
                    button.setOnClickListener(v -> inspect(item, active)); content.addView(button);
                }
            });
        }, "library-discovery").start();
    }
    private void inspect(PythonLibraries.Library item, boolean enabled) {
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("Python 库扩展")
            .setMessage(item.detail()).setNegativeButton("关闭", null);
        if (item.error == null) dialog.setPositiveButton(enabled ? "禁用" : "验证并启用", (d,w) -> {
            if (enabled) {
                try { PythonLibraries.setEnabled(this,item,false); refresh(); }
                catch (Exception e) { message(e.toString()); }
            } else {
                Toast.makeText(this,"正在验证，请等待…",Toast.LENGTH_LONG).show();
                new Thread(() -> {
                    String result;
                    java.util.concurrent.Future<String> verification = PythonPackService.verifyLibrary(getApplicationContext(),item);
                    try { result = verification.get(60,
                        java.util.concurrent.TimeUnit.SECONDS); }
                    catch (java.util.concurrent.ExecutionException e) {
                        Throwable cause = e.getCause() == null ? e : e.getCause();
                        result = "验证失败：" + cause;
                    }
                    catch (Exception e) { verification.cancel(true); result = "验证未确认完成：" + e + "\n请刷新查看状态；库代码可能仍在执行，可重启 Python 包进程。"; }
                    final String text=result;
                    runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) { message(text); refresh(); } });
                },"library-validation").start();
            }
        });
        dialog.show();
    }
    private void message(String text) { new AlertDialog.Builder(this).setMessage(text)
        .setPositiveButton("关闭",null).show(); }
}
