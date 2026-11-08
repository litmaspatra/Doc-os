package com.litmaspatra.docos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.litmaspatra.docos.ui.DocView;
import com.litmaspatra.docos.ui.DocxDocView;
import com.litmaspatra.docos.ui.PdfDocView;
import com.litmaspatra.docos.ui.PptxDocView;
import com.litmaspatra.docos.ui.Ui;
import com.litmaspatra.docos.ui.XlsxDocView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One screen for every document type: loads a private working copy, hosts the type-specific view, saves. */
public class ViewerActivity extends Activity implements DocView.Host {
    private static final int REQ_SAVE_AS = 7;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private Uri uri;
    private String name;
    private int type;
    private File work, pendingOut;
    private boolean pendingAdopt, finishAfterSave, busy, unsavedToDest, destroyed;
    private DocView doc;
    private FrameLayout content, bottom;
    private TextView titleTv;
    private View saveBtn;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BAR);
        getWindow().setNavigationBarColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(Ui.BAR);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setElevation(Ui.dp(this, 4));
        View back = Ui.icon(this, R.drawable.ic_back, Color.WHITE, "<");
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { onBackPressed(); } });
        bar.addView(back, Ui.lp(this, 52, 56));
        titleTv = Ui.text(this, "", 17, Color.WHITE, true);
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        titleTv.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(titleTv, new LinearLayout.LayoutParams(0, -1, 1f));
        saveBtn = Ui.icon(this, R.drawable.ic_save, Color.WHITE, "Save");
        saveBtn.setVisibility(View.GONE);
        saveBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { save(false); } });
        bar.addView(saveBtn, Ui.lp(this, 48, 56));
        final View more = Ui.icon(this, R.drawable.ic_more, Color.WHITE, ":");
        more.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showMenu(more); } });
        bar.addView(more, Ui.lp(this, 48, 56));
        root.addView(bar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 56)));

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        bottom = new FrameLayout(this);
        root.addView(bottom, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);

        Intent in = getIntent();
        uri = in.getData();
        if (uri == null && in.getParcelableExtra(Intent.EXTRA_STREAM) instanceof Uri) uri = (Uri) in.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null) { fail("No file to open"); return; }
        name = displayName(uri);
        type = Ui.typeOf(name, getContentResolver().getType(uri));
        titleTv.setText(name);
        if (type == 0) { fail("This file type is not supported.\nDoc-os opens PDF, DOCX, XLSX and PPTX files."); return; }
        showLoading();
        loadAsync();
    }

    private String displayName(Uri u) {
        String n = null;
        Cursor c = null;
        try {
            c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) n = c.getString(0);
        } catch (Exception ignored) { } finally { if (c != null) c.close(); }
        if (n == null) n = u.getLastPathSegment();
        return n == null ? "document" : n;
    }

    private void showLoading() {
        content.removeAllViews();
        ProgressBar p = new ProgressBar(this);
        content.addView(p, new FrameLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48), Gravity.CENTER));
    }

    private void fail(String msg) {
        content.removeAllViews();
        bottom.removeAllViews();
        TextView t = Ui.text(this, msg, 16, Ui.SUB, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(this, 32), 0, Ui.dp(this, 32), 0);
        content.addView(t, new FrameLayout.LayoutParams(-1, -1));
    }

    private File newWorkFile() {
        File dir = new File(getCacheDir(), "work");
        dir.mkdirs();
        return new File(dir, Long.toHexString(System.nanoTime()) + Ui.extOf(type));
    }

    private void loadAsync() {
        exec.execute(new Runnable() { @Override public void run() {
            String err = null;
            File f = newWorkFile();
            DocView d = null;
            try {
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) throw new IOException("Cannot open the file");
                try { copy(in, f); } finally { in.close(); }
                d = type == Ui.PDF ? new PdfDocView(ViewerActivity.this) : type == Ui.DOCX ? new DocxDocView(ViewerActivity.this)
                        : type == Ui.XLSX ? new XlsxDocView(ViewerActivity.this) : new PptxDocView(ViewerActivity.this);
                d.load(f);
            } catch (Throwable t) { err = t.getMessage() == null ? t.toString() : t.getMessage(); }
            final String fe = err; final DocView fd = d; final File ff = f;
            ui.post(new Runnable() { @Override public void run() { if (!destroyed) opened(fd, ff, fe); } });
        } });
    }

    private void opened(DocView d, File f, String err) {
        if (err != null || d == null) { fail("Couldn't open this file.\n\n" + (err == null ? "" : err)); return; }
        work = f;
        content.removeAllViews();
        try {
            View v = d.createView(this);
            doc = d;
            content.addView(v, new FrameLayout.LayoutParams(-1, -1));
            View b = d.bottomBar();
            if (b != null) bottom.addView(b, new FrameLayout.LayoutParams(-1, -2));
            d.restore(Recents.posOf(this, uri.toString()));
            Recents.touch(this, uri.toString(), name, type, d.position());
        } catch (Throwable t) {
            try { d.close(); } catch (Throwable ignored) { }
            fail("Couldn't open this file.\n\n" + (t.getMessage() == null ? t.toString() : t.getMessage()));
        }
    }

    private static void copy(InputStream in, File to) throws IOException {
        java.io.FileOutputStream os = new java.io.FileOutputStream(to);
        try { byte[] buf = new byte[65536]; int n; while ((n = in.read(buf)) > 0) os.write(buf, 0, n); } finally { os.close(); }
    }

    // ------------------------------------------------------------------ DocView.Host
    @Override public void dirtyChanged() {
        ui.post(new Runnable() { @Override public void run() {
            boolean d = doc != null && doc.dirty();
            saveBtn.setVisibility(d || unsavedToDest ? View.VISIBLE : View.GONE);
            titleTv.setText(d || unsavedToDest ? name + " \u2022" : name);
        } });
    }

    @Override public void toast(final String msg) {
        ui.post(new Runnable() { @Override public void run() { Toast.makeText(ViewerActivity.this, msg, Toast.LENGTH_LONG).show(); } });
    }

    // ------------------------------------------------------------------ menu
    private void showMenu(View anchor) {
        if (doc == null) return;
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        m.add(0, 1, 0, "Save");
        m.add(0, 2, 1, "Save a copy...");
        m.add(0, 3, 2, "Share");
        String[] extra = doc.menu();
        for (int i = 0; i < extra.length; i++) m.add(1, 100 + i, 10 + i, extra[i]);
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem it) {
                int id = it.getItemId();
                if (id == 1) save(false);
                else if (id == 2) save(true);
                else if (id == 3) share();
                else if (id >= 100) doc.onMenu(id - 100);
                return true;
            }
        });
        pm.show();
    }

    private void share() {
        if (doc.dirty() || unsavedToDest) { toast("Save your changes first"); return; }
        Intent s = new Intent(Intent.ACTION_SEND);
        s.setType(Ui.mimeOf(type));
        s.putExtra(Intent.EXTRA_STREAM, uri);
        s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(s, "Share"));
    }

    // ------------------------------------------------------------------ saving
    private void save(final boolean asCopy) {
        if (doc == null || busy) return;
        if (!asCopy && !doc.dirty() && !unsavedToDest) { toast("Nothing to save"); return; }
        busy = true;
        toast("Saving...");
        doc.beforeSave();
        final File in = work, out = newWorkFile();
        final DocView d = doc;
        exec.execute(new Runnable() { @Override public void run() {
            Throwable err = null;
            try { d.save(in, out); } catch (Throwable t) { err = t; }
            final Throwable fe = err;
            ui.post(new Runnable() { @Override public void run() { afterSave(fe, out, asCopy); } });
        } });
    }

    private void afterSave(Throwable err, final File out, boolean asCopy) {
        if (destroyed) return;
        if (err != null) {
            busy = false;
            final String msg = err.getMessage() == null ? err.toString() : err.getMessage();
            if (doc.hasFallback()) {
                new AlertDialog.Builder(this).setTitle("Can't save into this PDF")
                        .setMessage(msg + "\n\nSave a flattened copy instead? Annotations become part of the page image.")
                        .setPositiveButton("Save copy", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) { saveFlattened(); } })
                        .setNegativeButton("Cancel", null).show();
            } else toast("Save failed: " + msg);
            finishAfterSave = false;
            return;
        }
        doc.onSaved(out);
        work = out;
        if (asCopy) { askDestination(out, true); return; }
        commit(out, uri, false);
    }

    private void saveFlattened() {
        if (busy) return;
        busy = true;
        toast("Creating flattened copy...");
        doc.beforeSave();
        final File in = work, out = newWorkFile();
        final DocView d = doc;
        exec.execute(new Runnable() { @Override public void run() {
            Throwable err = null;
            try { d.saveFallback(in, out); } catch (Throwable t) { err = t; }
            final Throwable fe = err;
            ui.post(new Runnable() { @Override public void run() {
                busy = false;
                if (fe != null) toast("Could not create the copy: " + fe.getMessage());
                else askDestination(out, false);
            } });
        } });
    }

    private void askDestination(File out, boolean adopt) {
        busy = false;
        pendingOut = out; pendingAdopt = adopt;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(Ui.mimeOf(type));
        String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        i.putExtra(Intent.EXTRA_TITLE, base + (adopt ? " copy" : " flattened") + Ui.extOf(type));
        try { startActivityForResult(i, REQ_SAVE_AS); } catch (Exception e) { toast("No file picker available"); }
    }

    private void commit(final File out, final Uri dest, final boolean adopt) {
        exec.execute(new Runnable() { @Override public void run() {
            String err = null;
            try {
                OutputStream os = getContentResolver().openOutputStream(dest, "wt");
                if (os == null) throw new IOException("cannot write");
                try {
                    InputStream in = new FileInputStream(out);
                    try { byte[] buf = new byte[65536]; int n; while ((n = in.read(buf)) > 0) os.write(buf, 0, n); } finally { in.close(); }
                } finally { os.close(); }
            } catch (Throwable t) { err = t.getMessage() == null ? "cannot write" : t.getMessage(); }
            final String fe = err;
            ui.post(new Runnable() { @Override public void run() {
                busy = false;
                if (destroyed) return;
                if (fe == null) {
                    if (adopt) { uri = dest; name = displayName(dest); }
                    unsavedToDest = false;
                    toast("Saved");
                    dirtyChanged();
                    if (finishAfterSave) { finishAfterSave = false; finish(); }
                } else {
                    unsavedToDest = true;
                    dirtyChanged();
                    new AlertDialog.Builder(ViewerActivity.this).setTitle("Can't write to the original file")
                            .setMessage("It may be in a read-only location. Save a copy somewhere else?")
                            .setPositiveButton("Save a copy", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) { askDestination(out, true); } })
                            .setNegativeButton("Cancel", null).show();
                }
            } });
        } });
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req != REQ_SAVE_AS) return;
        if (res != RESULT_OK || data == null || data.getData() == null || pendingOut == null) { finishAfterSave = false; return; }
        busy = true;
        commit(pendingOut, data.getData(), pendingAdopt);
    }

    // ------------------------------------------------------------------ lifecycle
    @Override public void onBackPressed() {
        if (doc != null && (doc.dirty() || unsavedToDest)) {
            new AlertDialog.Builder(this).setTitle("Save changes?").setMessage(name)
                    .setPositiveButton("Save", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) { finishAfterSave = true; save(false); } })
                    .setNegativeButton("Discard", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) { unsavedToDest = false; finish(); } })
                    .setNeutralButton("Cancel", null).show();
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onPause() {
        super.onPause();
        if (doc != null && uri != null) Recents.touch(this, uri.toString(), name, type, doc.position());
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (doc != null) try { doc.close(); } catch (Throwable ignored) { }
        exec.shutdown();
        super.onDestroy();
    }
}
