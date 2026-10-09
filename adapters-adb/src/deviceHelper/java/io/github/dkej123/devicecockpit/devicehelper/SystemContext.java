package io.github.dkej123.devicecockpit.devicehelper;

import android.content.Context;
import android.os.Looper;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** The helper entry points' shared access to the system context. */
final class SystemContext {

    private static Context instance;

    private SystemContext() {
    }

    /**
     * A process started by {@code app_process} has no application context. The system context of
     * a freshly constructed {@code ActivityThread} is enough for read-only {@code PackageManager}
     * queries as the shell user; both are hidden APIs, hence the reflection.
     */
    static synchronized Context get() throws Exception {
        if (instance != null) {
            return instance;
        }
        Looper.prepareMainLooper();
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Constructor<?> constructor = activityThreadClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object activityThread = constructor.newInstance();
        Field current = activityThreadClass.getDeclaredField("sCurrentActivityThread");
        current.setAccessible(true);
        current.set(null, activityThread);
        Method getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext");
        instance = (Context) getSystemContext.invoke(activityThread);
        installInitialApplication(activityThreadClass, activityThread, instance);
        return instance;
    }

    /**
     * Inflating XML drawables (adaptive and vector launcher icons, even the framework's default app
     * icon) reaches {@code ActivityThread.currentApplication().getResources()} on recent platforms,
     * which throws in a process that never created an {@code Application}. Registering one backed by
     * the system context lets those drawables load. Best-effort: older platforms do not need it.
     */
    private static void installInitialApplication(Class<?> activityThreadClass, Object activityThread, Context base) {
        try {
            Object application = Class.forName("android.app.Application").getDeclaredConstructor().newInstance();
            Method attachBaseContext = Class.forName("android.content.ContextWrapper")
                .getDeclaredMethod("attachBaseContext", Context.class);
            attachBaseContext.setAccessible(true);
            attachBaseContext.invoke(application, base);
            Field initialApplication = activityThreadClass.getDeclaredField("mInitialApplication");
            initialApplication.setAccessible(true);
            initialApplication.set(activityThread, application);
        } catch (Exception unavailable) {
            // Icons may then fail to render; labels and everything else still work.
        }
    }
}
