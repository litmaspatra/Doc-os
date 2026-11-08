package com.litmaspatra.docos.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.litmaspatra.docos.R;
import com.litmaspatra.docos.ooxml.Pptx;

import java.io.File;

/** PowerPoint: one slide at a time, swipe or use the arrows; tap text to edit it. */
public final class PptxDocView implements DocView, SlideView.Listener {
    private final Host host;
    private Context ctx;
    private Pptx pptx;
    private SlideView view;
    private LinearLayout bar;
    private TextView counter;
    private int idx;
    private boolean dirty;
    private Pptx.Slide slide;

    public PptxDocView(Host h) { host = h; }

    @Override public void load(File f) throws Exception { pptx = Pptx.open(f); }

    @Override public View createView(Context c) throws Exception {
        ctx = c;
        view = new SlideView(c);
        view.setListener(this);
        show(0);
        bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setBackgroundColor(Ui.CARD);
        bar.setElevation(Ui.dp(c, 8));
        View prev = Ui.icon(c, R.drawable.ic_back, Ui.TEXT, "<");
        View next = Ui.icon(c, R.drawable.ic_back, Ui.TEXT, ">");
        next.setRotation(180);
        prev.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { onSwipe(-1); } });
        next.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { onSwipe(1); } });
        counter = Ui.text(c, "", 15, Ui.TEXT, true);
        counter.setGravity(Gravity.CENTER);
        counter.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { gotoDialog(); } });
        bar.addView(prev, Ui.lp(c, 52, 48));
        bar.addView(counter, Ui.lp(c, 90, 48));
        bar.addView(next, Ui.lp(c, 52, 48));
        update();
        return view;
    }

    private void show(int i) throws Exception {
        idx = Math.max(0, Math.min(pptx.slideCount() - 1, i));
        slide = pptx.slide(idx);
        view.setSlide(pptx, slide);
        if (counter != null) update();
    }

    private void update() { counter.setText((idx + 1) + " / " + pptx.slideCount()); }

    @Override public void onSwipe(int dir) {
        int n = idx + dir;
        if (n < 0 || n >= pptx.slideCount()) return;
        try { show(n); } catch (Exception e) { host.toast("Could not open slide: " + e.getMessage()); }
    }

    @Override public void onShape(final Pptx.Shape s) {
        final EditText et = new EditText(ctx);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setGravity(Gravity.TOP);
        et.setMinLines(3);
        final String old = pptx.textOf(s);
        et.setText(old);
        et.setSelection(old.length());
        AlertDialog dlg = new AlertDialog.Builder(ctx).setTitle("Edit text").setView(et)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String nt = et.getText().toString();
                        if (!nt.equals(old)) { pptx.setText(s, nt); dirty = true; host.dirtyChanged(); }
                        view.refresh();
                    }
                }).setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { view.refresh(); }
                }).create();
        dlg.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dlg.show();
    }

    private void gotoDialog() {
        final EditText et = new EditText(ctx);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint("1 - " + pptx.slideCount());
        new AlertDialog.Builder(ctx).setTitle("Go to slide").setView(et)
                .setPositiveButton("Go", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try { show(Integer.parseInt(et.getText().toString().trim()) - 1); } catch (Exception ignored) { }
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    @Override public View bottomBar() { return bar; }
    @Override public boolean dirty() { return dirty; }
    @Override public void beforeSave() { }
    @Override public void save(File in, File out) throws Exception { pptx.save(out); }
    @Override public boolean hasFallback() { return false; }
    @Override public void saveFallback(File in, File out) throws Exception { throw new UnsupportedOperationException(); }
    @Override public void onSaved(File newFile) { dirty = false; host.dirtyChanged(); }
    @Override public int position() { return idx; }
    @Override public void restore(int pos) { try { show(pos); } catch (Exception ignored) { } }
    @Override public String[] menu() { return new String[]{"Go to slide..."}; }
    @Override public void onMenu(int i) { gotoDialog(); }
    @Override public void close() { if (view != null) view.shutdown(); }
}
