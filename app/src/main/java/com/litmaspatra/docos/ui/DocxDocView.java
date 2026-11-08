package com.litmaspatra.docos.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.SubscriptSpan;
import android.text.style.SuperscriptSpan;
import android.text.style.UnderlineSpan;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.litmaspatra.docos.ooxml.Docx;

import org.w3c.dom.Element;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Word document: scrolling list of paragraphs/table rows; tap a paragraph to edit it. */
public final class DocxDocView implements DocView {
    private final Host host;
    private Context ctx;
    private Docx docx;
    private List<Docx.Block> blocks;
    private ListView list;
    private float scale = 1f;
    private boolean dirty;
    private final ExecutorService imgExec = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> imgCache = new LruCache<String, Bitmap>(Math.max(4 << 20, (int) Math.min(24L << 20, Runtime.getRuntime().maxMemory() / 10))) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    public DocxDocView(Host h) { host = h; }

    @Override public void load(File f) throws Exception { docx = Docx.open(f); blocks = docx.blocks(); }

    @Override public View createView(Context c) {
        ctx = c;
        list = new ListView(c);
        list.setDivider(null);
        list.setBackgroundColor(Color.WHITE);
        list.setFastScrollEnabled(true);
        list.setSelector(android.R.color.transparent);
        list.setPadding(Ui.dp(c, 4), Ui.dp(c, 8), Ui.dp(c, 4), Ui.dp(c, 80));
        list.setClipToPadding(false);
        list.setAdapter(adapter);
        return list;
    }

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override public int getCount() { return blocks.size(); }
        @Override public Object getItem(int i) { return blocks.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int i) { return blocks.get(i).para != null ? 0 : 1; }

