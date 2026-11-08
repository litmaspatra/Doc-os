package com.litmaspatra.docos.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.AlignmentSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import com.litmaspatra.docos.ooxml.Pptx;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Draws one slide (shapes, text, pictures) fitted to the view; tap a shape to edit, swipe to change slide. */
public final class SlideView extends View {
    public interface Listener { void onShape(Pptx.Shape s); void onSwipe(int dir); }

    private static final class Cached { float scale; StaticLayout layout; float dy; }

    private Listener listener;
    private Pptx pptx;
    private Pptx.Slide slide;
    private Pptx.Shape selected;
    private float scale = 1, ox, oy;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), bmp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final GestureDetector gd;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Set<String> loading = new HashSet<>();
    private final LruCache<String, Bitmap> pics = new LruCache<String, Bitmap>(Math.max(4 << 20, (int) Math.min(32L << 20, Runtime.getRuntime().maxMemory() / 8))) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    public SlideView(Context c) {
        super(c);
        setBackgroundColor(0xFF6B7280);
        line.setStyle(Paint.Style.STROKE);
        gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent e) { tap(e.getX(), e.getY()); return true; }
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (Math.abs(vx) > Math.abs(vy) * 1.5f && Math.abs(vx) > 800 && listener != null) { listener.onSwipe(vx < 0 ? 1 : -1); return true; }
                return false;
            }
        });
    }

    public void setListener(Listener l) { listener = l; }

    public void setSlide(Pptx p, Pptx.Slide s) { pptx = p; slide = s; selected = null; invalidate(); }

    public void refresh() { invalidate(); }

    private void tap(float x, float y) {
        if (slide == null) return;
        double ux = (x - ox) / scale, uy = (y - oy) / scale;
        for (int i = slide.shapes.size() - 1; i >= 0; i--) {
            Pptx.Shape s = slide.shapes.get(i);
            if (s.picture) continue;
            if (ux >= s.x && ux <= s.x + s.w && uy >= s.y && uy <= s.y + s.h) {
                selected = s; invalidate();
                if (listener != null) listener.onShape(s);
                return;
            }
        }
        selected = null; invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent e) { gd.onTouchEvent(e); return true; }

    private static boolean dark(int c) { return (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) < 110; }

    @Override protected void onDraw(Canvas c) {
        if (slide == null) return;
        float vw = getWidth() - 16 * getResources().getDisplayMetrics().density, vh = getHeight() - 16 * getResources().getDisplayMetrics().density;
        scale = (float) Math.min(vw / slide.w, vh / slide.h);
        ox = (float) ((getWidth() - slide.w * scale) / 2); oy = (float) ((getHeight() - slide.h * scale) / 2);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(slide.bg);
        c.drawRect(ox, oy, (float) (ox + slide.w * scale), (float) (oy + slide.h * scale), fill);
        c.save();
        c.clipRect(ox, oy, (float) (ox + slide.w * scale), (float) (oy + slide.h * scale));
        int textDefault = dark(slide.bg) ? Color.WHITE : Color.BLACK;
        for (Pptx.Shape s : slide.shapes) drawShape(c, s, textDefault);
        c.restore();
    }

    private void drawShape(Canvas c, Pptx.Shape s, int textDefault) {
        float l = (float) (ox + s.x * scale), t = (float) (oy + s.y * scale), w = (float) (s.w * scale), h = (float) (s.h * scale);
        c.save();
        if (s.rot != 0) c.rotate((float) s.rot, l + w / 2, t + h / 2);
        if (s.picture) {
            Bitmap b = pics.get(s.picPath);
            if (b != null) c.drawBitmap(b, null, new RectF(l, t, l + w, t + h), bmp);
            else { fill.setColor(0xFFE5E7EB); c.drawRect(l, t, l + w, t + h, fill); load(s, (int) Math.max(w, 64)); }
        } else {
            if (s.fill != 0) {
                fill.setColor(s.fill);
                if (s.geom.equals("ellipse")) c.drawOval(new RectF(l, t, l + w, t + h), fill);
                else if (s.geom.equals("roundRect")) c.drawRoundRect(new RectF(l, t, l + w, t + h), Math.min(w, h) * 0.12f, Math.min(w, h) * 0.12f, fill);
                else c.drawRect(l, t, l + w, t + h, fill);
            }
            if (s.line != 0) {
                line.setColor(s.line);
                line.setStrokeWidth(Math.max(1f, 1.2f * getResources().getDisplayMetrics().density));
                if (s.geom.equals("ellipse")) c.drawOval(new RectF(l, t, l + w, t + h), line);
                else c.drawRect(l, t, l + w, t + h, line);
            }
            if (!s.paras.isEmpty()) {
                Cached k = layoutOf(s, textDefault);
                if (k != null && k.layout != null) {
                    c.save();
                    c.clipRect(l, t, l + w, t + h);
                    c.translate((float) (l + s.inL * scale), t + (float) (s.inT * scale) + k.dy);
                    k.layout.draw(c);
                    c.restore();
                }
            }
        }
        if (s == selected) {
            line.setColor(Ui.ACCENT);
            line.setStrokeWidth(2.5f * getResources().getDisplayMetrics().density);
            c.drawRect(l, t, l + w, t + h, line);
        }
        c.restore();
    }

    private Cached layoutOf(Pptx.Shape s, int textDefault) {
        Cached k = s.cache instanceof Cached ? (Cached) s.cache : null;
        if (k != null && k.scale == scale) return k;
        k = new Cached();
        k.scale = scale;
        int availW = (int) Math.max(8, (s.w - s.inL - s.inR) * scale);
        float availH = (float) Math.max(8, (s.h - s.inT - s.inB) * scale);
        float f = 1f;
        StaticLayout l = null;
        for (int tries = 0; tries < 9; tries++) {
            l = build(s, availW, f, textDefault);
            if (l.getHeight() <= availH * 1.02f || f < 0.4f) break;
            f *= 0.88f;
        }
        k.layout = l;
        float spare = availH - l.getHeight();
        k.dy = "ctr".equals(s.anchor) ? Math.max(0, spare / 2) : "b".equals(s.anchor) ? Math.max(0, spare) : 0;
        s.cache = k;
        return k;
    }

    private StaticLayout build(Pptx.Shape s, int width, float f, int textDefault) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        boolean first = true;
        for (Pptx.Para p : s.paras) {
            if (!first) sb.append('\n');
            first = false;
            int ps = sb.length();
            boolean bullet = p.bullet || (("body".equals(s.phType) || "obj".equals(s.phType)) && s.paras.size() > 1);
            if (bullet && !p.runs.isEmpty()) sb.append("\u2022 ");
            int defSz = 1800;
            for (Pptx.Run r : p.runs) {
                int a = sb.length();
                sb.append(r.text);
                int b = sb.length();
                defSz = r.sz;
                if (b == a) continue;
                float px = r.sz / 100f * 12700f * scale * f;
                sb.setSpan(new AbsoluteSizeSpan(Math.max(6, Math.round(px)), false), a, b, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(r.color != null ? r.color : textDefault), a, b, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (r.b || r.i) sb.setSpan(new StyleSpan(r.b && r.i ? Typeface.BOLD_ITALIC : r.b ? Typeface.BOLD : Typeface.ITALIC), a, b, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (r.u) sb.setSpan(new UnderlineSpan(), a, b, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (sb.length() == ps) { sb.append(' '); sb.setSpan(new AbsoluteSizeSpan(Math.max(6, Math.round(defSz / 100f * 12700f * scale * f)), false), ps, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE); }
            Layout.Alignment al = p.align == 1 ? Layout.Alignment.ALIGN_CENTER : p.align == 2 ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL;
            sb.setSpan(new AlignmentSpan.Standard(al), ps, sb.length(), Spannable.SPAN_PARAGRAPH);
        }
        TextPaint p = new TextPaint(tp);
        return StaticLayout.Builder.obtain(sb, 0, sb.length(), p, width).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.0f).build();
    }

    private void load(final Pptx.Shape s, final int target) {
        final String path = s.picPath;
        if (path == null || !loading.add(path)) return;
        exec.execute(new Runnable() {
            @Override public void run() {
                Bitmap out = null;
                try {
                    byte[] d = pptx.media(path);
                    if (d != null) {
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(d, 0, d.length, o);
                        int ss = 1;
                        while (o.outWidth / (ss * 2) >= Math.max(64, target)) ss *= 2;
                        o = new BitmapFactory.Options();
                        o.inSampleSize = ss;
                        out = BitmapFactory.decodeByteArray(d, 0, d.length, o);
                    }
                } catch (Throwable ignored) { }
                final Bitmap fb = out;
                post(new Runnable() { @Override public void run() { loading.remove(path); if (fb != null) pics.put(path, fb); invalidate(); } });
            }
        });
    }

    public void shutdown() { exec.shutdownNow(); }
}
