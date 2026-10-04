package android.os;

/** Compile-only stub of the platform class; the device provides the real one. */
public class HandlerThread extends Thread {
    public HandlerThread(String name) {
        throw new UnsupportedOperationException("stub");
    }

    public Looper getLooper() {
        throw new UnsupportedOperationException("stub");
    }

    public boolean quitSafely() {
        throw new UnsupportedOperationException("stub");
    }
}
