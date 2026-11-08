package com.litmaspatra.docos.ooxml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/** Excel workbook: streaming read of sheets, cell edits patched into the sheet XML on save. */
public final class Xlsx {
    static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    public static final int MAX_CELLS = 400000;

    public static final class Cell {
        public String disp = "", raw = "", formula;
        public boolean num;
    }

    public static final class Sheet {
        public String name, path;
        public final HashMap<Long, Cell> cells = new HashMap<>();
        public final HashMap<Integer, Float> colW = new HashMap<>();      // chars
        public final HashMap<Integer, Float> rowH = new HashMap<>();      // points
        public final Set<Integer> hiddenCols = new HashSet<>(), hiddenRows = new HashSet<>();
        public int maxRow = -1, maxCol = -1;
        public boolean truncated, loaded;
        final Map<String, String> edits = new LinkedHashMap<>();

        public Cell get(int r, int c) { return cells.get(key(r, c)); }
    }

    public static long key(int r, int c) { return ((long) r << 20) | c; }

    private final File file;
    public final List<Sheet> sheets = new ArrayList<>();
    private String[] shared = new String[0];
    private int[] xfFmt = new int[0];
    private final Map<Integer, String> fmtCodes = new HashMap<>();
    private boolean date1904;
    public boolean modified;

    private Xlsx(File f) { this.file = f; }

    public static Xlsx open(File f) throws Exception {
        Xlsx x = new Xlsx(f);
        ZipFile zf = new ZipFile(f);
        try {
            byte[] wb = Zip.read(zf, "xl/workbook.xml");
            if (wb == null) throw new IOException("not an Excel workbook");
            Document wd = Xml.parse(wb);
            Element root = wd.getDocumentElement();
            Element wpr = Xml.kid(root, MAIN, "workbookPr");
            x.date1904 = wpr != null && "1".equals(Xml.attr(wpr, null, "date1904"));
            Map<String, String> rels = new HashMap<>();
            byte[] rb = Zip.read(zf, "xl/_rels/workbook.xml.rels");
            if (rb != null) for (Element r : Xml.kids(Xml.parse(rb).getDocumentElement(), null, "Relationship")) rels.put(r.getAttribute("Id"), r.getAttribute("Target"));
            Element sh = Xml.kid(root, MAIN, "sheets");
            for (Element s : Xml.kids(sh, MAIN, "sheet")) {
                Sheet sheet = new Sheet();
                sheet.name = Xml.attr(s, null, "name");
                String t = rels.get(Xml.attr(s, R, "id"));
                if (t == null) continue;
                sheet.path = t.startsWith("/") ? t.substring(1) : "xl/" + t;
                if (zf.getEntry(sheet.path) == null) continue;
                x.sheets.add(sheet);
            }
            if (x.sheets.isEmpty()) throw new IOException("no worksheets");
            x.loadStyles(Zip.read(zf, "xl/styles.xml"));
            x.loadShared(zf);
        } finally { zf.close(); }
        x.load(x.sheets.get(0));
        return x;
    }

