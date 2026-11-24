package com.example.pocketpythonide;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Read-only, bounded image preview. Decoding and SAF access never run on the UI thread. */
final class ImagePreview {
    interface Source { InputStream open() throws IOException; }

    private static final long MAX_BYTES = 32L * 1024 * 1024;
    private final Activity activity;
    private final Dialog dialog;
    private final TextView status;
    private final Picture picture;
    private final Source source;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Future<?> pending;
    private int generation;
    private boolean closed;

    static boolean supports(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".webp") || lower.endsWith(".bmp") || lower.endsWith(".gif");
    }

    ImagePreview(Activity activity, String name, Source source, int surface, int foreground) {
        this.activity = activity;
        this.source = source;
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(surface);
        TextView title = new TextView(activity);
        title.setText("图像预览 · " + name);
        title.setTextColor(foreground);
        title.setTextSize(16);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        title.setPadding(dp(16), dp(12), dp(16), dp(8));
        root.addView(title);
        status = new TextView(activity);
        status.setTextColor(foreground);
        status.setTextSize(12);
        status.setPadding(dp(16), 0, dp(16), dp(8));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status);
        picture = new Picture(activity);
        picture.setBackgroundColor(surface);
        picture.setContentDescription(name + "，双指缩放，拖动平移；也可使用下方按钮和方向键");
        root.addView(picture, new LinearLayout.LayoutParams(-1, 0, 1));
        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        LinearLayout actions = new LinearLayout(activity);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(dp(8), dp(4), dp(8), dp(4));
        addAction(actions, "缩小", () -> picture.zoom(0.8f));
        addAction(actions, "放大", () -> picture.zoom(1.25f));
        addAction(actions, "适应", picture::fit);
        addAction(actions, "1:1", () -> picture.zoomTo(1f));
        addAction(actions, "刷新", this::load);
        addAction(actions, "关闭", this::close);
        scroll.addView(actions);
        root.addView(scroll);
        dialog.setContentView(root);
        dialog.setOnDismissListener(ignored -> dispose());
    }

    void show() {
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            window.setLayout((int) (metrics.widthPixels * 0.96f),
                    (int) (metrics.heightPixels * 0.90f));
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
        picture.requestFocus();
        load();
    }

    void close() { dialog.dismiss(); }

    private void dispose() {
        closed = true;
        generation++;
        if (pending != null) pending.cancel(true);
        worker.shutdownNow();
        picture.setBitmap(null);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private void addAction(LinearLayout row, String label, Runnable action) {
        Button button = new Button(activity);
        button.setText(label);
        button.setTextSize(13);
        button.setMinWidth(dp(64));
        button.setMinimumHeight(dp(48));
        button.setOnClickListener(ignored -> action.run());
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, dp(48));
        size.setMargins(dp(4), 0, dp(4), 0);
        row.addView(button, size);
    }

    private void load() {
        if (closed) return;
        final int request = ++generation;
        if (pending != null) pending.cancel(true);
        picture.setBitmap(null);
        status.setText("正在读取图片…");
        pending = worker.submit(() -> {
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                try (InputStream input = boundedSource()) {
                    BitmapFactory.decodeStream(input, null, bounds);
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
                    throw new IOException("文件已损坏或不是支持的图片格式");
                if (Thread.currentThread().isInterrupted()) return;
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
                options.inScaled = false;
                Bitmap bitmap;
                try (InputStream input = boundedSource()) {
                    bitmap = BitmapFactory.decodeStream(input, null, options);
                }
                if (bitmap == null) throw new IOException("无法解码图片（文件损坏或超过 32 MB）");
                final Bitmap result = bitmap;
                main.post(() -> {
                    if (closed || request != generation || activity.isDestroyed()) {
                        result.recycle();
                        return;
                    }
                    picture.setBitmap(result);
                    status.setText(bounds.outWidth + " × " + bounds.outHeight + " px · 只读"
                            + (options.inSampleSize > 1 ? " · 大图已降采样，1:1 对应预览像素" : "")
                            + (sourceNameIsGif(bounds) ? " · GIF 显示首帧" : "")
                            + "\n双指缩放／拖动 · 图片更新后点刷新");
                });
            } catch (Exception | OutOfMemoryError error) {
                main.post(() -> {
                    if (!closed && request == generation && !activity.isDestroyed())
                        status.setText("预览失败：" + (error instanceof OutOfMemoryError
                                ? "图片过大，内存不足" : error.getMessage()));
                });
            }
        });
    }

    private static boolean sourceNameIsGif(BitmapFactory.Options options) {
        return "image/gif".equals(options.outMimeType);
    }

    // Bound both axes and decoded memory, including unusually tall/wide images.
    static int sampleSize(int width, int height) {
        int sample = 1;
        while ((long) ((width + (long) sample - 1) / sample)
                        * ((height + (long) sample - 1) / sample) > 4_000_000
                || (width + (long) sample - 1) / sample > 4096
                || (height + (long) sample - 1) / sample > 4096) sample *= 2;
        return sample;
    }

    private InputStream boundedSource() throws IOException {
        InputStream input = source.open();
        if (input == null) throw new IOException("无法读取图片，请检查工作区访问权限");
        return new FilterInputStream(input) {
            private long count;
            private void consumed(long amount) throws IOException {
                if (Thread.currentThread().isInterrupted())
                    throw new java.io.InterruptedIOException("预览已取消");
                count += Math.max(0, amount);
                if (count > MAX_BYTES) throw new IOException("图片超过 32 MB 预览限制");
            }
            @Override public int read() throws IOException {
                int value = in.read();
                consumed(value < 0 ? 0 : 1);
                return value;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                int read = in.read(bytes, offset, length);
                consumed(read);
                return read;
            }
            @Override public long skip(long amount) throws IOException {
                long skipped = in.skip(Math.min(amount, MAX_BYTES + 1 - count));
                consumed(skipped);
                return skipped;
            }
        };
    }

    private static final class Picture extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final ScaleGestureDetector scaling;
        private final GestureDetector gestures;
        private Bitmap bitmap;
        private float scale = 1, x, y;
        private boolean fitted = true;

        Picture(Activity activity) {
            super(activity);
            setFocusable(true);
            setFocusableInTouchMode(true);
            setClickable(true);
            scaling = new ScaleGestureDetector(activity,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override public boolean onScale(ScaleGestureDetector detector) {
                            zoomAt(scale * detector.getScaleFactor(), detector.getFocusX(),
                                    detector.getFocusY());
                            return true;
                        }
                    });
            gestures = new GestureDetector(activity, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent event) { return true; }
                @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                    return performClick();
                }
                @Override public boolean onScroll(MotionEvent first, MotionEvent second,
                        float dx, float dy) {
                    if (!scaling.isInProgress()) { x -= dx; y -= dy; clamp(); invalidate(); }
                    return true;
                }
                @Override public boolean onDoubleTap(MotionEvent event) {
                    if (scale > fitScale() * 1.05f) fit();
                    else zoomAt(Math.max(1f, fitScale() * 2), event.getX(), event.getY());
                    return true;
                }
            });
        }

        void setBitmap(Bitmap value) { bitmap = value; fit(); }
        float fitScale() {
            if (bitmap == null || getWidth() == 0 || getHeight() == 0) return 1;
            return Math.min((float) getWidth() / bitmap.getWidth(),
                    (float) getHeight() / bitmap.getHeight());
        }
        void fit() {
            fitted = true;
            scale = fitScale();
            x = bitmap == null ? 0 : (getWidth() - bitmap.getWidth() * scale) / 2;
            y = bitmap == null ? 0 : (getHeight() - bitmap.getHeight() * scale) / 2;
            invalidate();
        }
        void zoom(float factor) { zoomTo(scale * factor); }
        void zoomTo(float value) { zoomAt(value, getWidth() / 2f, getHeight() / 2f); }
        void zoomAt(float value, float focusX, float focusY) {
            if (bitmap == null) return;
            float target = Math.max(Math.min(fitScale(), 1f), Math.min(Math.max(8f,
                    fitScale() * 8), value));
            x = focusX - (focusX - x) * target / scale;
            y = focusY - (focusY - y) * target / scale;
            scale = target;
            fitted = false;
            clamp();
            invalidate();
        }
        private void clamp() {
            if (bitmap == null) return;
            float width = bitmap.getWidth() * scale, height = bitmap.getHeight() * scale;
            x = width <= getWidth() ? (getWidth() - width) / 2
                    : Math.max(getWidth() - width, Math.min(0, x));
            y = height <= getHeight() ? (getHeight() - height) / 2
                    : Math.max(getHeight() - height, Math.min(0, y));
        }
        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            if (fitted) fit(); else { clamp(); invalidate(); }
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (bitmap == null) return;
            RectF rect = new RectF(x, y, x + bitmap.getWidth() * scale,
                    y + bitmap.getHeight() * scale);
            paint.setColor(android.graphics.Color.WHITE);
            canvas.drawRect(rect, paint);
            canvas.drawBitmap(bitmap, null, rect, paint);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            scaling.onTouchEvent(event);
            gestures.onTouchEvent(event);
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
        @Override public boolean onKeyDown(int key, KeyEvent event) {
            switch (key) {
                case KeyEvent.KEYCODE_PLUS:
                case KeyEvent.KEYCODE_EQUALS: zoom(1.25f); return true;
                case KeyEvent.KEYCODE_MINUS: zoom(0.8f); return true;
                case KeyEvent.KEYCODE_0: fit(); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT: x += 48; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: x -= 48; break;
                case KeyEvent.KEYCODE_DPAD_UP: y += 48; break;
                case KeyEvent.KEYCODE_DPAD_DOWN: y -= 48; break;
                default: return super.onKeyDown(key, event);
            }
            clamp();
            invalidate();
            return true;
        }
    }
}
