package com.litmaspatra.docos.ooxml;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Word document: read, display model, and in-place edits of document.xml. */
public final class Docx {
    public static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    static final String WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing";
    private static final String DOC = "word/document.xml";

    // ------------------------------------------------------------------ display model
    public static final class Run {
        public String text = "";
        public boolean b, i, u, s;
        public String color, hl;
        public int sz;     // half points
        public int vert;   // 1 superscript, 2 subscript
    }

    public static final class Image { public String rid; public int cx, cy; }

    public static final class Para {
        public Element el;
        public final List<Run> runs = new ArrayList<>();
        public final List<Image> images = new ArrayList<>();
        public int align;                 // 0 left 1 center 2 right 3 justify
        public int left, first, before, after; // twips
        public String prefix = "";
        public int heading;
    }

    public static final class Cell { public final List<Para> paras = new ArrayList<>(); public int weight = 1; }

    public static final class Block {
        public Para para;                    // paragraph block
        public List<Cell> cells;             // table row block
    }

    private static final class Props {
        Boolean b, i, u, s; String color, hl; Integer sz, vert;
        void over(Props o) {
            if (o == null) return;
            if (o.b != null) b = o.b; if (o.i != null) i = o.i; if (o.u != null) u = o.u; if (o.s != null) s = o.s;
            if (o.color != null) color = o.color; if (o.hl != null) hl = o.hl; if (o.sz != null) sz = o.sz; if (o.vert != null) vert = o.vert;
        }
    }

    private static final class Style {
        String basedOn, numId; int ilvl; Props rpr = new Props(); Integer align, left, first, before, after;
    }

    // ------------------------------------------------------------------ state
    private final File file;
    private final Document dom;
    private final Element body;
    private final Map<String, Style> styles = new HashMap<>();
    private final Props defaults = new Props();
    private int defBefore, defAfter;
    private final Map<String, String> rels = new HashMap<>();
    private final Map<String, Map<Integer, String[]>> numbering = new HashMap<>(); // numId -> ilvl -> {fmt, text}
    public boolean modified;

    private Docx(File f, Document dom) {
        this.file = f; this.dom = dom;
        this.body = Xml.kid(dom.getDocumentElement(), W, "body");
    }

    public static Docx open(File f) throws Exception {
        ZipFile zf = new ZipFile(f);
        try {
            byte[] d = Zip.read(zf, DOC);
            if (d == null) throw new IOException("not a Word document");
            Docx x = new Docx(f, Xml.parse(d));
            if (x.body == null) throw new IOException("empty document");
            x.loadStyles(Zip.read(zf, "word/styles.xml"));
            x.loadNumbering(Zip.read(zf, "word/numbering.xml"));
            x.loadRels(Zip.read(zf, "word/_rels/document.xml.rels"));
            return x;
        } finally { zf.close(); }
    }

    private void loadRels(byte[] b) {
        if (b == null) return;
        try {
            for (Element r : Xml.kids(Xml.parse(b).getDocumentElement(), null, "Relationship"))
                rels.put(r.getAttribute("Id"), r.getAttribute("Target"));
        } catch (Exception ignored) { }
    }

    private static Boolean toggle(Element e) {
        if (e == null) return null;
        String v = Xml.attr(e, W, "val");
        return !(v != null && (v.equals("0") || v.equals("false") || v.equals("off")));
    }

    private static Props parseRpr(Element rpr) {
        Props p = new Props();
        if (rpr == null) return p;
        p.b = toggle(Xml.kid(rpr, W, "b"));
        p.i = toggle(Xml.kid(rpr, W, "i"));
        p.s = toggle(Xml.kid(rpr, W, "strike"));
        Element u = Xml.kid(rpr, W, "u");
        if (u != null) { String v = Xml.attr(u, W, "val"); p.u = v == null || !v.equals("none"); }
        Element c = Xml.kid(rpr, W, "color");
        if (c != null) { String v = Xml.attr(c, W, "val"); if (v != null && v.matches("[0-9A-Fa-f]{6}")) p.color = v; }
        Element sz = Xml.kid(rpr, W, "sz");
        if (sz != null) p.sz = Xml.intAttr(sz, W, "val", 0);
        Element h = Xml.kid(rpr, W, "highlight");
        if (h != null) { String v = Xml.attr(h, W, "val"); if (v != null && !v.equals("none")) p.hl = v; }
        Element va = Xml.kid(rpr, W, "vertAlign");
        if (va != null) { String v = Xml.attr(va, W, "val"); p.vert = "superscript".equals(v) ? 1 : "subscript".equals(v) ? 2 : 0; }
        return p;
    }

