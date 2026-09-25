package android.graphics;

import java.io.OutputStream;

/** Compile-only stub of the platform class; the device provides the real one. */
public final class Bitmap {
    public enum Config { ARGB_8888 }

    public enum CompressFormat { PNG }

    public static Bitmap createBitmap(int width, int height, Config config) {
        throw new UnsupportedOperationException("stub");
    }

    public boolean compress(CompressFormat format, int quality, OutputStream stream) {
        throw new UnsupportedOperationException("stub");
    }

    public void recycle() {
        throw new UnsupportedOperationException("stub");
    }
}
