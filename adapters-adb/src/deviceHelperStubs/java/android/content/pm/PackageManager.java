package android.content.pm;

import android.graphics.drawable.Drawable;
import java.util.List;

/** Compile-only stub of the platform class; the device provides the real one. */
public abstract class PackageManager {
    public abstract ApplicationInfo getApplicationInfo(String packageName, int flags) throws Exception;

    public abstract List<ApplicationInfo> getInstalledApplications(int flags);

    public abstract CharSequence getApplicationLabel(ApplicationInfo info);

    public abstract Drawable getApplicationIcon(ApplicationInfo info);
}
