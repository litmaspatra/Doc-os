package com.litmaspatra.docos.ooxml;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** PowerPoint: slide model (shapes, text, pictures, tables) and in-place text edits. */
public final class Pptx {
    static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    static final String P = "http://schemas.openxmlformats.org/presentationml/2006/main";
    static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    public static final class Run { public String text = ""; public int sz; public boolean b, i, u; public Integer color; }

    public static final class Para {
        public final List<Run> runs = new ArrayList<>();
        public int align;      // 0 left 1 center 2 right
        public int marL;       // EMU
        public boolean bullet;
    }

    public static final class Shape {
        public boolean picture;
        public double x, y, w, h, rot;      // EMU, degrees
        public int fill, line;              // ARGB, 0 = none
        public String geom = "rect";
        public String picPath;
        public String anchor = "t";
        public double inL = 91440, inT = 45720, inR = 91440, inB = 45720;
        public String phType;
        public Element owner, txBody;
        public final List<Para> paras = new ArrayList<>();
        public Object cache;                // UI layout cache
        public boolean editable() { return !picture; }
    }

    public static final class Slide { public double w, h; public int bg = 0xFFFFFFFF; public final List<Shape> shapes = new ArrayList<>(); }

    private final File file;
    private final List<String> slidePaths = new ArrayList<>();
    private final Map<String, Document> docs = new HashMap<>();
    private final Set<String> dirty = new HashSet<>();
    private final Map<String, Integer> theme = new HashMap<>();
    private double sw = 9144000, sh = 5143500;
    public boolean modified;

    private Pptx(File f) { this.file = f; }

    public static Pptx open(File f) throws Exception {
        Pptx x = new Pptx(f);
        ZipFile zf = new ZipFile(f);
        try {
            byte[] pb = Zip.read(zf, "ppt/presentation.xml");
            if (pb == null) throw new IOException("not a PowerPoint file");
            Element root = Xml.parse(pb).getDocumentElement();
            Element size = Xml.kid(root, P, "sldSz");
            if (size != null) { x.sw = Xml.dblAttr(size, null, "cx", x.sw); x.sh = Xml.dblAttr(size, null, "cy", x.sh); }
            Map<String, String> rels = x.rels("ppt/presentation.xml");
            for (Element id : Xml.kids(Xml.kid(root, P, "sldIdLst"), P, "sldId")) {
                String t = rels.get(Xml.attr(id, R, "id"));
                if (t != null) x.slidePaths.add(norm("ppt/", t));
            }
            if (x.slidePaths.isEmpty()) throw new IOException("no slides");
            for (String t : rels.values()) {
                if (t.contains("theme/")) { x.loadTheme(norm("ppt/", t)); break; }
            }
        } finally { zf.close(); }
        return x;
    }

    public int slideCount() { return slidePaths.size(); }

    static String norm(String baseDir, String target) {
        String t = target.startsWith("/") ? target.substring(1) : baseDir + target;
        List<String> out = new ArrayList<>();
        for (String s : t.split("/")) {
            if (s.equals("..")) { if (!out.isEmpty()) out.remove(out.size() - 1); }
            else if (!s.equals(".") && !s.isEmpty()) out.add(s);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < out.size(); i++) { if (i > 0) sb.append('/'); sb.append(out.get(i)); }
        return sb.toString();
    }

    private static String dirOf(String path) { int i = path.lastIndexOf('/'); return i < 0 ? "" : path.substring(0, i + 1); }

    private Map<String, String> rels(String part) {
        Map<String, String> m = new LinkedHashMap<>();
        try {
            byte[] b = Zip.read(file, dirOf(part) + "_rels/" + part.substring(part.lastIndexOf('/') + 1) + ".rels");
            if (b == null) return m;
            for (Element r : Xml.kids(Xml.parse(b).getDocumentElement(), null, "Relationship")) m.put(r.getAttribute("Id"), r.getAttribute("Target"));
        } catch (Exception ignored) { }
        return m;
    }

    private Map<String, String> relTypes(String part) {
        Map<String, String> m = new LinkedHashMap<>();
        try {
            byte[] b = Zip.read(file, dirOf(part) + "_rels/" + part.substring(part.lastIndexOf('/') + 1) + ".rels");
            if (b == null) return m;
            for (Element r : Xml.kids(Xml.parse(b).getDocumentElement(), null, "Relationship"))
                m.put(r.getAttribute("Type").substring(r.getAttribute("Type").lastIndexOf('/') + 1), norm(dirOf(part), r.getAttribute("Target")));
        } catch (Exception ignored) { }
        return m;
    }