    private static SAXParser sax() throws Exception {
        SAXParserFactory f = SAXParserFactory.newInstance();
        f.setNamespaceAware(true);
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) { }
        return f.newSAXParser();
    }

    private void loadStyles(byte[] b) {
        if (b == null) return;
        try {
            Element root = Xml.parse(b).getDocumentElement();
            for (Element nf : Xml.kids(Xml.kid(root, MAIN, "numFmts"), MAIN, "numFmt"))
                fmtCodes.put(Xml.intAttr(nf, null, "numFmtId", 0), Xml.attr(nf, null, "formatCode"));
            List<Element> xfs = Xml.kids(Xml.kid(root, MAIN, "cellXfs"), MAIN, "xf");
            xfFmt = new int[xfs.size()];
            for (int i = 0; i < xfFmt.length; i++) xfFmt[i] = Xml.intAttr(xfs.get(i), null, "numFmtId", 0);
        } catch (Exception ignored) { }
    }

    private void loadShared(ZipFile zf) throws Exception {
        ZipEntry e = zf.getEntry("xl/sharedStrings.xml");
        if (e == null) return;
        final List<String> out = new ArrayList<>();
        InputStream in = zf.getInputStream(e);
        try {
            sax().parse(in, new DefaultHandler() {
                StringBuilder sb; boolean inT; int phonetic;
                @Override public void startElement(String u, String l, String q, Attributes a) {
                    if (l.equals("si")) sb = new StringBuilder();
                    else if (l.equals("rPh")) phonetic++;
                    else if (l.equals("t") && phonetic == 0) inT = true;
                }
                @Override public void characters(char[] ch, int s, int n) { if (inT && sb != null) sb.append(ch, s, n); }
                @Override public void endElement(String u, String l, String q) {
                    if (l.equals("t")) inT = false;
                    else if (l.equals("rPh")) phonetic--;
                    else if (l.equals("si")) { out.add(sb == null ? "" : sb.toString()); sb = null; }
                }
            });
        } finally { in.close(); }
        shared = out.toArray(new String[0]);
    }

    // ------------------------------------------------------------------ sheet loading
    public void load(final Sheet sh) throws Exception {
        if (sh.loaded) return;
        ZipFile zf = new ZipFile(file);
        try {
            ZipEntry e = zf.getEntry(sh.path);
            InputStream in = zf.getInputStream(e);
            try {
                sax().parse(in, new DefaultHandler() {
                    int row = -1, col = -1, nextCol = 0, xf;
                    String type;
                    StringBuilder v = new StringBuilder(), f = new StringBuilder(), is = new StringBuilder();
                    boolean inV, inF, inIs, inT, hasF, inCell;
                    int phonetic;

                    @Override public void startElement(String u, String l, String q, Attributes a) {
                        switch (l) {
                            case "col": {
                                int min = i(a.getValue("min"), 1) - 1, max = Math.min(i(a.getValue("max"), min + 1) - 1, min + 2000);
                                float w = fl(a.getValue("width"), 8.43f);
                                boolean hid = "1".equals(a.getValue("hidden"));
                                for (int c = min; c <= max; c++) { sh.colW.put(c, w); if (hid) sh.hiddenCols.add(c); }
                                break;
                            }
                            case "row": {
                                row = i(a.getValue("r"), row + 2) - 1;
                                nextCol = 0;
                                String ht = a.getValue("ht");
                                if (ht != null) sh.rowH.put(row, fl(ht, 15f));
                                if ("1".equals(a.getValue("hidden"))) sh.hiddenRows.add(row);
                                break;
                            }
                            case "c": {
                                String r = a.getValue("r");
                                col = r != null ? colOf(r) : nextCol;
                                nextCol = col + 1;
                                type = a.getValue("t");
                                xf = i(a.getValue("s"), 0);
                                v.setLength(0); f.setLength(0); is.setLength(0);
                                hasF = false; inCell = true;
                                break;
                            }
                            case "v": inV = true; break;
                            case "f": inF = true; hasF = true; break;
                            case "is": inIs = true; break;
                            case "rPh": phonetic++; break;
                            case "t": if (inIs && phonetic == 0) inT = true; break;
                            default: break;
                        }
                    }

                    @Override public void characters(char[] ch, int s, int n) {
                        if (inV) v.append(ch, s, n);
                        else if (inF) f.append(ch, s, n);
                        else if (inT) is.append(ch, s, n);
                    }

                    @Override public void endElement(String u, String l, String q) {
                        switch (l) {
                            case "v": inV = false; break;
                            case "f": inF = false; break;
                            case "t": inT = false; break;
                            case "rPh": phonetic--; break;
                            case "is": inIs = false; break;
                            case "c": inCell = false; finishCell(); break;
                            default: break;
                        }
                    }

                    void finishCell() {
                        if (row < 0 || col < 0) return;
                        if (sh.cells.size() >= MAX_CELLS) { sh.truncated = true; return; }
                        String raw = v.toString();
                        Cell c = new Cell();
                        c.formula = hasF && f.length() > 0 ? f.toString() : null;
                        if ("s".equals(type)) {
                            int k = i(raw.trim(), -1);
                            c.raw = c.disp = k >= 0 && k < shared.length ? shared[k] : "";
                        } else if ("inlineStr".equals(type)) {
                            c.raw = c.disp = is.toString();
                        } else if ("str".equals(type) || "e".equals(type)) {
                            c.raw = c.disp = raw;
                        } else if ("b".equals(type)) {
                            c.raw = raw; c.disp = raw.trim().equals("1") ? "TRUE" : "FALSE";
                        } else {
                            c.raw = raw; c.num = true;
                            c.disp = raw.isEmpty() ? "" : format(raw, xf);
                        }
                        if (c.disp.isEmpty() && c.formula == null && c.raw.isEmpty()) return;
                        if (c.disp.isEmpty() && c.formula != null) c.disp = "=" + c.formula;
                        sh.cells.put(key(row, col), c);
                        if (row > sh.maxRow) sh.maxRow = row;
                        if (col > sh.maxCol) sh.maxCol = col;
                    }
                });
            } finally { in.close(); }
        } finally { zf.close(); }
        sh.loaded = true;
    }

    private static int i(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static float fl(String s, float def) {
        if (s == null) return def;
        try { return Float.parseFloat(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    public static int colOf(String ref) {
        int c = 0, k = 0;
        while (k < ref.length() && Character.isLetter(ref.charAt(k))) { c = c * 26 + (Character.toUpperCase(ref.charAt(k)) - 'A' + 1); k++; }
        return c - 1;
    }

    public static int rowOf(String ref) {
        int k = 0;
        while (k < ref.length() && Character.isLetter(ref.charAt(k))) k++;
        try { return Integer.parseInt(ref.substring(k)) - 1; } catch (NumberFormatException e) { return -1; }
    }

    public static String colName(int c) {
        StringBuilder sb = new StringBuilder();
        c++;
        while (c > 0) { sb.insert(0, (char) ('A' + (c - 1) % 26)); c = (c - 1) / 26; }
        return sb.toString();
    }

    public static String ref(int r, int c) { return colName(c) + (r + 1); }

    // ------------------------------------------------------------------ number formatting
    private String format(String raw, int xf) {
        try {
            int id = xf >= 0 && xf < xfFmt.length ? xfFmt[xf] : 0;
            String code = fmtCodes.get(id);
            double d = Double.parseDouble(raw);
            if (id == 0 && code == null) return general(raw);
            boolean dateLike = (id >= 14 && id <= 22) || (id >= 45 && id <= 47);
            if (code != null) {
                String c = code.replaceAll("\"[^\"]*\"", "").replaceAll("\\[[^\\]]*\\]", "").replace("\\", "");
                if (c.matches("(?i).*[ymdhs].*") && !c.equalsIgnoreCase("general")) dateLike = true;
            }
            if (dateLike) return date(d, code == null ? id : -1, code);
            if (id == 9) return String.format(Locale.US, "%.0f%%", d * 100);
            if (id == 10) return String.format(Locale.US, "%.2f%%", d * 100);
            int dec = -1; boolean thou = false, pct = false;
            if (code == null) {
                if (id == 1) dec = 0; else if (id == 2) dec = 2; else if (id == 3) { dec = 0; thou = true; } else if (id == 4) { dec = 2; thou = true; }
            } else {
                String sec = code.split(";")[0];
                String core = sec.replaceAll("\"[^\"]*\"", "").replaceAll("\\[[^\\]]*\\]", "");
                if (core.matches(".*[0#?].*") && !core.toLowerCase(Locale.US).contains("general")) {
                    pct = core.contains("%");
                    thou = core.matches(".*[0#],[0#].*");
                    int dot = core.indexOf('.');
                    dec = 0;
                    if (dot >= 0) for (int k = dot + 1; k < core.length() && (core.charAt(k) == '0' || core.charAt(k) == '#' || core.charAt(k) == '?'); k++) dec++;
                }
            }
            if (dec < 0) return general(raw);
            double val = pct ? d * 100 : d;
            String s = String.format(Locale.US, thou ? "%,." + dec + "f" : "%." + dec + "f", val);
            if (pct) s += "%";
            return s;
        } catch (RuntimeException e) { return general(raw); }
    }

    private static String general(String raw) {
        try {
            BigDecimal b = new BigDecimal(raw.trim());
            if (b.precision() > 11) b = b.round(new MathContext(11));
            String s = b.stripTrailingZeros().toPlainString();
            return s;
        } catch (RuntimeException e) { return raw; }
    }

    private String date(double serial, int id, String code) {
        long days = (long) Math.floor(serial);
        double frac = serial - days;
        boolean hasDate, hasTime;
        if (code == null) { hasDate = id != 18 && id != 19 && id != 20 && id != 21 && id != 45 && id != 46 && id != 47; hasTime = id == 18 || id == 19 || id == 20 || id == 21 || id == 22 || id == 45 || id == 46 || id == 47; }
        else {
            String c = code.replaceAll("\"[^\"]*\"", "").replaceAll("\\[[^\\]]*\\]", "").toLowerCase(Locale.US);
            hasDate = c.contains("y") || c.contains("d") || c.matches(".*m.*") && !c.contains("h") && !c.contains("s");
            hasTime = c.contains("h") || c.contains("s");
        }
        StringBuilder sb = new StringBuilder();
        if (hasDate) {
            long z = days - (date1904 ? 24107 : 25569);
            z += 719468;
            long era = (z >= 0 ? z : z - 146096) / 146097;
            long doe = z - era * 146097;
            long yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
            long y = yoe + era * 400;
            long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
            long mp = (5 * doy + 2) / 153;
            long dd = doy - (153 * mp + 2) / 5 + 1;
            long mm = mp < 10 ? mp + 3 : mp - 9;
            if (mm <= 2) y++;
            sb.append(String.format(Locale.US, "%04d-%02d-%02d", y, mm, dd));
        }
        if (hasTime) {
            long secs = Math.round(frac * 86400);
            if (hasDate) sb.append(' ');
            sb.append(String.format(Locale.US, "%02d:%02d:%02d", secs / 3600 % 24, secs / 60 % 60, secs % 60));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ editing
    private static final java.util.regex.Pattern NUMBER = java.util.regex.Pattern.compile("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?");

    public void setCell(Sheet s, int r, int c, String text) {
        String t = text == null ? "" : text;
        String trimmed = t.trim();
        String ref = ref(r, c);
        if (trimmed.isEmpty()) {
            s.cells.remove(key(r, c));
            s.edits.put(ref, "");
        } else {
            Cell cell = new Cell();
            if (trimmed.startsWith("=") && trimmed.length() > 1) {
                cell.formula = trimmed.substring(1); cell.raw = ""; cell.disp = trimmed; cell.num = false;
            } else if (NUMBER.matcher(trimmed).matches()) {
                cell.raw = trimmed; cell.num = true; cell.disp = general(trimmed);
            } else { cell.raw = t; cell.disp = t; }
            s.cells.put(key(r, c), cell);
            s.edits.put(ref, cell.formula != null ? "=" + cell.formula : cell.num ? trimmed : t);
            if (r > s.maxRow) s.maxRow = r;
            if (c > s.maxCol) s.maxCol = c;
        }
        modified = true;
    }

    /** Text shown in the editor for a cell. */
    public static String editText(Cell c) {
        if (c == null) return "";
        if (c.formula != null) return "=" + c.formula;
        return c.raw;
    }

    // ------------------------------------------------------------------ saving
    public void save(File dst) throws Exception {
        Map<String, byte[]> rep = new LinkedHashMap<>();
        Set<String> remove = new HashSet<>();
        boolean any = false;
        for (Sheet s : sheets) {
            if (s.edits.isEmpty()) continue;
            any = true;
            Document d = Xml.parse(Zip.read(file, s.path));
            patchSheet(d, s.edits);
            rep.put(s.path, Xml.write(d));
        }
        if (any) {
            // formulas elsewhere must be recalculated, and the cached calc chain is no longer valid
            Document wb = Xml.parse(Zip.read(file, "xl/workbook.xml"));
            Element root = wb.getDocumentElement();
            Element calc = Xml.kid(root, MAIN, "calcPr");
            if (calc == null) {
                calc = Xml.create(root, MAIN, "calcPr");
                Element after = Xml.kid(root, MAIN, "definedNames");
                if (after == null) after = Xml.kid(root, MAIN, "sheets");
                if (after != null && after.getNextSibling() != null) root.insertBefore(calc, after.getNextSibling()); else root.appendChild(calc);
            }
            calc.setAttribute("fullCalcOnLoad", "1");
            rep.put("xl/workbook.xml", Xml.write(wb));
            ZipFile zf = new ZipFile(file);
            try {
                if (zf.getEntry("xl/calcChain.xml") != null) {
                    remove.add("xl/calcChain.xml");
                    byte[] ct = Zip.read(zf, "[Content_Types].xml");
                    if (ct != null) {
                        Document cd = Xml.parse(ct);
                        for (Element o : Xml.kids(cd.getDocumentElement(), null, "Override"))
                            if (o.getAttribute("PartName").endsWith("calcChain.xml")) cd.getDocumentElement().removeChild(o);
                        rep.put("[Content_Types].xml", Xml.write(cd));
                    }
                    byte[] rl = Zip.read(zf, "xl/_rels/workbook.xml.rels");
                    if (rl != null) {
                        Document rd = Xml.parse(rl);
                        for (Element o : Xml.kids(rd.getDocumentElement(), null, "Relationship"))
                            if (o.getAttribute("Target").endsWith("calcChain.xml")) rd.getDocumentElement().removeChild(o);
                        rep.put("xl/_rels/workbook.xml.rels", Xml.write(rd));
                    }
                }
            } finally { zf.close(); }
        }
        Zip.rewrite(file, dst, rep, remove);
    }

    static void patchSheet(Document d, Map<String, String> edits) {
        Element root = d.getDocumentElement();
        String ns = root.getNamespaceURI();
        Element sd = Xml.kid(root, ns, "sheetData");
        if (sd == null) {
            sd = Xml.create(root, ns, "sheetData");
            root.appendChild(sd);
        }
        TreeMap<Integer, Element> rows = new TreeMap<>();
        for (Element r : Xml.kids(sd, ns, "row")) {
            int n = i(r.getAttribute("r"), -1);
            if (n > 0) rows.put(n, r);
        }
        for (Map.Entry<String, String> en : edits.entrySet()) {
            String ref = en.getKey();
            int col = colOf(ref), rowN = rowOf(ref) + 1;
            Element row = rows.get(rowN);
            if (row == null) {
                row = Xml.create(root, ns, "row");
                row.setAttribute("r", String.valueOf(rowN));
                Map.Entry<Integer, Element> hi = rows.higherEntry(rowN);
                if (hi != null) sd.insertBefore(row, hi.getValue()); else sd.appendChild(row);
                rows.put(rowN, row);
            }
            row.removeAttribute("spans");
            Element cell = null, before = null;
            for (Element c : Xml.kids(row, ns, "c")) {
                int cc = colOf(c.getAttribute("r"));
                if (cc == col) { cell = c; break; }
                if (cc > col && before == null) before = c;
            }
            if (cell == null) {
                cell = Xml.create(root, ns, "c");
                cell.setAttribute("r", ref);
                if (before != null) row.insertBefore(cell, before); else row.appendChild(cell);
            }
            while (cell.getFirstChild() != null) cell.removeChild(cell.getFirstChild());
            cell.removeAttribute("t");
            String v = en.getValue();
            if (v.isEmpty()) continue;
            if (v.startsWith("=")) {
                Element f = Xml.create(root, ns, "f");
                f.setTextContent(v.substring(1));
                cell.appendChild(f);
            } else if (NUMBER.matcher(v.trim()).matches()) {
                Element ve = Xml.create(root, ns, "v");
                ve.setTextContent(v.trim());
                cell.appendChild(ve);
            } else {
                cell.setAttribute("t", "inlineStr");
                Element is = Xml.create(root, ns, "is"), t = Xml.create(root, ns, "t");
                t.setAttributeNS(Xml.XML_NS, "xml:space", "preserve");
                t.setTextContent(v);
                is.appendChild(t);
                cell.appendChild(is);
            }
        }
    }
}
