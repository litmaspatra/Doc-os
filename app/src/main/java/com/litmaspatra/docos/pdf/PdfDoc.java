package com.litmaspatra.docos.pdf;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Minimal PDF reader and incremental-update writer. It understands just enough of the file
 * structure (xref tables/streams, object streams, the page tree) to add or remove
 * annotations without touching the rest of the file.
 */
public final class PdfDoc implements Closeable {

    // ------------------------------------------------------------------ object model
    public static final class Name {
        public final String v;
        public Name(String v) { this.v = v; }
        @Override public boolean equals(Object o) { return o instanceof Name && ((Name) o).v.equals(v); }
        @Override public int hashCode() { return v.hashCode(); }
        @Override public String toString() { return "/" + v; }
    }

    public static final class Ref {
        public final int num, gen;
        public Ref(int num, int gen) { this.num = num; this.gen = gen; }
        @Override public boolean equals(Object o) { return o instanceof Ref && ((Ref) o).num == num && ((Ref) o).gen == gen; }
        @Override public int hashCode() { return num * 31 + gen; }
        @Override public String toString() { return num + " " + gen + " R"; }
    }

    public static final class Str {
        public final byte[] b;
        public Str(byte[] b) { this.b = b; }

        /** PDF text string: ASCII as-is, anything else as UTF-16BE with BOM. */
        public static Str of(String s) {
            boolean ascii = true;
            for (int i = 0; i < s.length(); i++) if (s.charAt(i) > 0x7e || s.charAt(i) < 0x20) { ascii = false; break; }
            try {
                if (ascii) return new Str(s.getBytes("ISO-8859-1"));
                byte[] u = s.getBytes("UTF-16BE");
                byte[] r = new byte[u.length + 2];
                r[0] = (byte) 0xFE; r[1] = (byte) 0xFF;
                System.arraycopy(u, 0, r, 2, u.length);
                return new Str(r);
            } catch (java.io.UnsupportedEncodingException e) { throw new IllegalStateException(e); }
        }

        public String text() {
            try {
                if (b.length >= 2 && (b[0] & 0xff) == 0xFE && (b[1] & 0xff) == 0xFF) return new String(b, 2, b.length - 2, "UTF-16BE");
                if (b.length >= 3 && (b[0] & 0xff) == 0xEF && (b[1] & 0xff) == 0xBB && (b[2] & 0xff) == 0xBF) return new String(b, 3, b.length - 3, "UTF-8");
                return new String(b, "ISO-8859-1");
            } catch (java.io.UnsupportedEncodingException e) { return ""; }
        }
    }

    public static final class Stream {
        public final Map<String, Object> dict;
        final long off;      // file offset of the data (when read from the file)
        final byte[] mem;    // data (when created in memory)
        Stream(Map<String, Object> dict, long off, byte[] mem) { this.dict = dict; this.off = off; this.mem = mem; }
    }

    static final class Kw { final String s; Kw(String s) { this.s = s; } }

    public static final class Unsupported extends IOException {
        public Unsupported(String m) { super(m); }
    }

    private static final class NeedMore extends RuntimeException { NeedMore() { super("need more data"); } }
    private static final NeedMore NEED = new NeedMore();

    private static final class XEntry {
        final int type; final long a; final int b;
        XEntry(int type, long a, int b) { this.type = type; this.a = a; this.b = b; }
    }

    public interface UpdateFn { void apply(Update u) throws IOException; }

    // ------------------------------------------------------------------ state
    private final RandomAccessFile raf;
    private final long length;
    private final Map<Integer, XEntry> xref = new HashMap<>();
    private final Map<Integer, Object> cache = new HashMap<>();
    private final Map<Integer, Object[]> objStmCache = new HashMap<>();
    private Map<String, Object> trailer;
    private boolean xrefIsStream;
    private long startXref;
    private List<Ref> pages;

    public PdfDoc(File f) throws IOException {
        raf = new RandomAccessFile(f, "r");
        length = raf.length();
        try {
            readXrefChain();
            if (trailer == null || !(trailer.get("Root") instanceof Ref)) throw new Unsupported("no catalog");
        } catch (IOException e) { close(); throw e; }
          catch (RuntimeException e) { close(); throw new Unsupported("damaged file: " + e); }
    }