        @Override public View getView(int i, View v, ViewGroup parent) {
            Docx.Block b = blocks.get(i);
            if (b.para != null) {
                LinearLayout box = v instanceof LinearLayout ? (LinearLayout) v : null;
                if (box == null) {
                    box = new LinearLayout(ctx);
                    box.setOrientation(LinearLayout.VERTICAL);
                    box.addView(new TextView(ctx), new LinearLayout.LayoutParams(-1, -2));
                }
                while (box.getChildCount() > 1) box.removeViewAt(1);
                bindPara(box, (TextView) box.getChildAt(0), b.para, false);
                return box;
            }
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8), 0);
            for (Docx.Cell cell : b.cells) {
                LinearLayout cv = new LinearLayout(ctx);
                cv.setOrientation(LinearLayout.VERTICAL);
                GradientDrawable g = new GradientDrawable();
                g.setColor(Color.WHITE);
                g.setStroke(Ui.dp(ctx, 1), 0xFFBDBDBD);
                cv.setBackground(g);
                cv.setPadding(Ui.dp(ctx, 2), Ui.dp(ctx, 2), Ui.dp(ctx, 2), Ui.dp(ctx, 2));
                for (Docx.Para p : cell.paras) {
                    LinearLayout pb = new LinearLayout(ctx);
                    pb.setOrientation(LinearLayout.VERTICAL);
                    TextView tv = new TextView(ctx);
                    pb.addView(tv, new LinearLayout.LayoutParams(-1, -2));
                    bindPara(pb, tv, p, true);
                    cv.addView(pb, new LinearLayout.LayoutParams(-1, -2));
                }
                row.addView(cv, new LinearLayout.LayoutParams(0, -1, Math.max(1, cell.weight)));
            }
            return row;
        }
    };

    private static final int[] HEAD_PT = {20, 16, 14, 13, 12, 12};

    private static int highlight(String n) {
        switch (n) {
            case "yellow": return 0xFFFFFF00; case "green": return 0xFF00FF00; case "cyan": return 0xFF00FFFF;
            case "magenta": return 0xFFFF00FF; case "blue": return 0xFF9999FF; case "red": return 0xFFFF9999;
            case "lightGray": return 0xFFD3D3D3; default: return 0xFFFFFF99;
        }
    }

    private void bindPara(LinearLayout box, TextView tv, final Docx.Para p, boolean inCell) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(p.prefix);
        for (Docx.Run r : p.runs) {
            int s = sb.length();
            sb.append(r.text);
            int e = sb.length();
            if (e == s) continue;
            float pt = r.sz > 0 ? r.sz / 2f : (p.heading > 0 ? HEAD_PT[Math.min(5, p.heading - 1)] : 11f);
            sb.setSpan(new AbsoluteSizeSpan(Math.round(pt * 1.3f * scale * (inCell ? 0.9f : 1f)), true), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            boolean bold = r.b || (p.heading > 0 && r.sz == 0);
            if (bold || r.i) sb.setSpan(new StyleSpan(bold && r.i ? Typeface.BOLD_ITALIC : bold ? Typeface.BOLD : Typeface.ITALIC), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (r.u) sb.setSpan(new UnderlineSpan(), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (r.s) sb.setSpan(new StrikethroughSpan(), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (r.color != null) sb.setSpan(new ForegroundColorSpan(0xFF000000 | Integer.parseInt(r.color, 16)), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (r.hl != null) sb.setSpan(new BackgroundColorSpan(highlight(r.hl)), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (r.vert == 1) sb.setSpan(new SuperscriptSpan(), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            else if (r.vert == 2) sb.setSpan(new SubscriptSpan(), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        tv.setText(sb);
        tv.setTextColor(0xFF1A1A1A);
        tv.setTextSize(14.3f * scale);
        tv.setLineSpacing(Ui.dp(ctx, 2), 1f);
        tv.setGravity(p.align == 1 ? Gravity.CENTER_HORIZONTAL : p.align == 2 ? Gravity.END : Gravity.START);
        float k = ctx.getResources().getDisplayMetrics().density * 1.3f / 20f;
        int left = Ui.dp(ctx, inCell ? 4 : 12) + Math.round(Math.max(0, p.left + Math.min(0, p.first)) * k);
        tv.setPadding(left, Math.round(Math.min(p.before, 480) * k) + Ui.dp(ctx, 1), Ui.dp(ctx, inCell ? 4 : 12), Math.round(Math.min(p.after, 480) * k) + Ui.dp(ctx, 1));
        tv.setMinHeight(sb.length() == 0 && p.images.isEmpty() ? Ui.dp(ctx, 22) : 0);
        final Element el = p.el;
        tv.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { editPara(el); } });
        for (Docx.Image im : p.images) addImage(box, im);
    }

    private void addImage(LinearLayout box, Docx.Image im) {
        float dm = ctx.getResources().getDisplayMetrics().density;
        int maxW = ctx.getResources().getDisplayMetrics().widthPixels - Ui.dp(ctx, 32);
        float wpx = im.cx > 0 ? im.cx / 12700f * 1.3f * dm : maxW / 2f;
        float hpx = im.cy > 0 ? im.cy / 12700f * 1.3f * dm : wpx * 0.6f;
        if (wpx > maxW) { hpx = hpx * maxW / wpx; wpx = maxW; }
        final ImageView iv = new ImageView(ctx);
        iv.setBackgroundColor(0xFFEEEEEE);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(wpx), Math.round(Math.max(hpx, Ui.dp(ctx, 20))));
        lp.leftMargin = Ui.dp(ctx, 12);
        box.addView(iv, lp);
        final String rid = im.rid;
        final int target = Math.round(wpx);
        Bitmap cached = imgCache.get(rid);
        iv.setTag(rid);
        if (cached != null) { iv.setImageBitmap(cached); iv.setBackground(null); return; }
        imgExec.execute(new Runnable() {
            @Override public void run() {
                byte[] data = docx.image(rid);
                if (data == null) return;
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, o);
                int ss = 1;
                while (o.outWidth / (ss * 2) >= Math.max(64, target)) ss *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = ss;
                final Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                if (b == null) return;
                iv.post(new Runnable() { @Override public void run() {
                    imgCache.put(rid, b);
                    if (rid.equals(iv.getTag())) { iv.setImageBitmap(b); iv.setBackground(null); }
                } });
            }
        });
    }

    private void refresh() {
        blocks = docx.blocks();
        adapter.notifyDataSetChanged();
        dirty = docx.modified;
        host.dirtyChanged();
    }

    private void editPara(final Element el) {
        final EditText et = new EditText(ctx);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setGravity(Gravity.TOP);
        et.setMinLines(3);
        final String old = docx.getText(el);
        et.setText(old);
        et.setSelection(old.length());
        final boolean[] f0 = docx.formatOf(el);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(ctx, 16);
        box.setPadding(pad, Ui.dp(ctx, 8), pad, 0);
        box.addView(et, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout fr = new LinearLayout(ctx);
        final CheckBox cb = new CheckBox(ctx), ci = new CheckBox(ctx), cu = new CheckBox(ctx);
        cb.setText("Bold"); ci.setText("Italic"); cu.setText("Underline");
        cb.setChecked(f0[0]); ci.setChecked(f0[1]); cu.setChecked(f0[2]);
        fr.addView(cb); fr.addView(ci); fr.addView(cu);
        box.addView(fr);
        final Runnable apply = new Runnable() { @Override public void run() {
            String nt = et.getText().toString();
            if (!nt.equals(old)) docx.setText(el, nt);
            if (cb.isChecked() != f0[0] || ci.isChecked() != f0[1] || cu.isChecked() != f0[2]) docx.setFormat(el, cb.isChecked(), ci.isChecked(), cu.isChecked());
        } };
        AlertDialog dlg = new AlertDialog.Builder(ctx).setTitle("Edit paragraph").setView(box)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { apply.run(); refresh(); }
                })
                .setNegativeButton("Cancel", null)
                .setNeutralButton("More...", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        apply.run();
                        new AlertDialog.Builder(ctx).setItems(new String[]{"Insert paragraph below", "Delete paragraph"}, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dd, int which) {
                                if (which == 0) { Element np = docx.insertAfter(el); refresh(); editPara(np); }
                                else { docx.delete(el); refresh(); }
                            }
                        }).setOnCancelListener(new DialogInterface.OnCancelListener() { @Override public void onCancel(DialogInterface dd) { refresh(); } }).show();
                    }
                }).create();
        dlg.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dlg.show();
    }

    @Override public View bottomBar() { return null; }
    @Override public boolean dirty() { return docx != null && docx.modified && dirty; }
    @Override public void beforeSave() { }
    @Override public void save(File in, File out) throws Exception { docx.save(out); }
    @Override public boolean hasFallback() { return false; }
    @Override public void saveFallback(File in, File out) throws Exception { throw new UnsupportedOperationException(); }
    @Override public void onSaved(File newFile) { dirty = false; docx.modified = false; host.dirtyChanged(); }
    @Override public int position() { return list == null ? 0 : list.getFirstVisiblePosition(); }
    @Override public void restore(int pos) { if (list != null && pos > 0) list.setSelection(pos); }
    @Override public String[] menu() { return new String[]{"Larger text", "Smaller text"}; }
    @Override public void onMenu(int i) {
        scale = Math.max(0.7f, Math.min(2.2f, scale + (i == 0 ? 0.15f : -0.15f)));
        adapter.notifyDataSetChanged();
    }
    @Override public void close() { imgExec.shutdownNow(); }
}
