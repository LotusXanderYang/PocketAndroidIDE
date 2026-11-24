package com.example.pocketpythonide;

/** Host checks for decode memory bounds; this does not exercise Android bitmap rendering. */
public final class ImagePreviewSmokeTest {
    public static void main(String[] args) {
        int[][] dimensions = {{640, 480}, {2000, 2000}, {8000, 8000},
                {1, 1000000}, {1000000, 1}, {Integer.MAX_VALUE, Integer.MAX_VALUE}};
        for (int[] size : dimensions) {
            int sample = ImagePreview.sampleSize(size[0], size[1]);
            long width = (size[0] + (long) sample - 1) / sample;
            long height = (size[1] + (long) sample - 1) / sample;
            if (sample < 1 || (sample & (sample - 1)) != 0 || width > 4096
                    || height > 4096 || width * height > 4_000_000)
                throw new AssertionError("Unsafe decode size: " + size[0] + "x" + size[1]);
        }
        if (ImagePreview.sampleSize(640, 480) != 1
                || ImagePreview.sampleSize(2000, 2000) != 1)
            throw new AssertionError("Ordinary plots must retain all pixels");
        if (!ImagePreview.supports("plot.PNG") || !ImagePreview.supports("photo.jpeg")
                || ImagePreview.supports("plot.png.py") || ImagePreview.supports("main.py"))
            throw new AssertionError("Image routing failed");
        System.out.println("Image preview bounds and routing OK (6 size cases)");
    }
}
