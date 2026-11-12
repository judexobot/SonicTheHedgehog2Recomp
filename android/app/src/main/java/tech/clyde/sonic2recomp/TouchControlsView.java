package tech.clyde.sonic2recomp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import org.libsdl.app.SDLActivity;

/**
 * Small, transparent, multi-touch keyboard overlay for the original
 * three-button Sonic 2 controls. No controller driver or extra permissions.
 *
 * The recomp runner already maps arrows -> D-pad, X -> Genesis B (jump),
 * and Enter -> Start. Send those Android keycodes to SDL's own native input
 * entrypoints rather than introducing a second input path in the engine.
 */
public final class TouchControlsView extends View {
    private static final int UP = 1;
    private static final int DOWN = 2;
    private static final int LEFT = 4;
    private static final int RIGHT = 8;
    private static final int JUMP = 16;
    private static final int START = 32;
    private static final int[] BITS = { UP, DOWN, LEFT, RIGHT, JUMP, START };
    private static final int[] KEYS = {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_ENTER
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int held;
    private float unit;
    private float dpadX, dpadY, jumpX, jumpY, startX, startY;

    public TouchControlsView(Context context) {
        super(context);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setWillNotDraw(false);
        setClickable(true);
    }

    private void layoutControls() {
        float density = getResources().getDisplayMetrics().density;
        float w = getWidth();
        float h = getHeight();
        unit = Math.min(100f * density, Math.min(w / 7.3f, h / 3.5f));
        dpadX = unit * 1.04f;
        dpadY = h - unit * 1.04f;
        jumpX = w - unit * 1.02f;
        jumpY = h - unit * 1.04f;
        startX = w - unit * 0.57f;
        startY = unit * 0.46f;
    }

    private boolean within(float x, float y, float cx, float cy, float radius) {
        float dx = x - cx;
        float dy = y - cy;
        return dx * dx + dy * dy <= radius * radius;
    }

    private int buttonsAt(float x, float y) {
        if (within(x, y, jumpX, jumpY, unit * .62f)) return JUMP;
        if (within(x, y, startX, startY, unit * .34f)) return START;
        if (within(x, y, dpadX, dpadY, unit * 1.12f)) {
            float dx = x - dpadX;
            float dy = y - dpadY;
            float threshold = unit * .24f;
            int buttons = 0;
            if (dx < -threshold) buttons |= LEFT;
            if (dx > threshold) buttons |= RIGHT;
            if (dy < -threshold) buttons |= UP;
            if (dy > threshold) buttons |= DOWN;
            return buttons;
        }
        return 0;
    }

    private void setHeld(int next) {
        if (held == next) return;
        // Send key releases before presses so changing D-pad direction
        // cannot momentarily hold both opposite directions.
        int old = held;
        held = next;
        for (int i = 0; i < BITS.length; i++)
            if ((old & BITS[i]) != 0 && (next & BITS[i]) == 0)
                SDLActivity.onNativeKeyUp(KEYS[i]);
        for (int i = 0; i < BITS.length; i++)
            if ((old & BITS[i]) == 0 && (next & BITS[i]) != 0)
                SDLActivity.onNativeKeyDown(KEYS[i]);
        invalidate();
    }

    public void releaseAll() {
        setHeld(0);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL) {
            releaseAll();
            return true;
        }
        layoutControls();
        int changed = event.getActionIndex();
        int next = 0;
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (action == MotionEvent.ACTION_UP) continue;
            if (action == MotionEvent.ACTION_POINTER_UP && i == changed) continue;
            next |= buttonsAt(event.getX(i), event.getY(i));
        }
        setHeld(next);
        return true;
    }

    private void control(Canvas canvas, float x, float y, float radius,
                         String label, boolean active, float fontSize) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(active ? 0x665FFFFF : 0x2B101827);
        canvas.drawCircle(x, y, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.5f, unit * .018f));
        paint.setColor(active ? 0xDDEFFFFF : 0x70FFFFFF);
        canvas.drawCircle(x, y, radius, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(fontSize);
        paint.setColor(active ? 0xFFFFFFFF : 0xBBFFFFFF);
        Paint.FontMetrics fm = paint.getFontMetrics();
        canvas.drawText(label, x, y - (fm.ascent + fm.descent) / 2f, paint);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        layoutControls();
        float step = unit * .52f;
        float r = unit * .27f;
        control(canvas, dpadX, dpadY - step, r, "▲", (held & UP) != 0, unit * .25f);
        control(canvas, dpadX, dpadY + step, r, "▼", (held & DOWN) != 0, unit * .25f);
        control(canvas, dpadX - step, dpadY, r, "◀", (held & LEFT) != 0, unit * .25f);
        control(canvas, dpadX + step, dpadY, r, "▶", (held & RIGHT) != 0, unit * .25f);
        control(canvas, jumpX, jumpY, unit * .41f, "B", (held & JUMP) != 0, unit * .34f);
        control(canvas, startX, startY, unit * .23f, "II", (held & START) != 0, unit * .20f);
    }

    @Override
    protected void onDetachedFromWindow() {
        releaseAll();
        super.onDetachedFromWindow();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) releaseAll();
    }
}