    private Document doc(String part) throws Exception {
        Document d = docs.get(part);
        if (d == null) {
            byte[] b = Zip.read(file, part);
            if (b == null) return null;
            d = Xml.parse(b);
            docs.put(part, d);
        }
        return d;
    }

    private void loadTheme(String part) {
        try {
            Document d = doc(part);
            Element cs = Xml.path(d.getDocumentElement(), A, "themeElements", "clrScheme");
            for (Element c : Xml.kids(cs)) {
                Element v = Xml.kids(c).isEmpty() ? null : Xml.kids(c).get(0);
                if (v == null) continue;
                String hex = v.getLocalName().equals("sysClr") ? Xml.attr(v, null, "lastClr") : Xml.attr(v, null, "val");
                if (hex != null && hex.matches("[0-9A-Fa-f]{6}")) theme.put(c.getLocalName(), 0xFF000000 | Integer.parseInt(hex, 16));
            }
        } catch (Exception ignored) { }
    }

    private Integer color(Element holder) {
        Element sf = Xml.kid(holder, A, "solidFill");
        if (sf == null) return null;
        for (Element c : Xml.kids(sf)) {
            String ln = c.getLocalName();
            String v = Xml.attr(c, null, "val");
            if (ln.equals("srgbClr") && v != null && v.matches("[0-9A-Fa-f]{6}")) return 0xFF000000 | Integer.parseInt(v, 16);
            if (ln.equals("schemeClr") && v != null) {
                String k = v.equals("tx1") ? "dk1" : v.equals("bg1") ? "lt1" : v.equals("tx2") ? "dk2" : v.equals("bg2") ? "lt2" : v;
                return theme.get(k);
            }
            if (ln.equals("sysClr")) { String l = Xml.attr(c, null, "lastClr"); if (l != null && l.matches("[0-9A-Fa-f]{6}")) return 0xFF000000 | Integer.parseInt(l, 16); }
        }
        return null;
    }

    // ------------------------------------------------------------------ slides
    public Slide slide(int idx) throws Exception {
        String path = slidePaths.get(idx);
        Document d = doc(path);
        Slide s = new Slide();
        s.w = sw; s.h = sh;
        Map<String, String> types = relTypes(path);
        String layoutPath = types.get("slideLayout");
        Document layout = layoutPath == null ? null : doc(layoutPath);
        Map<String, String> lt = layoutPath == null ? new HashMap<String, String>() : relTypes(layoutPath);
        String masterPath = lt.get("slideMaster");
        Document master = masterPath == null ? null : doc(masterPath);
        s.bg = background(d, layout, master);
        Element tree = Xml.path(d.getDocumentElement(), P, "cSld", "spTree");
        Map<String, String> picRels = rels(path);
        collect(tree, new double[]{1, 1, 0, 0}, s, path, picRels, layout, master);
        return s;
    }

    private int background(Document... docs) {
        for (Document d : docs) {
            if (d == null) continue;
            Element bg = Xml.path(d.getDocumentElement(), P, "cSld", "bg");
            if (bg == null) continue;
            Element pr = Xml.kid(bg, P, "bgPr");
            Integer c = pr == null ? null : color(pr);
            if (c == null) { Element ref = Xml.kid(bg, P, "bgRef"); if (ref != null) { Element w = new FakeHolder(ref).holder(); c = color(w); } }
            if (c != null) return c;
        }
        return 0xFFFFFFFF;
    }

    /** bgRef carries its colour directly (not inside solidFill); wrap so color() can read it. */
    private static final class FakeHolder {
        final Element ref;
        FakeHolder(Element ref) { this.ref = ref; }
        Element holder() {
            Element h = ref.getOwnerDocument().createElementNS(A, "a:tmp");
            Element sf = ref.getOwnerDocument().createElementNS(A, "a:solidFill");
            for (Element c : Xml.kids(ref)) sf.appendChild(c.cloneNode(true));
            h.appendChild(sf);
            return h;
        }
    }