    @Override public void close() { try { raf.close(); } catch (IOException ignored) { } }

    public boolean encrypted() { return trailer.get("Encrypt") != null; }

    // ------------------------------------------------------------------ lexer
    private static boolean isWs(int c) { return c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32; }
    private static boolean isDelim(int c) {
        return c == '(' || c == ')' || c == '<' || c == '>' || c == '[' || c == ']' || c == '{' || c == '}' || c == '/' || c == '%';
    }

    private static final class Lx {
        final byte[] b; int p; final boolean atEnd;
        Lx(byte[] b, int p, boolean atEnd) { this.b = b; this.p = p; this.atEnd = atEnd; }

        void need() { if (!atEnd) throw NEED; }

        int peek() { if (p >= b.length) { need(); return -1; } return b[p] & 0xff; }

        void ws() {
            while (true) {
                int c = peek();
                if (c < 0) return;
                if (isWs(c)) p++;
                else if (c == '%') { while (p < b.length && b[p] != '\n' && b[p] != '\r') p++; if (p >= b.length) { need(); return; } }
                else return;
            }
        }

        boolean starts(String s) {
            if (p + s.length() > b.length) { need(); return false; }
            for (int i = 0; i < s.length(); i++) if (b[p + i] != s.charAt(i)) return false;
            return true;
        }

        Object obj() {
            ws();
            int c = peek();
            if (c < 0) throw new IllegalStateException("unexpected end");
            switch (c) {
                case '/': return name();
                case '(': return lit();
                case '[': {
                    p++;
                    List<Object> l = new ArrayList<>();
                    while (true) {
                        ws();
                        int d = peek();
                        if (d < 0) throw new IllegalStateException("unterminated array");
                        if (d == ']') { p++; break; }
                        Object o = obj();
                        if (!(o instanceof Kw)) l.add(o);
                    }
                    return l;
                }
                case '<':
                    if (p + 1 >= b.length) { need(); throw new IllegalStateException("eof"); }
                    if (b[p + 1] == '<') { p += 2; return dict(); }
                    return hex();
                case '+': case '-': case '.': case '0': case '1': case '2': case '3': case '4':
                case '5': case '6': case '7': case '8': case '9':
                    return num();
                default: return kw();
            }
        }

        Map<String, Object> dict() {
            Map<String, Object> m = new LinkedHashMap<>();
            while (true) {
                ws();
                int c = peek();
                if (c < 0) throw new IllegalStateException("unterminated dict");
                if (c == '>') { if (p + 1 >= b.length) need(); p += 2; break; }
                Object k = obj();
                if (!(k instanceof Name)) continue;
                ws();
                if (peek() == '>') continue;
                Object v = obj();
                if (v instanceof Kw) continue;
                m.put(((Name) k).v, v);
            }
            return m;
        }

        Name name() {
            p++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (p >= b.length) { need(); break; }
                int c = b[p] & 0xff;
                if (isWs(c) || isDelim(c)) break;
                if (c == '#' && p + 2 < b.length && hexv(b[p + 1]) >= 0 && hexv(b[p + 2]) >= 0) {
                    sb.append((char) (hexv(b[p + 1]) * 16 + hexv(b[p + 2]))); p += 3;
                } else { sb.append((char) c); p++; }
            }
            return new Name(sb.toString());
        }

