package android.media;

import android.os.Handler;
import android.view.Surface;

/** Compile-only stub of the platform class; the device provides the real one. */
public class ImageReader implements AutoCloseable {
    public interface OnImageAvailableListener {
        void onImageAvailable(ImageReader reader);
    }

    public static ImageReader newInstance(int width, int height, int format, int maxImages) {
        throw new UnsupportedOperationException("stub");
    }

    public Surface getSurface() {
        throw new UnsupportedOperationException("stub");
    }

    public Image acquireLatestImage() {
        throw new UnsupportedOperationException("stub");
    }

    public void setOnImageAvailableListener(OnImageAvailableListener listener, Handler handler) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public void close() {
        throw new UnsupportedOperationException("stub");
    }
}
