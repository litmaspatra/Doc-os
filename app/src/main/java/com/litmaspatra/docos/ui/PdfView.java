package com.litmaspatra.docos.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.OverScroller;

import com.litmaspatra.docos.pdf.PdfAnnot;
import com.litmaspatra.docos.pdf.PdfAnnotIO;
import com.litmaspatra.docos.pdf.PdfDoc;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Scrolling, zooming PDF page view with a vector annotation overlay. */
public final class PdfView extends View {

    public interface Host {
        void onPage(int page, int total);
        void onChanged();
        void askText(PdfAnnot existing, int page, float u, float v);
    }

    public static final int NONE = 0, PEN = 1, MARKER = 2, TEXT = 3, ERASER = 4;
    public static final int[] COLORS = {0xFF111111, 0xFFE53935, 0xFF1E88E5, 0xFF43A047, 0xFFFB8C00, 0xFFFDD835};

    private static final class Detail { float zoom; int l, t; Bitmap bmp; }
    private static final class Op { boolean added; PdfAnnot a; }

    private Host host;
    private PdfRenderer renderer;
    private ParcelFileDescriptor pfd;
    private ExecutorService exec;
    private volatile boolean closed = true;
    private int count;
    private float[] pw = new float[0], ph = new float[0], top = new float[0], hpx = new float[0];
    private float docH, gap;
    private int vw, vh;
    private float zoom = 1f, sx, sy;
    private int firstVis, lastVis, curPage = -1, wantPage = -1;
    private final LruCache<Integer, Bitmap> base;
    private final HashMap<Integer, Detail> details = new HashMap<>();
    private final Set<Integer> pending = new HashSet<>();
    private final Set<String> pendingDetail = new HashSet<>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG), fill = new Paint(), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
            thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    // gestures
    private final GestureDetector gd;
    private final ScaleGestureDetector sd;
    private final OverScroller scroller;
    private boolean scaling, multi, scrubbing;
    private long thumbUntil;

    // annotations
    private int tool = NONE, color = COLORS[1], sizeIdx = 1;
    private final Map<Integer, ArrayList<PdfAnnot>> annots = new HashMap<>();
    private final Map<Integer, ArrayList<PdfAnnot>> removed = new HashMap<>();
    private final ArrayDeque<Op> undo = new ArrayDeque<>();
    private int strokePage = -1;
    private final ArrayList<float[]> cur = new ArrayList<>();
    private float downX, downY;
    private boolean moved;

    public PdfView(Context c) {
        super(c);
        int mem = (int) Math.min(48L << 20, Runtime.getRuntime().maxMemory() / 6);
        base = new LruCache<Integer, Bitmap>(Math.max(8 << 20, mem)) {
            @Override protected int sizeOf(Integer k, Bitmap b) { return b.getByteCount(); }
        };
        gap = Ui.dp(c, 6);
        fill.setColor(Color.WHITE);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        thumb.setColor(0x99000000);
        scroller = new OverScroller(c);
        setBackgroundColor(0xFF9CA3AF);
        sd = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) { scaling = true; scroller.forceFinished(true); return true; }
            @Override public boolean onScale(ScaleGestureDetector d) {
                float nz = Math.max(1f, Math.min(6f, zoom * d.getScaleFactor()));
                float fx = d.getFocusX(), fy = d.getFocusY();
                float px = (sx + fx) / zoom, py = (sy + fy) / zoom;
                zoom = nz;
                sx = px * zoom - fx; sy = py * zoom - fy;
                clamp(); moveDone(); return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector d) { scaling = false; }
        });
        gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { scroller.forceFinished(true); return true; }
            @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                sx += dx; sy += dy; clamp(); moveDone(); return true;
            }
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                scroller.fling((int) sx, (int) sy, (int) -vx, (int) -vy, 0, (int) maxX(), 0, (int) maxY());
                postInvalidateOnAnimation(); return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                float nz = zoom > 1.4f ? 1f : 2.5f;
                float px = (sx + e.getX()) / zoom, py = (sy + e.getY()) / zoom;
                zoom = nz; sx = px * zoom - e.getX(); sy = py * zoom - e.getY();
                clamp(); moveDone(); return true;
            }
        });
    }

    public void setHost(Host h) { host = h; }

    // ------------------------------------------------------------------ opening
    public void open(File f) throws IOException {
        close();
        try {
            pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(pfd);
        } catch (SecurityException e) {
            closeQuiet();
            throw new IOException("This PDF is password protected");
        } catch (IOException e) { closeQuiet(); throw e; }
        count = renderer.getPageCount();
        if (count <= 0) { closeQuiet(); throw new IOException("This PDF has no pages"); }
        pw = new float[count]; ph = new float[count]; top = new float[count]; hpx = new float[count];
        PdfRenderer.Page p0 = renderer.openPage(0);
        float w0 = p0.getWidth(), h0 = p0.getHeight();
        p0.close();
        for (int i = 0; i < count; i++) { pw[i] = w0; ph[i] = h0; }
        closed = false;
        exec = Executors.newSingleThreadExecutor();
        base.evictAll(); details.clear(); pending.clear(); pendingDetail.clear();
        curPage = -1;
        postRelayout();
        if (count > 1) exec.execute(sizeLoader(1));
    }

    private void closeQuiet() {
        try { if (renderer != null) renderer.close(); } catch (Throwable ignored) { }
        try { if (pfd != null) pfd.close(); } catch (Throwable ignored) { }
        renderer = null; pfd = null;
    }

    public void close() {
        closed = true;
        ui.removeCallbacksAndMessages(null);
        final ExecutorService e = exec;
        exec = null;
        if (e != null) {
            final PdfRenderer r = renderer; final ParcelFileDescriptor p = pfd;
            e.execute(new Runnable() { @Override public void run() {
                try { if (r != null) r.close(); } catch (Throwable ignored) { }
                try { if (p != null) p.close(); } catch (Throwable ignored) { }
            } });
            e.shutdown();
            renderer = null; pfd = null;
        } else closeQuiet();
    }

    private Runnable sizeLoader(final int from) {
        return new Runnable() {
            @Override public void run() {
                if (closed) return;
                final int end = Math.min(count, from + 40);
                for (int i = from; i < end && !closed; i++) {
                    try {
                        PdfRenderer.Page p = renderer.openPage(i);
                        pw[i] = p.getWidth(); ph[i] = p.getHeight();
                        p.close();
                    } catch (Throwable ignored) { }
                }
                ui.post(new Runnable() { @Override public void run() { if (!closed) sizesChanged(); } });
                if (end < count && !closed && exec != null) {
                    try { exec.execute(sizeLoader(end)); } catch (RuntimeException ignored) { }
                }
            }
        };
    }

    // ------------------------------------------------------------------ layout
    private void postRelayout() { if (vw > 0) { relayout(); } invalidate(); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        int p = Math.max(0, pageAtCenter());
        float frac = count == 0 || hpx.length == 0 || hpx[p] == 0 ? 0 : fracAtCenter(p);
        vw = w; vh = h;
        base.evictAll(); details.clear(); pending.clear(); pendingDetail.clear();
        relayout();
        if (count > 0) { restoreAnchor(p, frac); }
        clamp();
        if (wantPage >= 0) { goTo(wantPage); wantPage = -1; }
        scheduleDetail();
    }

    private void relayout() {
        if (vw <= 0 || count == 0) return;
        float y = 0;
        for (int i = 0; i < count; i++) {
            float f = vw / pw[i];
            top[i] = y; hpx[i] = ph[i] * f;
            y += hpx[i] + gap;
        }
        docH = y - gap;
    }

    private int pageAtDocY(float y) {
        int lo = 0, hi = count - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (top[mid] <= y) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    private int pageAtCenter() { return count == 0 || vw == 0 ? 0 : pageAtDocY((sy + vh / 2f) / zoom); }

    private float fracAtCenter(int p) { return Math.max(0, Math.min(1, ((sy + vh / 2f) / zoom - top[p]) / Math.max(1, hpx[p]))); }

    private void restoreAnchor(int p, float frac) { sy = (top[p] + frac * hpx[p]) * zoom - vh / 2f; }

    private void sizesChanged() {
        if (vw <= 0) return;
        int p = pageAtCenter();
        float frac = fracAtCenter(p);
        relayout();
        restoreAnchor(p, frac);
        clamp();
        invalidate();
    }

    private float maxX() { return Math.max(0, vw * zoom - vw); }
    private float maxY() { return Math.max(0, docH * zoom - vh); }

    private void clamp() { sx = Math.max(0, Math.min(maxX(), sx)); sy = Math.max(0, Math.min(maxY(), sy)); }

    private void moveDone() { thumbUntil = System.currentTimeMillis() + 900; invalidate(); scheduleDetail(); }

    public void goTo(int page) {
        if (vw <= 0 || count == 0) { wantPage = page; return; }
        page = Math.max(0, Math.min(count - 1, page));
        sy = top[page] * zoom; sx = Math.min(sx, maxX());
        clamp(); moveDone();
    }

    public int page() { return Math.max(0, curPage); }
    public int pages() { return count; }

    // ------------------------------------------------------------------ rendering
    private final Runnable detailTask = new Runnable() { @Override public void run() { requestDetails(); } };

    private void scheduleDetail() { ui.removeCallbacks(detailTask); ui.postDelayed(detailTask, 170); }

    private void requestBase(final int i) {
        if (closed || exec == null || pending.contains(i) || base.get(i) != null) return;
        pending.add(i);
        final int w = vw;
        exec.execute(new Runnable() {
            @Override public void run() {
                if (closed) return;
                Bitmap b = null;
                if (i >= firstVis - 2 && i <= lastVis + 2) {
                    try {
                        PdfRenderer.Page pg = renderer.openPage(i);
                        try {
                            int bh = Math.max(1, Math.round(w * (float) pg.getHeight() / pg.getWidth()));
                            b = Bitmap.createBitmap(w, bh, Bitmap.Config.ARGB_8888);
                            b.eraseColor(Color.WHITE);
                            Matrix m = new Matrix();
                            m.setScale(w / (float) pg.getWidth(), bh / (float) pg.getHeight());
                            pg.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        } finally { pg.close(); }
                    } catch (Throwable t) { b = null; }
                }
                final Bitmap fb = b;
                ui.post(new Runnable() { @Override public void run() {
                    pending.remove(i);
                    if (closed) return;
                    if (fb != null && vw == w) base.put(i, fb);
                    invalidate();
                } });
            }
        });
    }

    private void requestDetails() {
        if (closed || exec == null || vw <= 0 || count == 0) return;
        if (zoom < 1.15f) { details.clear(); return; }
        for (int i = firstVis; i <= lastVis && i < count; i++) {
            final float z = zoom;
            final float pageW = vw * z, pageH = hpx[i] * z;
            float m = 0.1f * vw;
            float visL = Math.max(0, sx), visR = Math.min(pageW, sx + vw);
            float visT = Math.max(0, sy - top[i] * z), visB = Math.min(pageH, sy + vh - top[i] * z);
            if (visR <= visL || visB <= visT) continue;
            Detail d = details.get(i);
            if (d != null && d.zoom == z && d.l <= visL && d.l + d.bmp.getWidth() >= visR && d.t <= visT && d.t + d.bmp.getHeight() >= visB) continue;
            if ((visR - visL + 2 * m) * (visB - visT + 2 * m) > 4_500_000f) m = 0;
            final int l = (int) Math.max(0, visL - m), t = (int) Math.max(0, visT - m);
            final int r = (int) Math.min(pageW, visR + m), b = (int) Math.min(pageH, visB + m);
            final int w = r - l, h = b - t;
            if (w < 8 || h < 8) continue;
            final int page = i;
            final String key = page + ":" + z + ":" + l + ":" + t;
            if (!pendingDetail.add(key)) continue;
            exec.execute(new Runnable() {
                @Override public void run() {
                    Bitmap bm = null;
                    if (!closed && z == zoom) {
                        try {
                            PdfRenderer.Page pg = renderer.openPage(page);
                            try {
                                bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                                bm.eraseColor(Color.WHITE);
                                float s = pageW / pg.getWidth();
                                Matrix mt = new Matrix();
                                mt.setScale(s, s);
                                mt.postTranslate(-l, -t);
                                pg.render(bm, null, mt, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                            } finally { pg.close(); }
                        } catch (Throwable ex) { bm = null; }
                    }
                    final Bitmap fb = bm;
                    ui.post(new Runnable() { @Override public void run() {
                        pendingDetail.remove(key);
                        if (closed || fb == null) return;
                        Detail d = new Detail(); d.zoom = z; d.l = l; d.t = t; d.bmp = fb;
                        details.put(page, d);
                        invalidate();
                    } });
                }
            });
        }
    }

    // ------------------------------------------------------------------ drawing
    @Override protected void onDraw(Canvas c) {
        if (count == 0 || vw == 0) return;
        if (scroller.computeScrollOffset()) {
            sx = scroller.getCurrX(); sy = scroller.getCurrY(); clamp(); thumbUntil = System.currentTimeMillis() + 900;
            scheduleDetail();
            postInvalidateOnAnimation();
        }
        int f = pageAtDocY(sy / zoom), l = pageAtDocY((sy + vh) / zoom);
        firstVis = f; lastVis = l;
        int mid = pageAtCenter();
        if (mid != curPage) { curPage = mid; if (host != null) host.onPage(mid + 1, count); }
        for (int i = f; i <= l; i++) {
            float left = -sx, tp = top[i] * zoom - sy, w = vw * zoom, h = hpx[i] * zoom;
            c.drawRect(left, tp, left + w, tp + h, fill);
            Bitmap b = base.get(i);
            if (b != null) c.drawBitmap(b, null, new RectF(left, tp, left + w, tp + h), bmpPaint);
            else requestBase(i);
            Detail d = details.get(i);
            if (d != null && d.zoom == zoom) c.drawBitmap(d.bmp, left + d.l, tp + d.t, null);
            // annotations
            ArrayList<PdfAnnot> list = annots.get(i);
            boolean live = strokePage == i && !cur.isEmpty();
            if ((list != null && !list.isEmpty()) || live) {
                c.save();
                c.clipRect(left, tp, left + w, tp + h);
                c.translate(left, tp);
                float s = vw / pw[i] * zoom;
                c.scale(s, s);
                if (list != null) for (PdfAnnot a : list) drawAnnot(c, a, stroke, fill, textPaint);
                if (live) drawLive(c);
                c.restore();
            }
        }
        for (int i = Math.max(0, f - 1); i <= Math.min(count - 1, l + 1); i++) if (i < f || i > l) requestBase(i);
        // fast-scroll thumb
        if (docH * zoom > vh * 1.5f && System.currentTimeMillis() < thumbUntil || scrubbing) {
            float frac = maxY() == 0 ? 0 : sy / maxY();
            float th = Ui.dp(getContext(), 56), x = vw - Ui.dp(getContext(), 8);
            float y = frac * (vh - th);
            c.drawRoundRect(new RectF(x, y, x + Ui.dp(getContext(), 5), y + th), 8, 8, thumb);
            if (!scroller.isFinished() || scrubbing) postInvalidateOnAnimation(); else postInvalidateDelayed(950);
        }
    }

    private void drawLive(Canvas c) {
        float[] pts = new float[cur.size() * 2];
        for (int i = 0; i < cur.size(); i++) { pts[i * 2] = cur.get(i)[0]; pts[i * 2 + 1] = cur.get(i)[1]; }
        PdfAnnot a = new PdfAnnot();
        a.kind = PdfAnnot.INK; a.strokes = new float[][]{pts};
        applyBrush(a);
        drawAnnot(c, a, stroke, fill, textPaint);
    }

    private float widthFor(int t) {
        float[] pen = {1.2f, 2.5f, 5f}, mk = {9f, 14f, 22f};
        return t == MARKER ? mk[sizeIdx] : pen[sizeIdx];
    }

    private void applyBrush(PdfAnnot a) {
        a.color = color;
        a.lw = widthFor(tool);
        if (tool == MARKER) { a.alpha = 0.38f; a.flat = true; } else { a.alpha = 1f; a.flat = false; }
    }

    /** Draws one annotation; the canvas must be in page points. */
    public static void drawAnnot(Canvas c, PdfAnnot a, Paint stroke, Paint fill, TextPaint tp) {
        int alpha = Math.round(a.alpha * 255);
        int col = (alpha << 24) | (a.color & 0xFFFFFF);
        if (a.kind == PdfAnnot.INK && a.strokes != null) {
            stroke.setColor(col);
            stroke.setStrokeWidth(a.lw);
            stroke.setStrokeCap(a.flat ? Paint.Cap.BUTT : Paint.Cap.ROUND);
            if (a.cache == null) {
                Path[] ps = new Path[a.strokes.length];
                for (int k = 0; k < ps.length; k++) ps[k] = pathOf(a.strokes[k]);
                a.cache = ps;
            }
            Path[] ps = (Path[]) a.cache;
            for (int k = 0; k < ps.length; k++) {
                float[] s = a.strokes[k];
                if (ps[k] == null && s.length >= 2) {
                    fill.setColor(col); fill.setStyle(Paint.Style.FILL);
                    c.drawCircle(s[0], s[1], Math.max(0.6f, a.lw / 2), fill);
                    fill.setColor(Color.WHITE);
                } else if (ps[k] != null) c.drawPath(ps[k], stroke);
            }
        } else if (a.kind == PdfAnnot.RECTS && a.rects != null) {
            fill.setColor(col);
            for (int i = 0; i + 3 < a.rects.length; i += 4) c.drawRect(a.rects[i], a.rects[i + 1], a.rects[i + 2], a.rects[i + 3], fill);
            fill.setColor(Color.WHITE);
        } else if (a.kind == PdfAnnot.TEXT) {
            StaticLayout l = layoutOf(a, tp);
            c.save();
            c.translate(a.u, a.v);
            l.draw(c);
            c.restore();
        }
    }

    private static Path pathOf(float[] s) {
        if (s.length < 4) return null;
        Path p = new Path();
        p.moveTo(s[0], s[1]);
        for (int i = 2; i + 1 < s.length; i += 2) {
            float px = s[i - 2], py = s[i - 1];
            p.quadTo(px, py, (px + s[i]) / 2, (py + s[i + 1]) / 2);
        }
        p.lineTo(s[s.length - 2], s[s.length - 1]);
        return p;
    }

    private static StaticLayout layoutOf(PdfAnnot a, TextPaint tp) {
        Object cached = a.cache;
        if (cached instanceof StaticLayout) return (StaticLayout) cached;
        TextPaint p = new TextPaint(tp);
        p.setColor(0xFF000000 | a.color);
        p.setTextSize(a.fs);
        int w = Math.max(20, Math.round(a.w));
        StaticLayout l = StaticLayout.Builder.obtain(a.text, 0, a.text.length(), p, w).setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
        a.cache = l;
        a.h = Math.max(a.h, l.getHeight());
        return l;
    }

    /** Renders a text annotation to pixels for the appearance stream (3 px per point). */
    public static void rasterize(PdfAnnot a) {
        if (a.kind != PdfAnnot.TEXT) return;
        a.cache = null;
        StaticLayout l = layoutOf(a, new TextPaint(Paint.ANTI_ALIAS_FLAG));
        a.h = l.getHeight();
        float s = 3f;
        int w = Math.max(1, (int) Math.ceil(a.w * s)), h = Math.max(1, (int) Math.ceil(a.h * s));
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(s, s);
        l.draw(c);
        a.px = new int[w * h];
        b.getPixels(a.px, 0, w, 0, 0, w, h);
        a.pw = w; a.ph = h;
        b.recycle();
    }

    // ------------------------------------------------------------------ touch
    @Override public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        float thumbZone = vw - Ui.dp(getContext(), 30);
        if (a == MotionEvent.ACTION_DOWN) { multi = false; scrubbing = tool == NONE && e.getX() > thumbZone && docH * zoom > vh * 1.5f; }
        if (scrubbing) {
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) { scrubbing = false; invalidate(); return true; }
            scroller.forceFinished(true);
            float th = Ui.dp(getContext(), 56);
            float frac = Math.max(0, Math.min(1, (e.getY() - th / 2) / Math.max(1, vh - th)));
            sy = frac * maxY(); clamp(); moveDone();
            return true;
        }
        if (tool == NONE) { sd.onTouchEvent(e); gd.onTouchEvent(e); return true; }
        if (e.getPointerCount() > 1 || multi) {
            if (strokePage >= 0 && !cur.isEmpty()) { cur.clear(); strokePage = -1; }
            multi = a != MotionEvent.ACTION_UP && a != MotionEvent.ACTION_CANCEL;
            sd.onTouchEvent(e); gd.onTouchEvent(e);
            return true;
        }
        sd.onTouchEvent(e);
        return tool(e);
    }

    private int pageAt(float x, float y) {
        float dy = (sy + y) / zoom;
        return pageAtDocY(dy);
    }

    private float[] toPage(int p, float x, float y) {
        float f = vw / pw[p];
        float u = (sx + x) / zoom / f, v = ((sy + y) / zoom - top[p]) / f;
        return new float[]{Math.max(0, Math.min(pw[p], u)), Math.max(0, Math.min(ph[p], v))};
    }

    private boolean tool(MotionEvent e) {
        int a = e.getActionMasked();
        switch (a) {
            case MotionEvent.ACTION_DOWN: {
                downX = e.getX(); downY = e.getY(); moved = false;
                strokePage = pageAt(e.getX(), e.getY());
                cur.clear();
                float[] p = toPage(strokePage, e.getX(), e.getY());
                if (tool == PEN || tool == MARKER) cur.add(p);
                else if (tool == ERASER) eraseAt(strokePage, p[0], p[1]);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (strokePage < 0) return true;
                if (Math.abs(e.getX() - downX) > 12 || Math.abs(e.getY() - downY) > 12) moved = true;
                if (tool == PEN || tool == MARKER) {
                    for (int h = 0; h < e.getHistorySize(); h++) addPoint(e.getHistoricalX(h), e.getHistoricalY(h));
                    addPoint(e.getX(), e.getY());
                } else if (tool == ERASER) {
                    float[] p = toPage(strokePage, e.getX(), e.getY());
                    eraseAt(strokePage, p[0], p[1]);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (strokePage >= 0) {
                    if ((tool == PEN || tool == MARKER) && !cur.isEmpty()) {
                        PdfAnnot an = new PdfAnnot();
                        an.kind = PdfAnnot.INK; an.page = strokePage;
                        float[] pts = new float[cur.size() * 2];
                        for (int i = 0; i < cur.size(); i++) { pts[i * 2] = cur.get(i)[0]; pts[i * 2 + 1] = cur.get(i)[1]; }
                        an.strokes = new float[][]{pts};
                        applyBrush(an);
                        add(an, true);
                    } else if (tool == TEXT && !moved && host != null) {
                        float[] p = toPage(strokePage, e.getX(), e.getY());
                        host.askText(textAt(strokePage, p[0], p[1]), strokePage, p[0], p[1]);
                    }
                }
                cur.clear(); strokePage = -1; invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL: cur.clear(); strokePage = -1; invalidate(); return true;
            default: return true;
        }
    }

    private void addPoint(float x, float y) {
        float[] p = toPage(strokePage, x, y);
        float[] last = cur.get(cur.size() - 1);
        float dx = p[0] - last[0], dy = p[1] - last[1];
        if (dx * dx + dy * dy >= 0.25f) cur.add(p);
    }

    // ------------------------------------------------------------------ annotation model
    public void setTool(int t) { tool = t; cur.clear(); strokePage = -1; invalidate(); }
    public int tool() { return tool; }
    public void setColor(int c) { color = c; }
    public int color() { return color; }
    public int cycleSize() { sizeIdx = (sizeIdx + 1) % 3; return sizeIdx; }
    public float textSize() { return new float[]{11f, 15f, 21f}[sizeIdx]; }

    private ArrayList<PdfAnnot> listOf(int page) {
        ArrayList<PdfAnnot> l = annots.get(page);
        if (l == null) { l = new ArrayList<>(); annots.put(page, l); }
        return l;
    }

    private void add(PdfAnnot a, boolean record) {
        listOf(a.page).add(a);
        if (record) { Op o = new Op(); o.added = true; o.a = a; undo.push(o); }
        if (host != null) host.onChanged();
        invalidate();
    }

    private void remove(PdfAnnot a, boolean record) {
        listOf(a.page).remove(a);
        if (a.objNum >= 0) { ArrayList<PdfAnnot> r = removed.get(a.page); if (r == null) { r = new ArrayList<>(); removed.put(a.page, r); } r.add(a); }
        if (record) { Op o = new Op(); o.added = false; o.a = a; undo.push(o); }
        if (host != null) host.onChanged();
        invalidate();
    }

    public boolean canUndo() { return !undo.isEmpty(); }

    public void undo() {
        if (undo.isEmpty()) return;
        Op o = undo.pop();
        if (o.added) { remove(o.a, false); }
        else {
            ArrayList<PdfAnnot> r = removed.get(o.a.page);
            if (r != null) r.remove(o.a);
            listOf(o.a.page).add(o.a);
            if (host != null) host.onChanged();
            invalidate();
        }
    }

    public boolean hasChanges() {
        for (ArrayList<PdfAnnot> r : removed.values()) if (!r.isEmpty()) return true;
        for (ArrayList<PdfAnnot> l : annots.values()) for (PdfAnnot a : l) if (a.objNum == -1) return true;
        return false;
    }

    public Map<Integer, PdfAnnotIO.Delta> deltas() {
        Map<Integer, PdfAnnotIO.Delta> m = new TreeMap<>();
        for (Map.Entry<Integer, ArrayList<PdfAnnot>> e : annots.entrySet())
            for (PdfAnnot a : e.getValue()) if (a.objNum == -1) {
                PdfAnnotIO.Delta d = m.get(e.getKey());
                if (d == null) { d = new PdfAnnotIO.Delta(); m.put(e.getKey(), d); }
                if (a.kind == PdfAnnot.TEXT) rasterize(a);
                d.added.add(a);
            }
        for (Map.Entry<Integer, ArrayList<PdfAnnot>> e : removed.entrySet())
            for (PdfAnnot a : e.getValue()) {
                PdfAnnotIO.Delta d = m.get(e.getKey());
                if (d == null) { d = new PdfAnnotIO.Delta(); m.put(e.getKey(), d); }
                d.removed.add(a.objNum);
            }
        return m;
    }

    /** All annotations currently shown, by page (for the flattened export). */
    public Map<Integer, List<PdfAnnot>> shown() {
        Map<Integer, List<PdfAnnot>> m = new TreeMap<>();
        for (Map.Entry<Integer, ArrayList<PdfAnnot>> e : annots.entrySet()) m.put(e.getKey(), new ArrayList<PdfAnnot>(e.getValue()));
        return m;
    }

    public float pageW(int i) { return pw[i]; }
    public float pageH(int i) { return ph[i]; }

    /** Result of the text dialog. */
    public void textResult(PdfAnnot existing, int page, float u, float v, String text) {
        text = text == null ? "" : text.trim();
        if (existing != null) {
            if (text.equals(existing.text)) return;
            remove(existing, true);
            if (text.isEmpty()) return;
            u = existing.u; v = existing.v;
        }
        if (text.isEmpty()) return;
        PdfAnnot a = new PdfAnnot();
        a.kind = PdfAnnot.TEXT; a.page = page; a.text = text; a.color = color; a.fs = textSize();
        a.w = Math.min(pw[page] * 0.7f, 260f);
        if (existing != null && existing.w > 0) a.w = existing.w;
        a.u = Math.max(2, Math.min(u, pw[page] - a.w - 2)); a.v = Math.max(2, Math.min(v, ph[page] - 20));
        layoutOf(a, textPaint);
        add(a, true);
    }

    private PdfAnnot textAt(int page, float u, float v) {
        ArrayList<PdfAnnot> l = annots.get(page);
        if (l == null) return null;
        for (int i = l.size() - 1; i >= 0; i--) {
            PdfAnnot a = l.get(i);
            if (a.kind == PdfAnnot.TEXT && u >= a.u && u <= a.u + a.w && v >= a.v && v <= a.v + Math.max(a.h, 14)) return a;
        }
        return null;
    }

    private void eraseAt(int page, float u, float v) {
        ArrayList<PdfAnnot> l = annots.get(page);
        if (l == null) return;
        float r = 14f / (vw / pw[page] * zoom);
        for (int i = l.size() - 1; i >= 0; i--) {
            PdfAnnot a = l.get(i);
            if (a.objNum == -2) continue;
            if (hit(a, u, v, r)) { remove(a, true); return; }
        }
    }

    private static boolean hit(PdfAnnot a, float u, float v, float r) {
        if (a.kind == PdfAnnot.TEXT) return u >= a.u - r && u <= a.u + a.w + r && v >= a.v - r && v <= a.v + Math.max(a.h, 14) + r;
        if (a.kind == PdfAnnot.RECTS) {
            for (int i = 0; i + 3 < a.rects.length; i += 4) if (u >= a.rects[i] - r && u <= a.rects[i + 2] + r && v >= a.rects[i + 1] - r && v <= a.rects[i + 3] + r) return true;
            return false;
        }
        float rr = r + a.lw / 2;
        for (float[] s : a.strokes) {
            if (s.length == 2 && Math.hypot(u - s[0], v - s[1]) <= rr) return true;
            for (int i = 0; i + 3 < s.length; i += 2) if (segDist(u, v, s[i], s[i + 1], s[i + 2], s[i + 3]) <= rr) return true;
        }
        return false;
    }

    private static double segDist(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay;
        float len2 = dx * dx + dy * dy;
        float t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    // ------------------------------------------------------------------ loading saved annotations
    public void loadAnnotations(final File f) {
        annots.clear(); removed.clear(); undo.clear();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    PdfDoc d = new PdfDoc(f);
                    final Map<Integer, List<PdfAnnot>> m;
                    try { m = PdfAnnotIO.loadAll(d); } finally { d.close(); }
                    ui.post(new Runnable() { @Override public void run() {
                        if (closed) return;
                        for (Map.Entry<Integer, List<PdfAnnot>> e : m.entrySet()) listOf(e.getKey()).addAll(0, e.getValue());
                        invalidate();
                    } });
                } catch (Throwable ignored) { /* unsupported structure: the PDF still displays */ }
            }
        }, "docos-annots").start();
    }

    public void resetAnnotations() { annots.clear(); removed.clear(); undo.clear(); invalidate(); }
}
