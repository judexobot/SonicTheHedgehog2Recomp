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
 * Minimal three-button Mega Drive overlay. Multi-touch supports diagonals,
 * simultaneous movement + jump, and holding Down+B for spin dash.
 *
 * SDL2 stock keyboard mapping:
 * D-pad = arrows; Genesis A=Z, B=X, C=C; Start=Enter.
 */
public final class TouchControlsView extends View {
    private static final int UP = 1;
    private static final int DOWN = 2;
    private static final int LEFT = 4;
    private static final int RIGHT = 8;
    private static final int A = 16;
    private static final int B = 32;
    private static final int C = 64;
    private static final int START = 128;
    private static final int[] BITS = {UP, DOWN, LEFT, RIGHT, A, B, C, START};
    private static final int[] KEYS = {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_Z,
            KeyEvent.KEYCODE_X,
            KeyEvent.KEYCODE_C,
            KeyEvent.KEYCODE_ENTER
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int held;
    private float unit;
    private float dpadX, dpadY, startX, startY;
    private final float[] faceX = new float[3];
    private final float[] faceY = new float[3];

    public TouchControlsView(Context context) {
        super(context);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setWillNotDraw(false);
        setClickable(true);
        // SDL game surface stays independently focusable for physical pads.
    }

    private void layoutControls() {
        final float density = getResources().getDisplayMetrics().density;
        final float w = getWidth();
        final float h = getHeight();
        unit = Math.min(92f * density, Math.min(w / 7.3f, h / 3.35f));
        dpadX = unit * 1.00f;
        dpadY = h - unit * 0.98f;
        faceX[0] = w - unit * 2.33f;  // A
        faceX[1] = w - unit * 1.52f;  // B
        faceX[2] = w - unit * 0.70f;  // C
        faceY[0] = h - unit * 0.82f;
        faceY[1] = h - unit * 1.04f;
        faceY[2] = h - unit * 1.26f;
        startX = w - unit * 0.47f;
        startY = unit * 0.42f;
    }

    private boolean within(float x, float y, float cx, float cy, float radius) {
        float dx = x - cx;
        float dy = y - cy;
        return dx * dx + dy * dy <= radius * radius;
    }

    private int buttonsAt(float x, float y) {
        // Face buttons are mutually exclusive for each finger. Different
        // fingers can independently hold any combination of A/B/C.
        float r = unit * .39f;
        if (within(x, y, faceX[0], faceY[0], r)) return A;
        if (within(x, y, faceX[1], faceY[1], r)) return B;
        if (within(x, y, faceX[2], faceY[2], r)) return C;
        if (within(x, y, startX, startY, unit * .30f)) return START;

        // Four-way pad allows diagonals and dragging to change direction.
        if (within(x, y, dpadX, dpadY, unit * 1.12f)) {
            float dx = x - dpadX;
            float dy = y - dpadY;
            float threshold = unit * .23f;
            int b = 0;
            if (dx < -threshold) b |= LEFT;
            else if (dx > threshold) b |= RIGHT;
            if (dy < -threshold) b |= UP;
            else if (dy > threshold) b |= DOWN;
            return b;
        }
        return 0;
    }

    private void setHeld(int next) {
        if (held == next) return;
        int old = held;
        held = next;
        // Releases first avoid contradictory directions during transitions.
        for (int i = 0; i < BITS.length; i++) {
            if ((old & BITS[i]) != 0 && (next & BITS[i]) == 0)
                SDLActivity.onNativeKeyUp(KEYS[i]);
        }
        for (int i = 0; i < BITS.length; i++) {
            if ((old & BITS[i]) == 0 && (next & BITS[i]) != 0)
                SDLActivity.onNativeKeyDown(KEYS[i]);
        }
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
        int liftedIndex = event.getActionIndex();
        int next = 0;
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (action == MotionEvent.ACTION_UP) continue;
            if (action == MotionEvent.ACTION_POINTER_UP && i == liftedIndex) continue;
            next |= buttonsAt(event.getX(i), event.getY(i));
        }
        setHeld(next);
        return true;
    }

    private void control(Canvas canvas, float x, float y, float radius,
                         String label, boolean active, float fontSize) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(active ? 0x665FFFFF : 0x30121824);
        canvas.drawCircle(x, y, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.5f, unit * .018f));
        paint.setColor(active ? 0xDDEFFFFF : 0x90FFFFFF);
        canvas.drawCircle(x, y, radius, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(fontSize);
        paint.setColor(active ? 0xFFFFFFFF : 0xC9FFFFFF);
        Paint.FontMetrics fm = paint.getFontMetrics();
        canvas.drawText(label, x, y - (fm.ascent + fm.descent) / 2f, paint);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        layoutControls();
        float step = unit * .49f;
        float r = unit * .285f;
        control(canvas, dpadX, dpadY - step, r, "▲", (held & UP) != 0, unit * .24f);
        control(canvas, dpadX, dpadY + step, r, "▼", (held & DOWN) != 0, unit * .24f);
        control(canvas, dpadX - step, dpadY, r, "◀", (held & LEFT) != 0, unit * .24f);
        control(canvas, dpadX + step, dpadY, r, "▶", (held & RIGHT) != 0, unit * .24f);
        float faceR = unit * .335f;
        control(canvas, faceX[0], faceY[0], faceR, "A", (held & A) != 0, unit * .30f);
        control(canvas, faceX[1], faceY[1], faceR, "B", (held & B) != 0, unit * .30f);
        control(canvas, faceX[2], faceY[2], faceR, "C", (held & C) != 0, unit * .30f);
        control(canvas, startX, startY, unit * .22f, "Ⅱ", (held & START) != 0, unit * .17f);
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
