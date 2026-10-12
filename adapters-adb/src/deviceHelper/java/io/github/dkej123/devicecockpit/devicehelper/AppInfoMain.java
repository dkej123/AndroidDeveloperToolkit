package io.github.dkej123.devicecockpit.devicehelper;

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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The on-device app-info helper (ADR 0007): run by the plugin as
 * {@code CLASSPATH=<pushed jar> app_process / io.github.dkej123.devicecockpit.devicehelper.AppInfoMain <iconPx> [-n package]... [package...]}
 * under the shell user, it asks the device's own {@code PackageManager} for each package's
 * localized label, debuggable flag and launcher icon — none of which {@code dumpsys package}
 * exposes. With no package arguments it reports every installed application.
 *
 * <p>Output is line-based UTF-8, one record per package, so the host can publish rows as they are
 * parsed: a {@link #HEADER} line, then {@code P<TAB>package<TAB>d|-<TAB>base64(label)<TAB>base64(png)|-}
 * for a found package or {@code M<TAB>package} for one that is not installed, then {@code END}.
 * Labels are base64-encoded because they may contain tabs or newlines. A missing {@code END} tells
 * the host the run was cut short; {@code T<TAB>package}, printed before each package is described,
 * tells it which packages were in flight, and it reruns with those after {@code -n} (no icon).
 *
 * <p>Icons are loaded from each APK's own resources, as aya does, not through
 * {@code PackageManager.getApplicationIcon}/{@code getActivityIcon}: vendor builds route those
 * through their theming code, which in an {@code app_process} process (no fonts, no application)
 * can abort natively — Samsung's Android 16 did, losing every row after it. Packages are
 * described on a few threads, which halves a full run.
 */
public final class AppInfoMain {

    // Protocol header kept from the pre-rename "ADB Toolbox" name for compatibility (the IDE side parses it).
    static final String HEADER = "ADBTOOLBOX-APPINFO 1";
    private static final int FLAG_DEBUGGABLE = 2;
    private static final int THREADS = 4;

    private AppInfoMain() {
    }

    public static void main(String[] args) throws Exception {
        Writer out = new BufferedWriter(new OutputStreamWriter(System.out, "UTF-8"));
        out.write(HEADER);
        out.write('\n');
        out.flush();

        int iconSize = Integer.parseInt(args[0]);
        PackageManager packageManager = SystemContext.get().getPackageManager();

        Set<String> skipIcons = new HashSet<>();
        List<String> packages = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if ("-n".equals(args[i]) && i + 1 < args.length) {
                skipIcons.add(args[++i]);
            } else {
                packages.add(args[i]);
            }
        }
        if (packages.isEmpty()) {
            for (ApplicationInfo info : packageManager.getInstalledApplications(0)) {
                packages.add(info.packageName);
            }
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<?>> pending = new ArrayList<>();
        for (String packageName : packages) {
            pending.add(pool.submit(() -> {
                write(out, "T\t" + packageName);
                write(out, describe(packageManager, packageName, skipIcons.contains(packageName) ? 0 : iconSize));
                return null;
            }));
        }
        for (Future<?> task : pending) {
            task.get();
        }
        pool.shutdown();
        write(out, "END");
    }

    private static void write(Writer out, String line) throws Exception {
        synchronized (out) {
            out.write(line);
            out.write('\n');
            out.flush();
        }
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
        if (size <= 0) {
            return "-";
        }
        try {
            Drawable drawable = apkIcon(info, launcherIconResource(packageManager, info));
            if (drawable == null) {
                drawable = defaultIcon();
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
     * The icon resource the launcher shows: the launcher activity's own icon when it sets one —
     * apps may leave {@code <application android:icon>} unset or different — else the
     * application's. Only resolves the id; nothing is loaded through the vendor's icon code.
     */
    private static int launcherIconResource(PackageManager packageManager, ApplicationInfo info) throws Exception {
        try {
            Intent launch = packageManager.getLaunchIntentForPackage(info.packageName);
            if (launch != null) {
                Object resolved = PackageManager.class.getMethod("resolveActivity", Intent.class, int.class)
                    .invoke(packageManager, launch, 0);
                Object activity = resolved == null ? null : resolved.getClass().getField("activityInfo").get(resolved);
                int icon = activity == null ? 0 : (Integer) activity.getClass().getMethod("getIconResource").invoke(activity);
                if (icon != 0) {
                    return icon;
                }
            }
        } catch (Exception unavailable) {
            // Fall back to the application icon.
        }
        return ApplicationInfo.class.getField("icon").getInt(info);
    }

    /** The framework's default app icon, which {@code getApplicationIcon} returns for apps without one. */
    private static Drawable defaultIcon() throws Exception {
        Class<?> resourcesClass = Class.forName("android.content.res.Resources");
        Object system = resourcesClass.getMethod("getSystem").invoke(null);
        int id = (Integer) resourcesClass.getMethod("getIdentifier", String.class, String.class, String.class)
            .invoke(system, "sym_def_app_icon", "mipmap", "android");
        return (Drawable) resourcesClass.getMethod("getDrawable", int.class, Class.forName("android.content.res.Resources$Theme"))
            .invoke(system, id, null);
    }

    /**
     * Loads {@code iconRes} from the package's own APK (and its splits, where some apps keep their
     * resources) with a fresh {@code Resources}, the way aya does. {@code null} without an icon.
     */
    private static Drawable apkIcon(ApplicationInfo info, int iconRes) throws Exception {
        if (iconRes == 0) {
            return null;
        }
        Class<?> assetManagerClass = Class.forName("android.content.res.AssetManager");
        Object assets = assetManagerClass.getDeclaredConstructor().newInstance();
        Method addAssetPath = assetManagerClass.getMethod("addAssetPath", String.class);
        addAssetPath.invoke(assets, (String) ApplicationInfo.class.getField("sourceDir").get(info));
        String[] splits = (String[]) ApplicationInfo.class.getField("splitSourceDirs").get(info);
        if (splits != null) {
            for (String split : splits) {
                addAssetPath.invoke(assets, split);
            }
        }
        Class<?> metricsClass = Class.forName("android.util.DisplayMetrics");
        Object metrics = metricsClass.getDeclaredConstructor().newInstance();
        metricsClass.getMethod("setToDefaults").invoke(metrics);
        Class<?> configurationClass = Class.forName("android.content.res.Configuration");
        Object configuration = configurationClass.getDeclaredConstructor().newInstance();
        configurationClass.getMethod("setToDefaults").invoke(configuration);
        Class<?> resourcesClass = Class.forName("android.content.res.Resources");
        Object resources = resourcesClass.getConstructor(assetManagerClass, metricsClass, configurationClass)
            .newInstance(assets, metrics, configuration);
        return (Drawable) resourcesClass.getMethod("getDrawable", int.class, Class.forName("android.content.res.Resources$Theme"))
            .invoke(resources, iconRes, null);
    }

    private static String encode(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }
}