    private void loadStyles(byte[] b) {
        if (b == null) return;
        try {
            Element root = Xml.parse(b).getDocumentElement();
            Element dd = Xml.path(root, W, "docDefaults", "rPrDefault", "rPr");
            if (dd != null) defaults.over(parseRpr(dd));
            Element pd = Xml.path(root, W, "docDefaults", "pPrDefault", "pPr", "spacing");
            if (pd != null) { defBefore = Xml.intAttr(pd, W, "before", 0); defAfter = Xml.intAttr(pd, W, "after", 0); }
            for (Element s : Xml.kids(root, W, "style")) {
                String id = Xml.attr(s, W, "styleId");
                if (id == null) continue;
                Style st = new Style();
                Element bo = Xml.kid(s, W, "basedOn");
                if (bo != null) st.basedOn = Xml.attr(bo, W, "val");
                st.rpr = parseRpr(Xml.kid(s, W, "rPr"));
                Element ppr = Xml.kid(s, W, "pPr");
                if (ppr != null) readPpr(ppr, st);
                styles.put(id, st);
            }
        } catch (Exception ignored) { }
    }

    private static void readPpr(Element ppr, Style st) {
        Element jc = Xml.kid(ppr, W, "jc");
        if (jc != null) st.align = alignOf(Xml.attr(jc, W, "val"));
        Element ind = Xml.kid(ppr, W, "ind");
        if (ind != null) {
            String l = Xml.attr(ind, W, "left");
            if (l == null) l = Xml.attr(ind, W, "start");
            if (l != null) { try { st.left = (int) Math.round(Double.parseDouble(l)); } catch (NumberFormatException ignored) { } }
            if (Xml.attr(ind, W, "firstLine") != null) st.first = Xml.intAttr(ind, W, "firstLine", 0);
            if (Xml.attr(ind, W, "hanging") != null) st.first = -Xml.intAttr(ind, W, "hanging", 0);
        }
        Element np = Xml.kid(ppr, W, "numPr");
        if (np != null) {
            Element ni = Xml.kid(np, W, "numId"), il = Xml.kid(np, W, "ilvl");
            if (ni != null) st.numId = Xml.attr(ni, W, "val");
            if (il != null) st.ilvl = Xml.intAttr(il, W, "val", 0);
        }
        Element sp = Xml.kid(ppr, W, "spacing");
        if (sp != null) {
            if (Xml.attr(sp, W, "before") != null) st.before = Xml.intAttr(sp, W, "before", 0);
            if (Xml.attr(sp, W, "after") != null) st.after = Xml.intAttr(sp, W, "after", 0);
        }
    }

    private static int alignOf(String v) {
        if (v == null) return 0;
        switch (v) { case "center": return 1; case "right": case "end": return 2; case "both": case "distribute": return 3; default: return 0; }
    }

