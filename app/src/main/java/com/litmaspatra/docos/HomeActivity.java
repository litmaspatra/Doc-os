package com.litmaspatra.docos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.litmaspatra.docos.ui.Ui;

import java.io.File;
import java.util.List;

public class HomeActivity extends Activity {
    private static final int REQ_OPEN = 1;
    private List<Recents.Item> items;
    private BaseAdapter adapter;
    private TextView empty;

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
        TextView title = Ui.text(this, "Doc-os", 21, Color.WHITE, true);
        title.setPadding(Ui.dp(this, 18), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -1, 1f));
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(bar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 56)));

        FrameLayout body = new FrameLayout(this);
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 90));
        list.setClipToPadding(false);
        body.addView(list, new FrameLayout.LayoutParams(-1, -1));

        empty = Ui.text(this, "No recent files\n\nTap + to open a PDF, Word, Excel or PowerPoint file", 16, Ui.SUB, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(Ui.dp(this, 32), 0, Ui.dp(this, 32), Ui.dp(this, 60));
        body.addView(empty, new FrameLayout.LayoutParams(-1, -1));

        View fab = Ui.icon(this, R.drawable.ic_add, Color.WHITE, "+");
        fab.setBackground(Ui.round(Ui.ACCENT, 30, this));
        fab.setElevation(Ui.dp(this, 6));
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(Ui.dp(this, 60), Ui.dp(this, 60), Gravity.BOTTOM | Gravity.END);
        fp.setMargins(0, 0, Ui.dp(this, 20), Ui.dp(this, 20));
        body.addView(fab, fp);
        fab.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pick(); } });
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        adapter = new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int i) { return items.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup p) {
                Recents.Item it = items.get(i);
                LinearLayout card = new LinearLayout(HomeActivity.this);
                card.setGravity(Gravity.CENTER_VERTICAL);
                int pad = Ui.dp(HomeActivity.this, 12);
                card.setPadding(pad, pad, pad, pad);
                Ui.ripple(card, Ui.CARD, 12);
                TextView badge = Ui.text(HomeActivity.this, Ui.label(it.type), 12, Color.WHITE, true);
                badge.setGravity(Gravity.CENTER);
                badge.setBackground(Ui.round(Ui.colorOf(it.type), 10, HomeActivity.this));
                card.addView(badge, Ui.lp(HomeActivity.this, 46, 46));
                LinearLayout col = new LinearLayout(HomeActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(Ui.dp(HomeActivity.this, 14), 0, 0, 0);
                TextView name = Ui.text(HomeActivity.this, it.name, 16, Ui.TEXT, true);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                TextView sub = Ui.text(HomeActivity.this, DateUtils.getRelativeTimeSpanString(it.ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(), 13, Ui.SUB, false);
                col.addView(name);
                col.addView(sub);
                card.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
                FrameLayout wrap = new FrameLayout(HomeActivity.this);
                wrap.setPadding(0, 0, 0, Ui.dp(HomeActivity.this, 8));
                wrap.addView(card);
                return wrap;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> a, View v, int i, long id) { open(Uri.parse(items.get(i).uri)); }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> a, View v, final int i, long id) {
                new AlertDialog.Builder(HomeActivity.this).setTitle(items.get(i).name)
                        .setItems(new String[]{"Remove from list"}, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) { Recents.remove(HomeActivity.this, items.get(i).uri); reload(); }
                        }).show();
                return true;
            }
        });

        // clear old working copies
        new Thread(new Runnable() { @Override public void run() {
            File[] fs = new File(getCacheDir(), "work").listFiles();
            if (fs != null) for (File f : fs) if (System.currentTimeMillis() - f.lastModified() > 6L * 3600 * 1000) f.delete();
        } }).start();
    }

    @Override protected void onResume() { super.onResume(); reload(); }

    private void reload() {
        items = Recents.load(this);
        adapter.notifyDataSetChanged();
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{Ui.mimeOf(Ui.PDF), Ui.mimeOf(Ui.DOCX), Ui.mimeOf(Ui.XLSX), Ui.mimeOf(Ui.PPTX)});
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_OPEN);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req != REQ_OPEN || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); }
        catch (Exception e) {
            try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
        }
        open(u);
    }

    private void open(Uri u) {
        Intent i = new Intent(this, ViewerActivity.class);
        i.setData(u);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivity(i);
    }
}
