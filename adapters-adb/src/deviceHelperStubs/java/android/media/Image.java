package android.media;

import java.nio.ByteBuffer;

/** Compile-only stub of the platform class; the device provides the real one. */
public abstract class Image implements AutoCloseable {
    public abstract static class Plane {
        public abstract int getPixelStride();

        public abstract int getRowStride();

        public abstract ByteBuffer getBuffer();
    }

    public abstract int getWidth();

    public abstract int getHeight();

    public abstract Plane[] getPlanes();

    @Override
    public abstract void close();
}