    private void loadNumbering(byte[] b) {
        if (b == null) return;
        try {
            Element root = Xml.parse(b).getDocumentElement();
            Map<String, Map<Integer, String[]>> abs = new HashMap<>();
            for (Element an : Xml.kids(root, W, "abstractNum")) {
                Map<Integer, String[]> lv = new HashMap<>();
                for (Element l : Xml.kids(an, W, "lvl")) {
                    Element nf = Xml.kid(l, W, "numFmt"), lt = Xml.kid(l, W, "lvlText");
                    lv.put(Xml.intAttr(l, W, "ilvl", 0), new String[]{nf == null ? "decimal" : Xml.attr(nf, W, "val"), lt == null ? "" : Xml.attr(lt, W, "val")});
                }
                abs.put(Xml.attr(an, W, "abstractNumId"), lv);
            }
            for (Element n : Xml.kids(root, W, "num")) {
                Element a = Xml.kid(n, W, "abstractNumId");
                if (a != null && abs.containsKey(Xml.attr(a, W, "val"))) numbering.put(Xml.attr(n, W, "numId"), abs.get(Xml.attr(a, W, "val")));
            }
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ building blocks
    public List<Block> blocks() {
        List<Block> out = new ArrayList<>();
        Map<String, int[]> counters = new HashMap<>();
        add(body, out, counters);
        return out;
    }

    private void add(Element parent, List<Block> out, Map<String, int[]> counters) {
        for (Element c : Xml.kids(parent)) {
            if (!W.equals(c.getNamespaceURI())) continue;
            String ln = c.getLocalName();
            if (ln.equals("p")) { Block b = new Block(); b.para = para(c, counters); out.add(b); }
            else if (ln.equals("tbl")) {
                for (Element tr : Xml.kids(c, W, "tr")) {
                    Block b = new Block();
                    b.cells = new ArrayList<>();
                    for (Element tc : Xml.kids(tr, W, "tc")) {
                        Cell cell = new Cell();
                        Element pr = Xml.kid(tc, W, "tcPr");
                        Element tw = Xml.kid(pr, W, "tcW");
                        if (tw != null) cell.weight = Math.max(1, Xml.intAttr(tw, W, "w", 1));
                        for (Element p : Xml.kids(tc, W, "p")) cell.paras.add(para(p, counters));
                        b.cells.add(cell);
                    }
                    if (!b.cells.isEmpty()) out.add(b);
                }
            } else if (ln.equals("sdt")) add(Xml.kid(c, W, "sdtContent") == null ? c : Xml.kid(c, W, "sdtContent"), out, counters);
        }
    }

    private Props styleProps(String id, int depth) {
        Props p = new Props();
        Style s = id == null ? null : styles.get(id);
        if (s == null || depth > 8) return p;
        p.over(styleProps(s.basedOn, depth + 1));
        p.over(s.rpr);
        return p;
    }

    private Style mergedStyle(String id) {
        Style m = new Style();
        List<Style> chain = new ArrayList<>();
        String cur = id;
        for (int d = 0; d < 8 && cur != null && styles.get(cur) != null; d++) { chain.add(0, styles.get(cur)); cur = styles.get(cur).basedOn; }
        for (Style s : chain) {
            if (s.align != null) m.align = s.align; if (s.left != null) m.left = s.left; if (s.first != null) m.first = s.first;
            if (s.before != null) m.before = s.before; if (s.after != null) m.after = s.after;
            if (s.numId != null) { m.numId = s.numId; m.ilvl = s.ilvl; }
        }
        return m;
    }

    private Para para(Element p, Map<String, int[]> counters) {
        Para out = new Para();
        out.el = p;
        Element ppr = Xml.kid(p, W, "pPr");
        String styleId = null;
        if (ppr != null) { Element ps = Xml.kid(ppr, W, "pStyle"); if (ps != null) styleId = Xml.attr(ps, W, "val"); }
        Style ms = mergedStyle(styleId);
        Style direct = new Style();
        if (ppr != null) readPpr(ppr, direct);
        out.align = direct.align != null ? direct.align : ms.align != null ? ms.align : 0;
        out.left = direct.left != null ? direct.left : ms.left != null ? ms.left : 0;
        out.first = direct.first != null ? direct.first : ms.first != null ? ms.first : 0;
        out.before = direct.before != null ? direct.before : ms.before != null ? ms.before : defBefore;
        out.after = direct.after != null ? direct.after : ms.after != null ? ms.after : defAfter;
        if (styleId != null && styleId.toLowerCase().startsWith("heading")) {
            try { out.heading = Integer.parseInt(styleId.substring(7).trim()); } catch (NumberFormatException e) { out.heading = 1; }
        }
        // numbering
        String numId = direct.numId != null ? direct.numId : ms.numId;
        int lvl = direct.numId != null ? direct.ilvl : ms.ilvl;
        {
            if (numId != null && !numId.equals("0")) {
                Map<Integer, String[]> lv = numbering.get(numId);
                String[] def = lv == null ? null : lv.get(lvl);
                String fmt = def == null ? "bullet" : def[0];
                if ("bullet".equals(fmt) || "none".equals(fmt)) out.prefix = "\u2022 ";
                else {
                    int[] c = counters.get(numId);
                    if (c == null) { c = new int[10]; counters.put(numId, c); }
                    c[Math.min(lvl, 9)]++;
                    for (int k = lvl + 1; k < 10; k++) c[k] = 0;
                    out.prefix = counter(fmt, c[Math.min(lvl, 9)]) + ". ";
                }
                if (out.left == 0) out.left = 360 * (lvl + 1);
            }
        }
        Props base = new Props();
        base.over(defaults);
        base.over(styleProps(styleId, 0));
        List<Element> runs = new ArrayList<>();
        collectRuns(p, runs);
        for (Element r : runs) {
            Props rp = new Props();
            rp.over(base);
            Element rpr = Xml.kid(r, W, "rPr");
            if (rpr != null) { Element rs = Xml.kid(rpr, W, "rStyle"); if (rs != null) rp.over(styleProps(Xml.attr(rs, W, "val"), 0)); }
            rp.over(parseRpr(rpr));
            Run run = new Run();
            run.b = Boolean.TRUE.equals(rp.b); run.i = Boolean.TRUE.equals(rp.i); run.u = Boolean.TRUE.equals(rp.u); run.s = Boolean.TRUE.equals(rp.s);
            run.color = rp.color; run.hl = rp.hl; run.sz = rp.sz == null ? 0 : rp.sz; run.vert = rp.vert == null ? 0 : rp.vert;
            StringBuilder sb = new StringBuilder();
            for (Element c : Xml.kids(r)) {
                String ln = c.getLocalName();
                if (W.equals(c.getNamespaceURI())) {
                    if (ln.equals("t")) sb.append(c.getTextContent());
                    else if (ln.equals("tab")) sb.append('\t');
                    else if (ln.equals("br") || ln.equals("cr")) sb.append('\n');
                    else if (ln.equals("noBreakHyphen")) sb.append('-');
                    else if (ln.equals("drawing")) image(c, out);
                }
            }
            run.text = sb.toString();
            out.runs.add(run);
        }
        return out;
    }

    private void image(Element drawing, Para out) {
        Element blip = firstDesc(drawing, A, "blip");
        if (blip == null) return;
        String rid = Xml.attr(blip, R, "embed");
        if (rid == null) return;
        Image im = new Image();
        im.rid = rid;
        Element ext = firstDesc(drawing, WP, "extent");
        im.cx = ext == null ? 0 : Xml.intAttr(ext, null, "cx", 0);
        im.cy = ext == null ? 0 : Xml.intAttr(ext, null, "cy", 0);
        out.images.add(im);
    }

    private static Element firstDesc(Element e, String ns, String local) {
        for (Element c : Xml.kids(e)) {
            if (Xml.is(c, ns, local)) return c;
            Element d = firstDesc(c, ns, local);
            if (d != null) return d;
        }
        return null;
    }

    private static String counter(String fmt, int n) {
        switch (fmt) {
            case "lowerLetter": return String.valueOf((char) ('a' + (n - 1) % 26));
            case "upperLetter": return String.valueOf((char) ('A' + (n - 1) % 26));
            case "lowerRoman": return roman(n).toLowerCase();
            case "upperRoman": return roman(n);
            default: return String.valueOf(n);
        }
    }

    private static String roman(int n) {
        int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] s = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length && n > 0; i++) while (n >= v[i]) { sb.append(s[i]); n -= v[i]; }
        return sb.toString();
    }

