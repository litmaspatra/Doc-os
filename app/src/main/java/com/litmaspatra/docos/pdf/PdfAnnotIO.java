package com.litmaspatra.docos.pdf;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.Deflater;

import com.litmaspatra.docos.pdf.PdfDoc.Name;
import com.litmaspatra.docos.pdf.PdfDoc.Ref;
import com.litmaspatra.docos.pdf.PdfDoc.Str;

/** Reads existing annotations and writes new ones (with appearance streams) as an incremental update. */
public final class PdfAnnotIO {
    private PdfAnnotIO() { }

    public static final class Delta {
        public final List<PdfAnnot> added = new ArrayList<>();
        public final Set<Integer> removed = new HashSet<>();
    }

    // ------------------------------------------------------------------ geometry
    public static final class Geom {
        public final float x0, y0, cw, ch;
        public final int rot;
        Geom(float x0, float y0, float cw, float ch, int rot) { this.x0 = x0; this.y0 = y0; this.cw = cw; this.ch = ch; this.rot = rot; }

        public float dispW() { return rot % 180 == 0 ? cw : ch; }
        public float dispH() { return rot % 180 == 0 ? ch : cw; }

        public float[] toPdf(float u, float v) {
            switch (rot) {
                case 90: return new float[]{x0 + v, y0 + u};
                case 180: return new float[]{x0 + cw - u, y0 + v};
                case 270: return new float[]{x0 + cw - v, y0 + ch - u};
                default: return new float[]{x0 + u, y0 + ch - v};
            }
        }

        public float[] toDisp(float x, float y) {
            float px = x - x0, py = y - y0;
            switch (rot) {
                case 90: return new float[]{py, px};
                case 180: return new float[]{cw - px, py};
                case 270: return new float[]{ch - py, cw - px};
                default: return new float[]{px, ch - py};
            }
        }
    }

    private static float[] box(PdfDoc d, Object o) throws IOException {
        o = d.res(o);
        if (!(o instanceof List) || ((List<?>) o).size() < 4) return null;
        float[] r = new float[4];
        for (int i = 0; i < 4; i++) {
            Object v = d.res(((List<?>) o).get(i));
            if (!(v instanceof Number)) return null;
            r[i] = ((Number) v).floatValue();
        }
        return new float[]{Math.min(r[0], r[2]), Math.min(r[1], r[3]), Math.max(r[0], r[2]), Math.max(r[1], r[3])};
    }

    public static Geom geom(PdfDoc d, Map<String, Object> page) throws IOException {
        float[] m = box(d, d.inherited(page, "MediaBox"));
        if (m == null) m = new float[]{0, 0, 612, 792};
        float[] c = box(d, d.inherited(page, "CropBox"));
        float[] r = m;
        if (c != null) {
            r = new float[]{Math.max(m[0], c[0]), Math.max(m[1], c[1]), Math.min(m[2], c[2]), Math.min(m[3], c[3])};
            if (r[2] <= r[0] || r[3] <= r[1]) r = m;
        }
        Object ro = d.inherited(page, "Rotate");
        int rot = ro instanceof Number ? ((Number) ro).intValue() : 0;
        rot = ((rot % 360) + 360) % 360;
        rot = (rot / 90) * 90;
        return new Geom(r[0], r[1], r[2] - r[0], r[3] - r[1], rot);
    }

