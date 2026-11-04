package com.casttoyourtv.receiver.player;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 播放页共用的返回键控制面板。 */
final class PlayerControlPanel {

    private PlayerControlPanel() {
    }

    static Dialog show(Activity activity, Runnable onEndCast) {
        Dialog dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 32), dp(activity, 28), dp(activity, 32), dp(activity, 28));
        root.setMinimumWidth(dp(activity, 420));
        root.setBackground(panelBackground(activity));

        TextView title = new TextView(activity);
        title.setText("投屏控制");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = new TextView(activity);
        hint.setText("要结束这次投屏吗？接收服务会继续运行。");
        hint.setTextColor(0xFFAAAAAA);
        hint.setTextSize(16);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(activity, 12);
        root.addView(hint, hintLp);

        Button continueButton = makeButton(activity, "继续观看", 0xFF3DDC84, 0xFF7CFFB2);
        LinearLayout.LayoutParams continueLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 54));
        continueLp.topMargin = dp(activity, 24);
        root.addView(continueButton, continueLp);

        Button endButton = makeButton(activity, "结束投屏", 0xFFE05A5A, 0xFFFF8A8A);
        LinearLayout.LayoutParams endLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 54));
        endLp.topMargin = dp(activity, 12);
        root.addView(endButton, endLp);

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.dimAmount = 0.65f;
            lp.gravity = Gravity.CENTER;
            window.setAttributes(lp);
            window.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.setCanceledOnTouchOutside(false);

        continueButton.setOnClickListener(v -> dialog.dismiss());
        endButton.setOnClickListener(v -> {
            dialog.dismiss();
            onEndCast.run();
        });
        dialog.setOnShowListener(d -> continueButton.requestFocus());
        dialog.show();
        continueButton.requestFocus();
        return dialog;
    }

    private static Button makeButton(Activity activity, String text, int normal, int focused) {
        Button button = new Button(activity);
        button.setText(text);
        button.setTextColor(Color.BLACK);
        button.setTextSize(20);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);
        button.setFocusableInTouchMode(false);
        button.setBackground(buttonBackground(activity, normal, focused));
        return button;
    }

    private static Drawable panelBackground(Activity activity) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1E1E2E);
        bg.setCornerRadius(dp(activity, 16));
        bg.setStroke(dp(activity, 1), 0x553DDC84);
        return bg;
    }

    private static Drawable buttonBackground(Activity activity, int normal, int focused) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, rounded(activity, focused));
        states.addState(new int[]{android.R.attr.state_pressed}, rounded(activity, focused));
        states.addState(new int[]{}, rounded(activity, normal));
        return states;
    }

    private static Drawable rounded(Activity activity, int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(activity, 10));
        return bg;
    }

    private static int dp(Activity activity, int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}
