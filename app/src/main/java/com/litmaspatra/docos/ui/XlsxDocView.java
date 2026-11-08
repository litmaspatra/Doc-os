package com.litmaspatra.docos.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.litmaspatra.docos.ooxml.Xlsx;

import java.io.File;

/** Excel workbook: grid with a formula bar, sheet tabs and cell editing. */
public final class XlsxDocView implements DocView, SheetView.Listener {
    private final Host host;
    private Context ctx;
    private Xlsx xlsx;
    private SheetView grid;
    private TextView formula;
    private HorizontalScrollView tabs;
    private LinearLayout tabRow;
    private Xlsx.Sheet current;
    private boolean dirty;

    public XlsxDocView(Host h) { host = h; }

    @Override public void load(File f) throws Exception { xlsx = Xlsx.open(f); current = xlsx.sheets.get(0); }

    @Override public View createView(Context c) {
        ctx = c;
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        formula = Ui.text(c, "Tap a cell", 14, Ui.TEXT, false);
        formula.setSingleLine(true);
        formula.setEllipsize(TextUtils.TruncateAt.END);
        formula.setBackgroundColor(0xFFFFFFFF);
        formula.setPadding(Ui.dp(c, 12), Ui.dp(c, 9), Ui.dp(c, 12), Ui.dp(c, 9));
        formula.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (grid.selRow() >= 0) onEdit(grid.selRow(), grid.selCol());
        } });
        root.addView(formula, new LinearLayout.LayoutParams(-1, -2));
        View sep = new View(c);
        sep.setBackgroundColor(0xFFD9DCE1);
        root.addView(sep, new LinearLayout.LayoutParams(-1, 1));
        grid = new SheetView(c);
        grid.setListener(this);
        grid.setSheet(current);
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));
        buildTabs(c);
        return root;
    }

    private void buildTabs(Context c) {
        tabRow = new LinearLayout(c);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < xlsx.sheets.size(); i++) {
            final int idx = i;
            TextView t = Ui.text(c, xlsx.sheets.get(i).name, 14, Ui.TEXT, true);
            t.setPadding(Ui.dp(c, 16), Ui.dp(c, 12), Ui.dp(c, 16), Ui.dp(c, 12));
            t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { switchTo(idx); } });
            tabRow.addView(t);
        }
        tabs = new HorizontalScrollView(c);
        tabs.setHorizontalScrollBarEnabled(false);
        tabs.setBackgroundColor(0xFFF1F3F5);
        tabs.addView(tabRow);
        paintTabs();
        if (xlsx.sheets.size() < 2) tabs.setVisibility(View.GONE);
    }

    private void paintTabs() {
        for (int i = 0; i < tabRow.getChildCount(); i++) {
            TextView t = (TextView) tabRow.getChildAt(i);
            boolean on = xlsx.sheets.get(i) == current;
            t.setTextColor(on ? Ui.ACCENT : Ui.SUB);
            t.setBackgroundColor(on ? Color.WHITE : 0);
        }
    }

    private void switchTo(final int i) {
        final Xlsx.Sheet s = xlsx.sheets.get(i);
        if (s == current) return;
        if (s.loaded) { current = s; grid.setSheet(s); formula.setText("Tap a cell"); paintTabs(); return; }
        host.toast("Loading " + s.name + "...");
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                try { xlsx.load(s); } catch (Exception e) { err = e.getMessage(); }
                final String fe = err;
                grid.post(new Runnable() {
                    @Override public void run() {
                        if (fe != null) { host.toast("Could not load sheet: " + fe); return; }
                        current = s; grid.setSheet(s); formula.setText("Tap a cell"); paintTabs();
                        if (s.truncated) host.toast("Large sheet: showing the first " + Xlsx.MAX_CELLS + " cells");
                    }
                });
            }
        }, "docos-sheet").start();
    }

    @Override public void onSelect(int r, int c) {
        Xlsx.Cell cell = current.get(r, c);
        formula.setText(Xlsx.ref(r, c) + "   " + (cell == null ? "" : cell.disp));
    }

    @Override public void onEdit(final int r, final int c) {
        Xlsx.Cell cell = current.get(r, c);
        final EditText et = new EditText(ctx);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setText(Xlsx.editText(cell));
        et.setSelection(et.getText().length());
        AlertDialog dlg = new AlertDialog.Builder(ctx).setTitle("Cell " + Xlsx.ref(r, c)).setView(et)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) { set(r, c, et.getText().toString()); } })
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Clear", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) { set(r, c, ""); } })
                .create();
        dlg.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dlg.show();
    }

    private void set(int r, int c, String text) {
        xlsx.setCell(current, r, c, text);
        dirty = true;
        grid.invalidate();
        onSelect(r, c);
        host.dirtyChanged();
    }

    @Override public View bottomBar() { return tabs; }
    @Override public boolean dirty() { return dirty; }
    @Override public void beforeSave() { }
    @Override public void save(File in, File out) throws Exception { xlsx.save(out); }
    @Override public boolean hasFallback() { return false; }
    @Override public void saveFallback(File in, File out) throws Exception { throw new UnsupportedOperationException(); }
    @Override public void onSaved(File newFile) { dirty = false; host.dirtyChanged(); }
    @Override public int position() { return 0; }
    @Override public void restore(int pos) { }
    @Override public String[] menu() { return new String[0]; }
    @Override public void onMenu(int i) { }
    @Override public void close() { }
}
