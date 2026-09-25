package android.graphics.drawable;

import android.graphics.Canvas;

/** Compile-only stub of the platform class; the device provides the real one. */
public abstract class Drawable {
    public void setBounds(int left, int top, int right, int bottom) {
        throw new UnsupportedOperationException("stub");
    }

    public abstract void draw(Canvas canvas);
}
