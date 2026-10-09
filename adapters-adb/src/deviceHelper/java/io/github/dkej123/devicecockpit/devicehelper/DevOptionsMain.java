package io.github.dkej123.devicecockpit.devicehelper;

import java.lang.reflect.InvocationTargetException;

/**
 * The on-device Developer-options helper (ADR 0012), run as
 * {@code CLASSPATH=<pushed jar> app_process / io.github.dkej123.devicecockpit.devicehelper.DevOptionsMain <command>}
 * under the shell user, which holds {@code SET_PROCESS_LIMIT} and {@code SET_ALWAYS_FINISH}.
 *
 * <p>"Background process limit" and "Don't keep activities" live inside the running activity
 * manager; Settings changes them through {@code IActivityManager}, whose binder transaction codes
 * differ between Android releases, so they are called here by name through reflection.
 *
 * <p>Commands: {@code get}, {@code process-limit <n>} ({@code -1} = standard) and
 * {@code always-finish 0|1}. Output: {@link #HEADER}, then {@code process_limit=<n>} (after the
 * command ran) or {@code error=<exception>}, then {@code END}.
 */
public final class DevOptionsMain {

    static final String HEADER = "ADBTOOLBOX-DEVOPTIONS 1";

    private DevOptionsMain() {
    }

    public static void main(String[] args) {
        System.out.println(HEADER);
        try {
            Object activityManager = activityManager();
            String command = args.length > 0 ? args[0] : "get";
            if ("process-limit".equals(command)) {
                activityManager.getClass().getMethod("setProcessLimit", int.class)
                        .invoke(activityManager, Integer.parseInt(args[1]));
            } else if ("always-finish".equals(command)) {
                activityManager.getClass().getMethod("setAlwaysFinish", boolean.class)
                        .invoke(activityManager, "1".equals(args[1]));
            } else if (!"get".equals(command)) {
                throw new IllegalArgumentException("unknown command " + command);
            }
            Object limit = activityManager.getClass().getMethod("getProcessLimit").invoke(activityManager);
            System.out.println("process_limit=" + limit);
        } catch (Throwable error) {
            Throwable cause = error instanceof InvocationTargetException && error.getCause() != null ? error.getCause() : error;
            System.out.println("error=" + String.valueOf(cause).replace('\n', ' '));
        }
        System.out.println("END");
    }

    /** {@code ActivityManager.getService()} (API 26+), else the older {@code ActivityManagerNative.getDefault()}. */
    private static Object activityManager() throws Exception {
        try {
            return Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        } catch (NoSuchMethodException olderRelease) {
            return Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
        }
    }
}