    private static void collectRuns(Element p, List<Element> out) {
        for (Element c : Xml.kids(p)) {
            if (!W.equals(c.getNamespaceURI())) continue;
            String ln = c.getLocalName();
            if (ln.equals("r")) out.add(c);
            else if (ln.equals("hyperlink") || ln.equals("smartTag") || ln.equals("ins") || ln.equals("sdt") || ln.equals("sdtContent")
                    || ln.equals("fldSimple") || ln.equals("customXml")) collectRuns(c, out);
        }
    }

    public byte[] image(String rid) {
        String t = rels.get(rid);
        if (t == null) return null;
        String path = t.startsWith("/") ? t.substring(1) : "word/" + t;
        try { return Zip.read(file, path); } catch (IOException e) { return null; }
    }

    // ------------------------------------------------------------------ editing
    public String getText(Element p) {
        List<Element> ts = new ArrayList<>();
        OoxmlText.collect(p, W, "t", ts);
        return OoxmlText.text(ts);
    }

    private void setSingle(Element p, String text) {
        List<Element> ts = new ArrayList<>();
        OoxmlText.collect(p, W, "t", ts);
        OoxmlText.apply(p, ts, text, W, "r", "t", null);
    }

    public void setText(Element p, String text) {
        String[] lines = text.replace("\r", "").split("\n", -1);
        String first = lines[0];
        if (!first.equals(getText(p)) || lines.length > 1) { setSingle(p, first); modified = true; }
        Element prev = p;
        for (int i = 1; i < lines.length; i++) {
            Element np = insertAfter(prev);
            setSingle(np, lines[i]);
            prev = np;
        }
    }

