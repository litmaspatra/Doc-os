package com.litmaspatra.docos.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Tiny view-building helpers (no XML layouts, no support libraries). */
public final class Ui {
    private Ui() { }

    public static final int ACCENT = 0xFF2563EB, BAR = 0xFF1E3A8A, BG = 0xFFF3F4F6, TEXT = 0xFF111827, SUB = 0xFF6B7280, CARD = 0xFFFFFFFF;

    public static final int PDF = 1, DOCX = 2, XLSX = 3, PPTX = 4;

    public static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    public static int typeOf(String name, String mime) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.endsWith(".pdf") || "application/pdf".equals(mime)) return PDF;
        if (n.endsWith(".docx") || (mime != null && mime.contains("wordprocessingml"))) return DOCX;
        if (n.endsWith(".xlsx") || (mime != null && mime.contains("spreadsheetml"))) return XLSX;
        if (n.endsWith(".pptx") || (mime != null && mime.contains("presentationml"))) return PPTX;
        return 0;
    }

    public static String extOf(int type) {
        switch (type) { case PDF: return ".pdf"; case DOCX: return ".docx"; case XLSX: return ".xlsx"; default: return ".pptx"; }
    }

    public static String mimeOf(int type) {
        switch (type) {
            case PDF: return "application/pdf";
            case DOCX: return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case XLSX: return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            default: return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        }
    }

    public static String label(int type) {
        switch (type) { case PDF: return "PDF"; case DOCX: return "DOC"; case XLSX: return "XLS"; default: return "PPT"; }
    }

    public static int colorOf(int type) {
        switch (type) { case PDF: return 0xFFDC2626; case DOCX: return 0xFF2563EB; case XLSX: return 0xFF16A34A; default: return 0xFFEA580C; }
    }

    public static GradientDrawable round(int color, float radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    public static void ripple(View v, int color, float radiusDp) {
        Context c = v.getContext();
        GradientDrawable mask = round(0xFFFFFFFF, radiusDp, c);
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33000000), color == 0 ? null : round(color, radiusDp, c), mask));
    }

    public static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Icon button: vector drawable tinted with the given colour; falls back to a text glyph if it cannot be loaded. */
    public static View icon(Context c, int res, int tint, String fallback) {
        try {
            ImageButton b = new ImageButton(c);
            b.setImageDrawable(c.getDrawable(res));
            b.setColorFilter(tint, PorterDuff.Mode.SRC_IN);
            b.setScaleType(ImageView.ScaleType.CENTER);
            b.setBackground(null);
            int p = dp(c, 8);
            b.setPadding(p, p, p, p);
            ripple(b, 0, 24);
            return b;
        } catch (RuntimeException e) {
            TextView t = text(c, fallback, 18, tint, true);
            t.setGravity(Gravity.CENTER);
            ripple(t, 0, 24);
            return t;
        }
    }

    public static LinearLayout.LayoutParams lp(Context c, int wDp, int hDp) {
        return new LinearLayout.LayoutParams(wDp > 0 ? dp(c, wDp) : wDp, hDp > 0 ? dp(c, hDp) : hDp);
    }

    public static View spacer(Context c) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        return v;
    }
}
