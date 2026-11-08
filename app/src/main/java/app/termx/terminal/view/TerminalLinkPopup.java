package app.termx.terminal.view;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import app.termx.R;

/** Small, touch-friendly link action menu positioned at the tapped terminal cell. */
final class TerminalLinkPopup {
    private TerminalLinkPopup() { }

    static PopupWindow show(View anchor, int tapX, int tapY, Runnable open, Runnable copy) {
        int padding = dp(anchor, 4);
        LinearLayout menu = new LinearLayout(anchor.getContext());
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(padding, padding, padding, padding);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xff24262b);
        background.setCornerRadius(dp(anchor, 12));
        menu.setBackground(background);
        menu.setElevation(dp(anchor, 8));
        PopupWindow popup = new PopupWindow(menu, ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, true);
        menu.addView(action(anchor, R.string.terminal_link_open, open, popup));
        menu.addView(action(anchor, R.string.terminal_link_copy, copy, popup));
        popup.setBackgroundDrawable(background);
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(anchor, 8));
        popup.setClippingEnabled(true);
        menu.measure(View.MeasureSpec.makeMeasureSpec(dp(anchor, 280), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(dp(anchor, 120), View.MeasureSpec.AT_MOST));

        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        Rect visible = new Rect();
        anchor.getWindowVisibleDisplayFrame(visible);
        int width = menu.getMeasuredWidth();
        int height = menu.getMeasuredHeight();
        int x = Math.max(visible.left + dp(anchor, 8), Math.min(location[0] + tapX, visible.right - width - dp(anchor, 8)));
        int y = location[1] + tapY;
        if (y + height > visible.bottom) y = location[1] + tapY - height;
        y = Math.max(visible.top + dp(anchor, 8), Math.min(y, visible.bottom - height - dp(anchor, 8)));
        popup.showAtLocation(anchor, Gravity.TOP | Gravity.LEFT, x, y);
        return popup;
    }

    private static TextView action(View anchor, int label, Runnable callback, PopupWindow popup) {
        TextView item = new TextView(anchor.getContext());
        item.setText(label);
        item.setTextColor(Color.WHITE);
        item.setTextSize(15);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(anchor, 16), 0, dp(anchor, 20), 0);
        item.setMinHeight(dp(anchor, 48));
        item.setBackgroundResource(android.R.drawable.list_selector_background);
        item.setOnClickListener(view -> {
            callback.run();
            popup.dismiss();
        });
        return item;
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }
}
