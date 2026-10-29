package net.kdt.pojavlaunch.utils;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;

import net.kdt.pojavlaunch.LwjglGlfwKeycode;
import net.kdt.pojavlaunch.MainActivity;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

import org.lwjgl.glfw.CallbackBridge;

/**
 * A fixed, solid black panel placed in the notch / cutout strip of the screen. The game itself is
 * not drawn there, so the panel never covers anything. It holds, stacked vertically and sharing
 * the available height equally (icons only):
 * offhand, drop item, perspective, debug, HUD, keyboard, and the Up / Tab / Down keys.
 *
 * It lives in its own non-focusable window, so touching it never takes the focus (or the
 * on-screen keyboard) away from the game.
 */
public final class NotchPanel {
    private static final int MIN_PANEL_WIDTH_DP = 24;
    private static final long REPEAT_DELAY_MS = 400;
    private static final long REPEAT_INTERVAL_MS = 70;
    private static final int KEYBOARD_TOGGLE = -1;

    private final Activity mActivity;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Dialog mDialog;
    private boolean mOnLeft;

    public NotchPanel(Activity activity) {
        mActivity = activity;
    }

    /** Shows the panel (or refreshes its size/side). Does nothing when there is no notch strip. */
    public void show() {
        if (mActivity.isFinishing()) return;
        int[] bar = measureSideBars();
        boolean onLeft = bar[0] >= bar[1];
        int width = Math.max(bar[0], bar[1]);
        if (width < (int) Tools.dpToPx(MIN_PANEL_WIDTH_DP)) {
            // No empty strip on this screen
            dismiss();
            return;
        }
        if (mDialog != null && mOnLeft != onLeft) dismiss();
        mOnLeft = onLeft;

        DisplayMetrics metrics = new DisplayMetrics();
        mActivity.getWindowManager().getDefaultDisplay().getRealMetrics(metrics);

        try {
            if (mDialog == null) mDialog = createDialog();
            Window window = mDialog.getWindow();
            if (window == null) return;
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.gravity = Gravity.TOP | (onLeft ? Gravity.LEFT : Gravity.RIGHT);
            lp.width = width;
            lp.height = metrics.heightPixels;
            lp.x = 0;
            lp.y = 0;
            window.setAttributes(lp);
            if (!mDialog.isShowing()) mDialog.show();
        } catch (Exception e) {
            // The activity is not in a state that allows showing windows right now
            mDialog = null;
        }
    }

    public void dismiss() {
        mHandler.removeCallbacksAndMessages(null);
        if (mDialog != null) {
            try {
                mDialog.dismiss();
            } catch (Exception ignored) {}
            mDialog = null;
        }
    }

    /** Width in pixels of the left and right bars that the game window does not use (notch area). */
    private int[] measureSideBars() {
        View decor = mActivity.getWindow().getDecorView();
        int[] location = new int[2];
        decor.getLocationOnScreen(location);
        DisplayMetrics metrics = new DisplayMetrics();
        mActivity.getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        int left = Math.max(0, location[0]);
        int right = Math.max(0, metrics.widthPixels - location[0] - decor.getWidth());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowInsets insets = decor.getRootWindowInsets();
            DisplayCutout cutout = insets != null ? insets.getDisplayCutout() : null;
            if (cutout != null) {
                left = Math.max(left, cutout.getSafeInsetLeft());
                right = Math.max(right, cutout.getSafeInsetRight());
            }
        }
        return new int[]{left, right};
    }

    private Dialog createDialog() {
        Dialog dialog = new Dialog(mActivity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        dialog.setContentView(buildPanel());

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            window.setFlags(flags, flags);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.windowAnimations = 0;
            lp.dimAmount = 0f;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(lp);
        }
        return dialog;
    }

    private View buildPanel() {
        LinearLayout panel = new LinearLayout(mActivity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.BLACK);
        int pad = (int) Tools.dpToPx(2);
        panel.setPadding(pad, pad, pad, pad);

        // Top to bottom. Tab also shows the player list while held.
        panel.addView(buildButton(R.drawable.ic_key_offhand, LwjglGlfwKeycode.GLFW_KEY_F, false));
        panel.addView(buildButton(R.drawable.ic_key_drop, LwjglGlfwKeycode.GLFW_KEY_Q, false));
        panel.addView(buildButton(R.drawable.ic_key_perspective, LwjglGlfwKeycode.GLFW_KEY_F5, false));
        panel.addView(buildButton(R.drawable.ic_key_debug, LwjglGlfwKeycode.GLFW_KEY_F3, false));
        panel.addView(buildButton(R.drawable.ic_key_hud, LwjglGlfwKeycode.GLFW_KEY_F1, false));
        panel.addView(buildButton(R.drawable.ic_key_keyboard, KEYBOARD_TOGGLE, false));
        panel.addView(buildButton(R.drawable.ic_key_up, LwjglGlfwKeycode.GLFW_KEY_UP, true));
        panel.addView(buildButton(R.drawable.ic_key_tab, LwjglGlfwKeycode.GLFW_KEY_TAB, false));
        panel.addView(buildButton(R.drawable.ic_key_down, LwjglGlfwKeycode.GLFW_KEY_DOWN, true));
        return panel;
    }

    private View buildButton(int iconRes, int keycode, boolean repeats) {
        ImageView button = new ImageView(mActivity);
        button.setImageResource(iconRes);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int padding = (int) Tools.dpToPx(8);
        button.setPadding(padding, padding, padding, padding);
        button.setContentDescription(null); // icons only, no text

        float radius = Tools.dpToPx(9);
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(0xFF1E1E22);
        normal.setCornerRadius(radius);
        normal.setStroke((int) Tools.dpToPx(1), 0x55FFFFFF);
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(0xFF4A4A52);
        pressed.setCornerRadius(radius);
        pressed.setStroke((int) Tools.dpToPx(1), 0xCCFFFFFF);
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_pressed}, pressed);
        background.addState(new int[]{}, normal);
        button.setBackground(background);

        // Equal share of the panel's height, so every button fits whatever the screen size
        int margin = (int) Tools.dpToPx(1.5f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        params.setMargins(0, margin, 0, margin);
        button.setLayoutParams(params);

        final Runnable repeat = new Runnable() {
            @Override
            public void run() {
                CallbackBridge.sendKeyPress(keycode, 0, false);
                CallbackBridge.sendKeyPress(keycode, 0, true);
                mHandler.postDelayed(this, REPEAT_INTERVAL_MS);
            }
        };
        button.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    if (keycode == KEYBOARD_TOGGLE) {
                        MainActivity.switchKeyboardState();
                    } else {
                        CallbackBridge.sendKeyPress(keycode, 0, true);
                        if (repeats) mHandler.postDelayed(repeat, REPEAT_DELAY_MS);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    if (keycode != KEYBOARD_TOGGLE) {
                        mHandler.removeCallbacks(repeat);
                        CallbackBridge.sendKeyPress(keycode, 0, false);
                    }
                    return true;
            }
            return true;
        });
        return button;
    }
}
