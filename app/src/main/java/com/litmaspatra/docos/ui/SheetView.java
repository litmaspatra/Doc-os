package com.litmaspatra.docos.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.OverScroller;

import com.litmaspatra.docos.ooxml.Xlsx;

/** Spreadsheet grid drawn directly on a canvas: only visible cells are touched. */
public final class SheetView extends View {
    public interface Listener { void onSelect(int r, int c); void onEdit(int r, int c); }

    private Listener listener;
    private Xlsx.Sheet sheet;
    private float[] colX = new float[1], rowY = new float[1];   // prefix sums at scale 1 (px)
    private int rows, cols;
    private float scale = 1f, sx, sy;
    private int selR = -1, selC = -1;
    private final float hw, hh, dm;
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(), fill = new Paint(), sel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GestureDetector gd;
    private final ScaleGestureDetector sd;
    private final OverScroller scroller;

    public SheetView(Context c) {
        super(c);
        dm = c.getResources().getDisplayMetrics().density;
        hw = 46 * dm; hh = 30 * dm;
        line.setColor(0xFFD9DCE1); line.setStrokeWidth(1f);
        sel.setStyle(Paint.Style.STROKE); sel.setStrokeWidth(2.5f * dm); sel.setColor(Ui.ACCENT);
        scroller = new OverScroller(c);
        setBackgroundColor(Color.WHITE);
        sd = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                float ns = Math.max(0.5f, Math.min(2.5f, scale * d.getScaleFactor()));
                float fx = d.getFocusX() - hw, fy = d.getFocusY() - hh;
                float px = (sx + fx) / scale, py = (sy + fy) / scale;
                scale = ns; sx = px * scale - fx; sy = py * scale - fy;
                clamp(); invalidate(); return true;
            }
        });
        gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { scroller.forceFinished(true); return true; }
            @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) { sx += dx; sy += dy; clamp(); invalidate(); return true; }
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                scroller.fling((int) sx, (int) sy, (int) -vx, (int) -vy, 0, (int) maxX(), 0, (int) maxY());
                postInvalidateOnAnimation(); return true;
            }
            @Override public boolean onSingleTapUp(MotionEvent e) { tap(e.getX(), e.getY()); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (selR >= 0 && listener != null) listener.onEdit(selR, selC);
                return true;
            }
        });
    }

    public void setListener(Listener l) { listener = l; }
    public int selRow() { return selR; }
    public int selCol() { return selC; }

    public void setSheet(Xlsx.Sheet s) {
        sheet = s;
        cols = Math.max(s.maxCol + 4, 26);
        rows = Math.max(s.maxRow + 21, 100);
        colX = new float[cols + 1];
        rowY = new float[rows + 1];
        for (int c = 0; c < cols; c++) {
            float w = 0;
            if (!s.hiddenCols.contains(c)) { Float cw = s.colW.get(c); float chars = cw == null ? 8.43f : cw; w = Math.max(40f, Math.min(400f, chars * 8.4f)) * dm; }
            colX[c + 1] = colX[c] + w;
        }
        for (int r = 0; r < rows; r++) {
            float h = 0;
            if (!s.hiddenRows.contains(r)) { Float rh = s.rowH.get(r); h = (rh == null ? 30f : Math.max(26f, rh * 1.9f)) * dm; }
            rowY[r + 1] = rowY[r] + h;
        }
        sx = sy = 0; selR = selC = -1;
        clamp(); invalidate();
    }

    public void select(int r, int c) {
        selR = r; selC = c;
        ensureVisible(r, c);
        invalidate();
        if (listener != null) listener.onSelect(r, c);
    }

    private void ensureVisible(int r, int c) {
        float l = colX[c] * scale, rt = colX[c + 1] * scale, t = rowY[r] * scale, b = rowY[r + 1] * scale;
        float vw = getWidth() - hw, vh = getHeight() - hh;
        if (l < sx) sx = l; else if (rt > sx + vw) sx = rt - vw;
        if (t < sy) sy = t; else if (b > sy + vh) sy = b - vh;
        clamp();
    }

    private float maxX() { return Math.max(0, colX[cols] * scale - (getWidth() - hw)); }
    private float maxY() { return Math.max(0, rowY[rows] * scale - (getHeight() - hh)); }
    private void clamp() { sx = Math.max(0, Math.min(maxX(), sx)); sy = Math.max(0, Math.min(maxY(), sy)); }

    private static int find(float[] prefix, int n, float v) {
        int lo = 0, hi = n - 1;
        while (lo < hi) { int mid = (lo + hi + 1) >>> 1; if (prefix[mid] <= v) lo = mid; else hi = mid - 1; }
        return lo;
    }

    private void tap(float x, float y) {
        if (sheet == null || x < hw || y < hh) return;
        int c = find(colX, cols, (sx + x - hw) / scale), r = find(rowY, rows, (sy + y - hh) / scale);
        if (colX[c + 1] == colX[c] || rowY[r + 1] == rowY[r]) return;
        if (r == selR && c == selC) { if (listener != null) listener.onEdit(r, c); }
        else select(r, c);
    }

    @Override public boolean onTouchEvent(MotionEvent e) { sd.onTouchEvent(e); if (!sd.isInProgress()) gd.onTouchEvent(e); return true; }

    @Override protected void onDraw(Canvas cv) {
        if (sheet == null) return;
        if (scroller.computeScrollOffset()) { sx = scroller.getCurrX(); sy = scroller.getCurrY(); clamp(); postInvalidateOnAnimation(); }
        int w = getWidth(), h = getHeight();
        int c0 = find(colX, cols, sx / scale), r0 = find(rowY, rows, sy / scale);
        float ts = 14f * dm * scale;
        text.setTextSize(ts);
        text.setColor(0xFF111827);
        float pad = 5 * dm;
        cv.save();
        cv.clipRect(hw, hh, w, h);
        // cells
        for (int r = r0; r < rows; r++) {
            float top = hh + rowY[r] * scale - sy, bot = hh + rowY[r + 1] * scale - sy;
            if (top > h) break;
            if (bot <= top) continue;
            for (int c = c0; c < cols; c++) {
                float l = hw + colX[c] * scale - sx, rt = hw + colX[c + 1] * scale - sx;
                if (l > w) break;
                if (rt <= l) continue;
                Xlsx.Cell cell = sheet.get(r, c);
                if (cell != null && !cell.disp.isEmpty()) {
                    cv.save();
                    cv.clipRect(l, top, rt, bot);
                    String s = cell.disp;
                    if (s.length() > 200) s = s.substring(0, 200);
                    float y = top + (bot - top) / 2 + ts * 0.35f;
                    if (cell.num && cell.formula == null) {
                        text.setTextAlign(Paint.Align.RIGHT);
                        cv.drawText(s, rt - pad, y, text);
                    } else {
                        text.setTextAlign(Paint.Align.LEFT);
                        cv.drawText(s, l + pad, y, text);
                    }
                    cv.restore();
                }
                cv.drawLine(rt, top, rt, bot, line);
            }
            cv.drawLine(hw, bot, w, bot, line);
        }
        if (selR >= 0 && selC >= 0) {
            float l = hw + colX[selC] * scale - sx, t = hh + rowY[selR] * scale - sy;
            fill.setColor(0x142563EB);
            cv.drawRect(l, t, hw + colX[selC + 1] * scale - sx, hh + rowY[selR + 1] * scale - sy, fill);
            cv.drawRect(l, t, hw + colX[selC + 1] * scale - sx, hh + rowY[selR + 1] * scale - sy, sel);
        }
        cv.restore();
        // headers
        fill.setColor(0xFFF1F3F5);
        cv.drawRect(0, 0, w, hh, fill);
        cv.drawRect(0, 0, hw, h, fill);
        text.setTextSize(12f * dm);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(0xFF4B5563);
        cv.save();
        cv.clipRect(hw, 0, w, hh);
        for (int c = c0; c < cols; c++) {
            float l = hw + colX[c] * scale - sx, rt = hw + colX[c + 1] * scale - sx;
            if (l > w) break;
            if (rt <= l) continue;
            if (c == selC) { fill.setColor(0xFFDCE6FF); cv.drawRect(l, 0, rt, hh, fill); }
            cv.drawText(Xlsx.colName(c), (l + rt) / 2, hh / 2 + 4.5f * dm, text);
            cv.drawLine(rt, 0, rt, hh, line);
        }
        cv.restore();
        cv.save();
        cv.clipRect(0, hh, hw, h);
        for (int r = r0; r < rows; r++) {
            float top = hh + rowY[r] * scale - sy, bot = hh + rowY[r + 1] * scale - sy;
            if (top > h) break;
            if (bot <= top) continue;
            if (r == selR) { fill.setColor(0xFFDCE6FF); cv.drawRect(0, top, hw, bot, fill); }
            cv.drawText(String.valueOf(r + 1), hw / 2, (top + bot) / 2 + 4.5f * dm, text);
            cv.drawLine(0, bot, hw, bot, line);
        }
        cv.restore();
        cv.drawLine(0, hh, w, hh, line);
        cv.drawLine(hw, 0, hw, h, line);
    }
}