        Str lit() {
            p++;
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            int depth = 1;
            while (true) {
                if (p >= b.length) { need(); break; }
                int c = b[p++] & 0xff;
                if (c == '\\') {
                    if (p >= b.length) { need(); break; }
                    int d = b[p++] & 0xff;
                    switch (d) {
                        case 'n': o.write('\n'); break;
                        case 'r': o.write('\r'); break;
                        case 't': o.write('\t'); break;
                        case 'b': o.write('\b'); break;
                        case 'f': o.write('\f'); break;
                        case '\r': if (p < b.length && b[p] == '\n') p++; break;
                        case '\n': break;
                        default:
                            if (d >= '0' && d <= '7') {
                                int v = d - '0';
                                for (int i = 0; i < 2 && p < b.length && b[p] >= '0' && b[p] <= '7'; i++) v = v * 8 + (b[p++] - '0');
                                o.write(v & 0xff);
                            } else o.write(d);
                    }
                } else if (c == '(') { depth++; o.write(c); }
                else if (c == ')') { if (--depth == 0) break; o.write(c); }
                else o.write(c);
            }
            return new Str(o.toByteArray());
        }

        Str hex() {
            p++;
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            int hi = -1;
            while (true) {
                if (p >= b.length) { need(); break; }
                int c = b[p++] & 0xff;
                if (c == '>') break;
                int v = hexv(c);
                if (v < 0) continue;
                if (hi < 0) hi = v; else { o.write(hi * 16 + v); hi = -1; }
            }
            if (hi >= 0) o.write(hi * 16);
            return new Str(o.toByteArray());
        }

        Object num() {
            int s = p;
            if (b[p] == '+' || b[p] == '-') p++;
            boolean dot = false;
            while (p < b.length) {
                int c = b[p];
                if (c >= '0' && c <= '9') p++;
                else if (c == '.' && !dot) { dot = true; p++; }
                else break;
            }
            if (p >= b.length) need();
            String t = ascii(b, s, p - s);
            if (dot || t.equals("+") || t.equals("-")) {
                try { return Double.valueOf(t.equals("+") || t.equals("-") ? "0" : t); } catch (NumberFormatException e) { return 0.0; }
            }
            long v;
            try { v = Long.parseLong(t.startsWith("+") ? t.substring(1) : t); } catch (NumberFormatException e) { v = 0; }
            if (v >= 0 && !t.startsWith("-") && !t.startsWith("+")) {
                int save = p;
                ws();
                int q = p;
                while (q < b.length && b[q] >= '0' && b[q] <= '9') q++;
                if (q == b.length && q > p) need();
                if (q > p && q - p < 11) {
                    long g = Long.parseLong(ascii(b, p, q - p));
                    p = q;
                    ws();
                    if (p < b.length && b[p] == 'R' && (p + 1 >= b.length || isWs(b[p + 1] & 0xff) || isDelim(b[p + 1] & 0xff))) {
                        p++;
                        return new Ref((int) v, (int) g);
                    }
                    if (p >= b.length) need();
                }
                p = save;
            }
            return v;
        }

