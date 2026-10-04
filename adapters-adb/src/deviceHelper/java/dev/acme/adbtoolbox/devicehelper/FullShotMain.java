package dev.acme.adbtoolbox.devicehelper;

import android.graphics.Bitmap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.view.Surface;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The on-device full-content screenshot helper (ADR 0013), run as
 * {@code CLASSPATH=<pushed jar> app_process / dev.acme.adbtoolbox.devicehelper.FullShotMain <output.png>}
 * under the shell user.
 *
 * <p>It creates a virtual display as wide as the screen and {@link #HEIGHT_FACTOR} times as tall,
 * moves the focused task onto it — the app is recreated there with its saved state and lays out
 * its scrolling content at the full height — captures one frame, and always moves the task back.
 * The display is created without {@code DESTROY_CONTENT_ON_REMOVAL}, so even if this process dies
 * mid-capture Android moves the task back to the main display instead of destroying it.
 *
 * <p>The frame is trimmed of the uniform rows below the content and written to the output path as
 * a PNG. Output: {@link #HEADER}, then {@code file=}, {@code size=<w>x<h>} and {@code truncated=0|1}
 * (the content may continue below the display) or {@code error=<reason>}, then {@code END}. A
 * diagnostic {@code frames=<n>} line may come first.
 */
public final class FullShotMain {

    static final String HEADER = "ADBTOOLBOX-FULLSHOT 1";

    private static final int HEIGHT_FACTOR = 3;
    private static final int MAX_HEIGHT_PX = 8192;
    private static final int RGBA_8888 = 1;
    private static final long FRAME_IDLE_MS = 800;
    private static final long FRAME_TIMEOUT_MS = 15_000;
    private static final int BOTTOM_PADDING_DP = 16;
    private static final long WINDOW_POLL_MS = 500;
    private static final long WINDOW_COMPOSE_MS = 300;
    private static final Pattern REQUESTED_SIZE = Pattern.compile("Requested w=\\d+ h=(\\d+)");

    // What the window manager says about the moved app's window.
    private static final int WINDOW_NOT_READY = 0;
    private static final int WINDOW_READY = 1;
    private static final int WINDOW_UNKNOWN = 2;

    // DisplayManager.VIRTUAL_DISPLAY_FLAG_*: public and own-content-only everywhere; trusted, own
    // display group and always unlocked (API 33+) let other apps' activities run on it.
    private static final int FLAG_PUBLIC = 1;
    private static final int FLAG_OWN_CONTENT_ONLY = 1 << 3;
    private static final int FLAG_TRUSTED = 1 << 10;
    private static final int FLAG_OWN_DISPLAY_GROUP = 1 << 11;
    private static final int FLAG_ALWAYS_UNLOCKED = 1 << 12;

    // WindowConfiguration.ACTIVITY_TYPE_*: only ordinary app tasks are moved.
    private static final int ACTIVITY_TYPE_UNDEFINED = 0;
    private static final int ACTIVITY_TYPE_STANDARD = 1;

    private static final Object frameLock = new Object();
    private static Image latestFrame;
    private static boolean frameTaken;
    private static int frameCount;
    private static long lastFrameAt;

    private FullShotMain() {
    }

    public static void main(String[] args) {
        System.out.println(HEADER);
        try {
            capture(args[0]);
        } catch (Throwable error) {
            Throwable cause = error instanceof InvocationTargetException && error.getCause() != null ? error.getCause() : error;
            System.out.println("error=" + String.valueOf(cause).replace('\n', ' '));
        }
        System.out.println("END");
        System.out.flush();
        System.exit(0);
    }

    private static void capture(String outputPath) throws Exception {
        int sdk = Class.forName("android.os.Build$VERSION").getField("SDK_INT").getInt(null);
        if (sdk < 29) {
            throw new IllegalStateException("full screenshots need Android 10 or newer");
        }
        dropRootToShell();
        new File(outputPath).delete();
        Object taskManager = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        Object task = focusedTask(taskManager);
        if (task == null) {
            throw new IllegalStateException("no app is in the foreground");
        }
        int taskId = intField(task, "taskId", "stackId");
        int originalDisplayId = intField(task, "displayId");
        int activityType = activityType(task);
        if (activityType != ACTIVITY_TYPE_STANDARD && activityType != ACTIVITY_TYPE_UNDEFINED) {
            throw new IllegalStateException("the home screen or a system screen is in the foreground; open an app first");
        }

        Class<?> displayManagerClass = Class.forName("android.hardware.display.DisplayManager");
        Constructor<?> displayManagerConstructor = displayManagerClass.getDeclaredConstructor(android.content.Context.class);
        displayManagerConstructor.setAccessible(true);
        Object displayManager = displayManagerConstructor.newInstance(new ShellContext(SystemContext.get()));
        DisplayMetrics metrics = new DisplayMetrics();
        Object mainDisplay = displayManagerClass.getMethod("getDisplay", int.class).invoke(displayManager, originalDisplayId);
        mainDisplay.getClass().getMethod("getRealMetrics", DisplayMetrics.class).invoke(mainDisplay, metrics);
        int width = metrics.widthPixels;
        int height = Math.min(metrics.heightPixels * HEIGHT_FACTOR, MAX_HEIGHT_PX);
        int flags = FLAG_PUBLIC | FLAG_OWN_CONTENT_ONLY;
        if (sdk >= 33) {
            flags |= FLAG_TRUSTED | FLAG_OWN_DISPLAY_GROUP | FLAG_ALWAYS_UNLOCKED;
        }

        HandlerThread frameThread = new HandlerThread("adbtoolbox-fullshot");
        frameThread.start();
        // One frame handed to the caller, one held as the newest, one for acquireLatestImage to swap in.
        ImageReader reader = ImageReader.newInstance(width, height, RGBA_8888, 3);
        reader.setOnImageAvailableListener(new FrameListener(), new Handler(frameThread.getLooper()));
        Object virtualDisplay = null;
        boolean moved = false;
        try {
            virtualDisplay = displayManagerClass
                    .getMethod("createVirtualDisplay", String.class, int.class, int.class, int.class, Surface.class, int.class)
                    .invoke(displayManager, "adbtoolbox-fullshot", width, height, metrics.densityDpi, reader.getSurface(), flags);
            Object display = virtualDisplay.getClass().getMethod("getDisplay").invoke(virtualDisplay);
            int virtualDisplayId = (Integer) display.getClass().getMethod("getDisplayId").invoke(display);

            int framesBeforeMove = frameCountNow();
            moveTask(taskManager, taskId, virtualDisplayId);
            moved = true;
            Image frame = awaitSettledFrame(framesBeforeMove, virtualDisplayId, metrics.heightPixels);
            try {
                writePng(frame, outputPath, metrics.densityDpi);
            } finally {
                frame.close();
            }
        } finally {
            if (moved) {
                moveTask(taskManager, taskId, originalDisplayId);
            }
            if (virtualDisplay != null) {
                virtualDisplay.getClass().getMethod("release").invoke(virtualDisplay);
            }
            synchronized (frameLock) {
                if (latestFrame != null) {
                    latestFrame.close();
                    latestFrame = null;
                }
            }
            reader.close();
            frameThread.quitSafely();
        }
    }

    /** Keeps only the newest frame; runs on the frame thread. */
    private static final class FrameListener implements ImageReader.OnImageAvailableListener {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image;
            try {
                image = reader.acquireLatestImage();
            } catch (IllegalStateException allBuffersHeld) {
                return;
            }
            if (image == null) {
                return;
            }
            synchronized (frameLock) {
                if (frameTaken) {
                    image.close();
                    return;
                }
                if (latestFrame != null) {
                    latestFrame.close();
                }
                latestFrame = image;
                frameCount++;
                lastFrameAt = System.currentTimeMillis();
                frameLock.notifyAll();
            }
        }
    }

    private static int frameCountNow() {
        synchronized (frameLock) {
            return frameCount;
        }
    }

    /**
     * The newest frame once the moved app has laid out and drawn at the capture display's height and
     * then stayed still for {@link #FRAME_IDLE_MS}; at the deadline, whatever was drawn last.
     *
     * <p>Frames alone are not enough: right after the move Android shows a starting window — a
     * snapshot of the task at the old screen size — and a slow app can take longer than the idle
     * time to lay itself out again. The window manager knows when the app's window has asked for
     * the new height and drawn ({@link #appWindowState}). Where its dump cannot be read, the app's
     * own drawing is taken to be at least the second frame, never a flat colour.
     */
    private static Image awaitSettledFrame(int framesBeforeMove, int displayId, int screenHeight) throws Exception {
        long deadline = System.currentTimeMillis() + FRAME_TIMEOUT_MS;
        int windowState = WINDOW_NOT_READY;
        long readyAt = 0;
        while (true) {
            if (windowState == WINDOW_NOT_READY) {
                windowState = appWindowState(displayId, screenHeight);
                readyAt = System.currentTimeMillis();
            }
            synchronized (frameLock) {
                long now = System.currentTimeMillis();
                boolean drawn = frameCount > framesBeforeMove;
                boolean settled = drawn && now - lastFrameAt >= FRAME_IDLE_MS && lastContentRow(latestFrame) >= 0;
                boolean ready = windowState == WINDOW_READY
                        ? drawn && now - readyAt >= WINDOW_COMPOSE_MS
                        : windowState == WINDOW_UNKNOWN && frameCount > framesBeforeMove + 1;
                if (ready && settled) {
                    return takeLatestFrame();
                }
                if (now >= deadline) {
                    if (drawn) {
                        return takeLatestFrame();
                    }
                    throw new IllegalStateException("the app drew nothing on the capture display");
                }
                frameLock.wait(Math.min(WINDOW_POLL_MS, deadline - now));
            }
        }
    }

    /**
     * Whether an app window on {@code displayId} has asked for more than the screen's height and been
     * drawn, from {@code dumpsys window windows} (the shell holds {@code DUMP}); unknown when the
     * dump has no {@code Requested w= h=} lines to judge by.
     */
    private static int appWindowState(int displayId, int screenHeight) throws Exception {
        java.lang.Process dump = Runtime.getRuntime().exec(new String[] {"dumpsys", "window", "windows"});
        String text;
        try {
            text = readAll(dump.getInputStream());
        } finally {
            dump.destroy();
        }
        if (!text.contains("Requested w=")) {
            return WINDOW_UNKNOWN;
        }
        for (String window : text.split("\n  Window #")) {
            if (!window.contains("mDisplayId=" + displayId + " ") || !window.contains("ty=BASE_APPLICATION")
                    || !window.contains("mDrawState=HAS_DRAWN")) {
                continue;
            }
            Matcher requested = REQUESTED_SIZE.matcher(window);
            if (requested.find() && Integer.parseInt(requested.group(1)) > screenHeight) {
                return WINDOW_READY;
            }
        }
        return WINDOW_NOT_READY;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        for (int read; (read = in.read(buffer)) != -1; ) {
            bytes.write(buffer, 0, read);
        }
        return new String(bytes.toByteArray(), "UTF-8");
    }

    /** Hands the newest frame to the caller, who closes it; the frame thread no longer touches it. */
    private static Image takeLatestFrame() {
        Image frame = latestFrame;
        latestFrame = null;
        frameTaken = true;
        System.out.println("frames=" + frameCount);
        return frame;
    }

    private static void writePng(Image frame, String outputPath, int densityDpi) throws Exception {
        int width = frame.getWidth();
        int height = frame.getHeight();
        Image.Plane plane = frame.getPlanes()[0];
        ByteBuffer pixels = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();

        int lastContentRow = lastContentRow(frame);
        int contentBottom = lastContentRow < 0 ? height : lastContentRow + 1;
        int padding = BOTTOM_PADDING_DP * densityDpi / 160;
        boolean truncated = height - contentBottom < padding;
        int outputHeight = Math.min(height, contentBottom + padding);

        Bitmap padded = Bitmap.createBitmap(rowStride / pixelStride, height, Bitmap.Config.ARGB_8888);
        pixels.rewind();
        padded.copyPixelsFromBuffer(pixels);
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, outputHeight);
        OutputStream out = new FileOutputStream(outputPath);
        try {
            cropped.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally {
            out.close();
        }
        cropped.recycle();
        padded.recycle();
        System.out.println("file=" + outputPath);
        System.out.println("size=" + width + "x" + outputHeight);
        System.out.println("truncated=" + (truncated ? 1 : 0));
    }

    /** The last row that differs from the bottom row, where the content ends; -1 for a blank frame. */
    private static int lastContentRow(Image frame) {
        Image.Plane plane = frame.getPlanes()[0];
        ByteBuffer pixels = plane.getBuffer();
        int rowStride = plane.getRowStride();
        byte[] bottom = new byte[frame.getWidth() * plane.getPixelStride()];
        byte[] row = new byte[bottom.length];
        readRow(pixels, (frame.getHeight() - 1) * rowStride, bottom);
        for (int y = frame.getHeight() - 2; y >= 0; y--) {
            readRow(pixels, y * rowStride, row);
            if (!Arrays.equals(row, bottom)) {
                return y;
            }
        }
        return -1;
    }

    private static void readRow(ByteBuffer pixels, int offset, byte[] into) {
        ByteBuffer view = pixels.duplicate();
        view.position(offset);
        view.get(into);
    }

    /**
     * Under {@code adb root} the helper runs as uid 0, which owns no package, and before Android 15
     * creating a virtual display rejects any package name for it. Becoming the shell user, as on an
     * unrooted device, makes {@link ShellContext}'s package match.
     */
    private static void dropRootToShell() throws Exception {
        Class<?> process = Class.forName("android.os.Process");
        if ((Integer) process.getMethod("myUid").invoke(null) != 0) {
            return;
        }
        process.getMethod("setGid", int.class).invoke(null, ShellContext.SHELL_UID);
        process.getMethod("setUid", int.class).invoke(null, ShellContext.SHELL_UID);
    }

    /** {@code getFocusedRootTaskInfo()} (API 31+), else {@code getFocusedStackInfo()} (API 29–30). */
    private static Object focusedTask(Object taskManager) throws Exception {
        try {
            return taskManager.getClass().getMethod("getFocusedRootTaskInfo").invoke(taskManager);
        } catch (NoSuchMethodException olderRelease) {
            return taskManager.getClass().getMethod("getFocusedStackInfo").invoke(taskManager);
        }
    }

    /** {@code moveRootTaskToDisplay} (API 31+), else {@code moveStackToDisplay} (API 29–30) — what {@code am display move-stack} calls. */
    private static void moveTask(Object taskManager, int taskId, int displayId) throws Exception {
        Method move;
        try {
            move = taskManager.getClass().getMethod("moveRootTaskToDisplay", int.class, int.class);
        } catch (NoSuchMethodException olderRelease) {
            move = taskManager.getClass().getMethod("moveStackToDisplay", int.class, int.class);
        }
        move.invoke(taskManager, taskId, displayId);
    }

    private static int intField(Object target, String... names) throws Exception {
        for (String name : names) {
            try {
                return target.getClass().getField(name).getInt(target);
            } catch (NoSuchFieldException tryNext) {
                // RootTaskInfo has taskId; the older StackInfo has stackId.
            }
        }
        throw new NoSuchFieldException(names[0]);
    }

    /** The task's {@code configuration.windowConfiguration.getActivityType()}, or undefined when unreadable. */
    private static int activityType(Object task) {
        try {
            Object configuration = task.getClass().getField("configuration").get(task);
            Object windowConfiguration = configuration.getClass().getField("windowConfiguration").get(configuration);
            return (Integer) windowConfiguration.getClass().getMethod("getActivityType").invoke(windowConfiguration);
        } catch (Exception unreadable) {
            return ACTIVITY_TYPE_UNDEFINED;
        }
    }
}