    private void collect(Element tree, double[] t, Slide out, String slidePath, Map<String, String> rels, Document layout, Document master) throws Exception {
        if (tree == null) return;
        for (Element e : Xml.kids(tree)) {
            if (!P.equals(e.getNamespaceURI())) continue;
            switch (e.getLocalName()) {
                case "sp": shape(e, t, out, layout, master); break;
                case "pic": picture(e, t, out, slidePath, rels); break;
                case "grpSp": {
                    Element x = Xml.kid(Xml.kid(e, P, "grpSpPr"), A, "xfrm");
                    double[] nt = t;
                    if (x != null) {
                        Element off = Xml.kid(x, A, "off"), ext = Xml.kid(x, A, "ext"), co = Xml.kid(x, A, "chOff"), ce = Xml.kid(x, A, "chExt");
                        if (off != null && ext != null && co != null && ce != null) {
                            double cw = Xml.dblAttr(ce, null, "cx", 1), ch = Xml.dblAttr(ce, null, "cy", 1);
                            double sx = cw == 0 ? 1 : Xml.dblAttr(ext, null, "cx", cw) / cw, sy = ch == 0 ? 1 : Xml.dblAttr(ext, null, "cy", ch) / ch;
                            double tx = Xml.dblAttr(off, null, "x", 0) - Xml.dblAttr(co, null, "x", 0) * sx;
                            double ty = Xml.dblAttr(off, null, "y", 0) - Xml.dblAttr(co, null, "y", 0) * sy;
                            nt = new double[]{sx * t[0], sy * t[1], tx * t[0] + t[2], ty * t[1] + t[3]};
                        }
                    }
                    collect(e, nt, out, slidePath, rels, layout, master);
                    break;
                }
                case "graphicFrame": table(e, t, out); break;
                default: break;
            }
        }
    }

    private static double[] xfrm(Element x) {
        if (x == null) return null;
        Element off = Xml.kid(x, A, "off"), ext = Xml.kid(x, A, "ext");
        if (off == null || ext == null) return null;
        return new double[]{Xml.dblAttr(off, null, "x", 0), Xml.dblAttr(off, null, "y", 0), Xml.dblAttr(ext, null, "cx", 0), Xml.dblAttr(ext, null, "cy", 0), Xml.dblAttr(x, null, "rot", 0) / 60000.0};
    }

    private double[] placeholderXfrm(Document d, String type, String idx) {
        if (d == null) return null;
        Element tree = Xml.path(d.getDocumentElement(), P, "cSld", "spTree");
        for (Element sp : Xml.kids(tree, P, "sp")) {
            Element ph = Xml.path(sp, P, "nvSpPr", "nvPr", "ph");
            if (ph == null) continue;
            String t = Xml.attr(ph, null, "type"), i = Xml.attr(ph, null, "idx");
            boolean match = (idx != null && idx.equals(i)) || (idx == null && i == null && eq(type, t))
                    || (type != null && eq(type, t) && (idx == null || i == null))
                    || ((type == null || type.equals("body") || type.equals("obj")) && (t == null || t.equals("body") || t.equals("obj")) && idx == null);
            if (match) { double[] x = xfrm(Xml.kid(Xml.kid(sp, P, "spPr"), A, "xfrm")); if (x != null) return x; }
        }
        return null;
    }

    private static boolean eq(String a, String b) {
        if (a == null) return b == null;
        if (a.equals("ctrTitle")) a = "title"; if (b != null && b.equals("ctrTitle")) b = "title";
        return a.equals(b);
    }

    private void shape(Element sp, double[] t, Slide out, Document layout, Document master) {
        Shape s = new Shape();
        s.owner = sp;
        Element spPr = Xml.kid(sp, P, "spPr");
        double[] x = xfrm(Xml.kid(spPr, A, "xfrm"));
        Element ph = Xml.path(sp, P, "nvSpPr", "nvPr", "ph");
        if (ph != null) s.phType = Xml.attr(ph, null, "type") == null ? "body" : Xml.attr(ph, null, "type");
        if (x == null && ph != null) {
            String type = Xml.attr(ph, null, "type"), idx = Xml.attr(ph, null, "idx");
            x = placeholderXfrm(layout, type, idx);
            if (x == null) x = placeholderXfrm(master, type, idx);
        }
        if (x == null) return;
        s.x = x[0] * t[0] + t[2]; s.y = x[1] * t[1] + t[3]; s.w = x[2] * t[0]; s.h = x[3] * t[1]; s.rot = x[4];
        Element geom = Xml.kid(spPr, A, "prstGeom");
        if (geom != null && Xml.attr(geom, null, "prst") != null) s.geom = Xml.attr(geom, null, "prst");
        Integer f = spPr == null ? null : color(spPr);
        if (f != null) s.fill = f;
        Element ln = Xml.kid(spPr, A, "ln");
        Integer lc = ln == null ? null : color(ln);
        if (lc != null) s.line = lc;
        s.txBody = Xml.kid(sp, P, "txBody");
        readText(s);
        if (s.fill == 0 && s.line == 0 && s.paras.isEmpty() && ph == null) return;
        out.shapes.add(s);
    }

