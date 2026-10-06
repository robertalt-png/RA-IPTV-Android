package com.nenotv.player;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared block ("tile") layout for the simple overview screens: big, readable, remote-friendly. */
public final class Tiles {
    public static final int BG = 0xFF07090D, CARD = 0xFF151A21, CARD_FOCUS = 0xFF232A35, TEXT = 0xFFF7F8FA, MUTED = 0xFFA7AFBC, ACCENT = 0xFFFFD600;
    private Tiles() {}

    static int dp(Activity a, int v) { return Math.round(v * a.getResources().getDisplayMetrics().density); }

    /** Two tiles per row on phones, three on wide screens (tablets, TV). */
    public static int columns(Activity a) { return a.getResources().getConfiguration().screenWidthDp >= 720 ? 3 : 2; }

    private static GradientDrawable shape(Activity a, int color, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(a, 14));
        if (stroke != 0) g.setStroke(dp(a, 2), stroke);
        return g;
    }

    /** Background with a clear yellow outline when focused by a remote control or selected. */
    public static StateListDrawable background(Activity a, boolean selected) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, shape(a, CARD_FOCUS, ACCENT));
        s.addState(new int[]{android.R.attr.state_pressed}, shape(a, CARD_FOCUS, 0));
        s.addState(new int[]{}, shape(a, CARD, selected ? ACCENT : 0));
        return s;
    }

    /** A grid that fills rows of equal-width tiles. */
    public static final class Grid {
        private final Activity activity;
        private final LinearLayout parent;
        private final int columns;
        private LinearLayout row;
        private int inRow;

        public Grid(Activity activity, LinearLayout parent) { this(activity, parent, columns(activity)); }
        public Grid(Activity activity, LinearLayout parent, int columns) { this.activity = activity; this.parent = parent; this.columns = columns; }

        public View add(String icon, String title, String subtitle, boolean selected, View.OnClickListener click) {
            if (row == null || inRow == columns) {
                row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                rp.bottomMargin = dp(activity, 10);
                parent.addView(row, rp);
                inRow = 0;
            }
            LinearLayout tile = new LinearLayout(activity);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setPadding(dp(activity, 14), dp(activity, 14), dp(activity, 14), dp(activity, 14));
            tile.setMinimumHeight(dp(activity, 104));
            tile.setBackground(background(activity, selected));
            tile.setFocusable(true);
            tile.setClickable(true);
            tile.setOnClickListener(click);
            TextView top = new TextView(activity);
            top.setText(icon == null || icon.isEmpty() ? title : icon + "  " + title);
            top.setTextColor(TEXT);
            top.setTextSize(17);
            top.setTypeface(null, Typeface.BOLD);
            tile.addView(top);
            if (subtitle != null && !subtitle.isEmpty()) {
                TextView sub = new TextView(activity);
                sub.setText(subtitle);
                sub.setTextColor(MUTED);
                sub.setTextSize(13);
                sub.setPadding(0, dp(activity, 6), 0, 0);
                tile.addView(sub);
            }
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -1, 1);
            if (inRow > 0) tp.leftMargin = dp(activity, 10);
            row.addView(tile, tp);
            inRow++;
            tile.setContentDescription(subtitle == null ? title : title + ". " + subtitle);
            return tile;
        }

        /** Pads the last row so a single tile keeps the same width as the others. */
        public void finish() {
            while (row != null && inRow > 0 && inRow < columns) {
                View spacer = new View(activity);
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, 1, 1);
                tp.leftMargin = dp(activity, 10);
                row.addView(spacer, tp);
                inRow++;
            }
        }
    }

    public static TextView heading(Activity a, LinearLayout parent, String text) {
        TextView v = new TextView(a);
        v.setText(text);
        v.setTextColor(TEXT);
        v.setTextSize(18);
        v.setTypeface(null, Typeface.BOLD);
        v.setPadding(0, dp(a, 18), 0, dp(a, 10));
        parent.addView(v);
        return v;
    }

    public static TextView note(Activity a, LinearLayout parent, String text) {
        TextView v = new TextView(a);
        v.setText(text);
        v.setTextColor(MUTED);
        v.setTextSize(13);
        v.setPadding(0, dp(a, 6), 0, dp(a, 6));
        parent.addView(v);
        return v;
    }

    /** Small text link (privacy, account deletion): focusable for remotes, underlined to look clickable. */
    public static TextView link(Activity a, LinearLayout parent, String text, View.OnClickListener click) {
        TextView v = note(a, parent, text);
        v.setPaintFlags(v.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnClickListener(click);
        v.setBackground(background(a, false));
        v.setPadding(dp(a, 10), dp(a, 10), dp(a, 10), dp(a, 10));
        v.setGravity(Gravity.START);
        return v;
    }

    /** Page with a title and a close button; returns the content column. */
    public static LinearLayout page(Activity a, String title) {
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(a, 18), dp(a, 18), dp(a, 18), dp(a, 34));
        sv.addView(box);
        LinearLayout h = new LinearLayout(a);
        h.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextColor(TEXT);
        t.setTextSize(26);
        t.setTypeface(null, Typeface.BOLD);
        h.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        android.widget.Button close = new android.widget.Button(a);
        close.setText(UiText.t(a, "close"));
        close.setAllCaps(false);
        close.setTextColor(TEXT);
        close.setBackground(background(a, false));
        close.setOnClickListener(v -> a.finish());
        h.addView(close);
        box.addView(h);
        a.setContentView(sv);
        ScreenInsets.browsing(a);
        return box;
    }

    public static void open(Activity a, String url) {
        try { a.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))); }
        catch (Exception noBrowser) {
            // TV boxes often have no browser: show the address so it can be opened on a phone.
            new android.app.AlertDialog.Builder(a).setMessage(url).setPositiveButton(android.R.string.ok, null).show();
        }
    }
}
