package dev.acme.adbtoolbox.devicehelper;

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
        return instance;
    }
}
