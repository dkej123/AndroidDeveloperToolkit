package dev.acme.adbtoolbox.devicehelper;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The on-device app-info helper (ADR 0007): run by the plugin as
 * {@code CLASSPATH=<pushed jar> app_process / dev.acme.adbtoolbox.devicehelper.AppInfoMain <iconPx> [package...]}
 * under the shell user, it asks the device's own {@code PackageManager} for each package's
 * localized label, debuggable flag and launcher icon — none of which {@code dumpsys package}
 * exposes. With no package arguments it reports every installed application.
 *
 * <p>Output is line-based UTF-8, one record per package, so the host can publish rows as they are
 * parsed: a {@link #HEADER} line, then {@code P<TAB>package<TAB>d|-<TAB>base64(label)<TAB>base64(png)|-}
 * for a found package or {@code M<TAB>package} for one that is not installed, then {@code END}.
 * Labels are base64-encoded because they may contain tabs or newlines. A missing {@code END} tells
 * the host the run was cut short.
 */
public final class AppInfoMain {

    static final String HEADER = "ADBTOOLBOX-APPINFO 1";
    private static final int FLAG_DEBUGGABLE = 2;

    private AppInfoMain() {
    }

    public static void main(String[] args) throws Exception {
        Writer out = new BufferedWriter(new OutputStreamWriter(System.out, "UTF-8"));
        out.write(HEADER);
        out.write('\n');
        out.flush();

        int iconSize = Integer.parseInt(args[0]);
        PackageManager packageManager = SystemContext.get().getPackageManager();

        List<String> packages = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            packages.add(args[i]);
        }
        if (packages.isEmpty()) {
            for (ApplicationInfo info : packageManager.getInstalledApplications(0)) {
                packages.add(info.packageName);
            }
        }

        for (String packageName : packages) {
            out.write(describe(packageManager, packageName, iconSize));
            out.write('\n');
            out.flush();
        }
        out.write("END\n");
        out.flush();
    }

    private static String describe(PackageManager packageManager, String packageName, int iconSize) {
        ApplicationInfo info;
        try {
            info = packageManager.getApplicationInfo(packageName, 0);
        } catch (Exception notInstalled) {
            return "M\t" + packageName;
        }
        String label;
        try {
            label = String.valueOf(packageManager.getApplicationLabel(info));
        } catch (Exception unresolved) {
            label = packageName;
        }
        String debuggable = (info.flags & FLAG_DEBUGGABLE) != 0 ? "d" : "-";
        return "P\t" + packageName + "\t" + debuggable + "\t" + encode(label.getBytes(StandardCharsets.UTF_8))
            + "\t" + icon(packageManager, info, iconSize);
    }

    private static String icon(PackageManager packageManager, ApplicationInfo info, int size) {
        try {
            Drawable drawable = launcherIcon(packageManager, info.packageName);
            if (drawable == null) {
                drawable = packageManager.getApplicationIcon(info);
            }
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            drawable.setBounds(0, 0, size, size);
            drawable.draw(new Canvas(bitmap));
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, png);
            bitmap.recycle();
            return encode(png.toByteArray());
        } catch (Throwable unavailable) {
            // Any icon failure (resources missing, a drawable that cannot render off-screen) only
            // costs this row its thumbnail; the label and flags are still reported.
            return "-";
        }
    }

    /**
     * The icon the launcher shows, which is the launcher activity's own icon when it sets one —
     * apps may leave {@code <application android:icon>} unset or different. {@code null} for apps
     * without a launcher activity or whose activity icon cannot be loaded.
     */
    private static Drawable launcherIcon(PackageManager packageManager, String packageName) {
        try {
            Intent launch = packageManager.getLaunchIntentForPackage(packageName);
            return launch == null ? null : packageManager.getActivityIcon(launch);
        } catch (Exception unavailable) {
            return null;
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }
}
