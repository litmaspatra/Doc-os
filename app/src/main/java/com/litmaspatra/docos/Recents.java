package com.litmaspatra.docos;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Recently opened files, kept in SharedPreferences (name, uri, type, time, last position). */
public final class Recents {
    private Recents() { }

    public static final class Item { public String uri, name; public int type, pos; public long ts; }

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("recents", Context.MODE_PRIVATE); }

    public static List<Item> load(Context c) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("l", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item it = new Item();
                it.uri = o.getString("u"); it.name = o.getString("n"); it.type = o.getInt("t"); it.ts = o.getLong("s"); it.pos = o.optInt("p", 0);
                out.add(it);
            }
        } catch (JSONException ignored) { }
        return out;
    }

    private static void save(Context c, List<Item> l) {
        JSONArray a = new JSONArray();
        try {
            for (Item it : l) {
                JSONObject o = new JSONObject();
                o.put("u", it.uri); o.put("n", it.name); o.put("t", it.type); o.put("s", it.ts); o.put("p", it.pos);
                a.put(o);
            }
        } catch (JSONException ignored) { }
        sp(c).edit().putString("l", a.toString()).apply();
    }

    public static int posOf(Context c, String uri) {
        for (Item it : load(c)) if (it.uri.equals(uri)) return it.pos;
        return 0;
    }

    public static void touch(Context c, String uri, String name, int type, int pos) {
        List<Item> l = load(c);
        for (int i = 0; i < l.size(); i++) if (l.get(i).uri.equals(uri)) l.remove(i--);
        Item it = new Item();
        it.uri = uri; it.name = name; it.type = type; it.pos = pos; it.ts = System.currentTimeMillis();
        l.add(0, it);
        while (l.size() > 40) l.remove(l.size() - 1);
        save(c, l);
    }

    public static void remove(Context c, String uri) {
        List<Item> l = load(c);
        for (int i = 0; i < l.size(); i++) if (l.get(i).uri.equals(uri)) l.remove(i--);
        save(c, l);
    }
}
