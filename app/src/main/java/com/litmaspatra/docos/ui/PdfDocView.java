package com.litmaspatra.docos.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.litmaspatra.docos.R;
import com.litmaspatra.docos.pdf.PdfAnnot;
import com.litmaspatra.docos.pdf.PdfAnnotIO;
import com.litmaspatra.docos.pdf.PdfDoc;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

public final class PdfDocView implements DocView, PdfView.Host {
    private final Host host;
    private Context ctx;
    private File file;
    private PdfView view;
    private FrameLayout root;
    private TextView indicator, sizeBtn;
    private HorizontalScrollView bar;
    private View fab;
    private View[] toolBtns = new View[5];
    private View[] dots = new View[PdfView.COLORS.length];
    private View undoBtn;
    private boolean annotating;
    private Map<Integer, PdfAnnotIO.Delta> deltas;
    private Map<Integer, List<PdfAnnot>> snapshot;

    public PdfDocView(Host h) { host = h; }

    @Override public void load(File f) { file = f; }

    @Override public View createView(Context c) throws Exception {
        ctx = c;
        view = new PdfView(c);
        view.setHost(this);
        view.open(file);
        view.loadAnnotations(file);
        root = new FrameLayout(c);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));

        indicator = Ui.text(c, "", 13, Color.WHITE, true);
        indicator.setBackground(Ui.round(0xCC111827, 14, c));
        indicator.setPadding(Ui.dp(c, 12), Ui.dp(c, 5), Ui.dp(c, 12), Ui.dp(c, 5));
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        ip.bottomMargin = Ui.dp(c, 18);
        root.addView(indicator, ip);
        indicator.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { gotoDialog(); } });

        fab = Ui.icon(c, R.drawable.ic_pen, Color.WHITE, "\u270E");
        fab.setBackground(Ui.round(Ui.ACCENT, 28, c));
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(Ui.dp(c, 54), Ui.dp(c, 54), Gravity.BOTTOM | Gravity.END);
        fp.setMargins(0, 0, Ui.dp(c, 16), Ui.dp(c, 16));
        root.addView(fab, fp);
        fab.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { setAnnotating(!annotating); } });

        buildBar(c);
        return root;
    }

    private void buildBar(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(c, 6), 0, Ui.dp(c, 6), 0);
        int[] res = {R.drawable.ic_pen, R.drawable.ic_marker, R.drawable.ic_text, R.drawable.ic_eraser};
        String[] fb = {"Pen", "Hi", "T", "Er"};
        final int[] tools = {PdfView.PEN, PdfView.MARKER, PdfView.TEXT, PdfView.ERASER};
        for (int i = 0; i < 4; i++) {
            final int t = tools[i];
            View b = Ui.icon(c, res[i], Ui.TEXT, fb[i]);
            b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { view.setTool(t); refreshBar(); } });
            row.addView(b, Ui.lp(c, 44, 44));
            toolBtns[tools[i]] = b;
        }
        undoBtn = Ui.icon(c, R.drawable.ic_undo, Ui.TEXT, "Undo");
        undoBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { view.undo(); refreshBar(); } });
        row.addView(undoBtn, Ui.lp(c, 44, 44));
        sizeBtn = Ui.text(c, "M", 14, Ui.TEXT, true);
        sizeBtn.setGravity(Gravity.CENTER);
        Ui.ripple(sizeBtn, 0, 22);
        sizeBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            int i = view.cycleSize();
            sizeBtn.setText(i == 0 ? "S" : i == 1 ? "M" : "L");
        } });
        row.addView(sizeBtn, Ui.lp(c, 40, 44));
        for (int i = 0; i < PdfView.COLORS.length; i++) {
            final int col = PdfView.COLORS[i];
            View d = new View(c);
            d.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { view.setColor(col); refreshBar(); } });
            LinearLayout.LayoutParams lp = Ui.lp(c, 30, 30);
            lp.setMargins(Ui.dp(c, 4), 0, Ui.dp(c, 4), 0);
            row.addView(d, lp);
            dots[i] = d;
        }
        bar = new HorizontalScrollView(c);
        bar.setHorizontalScrollBarEnabled(false);
        bar.setBackgroundColor(Ui.CARD);
        bar.setElevation(Ui.dp(c, 8));
        bar.addView(row, new ViewGroup.LayoutParams(-2, Ui.dp(c, 52)));
        bar.setVisibility(View.GONE);
    }

    private void refreshBar() {
        for (int t = 1; t <= 4; t++) {
            View b = toolBtns[t];
            if (b == null) continue;
            boolean on = view.tool() == t;
            b.setBackground(on ? Ui.round(0x332563EB, 22, ctx) : null);
            if (!on) Ui.ripple(b, 0, 22);
        }
        undoBtn.setAlpha(view.canUndo() ? 1f : 0.35f);
        for (int i = 0; i < dots.length; i++) {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(PdfView.COLORS[i]);
            boolean on = PdfView.COLORS[i] == view.color();
            g.setStroke(Ui.dp(ctx, on ? 3 : 1), on ? Ui.ACCENT : 0x33000000);
            dots[i].setBackground(g);
        }
    }

    private void setAnnotating(boolean on) {
        annotating = on;
        bar.setVisibility(on ? View.VISIBLE : View.GONE);
        view.setTool(on ? PdfView.PEN : PdfView.NONE);
        if (fab instanceof android.widget.ImageButton) {
            try { ((android.widget.ImageButton) fab).setImageDrawable(ctx.getDrawable(on ? R.drawable.ic_check : R.drawable.ic_pen)); }
            catch (RuntimeException ignored) { }
        }
        refreshBar();
    }

    @Override public View bottomBar() { return bar; }

    // ------------------------------------------------------------------ PdfView.Host
    @Override public void onPage(int page, int total) { indicator.setText(page + " / " + total); }
    @Override public void onChanged() { refreshBarSafe(); host.dirtyChanged(); }

    private void refreshBarSafe() { if (undoBtn != null) undoBtn.setAlpha(view.canUndo() ? 1f : 0.35f); }

    @Override public void askText(final PdfAnnot existing, final int page, final float u, final float v) {
        final EditText et = new EditText(ctx);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setMinLines(2);
        et.setGravity(Gravity.TOP);
        if (existing != null) et.setText(existing.text);
        AlertDialog.Builder b = new AlertDialog.Builder(ctx).setTitle(existing == null ? "Add text" : "Edit text").setView(et)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { view.textResult(existing, page, u, v, et.getText().toString()); }
                })
                .setNegativeButton("Cancel", null);
        if (existing != null) b.setNeutralButton("Delete", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) { view.textResult(existing, page, u, v, ""); }
        });
        AlertDialog dlg = b.create();
        dlg.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dlg.show();
    }

    private void gotoDialog() {
        final EditText et = new EditText(ctx);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint("1 - " + view.pages());
        new AlertDialog.Builder(ctx).setTitle("Go to page").setView(et)
                .setPositiveButton("Go", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try { view.goTo(Integer.parseInt(et.getText().toString().trim()) - 1); } catch (NumberFormatException ignored) { }
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    // ------------------------------------------------------------------ DocView
    @Override public boolean dirty() { return view != null && view.hasChanges(); }

    @Override public void beforeSave() {
        deltas = view.deltas();
        snapshot = view.shown();
    }

    @Override public void save(File in, File out) throws Exception {
        if (deltas == null || deltas.isEmpty()) { copy(in, out); return; }
        PdfDoc d = new PdfDoc(in);
        try {
            OutputStream os = new BufferedOutputStream(new FileOutputStream(out));
            try { PdfAnnotIO.write(d, os, deltas); } finally { os.close(); }
        } finally { d.close(); }
    }

    private static void copy(File a, File b) throws IOException {
        InputStream in = new FileInputStream(a);
        OutputStream os = new FileOutputStream(b);
        try { byte[] buf = new byte[65536]; int n; while ((n = in.read(buf)) > 0) os.write(buf, 0, n); } finally { in.close(); os.close(); }
    }

    @Override public boolean hasFallback() { return true; }

    /** Renders every page to a new PDF and draws the annotations on top (works for any PDF PdfRenderer can open). */
    @Override public void saveFallback(File in, File out) throws Exception {
        ParcelFileDescriptor pfd = ParcelFileDescriptor.open(in, ParcelFileDescriptor.MODE_READ_ONLY);
        PdfRenderer r = new PdfRenderer(pfd);
        PdfDocument doc = new PdfDocument();
        try {
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint();
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            for (int i = 0; i < r.getPageCount(); i++) {
                PdfRenderer.Page p = r.openPage(i);
                float w = p.getWidth(), h = p.getHeight();
                float sc = Math.min(2f, 2400f / Math.max(w, h));
                Bitmap b = Bitmap.createBitmap(Math.max(1, Math.round(w * sc)), Math.max(1, Math.round(h * sc)), Bitmap.Config.ARGB_8888);
                b.eraseColor(Color.WHITE);
                p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                p.close();
                PdfDocument.Page dp = doc.startPage(new PdfDocument.PageInfo.Builder(Math.round(w), Math.round(h), i + 1).create());
                Canvas c = dp.getCanvas();
                c.drawBitmap(b, null, new RectF(0, 0, w, h), paint);
                b.recycle();
                List<PdfAnnot> list = snapshot == null ? null : snapshot.get(i);
                if (list != null) for (PdfAnnot a : list) PdfView.drawAnnot(c, a, stroke, fill, tp);
                doc.finishPage(dp);
            }
            OutputStream os = new BufferedOutputStream(new FileOutputStream(out));
            try { doc.writeTo(os); } finally { os.close(); }
        } finally { doc.close(); r.close(); pfd.close(); }
    }

    @Override public void onSaved(File newFile) {
        int pg = view.page();
        file = newFile;
        try {
            view.open(newFile);
            view.resetAnnotations();
            view.loadAnnotations(newFile);
            view.goTo(pg);
        } catch (IOException e) { host.toast("Saved, but could not reload: " + e.getMessage()); }
        host.dirtyChanged();
    }

    @Override public int position() { return view == null ? 0 : view.page(); }
    @Override public void restore(int pos) { if (view != null) view.goTo(pos); }
    @Override public String[] menu() { return new String[]{"Go to page..."}; }
    @Override public void onMenu(int i) { gotoDialog(); }
    @Override public void close() { if (view != null) view.close(); }
}
