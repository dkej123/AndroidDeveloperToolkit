package dev.acme.adbtoolbox.devicehelper;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;

/**
 * The system context, presented as the {@code com.android.shell} package the helper's uid owns.
 * System services that check the caller's package against its uid (creating a virtual display
 * does) reject the system context's own {@code android} package.
 */
final class ShellContext extends ContextWrapper {

    static final String PACKAGE_NAME = "com.android.shell";
    static final int SHELL_UID = 2000;

    ShellContext(Context systemContext) {
        super(systemContext);
    }

    @Override
    public String getPackageName() {
        return PACKAGE_NAME;
    }

    /** Hidden {@code Context} method; overridden by name and signature. */
    public String getOpPackageName() {
        return PACKAGE_NAME;
    }

    /** {@code Context.getAttributionSource()} (API 31+); overridden by name and signature. */
    public AttributionSource getAttributionSource() {
        try {
            Class<?> builderClass = Class.forName("android.content.AttributionSource$Builder");
            Object builder = builderClass.getConstructor(int.class).newInstance(SHELL_UID);
            builderClass.getMethod("setPackageName", String.class).invoke(builder, PACKAGE_NAME);
            return (AttributionSource) builderClass.getMethod("build").invoke(builder);
        } catch (Exception unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    /** {@code Context.getDeviceId()} (API 34+): the default device. */
    public int getDeviceId() {
        return 0;
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }
}