    private void readText(Shape s) {
        s.paras.clear();
        Element tb = s.txBody;
        if (tb == null) return;
        Element bp = Xml.kid(tb, A, "bodyPr");
        if (bp != null) {
            if (Xml.attr(bp, null, "anchor") != null) s.anchor = Xml.attr(bp, null, "anchor");
            s.inL = Xml.dblAttr(bp, null, "lIns", s.inL); s.inR = Xml.dblAttr(bp, null, "rIns", s.inR);
            s.inT = Xml.dblAttr(bp, null, "tIns", s.inT); s.inB = Xml.dblAttr(bp, null, "bIns", s.inB);
        }
        int def = s.phType == null ? 1800 : s.phType.equals("title") || s.phType.equals("ctrTitle") ? 4000 : s.phType.equals("subTitle") ? 2400 : s.phType.equals("body") ? 2400 : 1800;
        for (Element p : Xml.kids(tb, A, "p")) {
            Para pa = new Para();
            Element ppr = Xml.kid(p, A, "pPr");
            if (ppr != null) {
                String al = Xml.attr(ppr, null, "algn");
                pa.align = "ctr".equals(al) ? 1 : "r".equals(al) ? 2 : 0;
                pa.marL = Xml.intAttr(ppr, null, "marL", 0);
                pa.bullet = Xml.kid(ppr, A, "buChar") != null || Xml.kid(ppr, A, "buAutoNum") != null;
            }
            for (Element r : Xml.kids(p)) {
                String ln = r.getLocalName();
                if (!A.equals(r.getNamespaceURI())) continue;
                Run run = new Run();
                if (ln.equals("br")) { run.text = "\n"; pa.runs.add(run); continue; }
                if (!ln.equals("r") && !ln.equals("fld")) continue;
                Element rpr = Xml.kid(r, A, "rPr");
                run.sz = rpr != null && Xml.attr(rpr, null, "sz") != null ? Xml.intAttr(rpr, null, "sz", def) : def;
                if (rpr != null) {
                    run.b = "1".equals(Xml.attr(rpr, null, "b")); run.i = "1".equals(Xml.attr(rpr, null, "i"));
                    String u = Xml.attr(rpr, null, "u"); run.u = u != null && !u.equals("none");
                    run.color = color(rpr);
                }
                Element te = Xml.kid(r, A, "t");
                run.text = te == null ? "" : te.getTextContent();
                pa.runs.add(run);
            }
            s.paras.add(pa);
        }
    }

    private void picture(Element pic, double[] t, Slide out, String slidePath, Map<String, String> rels) {
        double[] x = xfrm(Xml.kid(Xml.kid(pic, P, "spPr"), A, "xfrm"));
        Element blip = Xml.path(pic, P, "blipFill", "blip");
        if (x == null || blip == null) return;
        String target = rels.get(Xml.attr(blip, R, "embed"));
        if (target == null) return;
        Shape s = new Shape();
        s.picture = true;
        s.owner = pic;
        s.x = x[0] * t[0] + t[2]; s.y = x[1] * t[1] + t[3]; s.w = x[2] * t[0]; s.h = x[3] * t[1]; s.rot = x[4];
        s.picPath = norm(dirOf(slidePath), target);
        out.shapes.add(s);
    }

