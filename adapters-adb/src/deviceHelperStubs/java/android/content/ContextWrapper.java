package android.content;

import android.content.pm.PackageManager;

/** Compile-only stub of the platform class; the device provides the real one. */
public class ContextWrapper extends Context {
    public ContextWrapper(Context base) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public PackageManager getPackageManager() {
        throw new UnsupportedOperationException("stub");
    }

    public String getPackageName() {
        throw new UnsupportedOperationException("stub");
    }

    public Context getApplicationContext() {
        throw new UnsupportedOperationException("stub");
    }
}