    /** Inserts an empty paragraph (same paragraph properties and first-run formatting) after p. */
    public Element insertAfter(Element p) {
        Element np = Xml.create(p, W, "p");
        Element ppr = Xml.kid(p, W, "pPr");
        if (ppr != null) np.appendChild(ppr.cloneNode(true));
        List<Element> runs = new ArrayList<>();
        collectRuns(p, runs);
        if (!runs.isEmpty()) {
            Element r = Xml.create(p, W, "r");
            Element rpr = Xml.kid(runs.get(0), W, "rPr");
            if (rpr != null) r.appendChild(rpr.cloneNode(true));
            np.appendChild(r);
        }
        Node next = p.getNextSibling();
        if (next != null) p.getParentNode().insertBefore(np, next); else p.getParentNode().appendChild(np);
        modified = true;
        return np;
    }

    public void delete(Element p) {
        Node parent = p.getParentNode();
        if (parent instanceof Element && Xml.is(parent, W, "tc") && Xml.kids(parent, W, "p").size() <= 1) {
            setSingle(p, "");
        } else if (Xml.kids(parent, W, "p").size() <= 1 && parent == body) {
            setSingle(p, "");
        } else parent.removeChild(p);
        modified = true;
    }

    private static final String[] RPR_ORDER = {"rStyle", "rFonts", "b", "bCs", "i", "iCs", "caps", "smallCaps", "strike", "dstrike", "outline",
        "shadow", "emboss", "imprint", "noProof", "snapToGrid", "vanish", "webHidden", "color", "spacing", "w", "kern", "position", "sz", "szCs",
        "highlight", "u", "effect", "bdr", "shd", "fitText", "vertAlign"};

    private static int order(String n) { for (int i = 0; i < RPR_ORDER.length; i++) if (RPR_ORDER[i].equals(n)) return i; return 999; }

    private static void setFlag(Element rpr, String name, boolean on, String val) {
        Element ex = Xml.kid(rpr, W, name);
        if (!on) { if (ex != null) rpr.removeChild(ex); return; }
        if (ex != null) { if (ex.hasAttributeNS(W, "val") && val == null) ex.removeAttributeNS(W, "val"); if (val != null) ex.setAttributeNS(W, "w:val", val); return; }
        Element e = Xml.create(rpr, W, name);
        if (val != null) e.setAttributeNS(W, "w:val", val);
        Node before = null;
        for (Element k : Xml.kids(rpr)) if (order(k.getLocalName()) > order(name)) { before = k; break; }
        if (before != null) rpr.insertBefore(e, before); else rpr.appendChild(e);
    }

    /** Returns {bold, italic, underline}: true only when every run with text has it. */
    public boolean[] formatOf(Element p) {
        boolean b = true, i = true, u = true, any = false;
        for (Para pa : blocksOf(p)) {
            for (Run r : pa.runs) {
                if (r.text.isEmpty()) continue;
                any = true; b &= r.b; i &= r.i; u &= r.u;
            }
        }
        return any ? new boolean[]{b, i, u} : new boolean[]{false, false, false};
    }

    private List<Para> blocksOf(Element p) {
        List<Para> l = new ArrayList<>();
        l.add(para(p, new HashMap<String, int[]>()));
        return l;
    }

    public void setFormat(Element p, boolean bold, boolean italic, boolean underline) {
        List<Element> runs = new ArrayList<>();
        collectRuns(p, runs);
        for (Element r : runs) {
            Element rpr = Xml.kid(r, W, "rPr");
            if (rpr == null) {
                rpr = Xml.create(r, W, "rPr");
                r.insertBefore(rpr, r.getFirstChild());
            }
            setFlag(rpr, "b", bold, null);
            setFlag(rpr, "i", italic, null);
            setFlag(rpr, "u", underline, "single");
        }
        modified = true;
    }

    // ------------------------------------------------------------------ saving
    public void save(File dst) throws Exception {
        Map<String, byte[]> rep = new LinkedHashMap<>();
        rep.put(DOC, Xml.write(dom));
        Zip.rewrite(file, dst, rep, null);
    }
}