    private void table(Element gf, double[] t, Slide out) {
        Element tbl = Xml.path(gf, A, "graphic", "graphicData", "tbl");
        double[] x = xfrm(Xml.kid(gf, P, "xfrm"));
        if (tbl == null || x == null) return;
        List<Double> cols = new ArrayList<>();
        for (Element g : Xml.kids(Xml.kid(tbl, A, "tblGrid"), A, "gridCol")) cols.add(Xml.dblAttr(g, null, "w", 0));
        double y = x[1];
        for (Element tr : Xml.kids(tbl, A, "tr")) {
            double h = Xml.dblAttr(tr, null, "h", 370840), cx = x[0];
            int ci = 0;
            for (Element tc : Xml.kids(tr, A, "tc")) {
                double w = ci < cols.size() ? cols.get(ci) : 914400;
                int span = Xml.intAttr(tc, null, "gridSpan", 1);
                for (int k = 1; k < span && ci + k < cols.size(); k++) w += cols.get(ci + k);
                ci += span;
                if (!"1".equals(Xml.attr(tc, null, "hMerge")) && !"1".equals(Xml.attr(tc, null, "vMerge"))) {
                    Shape s = new Shape();
                    s.owner = tc;
                    s.txBody = Xml.kid(tc, A, "txBody");
                    s.x = cx * t[0] + t[2]; s.y = y * t[1] + t[3]; s.w = w * t[0]; s.h = h * t[1];
                    Integer f = Xml.kid(tc, A, "tcPr") == null ? null : color(Xml.kid(tc, A, "tcPr"));
                    if (f != null) s.fill = f;
                    s.line = 0xFFBFBFBF;
                    readText(s);
                    out.shapes.add(s);
                }
                cx += w;
            }
            y += h;
        }
    }

    public byte[] media(String path) {
        try { return Zip.read(file, path); } catch (IOException e) { return null; }
    }

    // ------------------------------------------------------------------ editing
    public String textOf(Shape s) {
        StringBuilder sb = new StringBuilder();
        if (s.txBody == null) return "";
        boolean first = true;
        for (Element p : Xml.kids(s.txBody, A, "p")) {
            if (!first) sb.append('\n');
            first = false;
            List<Element> ts = new ArrayList<>();
            OoxmlText.collect(p, A, "t", ts);
            sb.append(OoxmlText.text(ts));
        }
        return sb.toString();
    }

    private String slidePathOf(Shape s) {
        Document d = s.owner.getOwnerDocument();
        for (Map.Entry<String, Document> e : docs.entrySet()) if (e.getValue() == d) return e.getKey();
        return null;
    }

    public void setText(Shape s, String text) {
        if (s.txBody == null) {
            if (!Xml.is(s.owner, P, "sp")) return;
            Element tb = Xml.create(s.owner, P, "txBody");
            tb.appendChild(Xml.create(tb, A, "bodyPr"));
            tb.appendChild(Xml.create(tb, A, "lstStyle"));
            tb.appendChild(Xml.create(tb, A, "p"));
            s.owner.appendChild(tb);
            s.txBody = tb;
        }
        String[] lines = text.replace("\r", "").split("\n", -1);
        List<Element> ps = Xml.kids(s.txBody, A, "p");
        if (ps.isEmpty()) { Element np = Xml.create(s.txBody, A, "p"); s.txBody.appendChild(np); ps.add(np); }
        while (ps.size() < lines.length) {
            Element last = ps.get(ps.size() - 1);
            Element np = (Element) last.cloneNode(true);
            for (Element c : Xml.kids(np)) {
                String ln = c.getLocalName();
                if (ln.equals("r") || ln.equals("br") || ln.equals("fld")) np.removeChild(c);
            }
            Element rpr = null;
            for (Element c : Xml.kids(last, A, "r")) { rpr = Xml.kid(c, A, "rPr"); if (rpr != null) break; }
            if (rpr != null) {
                Element r = Xml.create(np, A, "r");
                r.appendChild(rpr.cloneNode(true));
                Element end = Xml.kid(np, A, "endParaRPr");
                if (end != null) np.insertBefore(r, end); else np.appendChild(r);
            }
            Node next = last.getNextSibling();
            if (next != null) s.txBody.insertBefore(np, next); else s.txBody.appendChild(np);
            ps.add(np);
        }
        while (ps.size() > lines.length) { Element x = ps.remove(ps.size() - 1); s.txBody.removeChild(x); }
        for (int i = 0; i < lines.length; i++) {
            List<Element> ts = new ArrayList<>();
            OoxmlText.collect(ps.get(i), A, "t", ts);
            OoxmlText.apply(ps.get(i), ts, lines[i], A, "r", "t", "endParaRPr");
        }
        String path = slidePathOf(s);
        if (path != null) dirty.add(path);
        modified = true;
        readText(s);
        s.cache = null;
    }

    public void save(File dst) throws Exception {
        Map<String, byte[]> rep = new LinkedHashMap<>();
        for (String p : dirty) rep.put(p, Xml.write(docs.get(p)));
        Zip.rewrite(file, dst, rep, null);
    }
}