    // ------------------------------------------------------------------ reading
    private static int colorOf(PdfDoc d, Object o, int def) throws IOException {
        o = d.res(o);
        if (!(o instanceof List)) return def;
        List<?> l = (List<?>) o;
        float[] v = new float[l.size()];
        for (int i = 0; i < v.length; i++) { Object x = d.res(l.get(i)); v[i] = x instanceof Number ? ((Number) x).floatValue() : 0; }
        int r, g, b;
        if (v.length == 1) { r = g = b = to255(v[0]); }
        else if (v.length == 3) { r = to255(v[0]); g = to255(v[1]); b = to255(v[2]); }
        else if (v.length == 4) { r = to255((1 - v[0]) * (1 - v[3])); g = to255((1 - v[1]) * (1 - v[3])); b = to255((1 - v[2]) * (1 - v[3])); }
        else return def;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int to255(float f) { return Math.max(0, Math.min(255, Math.round(f * 255f))); }

    private static float num(PdfDoc d, Object o, float def) throws IOException {
        o = d.res(o);
        return o instanceof Number ? ((Number) o).floatValue() : def;
    }

    private static float[] nums(PdfDoc d, Object o) throws IOException {
        o = d.res(o);
        if (!(o instanceof List)) return null;
        List<?> l = (List<?>) o;
        float[] r = new float[l.size()];
        for (int i = 0; i < r.length; i++) r[i] = num(d, l.get(i), 0);
        return r;
    }

    public static Map<Integer, List<PdfAnnot>> loadAll(PdfDoc d) throws IOException {
        Map<Integer, List<PdfAnnot>> out = new TreeMap<>();
        int n = d.pageCount();
        for (int i = 0; i < n; i++) {
            List<PdfAnnot> l = loadPage(d, i);
            if (!l.isEmpty()) out.put(i, l);
        }
        return out;
    }

    public static List<PdfAnnot> loadPage(PdfDoc d, int pi) throws IOException {
        List<PdfAnnot> out = new ArrayList<>();
        Map<String, Object> page = d.pageDict(pi);
        if (page == null) return out;
        Object arr = d.res(page.get("Annots"));
        if (!(arr instanceof List)) return out;
        Geom g = geom(d, page);
        for (Object item : (List<?>) arr) {
            try {
                Map<String, Object> a = d.dictOf(item);
                if (a == null) continue;
                Object st = d.res(a.get("Subtype"));
                if (!(st instanceof Name)) continue;
                int flags = (int) num(d, a.get("F"), 0);
                if ((flags & 2) != 0) continue; // hidden
                PdfAnnot p = convert(d, g, a, ((Name) st).v);
                if (p == null) continue;
                p.page = pi;
                p.objNum = item instanceof Ref ? ((Ref) item).num : -2;
                p.gen = item instanceof Ref ? ((Ref) item).gen : 0;
                out.add(p);
            } catch (RuntimeException | IOException ignored) { /* skip unreadable annotation */ }
        }
        return out;
    }

    private static float[] disp(Geom g, float x, float y) { return g.toDisp(x, y); }

    private static PdfAnnot convert(PdfDoc d, Geom g, Map<String, Object> a, String st) throws IOException {
        PdfAnnot p = new PdfAnnot();
        float ca = num(d, a.get("CA"), 1f);
        if (st.equals("Ink")) {
            Object il = d.res(a.get("InkList"));
            if (!(il instanceof List)) return null;
            List<float[]> strokes = new ArrayList<>();
            for (Object s : (List<?>) il) {
                float[] v = nums(d, s);
                if (v == null || v.length < 2) continue;
                float[] pts = new float[v.length / 2 * 2];
                for (int i = 0; i + 1 < v.length; i += 2) { float[] q = disp(g, v[i], v[i + 1]); pts[i] = q[0]; pts[i + 1] = q[1]; }
                strokes.add(pts);
            }
            if (strokes.isEmpty()) return null;
            p.kind = PdfAnnot.INK;
            p.strokes = strokes.toArray(new float[0][]);
            p.color = colorOf(d, a.get("C"), 0xFF000000);
            p.alpha = ca;
            p.lw = borderWidth(d, a);
            p.flat = ca < 0.95f;
            return p;
        }
        if (st.equals("Highlight") || st.equals("Underline") || st.equals("StrikeOut")) {
            float[] q = nums(d, a.get("QuadPoints"));
            if (q == null || q.length < 8) return null;
            List<Float> rects = new ArrayList<>();
            List<float[]> lines = new ArrayList<>();
            for (int i = 0; i + 7 < q.length; i += 8) {
                float l = 1e9f, t = 1e9f, r = -1e9f, b = -1e9f;
                for (int k = 0; k < 4; k++) {
                    float[] pt = disp(g, q[i + k * 2], q[i + k * 2 + 1]);
                    l = Math.min(l, pt[0]); r = Math.max(r, pt[0]); t = Math.min(t, pt[1]); b = Math.max(b, pt[1]);
                }
                if (st.equals("Highlight")) { rects.add(l); rects.add(t); rects.add(r); rects.add(b); }
                else { float y = st.equals("Underline") ? b - 0.5f : (t + b) / 2; lines.add(new float[]{l, y, r, y}); }
            }
            p.alpha = ca < 0.95f ? ca : 0.4f;
            if (st.equals("Highlight")) {
                p.kind = PdfAnnot.RECTS;
                p.rects = new float[rects.size()];
                for (int i = 0; i < p.rects.length; i++) p.rects[i] = rects.get(i);
                p.color = colorOf(d, a.get("C"), 0xFFFFFF00);
            } else {
                p.kind = PdfAnnot.INK;
                p.strokes = lines.toArray(new float[0][]);
                p.color = colorOf(d, a.get("C"), 0xFF000000);
                p.alpha = ca;
                p.lw = 1f;
            }
            return p;
        }
        if (st.equals("Square") || st.equals("Line")) {
            p.kind = PdfAnnot.INK;
            p.color = colorOf(d, a.get("C"), 0xFF000000);
            p.alpha = ca;
            p.lw = borderWidth(d, a);
            if (st.equals("Line")) {
                float[] l = nums(d, a.get("L"));
                if (l == null || l.length < 4) return null;
                float[] s = disp(g, l[0], l[1]), e = disp(g, l[2], l[3]);
                p.strokes = new float[][]{{s[0], s[1], e[0], e[1]}};
            } else {
                float[] r = box(d, a.get("Rect"));
                if (r == null) return null;
                float[] s = disp(g, r[0], r[1]), e = disp(g, r[2], r[3]);
                float l = Math.min(s[0], e[0]), rr = Math.max(s[0], e[0]), t = Math.min(s[1], e[1]), b = Math.max(s[1], e[1]);
                p.strokes = new float[][]{{l, t, rr, t, rr, b, l, b, l, t}};
            }
            return p;
        }
        if (st.equals("FreeText")) {
            float[] r = box(d, a.get("Rect"));
            if (r == null) return null;
            float[] s = disp(g, r[0], r[1]), e = disp(g, r[2], r[3]);
            p.kind = PdfAnnot.TEXT;
            p.u = Math.min(s[0], e[0]); p.v = Math.min(s[1], e[1]);
            p.w = Math.abs(e[0] - s[0]); p.h = Math.abs(e[1] - s[1]);
            Object c = d.res(a.get("Contents"));
            p.text = c instanceof Str ? ((Str) c).text() : "";
            Object da = d.res(a.get("DA"));
            p.color = 0xFF000000;
            p.fs = 14f;
            if (da instanceof Str) parseDA(((Str) da).text(), p);
            return p;
        }
        return null;
    }

    private static float borderWidth(PdfDoc d, Map<String, Object> a) throws IOException {
        Map<String, Object> bs = d.dictOf(a.get("BS"));
        if (bs != null && bs.get("W") != null) return num(d, bs.get("W"), 1f);
        float[] b = nums(d, a.get("Border"));
        if (b != null && b.length >= 3) return b[2];
        return 1f;
    }

    private static void parseDA(String da, PdfAnnot p) {
        String[] t = da.trim().split("\\s+");
        for (int i = 0; i < t.length; i++) {
            try {
                if (t[i].equals("rg") && i >= 3) p.color = 0xFF000000 | (to255(Float.parseFloat(t[i - 3])) << 16) | (to255(Float.parseFloat(t[i - 2])) << 8) | to255(Float.parseFloat(t[i - 1]));
                else if (t[i].equals("g") && i >= 1) { int v = to255(Float.parseFloat(t[i - 1])); p.color = 0xFF000000 | (v << 16) | (v << 8) | v; }
                else if (t[i].equals("Tf") && i >= 1) { float f = Float.parseFloat(t[i - 1]); if (f > 1) p.fs = f; }
            } catch (NumberFormatException ignored) { }
        }
    }

    // ------------------------------------------------------------------ writing
    public static void write(final PdfDoc doc, OutputStream out, final Map<Integer, Delta> deltas) throws IOException {
        doc.writeUpdated(out, new PdfDoc.UpdateFn() {
            @Override public void apply(PdfDoc.Update u) throws IOException {
                for (Map.Entry<Integer, Delta> en : deltas.entrySet()) {
                    Delta dl = en.getValue();
                    if (dl.added.isEmpty() && dl.removed.isEmpty()) continue;
                    Ref pr = doc.pageRef(en.getKey());
                    Map<String, Object> page = new LinkedHashMap<>(doc.pageDict(en.getKey()));
                    Geom g = geom(doc, page);
                    List<Object> arr = new ArrayList<>();
                    Object ex = doc.res(page.get("Annots"));
                    if (ex instanceof List) {
                        for (Object o : (List<?>) ex) {
                            if (o instanceof Ref && dl.removed.contains(((Ref) o).num)) continue;
                            arr.add(o);
                        }
                    }
                    for (PdfAnnot a : dl.added) {
                        int num = build(u, a, g, pr);
                        if (num >= 0) arr.add(new Ref(num, 0));
                    }
                    page.put("Annots", arr);
                    u.put(pr.num, pr.gen, page);
                }
            }
        });
    }

    private static List<Object> list(double... v) {
        List<Object> l = new ArrayList<>();
        for (double x : v) l.add(x);
        return l;
    }

    private static String f(double v) { return PdfDoc.fmt(v); }

    private static List<Object> rgb(int c) {
        return list(((c >> 16) & 0xff) / 255.0, ((c >> 8) & 0xff) / 255.0, (c & 0xff) / 255.0);
    }

    private static String nm() {
        return "docos:" + Long.toHexString(System.nanoTime()) + Integer.toHexString((int) (Math.random() * 0xffff));
    }

    private static int build(PdfDoc.Update u, PdfAnnot a, Geom g, Ref page) throws IOException {
        if (a.kind == PdfAnnot.INK) return buildInk(u, a, g, page);
        if (a.kind == PdfAnnot.TEXT) return buildText(u, a, g, page);
        return -1;
    }

    /** A lone tap: butt caps would draw nothing, so these use round caps. */
    private static boolean single(PdfAnnot a) {
        for (float[] s : a.strokes) if (s.length > 2) return false;
        return true;
    }

    private static int buildInk(PdfDoc.Update u, PdfAnnot a, Geom g, Ref page) throws IOException {
        List<Object> inkList = new ArrayList<>();
        StringBuilder cs = new StringBuilder();
        double minX = 1e9, minY = 1e9, maxX = -1e9, maxY = -1e9;
        int c = a.color;
        cs.append("q /GS0 gs ").append(f(((c >> 16) & 0xff) / 255.0)).append(' ').append(f(((c >> 8) & 0xff) / 255.0)).append(' ')
          .append(f((c & 0xff) / 255.0)).append(" RG ").append(f(a.lw)).append(a.flat && !single(a) ? " w 0 J 0 j\n" : " w 1 J 1 j\n");
        for (float[] s : a.strokes) {
            List<Object> pts = new ArrayList<>();
            for (int i = 0; i + 1 < s.length; i += 2) {
                float[] p = g.toPdf(s[i], s[i + 1]);
                pts.add((double) p[0]); pts.add((double) p[1]);
                minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]); minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]);
                cs.append(f(p[0])).append(' ').append(f(p[1])).append(i == 0 ? " m\n" : " l\n");
                if (s.length == 2) cs.append(f(p[0])).append(' ').append(f(p[1])).append(" l\n");
            }
            if (pts.size() >= 2) { inkList.add(pts); cs.append("S\n"); }
        }
        cs.append("Q\n");
        if (inkList.isEmpty()) return -1;
        double pad = a.lw / 2 + 1;
        List<Object> rect = list(minX - pad, minY - pad, maxX + pad, maxY + pad);

        Map<String, Object> gs = new LinkedHashMap<>();
        gs.put("Type", new Name("ExtGState"));
        gs.put("CA", (double) a.alpha);
        gs.put("ca", (double) a.alpha);
        Map<String, Object> egs = new LinkedHashMap<>();
        egs.put("GS0", gs);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ExtGState", egs);
        Map<String, Object> apd = new LinkedHashMap<>();
        apd.put("Type", new Name("XObject"));
        apd.put("Subtype", new Name("Form"));
        apd.put("BBox", rect);
        apd.put("Resources", res);
        int apNum = u.alloc();
        u.putStream(apNum, apd, cs.toString().getBytes("ISO-8859-1"), true);

        Map<String, Object> bs = new LinkedHashMap<>();
        bs.put("W", (double) a.lw);
        Map<String, Object> ap = new LinkedHashMap<>();
        ap.put("N", new Ref(apNum, 0));
        Map<String, Object> an = new LinkedHashMap<>();
        an.put("Type", new Name("Annot"));
        an.put("Subtype", new Name("Ink"));
        an.put("Rect", rect);
        an.put("F", 4L);
        an.put("C", rgb(a.color));
        an.put("CA", (double) a.alpha);
        an.put("BS", bs);
        an.put("InkList", inkList);
        an.put("NM", Str.of(nm()));
        an.put("T", Str.of("Doc-os"));
        an.put("P", page);
        an.put("AP", ap);
        int num = u.alloc();
        u.put(num, 0, an);
        return num;
    }

    private static int buildText(PdfDoc.Update u, PdfAnnot a, Geom g, Ref page) throws IOException {
        if (a.text == null || a.text.isEmpty() || a.px == null || a.pw <= 0 || a.ph <= 0) return -1;
        // rect (bounding box in PDF space)
        float[] p1 = g.toPdf(a.u, a.v), p2 = g.toPdf(a.u + a.w, a.v + a.h);
        float llx = Math.min(p1[0], p2[0]), lly = Math.min(p1[1], p2[1]), urx = Math.max(p1[0], p2[0]), ury = Math.max(p1[1], p2[1]);
        // image transform: unit square (X right, Y up) -> display box -> PDF
        float[] o = g.toPdf(a.u, a.v + a.h), xe = g.toPdf(a.u + a.w, a.v + a.h), ye = g.toPdf(a.u, a.v);
        double ma = xe[0] - o[0], mb = xe[1] - o[1], mc = ye[0] - o[0], md = ye[1] - o[1], me = o[0] - llx, mf = o[1] - lly;

        byte[] rgbBytes = new byte[a.pw * a.ph * 3], alpha = new byte[a.pw * a.ph];
        for (int i = 0; i < a.px.length && i < a.pw * a.ph; i++) {
            int p = a.px[i];
            rgbBytes[i * 3] = (byte) (p >> 16); rgbBytes[i * 3 + 1] = (byte) (p >> 8); rgbBytes[i * 3 + 2] = (byte) p;
            alpha[i] = (byte) (p >>> 24);
        }
        int smNum = u.alloc();
        Map<String, Object> sm = new LinkedHashMap<>();
        sm.put("Type", new Name("XObject")); sm.put("Subtype", new Name("Image"));
        sm.put("Width", (long) a.pw); sm.put("Height", (long) a.ph);
        sm.put("ColorSpace", new Name("DeviceGray")); sm.put("BitsPerComponent", 8L);
        u.putStream(smNum, sm, alpha, true);
        int imNum = u.alloc();
        Map<String, Object> im = new LinkedHashMap<>();
        im.put("Type", new Name("XObject")); im.put("Subtype", new Name("Image"));
        im.put("Width", (long) a.pw); im.put("Height", (long) a.ph);
        im.put("ColorSpace", new Name("DeviceRGB")); im.put("BitsPerComponent", 8L);
        im.put("SMask", new Ref(smNum, 0));
        u.putStream(imNum, im, rgbBytes, true);

        Map<String, Object> xo = new LinkedHashMap<>();
        xo.put("Im0", new Ref(imNum, 0));
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("XObject", xo);
        String cs = "q " + f(ma) + " " + f(mb) + " " + f(mc) + " " + f(md) + " " + f(me) + " " + f(mf) + " cm /Im0 Do Q\n";
        Map<String, Object> apd = new LinkedHashMap<>();
        apd.put("Type", new Name("XObject")); apd.put("Subtype", new Name("Form"));
        apd.put("BBox", list(0, 0, urx - llx, ury - lly));
        apd.put("Resources", res);
        int apNum = u.alloc();
        u.putStream(apNum, apd, cs.getBytes("ISO-8859-1"), true);

        Map<String, Object> ap = new LinkedHashMap<>();
        ap.put("N", new Ref(apNum, 0));
        int c = a.color;
        String da = f(((c >> 16) & 0xff) / 255.0) + " " + f(((c >> 8) & 0xff) / 255.0) + " " + f((c & 0xff) / 255.0) + " rg /Helv " + f(a.fs) + " Tf";
        Map<String, Object> an = new LinkedHashMap<>();
        an.put("Type", new Name("Annot"));
        an.put("Subtype", new Name("FreeText"));
        an.put("Rect", list(llx, lly, urx, ury));
        an.put("F", 4L);
        an.put("Contents", Str.of(a.text));
        an.put("DA", Str.of(da));
        an.put("NM", Str.of(nm()));
        an.put("T", Str.of("Doc-os"));
        an.put("P", page);
        an.put("AP", ap);
        int num = u.alloc();
        u.put(num, 0, an);
        return num;
    }
}
