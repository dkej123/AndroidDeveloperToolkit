package dev.acme.adbtoolbox.devicehelper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * The on-device locale helper (task 060), run as
 * {@code CLASSPATH=<pushed jar> app_process / dev.acme.adbtoolbox.devicehelper.LocaleMain <command>}
 * under the shell user, which holds {@code CHANGE_CONFIGURATION}.
 *
 * <p>{@code cmd locale} exists only on recent releases; this does what Settings does on every
 * release: take the activity manager's configuration, set its locales and mark them user-set, and
 * persist it with {@code IActivityManager.updatePersistentConfiguration}, called by name.
 *
 * <p>Commands: {@code get} and {@code set <BCP-47 tags, comma separated>}. Output: {@link #HEADER},
 * then {@code locales=<tags>} (after the command ran) or {@code error=<exception>}, then {@code END}.
 */
public final class LocaleMain {

    static final String HEADER = "ADBTOOLBOX-LOCALE 1";

    private LocaleMain() {
    }

    public static void main(String[] args) {
        System.out.println(HEADER);
        try {
            Object activityManager = activityManager();
            String command = args.length > 0 ? args[0] : "get";
            Class<?> configurationClass = Class.forName("android.content.res.Configuration");
            Class<?> localeListClass = Class.forName("android.os.LocaleList");
            if ("set".equals(command)) {
                Object configuration = activityManager.getClass().getMethod("getConfiguration").invoke(activityManager);
                Object locales = localeListClass.getMethod("forLanguageTags", String.class).invoke(null, args[1]);
                configurationClass.getMethod("setLocales", localeListClass).invoke(configuration, locales);
                configurationClass.getField("userSetLocale").setBoolean(configuration, true);
                persist(activityManager, configurationClass, configuration);
            } else if (!"get".equals(command)) {
                throw new IllegalArgumentException("unknown command " + command);
            }
            Object configuration = activityManager.getClass().getMethod("getConfiguration").invoke(activityManager);
            Object locales = configurationClass.getMethod("getLocales").invoke(configuration);
            System.out.println("locales=" + localeListClass.getMethod("toLanguageTags").invoke(locales));
        } catch (Throwable error) {
            Throwable cause = error instanceof InvocationTargetException && error.getCause() != null ? error.getCause() : error;
            System.out.println("error=" + String.valueOf(cause).replace('\n', ' '));
        }
        System.out.println("END");
    }

    /** {@code updatePersistentConfiguration(Configuration)}, or its API 33+ variant with the caller's package and attribution. */
    private static void persist(Object activityManager, Class<?> configurationClass, Object configuration) throws Exception {
        for (Method method : activityManager.getClass().getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getName().equals("updatePersistentConfigurationWithAttribution") && parameters.length == 3) {
                method.invoke(activityManager, configuration, "com.android.shell", null);
                return;
            }
        }
        activityManager.getClass().getMethod("updatePersistentConfiguration", configurationClass).invoke(activityManager, configuration);
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