        Kw kw() {
            int s = p;
            while (p < b.length && !isWs(b[p] & 0xff) && !isDelim(b[p] & 0xff)) p++;
            if (p >= b.length) need();
            if (p == s) { p++; return new Kw(""); }
            return new Kw(ascii(b, s, p - s));
        }
    }

    private static int hexv(int c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private static String ascii(byte[] b, int off, int len) {
        char[] c = new char[len];
        for (int i = 0; i < len; i++) c[i] = (char) (b[off + i] & 0xff);
        return new String(c);
    }

    // ------------------------------------------------------------------ file access
    private byte[] readAt(long off, int n) throws IOException {
        byte[] b = new byte[n];
        raf.seek(off);
        raf.readFully(b);
        return b;
    }

    private Object parseIndirect(long off, int expectNum) throws IOException {
        int size = 4096;
        while (true) {
            long avail = length - off;
            if (off < 0 || avail <= 0) throw new IOException("bad object offset " + off);
            int n = (int) Math.min(size, avail);
            byte[] buf = readAt(off, n);
            boolean atEnd = n >= avail;
            try {
                Lx l = new Lx(buf, 0, atEnd);
                Object a = l.obj(), g = l.obj(), k = l.obj();
                if (!(a instanceof Long) || !(g instanceof Long) || !(k instanceof Kw) || !((Kw) k).s.equals("obj"))
                    throw new IOException("bad object header at " + off);
                if (expectNum >= 0 && ((Long) a).intValue() != expectNum) throw new IOException("object number mismatch at " + off);
                Object v = l.obj();
                if (v instanceof Map) {
                    @SuppressWarnings("unchecked") Map<String, Object> d = (Map<String, Object>) v;
                    l.ws();
                    if (l.starts("stream")) {
                        int q = l.p + 6;
                        if (q >= buf.length) l.need();
                        if (q < buf.length && buf[q] == '\r') { q++; if (q < buf.length && buf[q] == '\n') q++; }
                        else if (q < buf.length && buf[q] == '\n') q++;
                        return new Stream(d, off + q, null);
                    }
                }
                return v;
            } catch (NeedMore e) {
                if (atEnd) throw new IOException("truncated object at " + off);
                size *= 4;
            } catch (IllegalStateException e) {
                throw new IOException("unreadable object at " + off + ": " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ xref
    private void readXrefChain() throws IOException {
        int n = (int) Math.min(2048, length);
        byte[] tail = readAt(length - n, n);
        String t = ascii(tail, 0, n);
        int i = t.lastIndexOf("startxref");
        if (i < 0) throw new Unsupported("no startxref");
        long off;
        try {
            String num = t.substring(i + 9).trim().split("\\s+")[0];
            off = Long.parseLong(num);
        } catch (RuntimeException e) { throw new Unsupported("bad startxref"); }
        startXref = off;
        Set<Long> seen = new HashSet<>();
        boolean first = true;
        while (off >= 0 && seen.add(off)) {
            Map<String, Object> tr = readSection(off, first);
            if (first) { trailer = tr; first = false; }
            Object xs = tr.get("XRefStm");
            if (xs instanceof Long) { try { readSection((Long) xs, false); } catch (IOException ignored) { } }
            Object prev = tr.get("Prev");
            off = prev instanceof Number ? ((Number) prev).longValue() : -1;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readSection(long off, boolean first) throws IOException {
        int size = 1 << 16;
        while (true) {
            long avail = length - off;
            if (off < 0 || avail <= 0) throw new IOException("bad xref offset");
            int n = (int) Math.min(size, avail);
            byte[] buf = readAt(off, n);
            boolean atEnd = n >= avail;
            try {
                Lx l = new Lx(buf, 0, atEnd);
                l.ws();
                if (l.starts("xref")) {
                    l.p += 4;
                    while (true) {
                        l.ws();
                        if (l.starts("trailer")) {
                            l.p += 7;
                            Object tr = l.obj();
                            if (!(tr instanceof Map)) throw new IOException("bad trailer");
                            if (first) xrefIsStream = false;
                            return (Map<String, Object>) tr;
                        }
                        Object a = l.obj(), c = l.obj();
                        if (!(a instanceof Long) || !(c instanceof Long)) throw new IOException("bad xref subsection");
                        int start = ((Long) a).intValue(), count = ((Long) c).intValue();
                        for (int i = 0; i < count; i++) {
                            Object o1 = l.obj(), o2 = l.obj(), o3 = l.obj();
                            if (!(o1 instanceof Long) || !(o2 instanceof Long) || !(o3 instanceof Kw)) throw new IOException("bad xref entry");
                            int num = start + i;
                            if (!xref.containsKey(num)) {
                                boolean inUse = ((Kw) o3).s.startsWith("n");
                                xref.put(num, inUse ? new XEntry(1, (Long) o1, ((Long) o2).intValue()) : new XEntry(0, 0, 0));
                            }
                        }
                    }
                }
            } catch (NeedMore e) {
                if (atEnd) throw new IOException("truncated xref");
                size *= 4;
                continue;
            } catch (IllegalStateException e) {
                throw new IOException("unreadable xref: " + e.getMessage());
            }
            // cross-reference stream
            Object o = parseIndirect(off, -1);
            if (!(o instanceof Stream)) throw new IOException("bad xref stream");
            Stream s = (Stream) o;
            Map<String, Object> d = s.dict;
            byte[] data = decode(s);
            List<Object> wl = (List<Object>) d.get("W");
            if (wl == null || wl.size() < 3) throw new IOException("bad xref /W");
            int[] w = new int[3];
            for (int i = 0; i < 3; i++) w[i] = ((Number) wl.get(i)).intValue();
            List<Object> idx = (List<Object>) d.get("Index");
            if (idx == null) { idx = new ArrayList<>(); idx.add(0L); idx.add(d.get("Size")); }
            int rowLen = w[0] + w[1] + w[2];
            int pos = 0;
            for (int k = 0; k + 1 < idx.size(); k += 2) {
                int start = ((Number) idx.get(k)).intValue(), count = ((Number) idx.get(k + 1)).intValue();
                for (int i = 0; i < count && pos + rowLen <= data.length; i++, pos += rowLen) {
                    long type = w[0] == 0 ? 1 : be(data, pos, w[0]);
                    long f2 = be(data, pos + w[0], w[1]);
                    long f3 = be(data, pos + w[0] + w[1], w[2]);
                    int num = start + i;
                    if (!xref.containsKey(num)) xref.put(num, new XEntry((int) type, f2, (int) f3));
                }
            }
            if (first) xrefIsStream = true;
            return d;
        }
    }

    private static long be(byte[] b, int off, int n) {
        long v = 0;
        for (int i = 0; i < n; i++) v = (v << 8) | (b[off + i] & 0xff);
        return v;
    }

    // ------------------------------------------------------------------ streams
    private byte[] rawBytes(Stream s) throws IOException {
        if (s.mem != null) return s.mem;
        Object lo = s.dict.get("Length");
        if (lo instanceof Ref) lo = load(((Ref) lo).num);
        long len = lo instanceof Number ? ((Number) lo).longValue() : -1;
        if (len < 0 || s.off + len > length) {
            // fall back to scanning for "endstream"
            long end = Math.min(length, s.off + (64L << 20));
            byte[] b = readAt(s.off, (int) (end - s.off));
            String t = ascii(b, 0, b.length);
            int i = t.indexOf("endstream");
            if (i < 0) throw new IOException("stream without end");
            len = i;
            while (len > 0 && (b[(int) len - 1] == '\n' || b[(int) len - 1] == '\r')) len--;
        }
        if (len > Integer.MAX_VALUE - 16) throw new Unsupported("stream too large");
        return readAt(s.off, (int) len);
    }

    @SuppressWarnings("unchecked")
    public byte[] decode(Stream s) throws IOException {
        byte[] data = rawBytes(s);
        Object f = res(s.dict.get("Filter"));
        if (f == null) return data;
        List<Object> filters = f instanceof List ? (List<Object>) f : java.util.Collections.singletonList(f);
        Object p = res(s.dict.get("DecodeParms"));
        if (p == null) p = res(s.dict.get("DP"));
        for (int i = 0; i < filters.size(); i++) {
            Object fo = res(filters.get(i));
            String nm = fo instanceof Name ? ((Name) fo).v : "";
            Object po = p instanceof List ? (i < ((List<Object>) p).size() ? res(((List<Object>) p).get(i)) : null) : p;
            if (nm.equals("FlateDecode") || nm.equals("Fl")) {
                data = predictor(inflate(data), po instanceof Map ? (Map<String, Object>) po : null);
            } else throw new Unsupported("stream filter " + nm);
        }
        return data;
    }

    private static byte[] inflate(byte[] in) throws IOException {
        Inflater inf = new Inflater();
        inf.setInput(in);
        ByteArrayOutputStream o = new ByteArrayOutputStream(Math.max(64, in.length * 3));
        byte[] buf = new byte[8192];
        try {
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                o.write(buf, 0, n);
            }
        } catch (DataFormatException e) {
            if (o.size() == 0) throw new IOException("bad flate data");
        } finally { inf.end(); }
        return o.toByteArray();
    }

    private static int intOf(Map<String, Object> m, String k, int def) {
        Object v = m == null ? null : m.get(k);
        return v instanceof Number ? ((Number) v).intValue() : def;
    }

    private static byte[] predictor(byte[] d, Map<String, Object> p) throws IOException {
        int pred = intOf(p, "Predictor", 1);
        if (pred < 10) { if (pred == 1) return d; throw new Unsupported("predictor " + pred); }
        int colors = intOf(p, "Colors", 1), bpc = intOf(p, "BitsPerComponent", 8), cols = intOf(p, "Columns", 1);
        int bpp = Math.max(1, (colors * bpc + 7) / 8);
        int row = (cols * colors * bpc + 7) / 8;
        int rows = d.length / (row + 1);
        byte[] out = new byte[rows * row];
        for (int r = 0; r < rows; r++) {
            int ft = d[r * (row + 1)] & 0xff;
            int src = r * (row + 1) + 1, dst = r * row, up = (r - 1) * row;
            for (int i = 0; i < row; i++) {
                int x = d[src + i] & 0xff;
                int left = i >= bpp ? out[dst + i - bpp] & 0xff : 0;
                int above = r > 0 ? out[up + i] & 0xff : 0;
                int ul = (r > 0 && i >= bpp) ? out[up + i - bpp] & 0xff : 0;
                int v;
                switch (ft) {
                    case 1: v = x + left; break;
                    case 2: v = x + above; break;
                    case 3: v = x + ((left + above) >> 1); break;
                    case 4: {
                        int pp = left + above - ul, pa = Math.abs(pp - left), pb = Math.abs(pp - above), pc = Math.abs(pp - ul);
                        v = x + (pa <= pb && pa <= pc ? left : pb <= pc ? above : ul);
                        break;
                    }
                    default: v = x;
                }
                out[dst + i] = (byte) v;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ objects
    public Object load(int num) throws IOException {
        if (cache.containsKey(num)) return cache.get(num);
        XEntry e = xref.get(num);
        Object v = null;
        if (e != null && e.type == 1) v = parseIndirect(e.a, num);
        else if (e != null && e.type == 2) {
            Object[] os = objStm((int) e.a);
            if (e.b >= 0 && e.b < os.length) v = os[e.b];
        }
        if (!(v instanceof Stream)) cache.put(num, v);
        return v;
    }

    public Object res(Object o) throws IOException {
        int guard = 0;
        while (o instanceof Ref && guard++ < 16) o = load(((Ref) o).num);
        return o;
    }

    @SuppressWarnings("unchecked")
    private Object[] objStm(int sn) throws IOException {
        Object[] c = objStmCache.get(sn);
        if (c != null) return c;
        Object so = load(sn);
        if (!(so instanceof Stream)) throw new IOException("bad object stream " + sn);
        Stream s = (Stream) so;
        byte[] data = decode(s);
        int n = ((Number) s.dict.get("N")).intValue(), first = ((Number) s.dict.get("First")).intValue();
        Lx l = new Lx(data, 0, true);
        int[] offs = new int[n];
        for (int i = 0; i < n; i++) { l.obj(); offs[i] = ((Long) l.obj()).intValue(); }
        Object[] out = new Object[n];
        for (int i = 0; i < n; i++) {
            try { out[i] = new Lx(data, first + offs[i], true).obj(); } catch (RuntimeException e) { out[i] = null; }
        }
        objStmCache.put(sn, out);
        return out;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> dictOf(Object o) throws IOException {
        o = res(o);
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    /** Looks a key up on a page dictionary, following /Parent for inheritable attributes. */
    public Object inherited(Map<String, Object> page, String key) throws IOException {
        Map<String, Object> d = page;
        for (int i = 0; i < 32 && d != null; i++) {
            Object v = d.get(key);
            if (v != null) return res(v);
            d = dictOf(d.get("Parent"));
        }
        return null;
    }

    // ------------------------------------------------------------------ page tree
    public synchronized List<Ref> pages() throws IOException {
        if (pages != null) return pages;
        List<Ref> out = new ArrayList<>();
        Map<String, Object> root = dictOf(trailer.get("Root"));
        if (root == null) throw new Unsupported("no catalog");
        walk(root.get("Pages"), out, new HashSet<Integer>());
        pages = out;
        return out;
    }

    private void walk(Object node, List<Ref> out, Set<Integer> seen) throws IOException {
        if (!(node instanceof Ref)) return;
        Ref r = (Ref) node;
        if (!seen.add(r.num)) return;
        Map<String, Object> d = dictOf(r);
        if (d == null) return;
        Object kids = res(d.get("Kids"));
        if (kids instanceof List) {
            for (Object k : (List<?>) kids) walk(k, out, seen);
        } else out.add(r);
    }

    public int pageCount() throws IOException { return pages().size(); }
    public Ref pageRef(int i) throws IOException { return pages().get(i); }
    public Map<String, Object> pageDict(int i) throws IOException { return dictOf(pageRef(i)); }

    // ------------------------------------------------------------------ writing
    public final class Update {
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        final long base;
        private final TreeMap<Integer, long[]> ents = new TreeMap<>();
        private int next;

        Update(long base, int next) { this.base = base; this.next = next; }

        public int alloc() { return next++; }

        public void put(int num, int gen, Object value) throws IOException {
            ents.put(num, new long[]{base + buf.size(), gen});
            a(buf, num + " " + gen + " obj\n");
            ser(value, buf);
            a(buf, "\nendobj\n");
        }

        public void putStream(int num, Map<String, Object> dict, byte[] data, boolean deflate) throws IOException {
            Map<String, Object> d = new LinkedHashMap<>(dict);
            if (deflate) { data = deflateBytes(data); d.put("Filter", new Name("FlateDecode")); }
            d.put("Length", (long) data.length);
            ents.put(num, new long[]{base + buf.size(), 0});
            a(buf, num + " 0 obj\n");
            ser(d, buf);
            a(buf, "\nstream\n");
            buf.write(data, 0, data.length);
            a(buf, "\nendstream\nendobj\n");
        }

        void finish() throws IOException {
            Map<String, Object> tr = new LinkedHashMap<>();
            tr.put("Root", trailer.get("Root"));
            if (trailer.get("Info") != null) tr.put("Info", trailer.get("Info"));
            if (trailer.get("ID") != null) tr.put("ID", trailer.get("ID"));
            tr.put("Prev", startXref);
            if (xrefIsStream) {
                int xnum = alloc();
                long xoff = base + buf.size();
                ents.put(xnum, new long[]{xoff, 0});
                tr.put("Size", (long) next);
                tr.put("Type", new Name("XRef"));
                List<Object> w = new ArrayList<>();
                w.add(1L); w.add(4L); w.add(2L);
                tr.put("W", w);
                List<Object> index = new ArrayList<>();
                ByteArrayOutputStream rows = new ByteArrayOutputStream();
                int runStart = -1, runLen = 0, prev = -2;
                for (Map.Entry<Integer, long[]> e : ents.entrySet()) {
                    int n = e.getKey();
                    if (n != prev + 1) {
                        if (runStart >= 0) { index.add((long) runStart); index.add((long) runLen); }
                        runStart = n; runLen = 0;
                    }
                    runLen++; prev = n;
                    long off = e.getValue()[0], gen = e.getValue()[1];
                    rows.write(1);
                    rows.write((int) (off >> 24)); rows.write((int) (off >> 16)); rows.write((int) (off >> 8)); rows.write((int) off);
                    rows.write((int) (gen >> 8)); rows.write((int) gen);
                }
                if (runStart >= 0) { index.add((long) runStart); index.add((long) runLen); }
                tr.put("Index", index);
                byte[] data = rows.toByteArray();
                tr.put("Length", (long) data.length);
                a(buf, xnum + " 0 obj\n");
                ser(tr, buf);
                a(buf, "\nstream\n");
                buf.write(data, 0, data.length);
                a(buf, "\nendstream\nendobj\n");
                a(buf, "startxref\n" + xoff + "\n%%EOF\n");
            } else {
                long xoff = base + buf.size();
                tr.put("Size", (long) next);
                a(buf, "xref\n");
                int runStart = -1, prev = -2;
                List<Integer> keys = new ArrayList<>(ents.keySet());
                int i = 0;
                while (i < keys.size()) {
                    int j = i;
                    while (j + 1 < keys.size() && keys.get(j + 1) == keys.get(j) + 1) j++;
                    a(buf, keys.get(i) + " " + (j - i + 1) + "\n");
                    for (int k = i; k <= j; k++) {
                        long[] e = ents.get(keys.get(k));
                        a(buf, String.format(java.util.Locale.US, "%010d %05d n \n", e[0], e[1]));
                    }
                    i = j + 1;
                }
                a(buf, "trailer\n");
                ser(tr, buf);
                a(buf, "\nstartxref\n" + xoff + "\n%%EOF\n");
            }
        }
    }

    public void writeUpdated(OutputStream out, UpdateFn fn) throws IOException {
        if (encrypted()) throw new Unsupported("encrypted PDF");
        raf.seek(0);
        byte[] chunk = new byte[1 << 16];
        long left = length;
        while (left > 0) {
            int n = raf.read(chunk, 0, (int) Math.min(chunk.length, left));
            if (n <= 0) throw new IOException("short read");
            out.write(chunk, 0, n);
            left -= n;
        }
        long base = length;
        raf.seek(length - 1);
        int last = raf.read();
        if (last != '\n' && last != '\r') { out.write('\n'); base++; }
        int maxNum = 0;
        for (Integer k : xref.keySet()) if (k > maxNum) maxNum = k;
        long sz = trailer.get("Size") instanceof Number ? ((Number) trailer.get("Size")).longValue() : 0;
        Update u = new Update(base, (int) Math.max(sz, maxNum + 1));
        fn.apply(u);
        u.finish();
        byte[] b = u.buf.toByteArray();
        out.write(b, 0, b.length);
        out.flush();
    }

    // ------------------------------------------------------------------ serialization
    private static void a(ByteArrayOutputStream o, String s) {
        for (int i = 0; i < s.length(); i++) o.write(s.charAt(i));
    }

    public static String fmt(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "0";
        if (Math.abs(d - Math.rint(d)) < 1e-6) return Long.toString((long) Math.rint(d));
        return new BigDecimal(d).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    @SuppressWarnings("unchecked")
    public static void ser(Object o, ByteArrayOutputStream w) throws IOException {
        if (o == null) a(w, "null");
        else if (o instanceof Name) {
            a(w, "/");
            String v = ((Name) o).v;
            for (int i = 0; i < v.length(); i++) {
                char c = v.charAt(i);
                if (c <= 32 || c >= 127 || c == '#' || isDelim(c)) a(w, String.format("#%02X", (int) c & 0xff));
                else w.write(c);
            }
        }
        else if (o instanceof Ref) a(w, ((Ref) o).num + " " + ((Ref) o).gen + " R");
        else if (o instanceof Long || o instanceof Integer) a(w, o.toString());
        else if (o instanceof Number) a(w, fmt(((Number) o).doubleValue()));
        else if (o instanceof Boolean) a(w, o.toString());
        else if (o instanceof Str) {
            a(w, "<");
            for (byte x : ((Str) o).b) a(w, String.format("%02X", x & 0xff));
            a(w, ">");
        }
        else if (o instanceof List) {
            a(w, "[");
            boolean first = true;
            for (Object x : (List<Object>) o) { if (!first) a(w, " "); ser(x, w); first = false; }
            a(w, "]");
        }
        else if (o instanceof Map) {
            a(w, "<<");
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                a(w, " ");
                ser(new Name(e.getKey()), w);
                a(w, " ");
                ser(e.getValue(), w);
            }
            a(w, " >>");
        }
        else throw new IOException("cannot serialize " + o.getClass());
    }

    private static byte[] deflateBytes(byte[] in) {
        Deflater d = new Deflater(Deflater.DEFAULT_COMPRESSION);
        d.setInput(in);
        d.finish();
        ByteArrayOutputStream o = new ByteArrayOutputStream(Math.max(64, in.length / 2));
        byte[] buf = new byte[8192];
        while (!d.finished()) { int n = d.deflate(buf); o.write(buf, 0, n); }
        d.end();
        return o.toByteArray();
    }
}
