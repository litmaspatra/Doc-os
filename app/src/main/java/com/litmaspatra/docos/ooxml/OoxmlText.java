package com.litmaspatra.docos.ooxml;

import java.util.List;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Text editing for paragraphs of WordprocessingML (w:p) and DrawingML (a:p): the new text is diffed
 * against the old, so only the changed span is touched and formatting of the rest survives.
 * Tabs and line breaks are not part of the edit buffer; they stay where they are.
 */
public final class OoxmlText {
    private OoxmlText() { }

    /** Collects text elements (local name tName) of this paragraph, skipping nested paragraphs and drawings. */
    public static void collect(Element p, String ns, String tName, List<Element> out) {
        for (Node c = p.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (!(c instanceof Element)) continue;
            String ln = c.getLocalName();
            if (ns.equals(c.getNamespaceURI()) && ln.equals(tName)) { out.add((Element) c); continue; }
            if (ln.equals("p") || ln.equals("pPr") || ln.equals("rPr") || ln.equals("drawing") || ln.equals("pict")
                    || ln.equals("txbxContent") || ln.equals("object") || ln.equals("AlternateContent")) continue;
            collect((Element) c, ns, tName, out);
        }
    }

    public static String text(List<Element> ts) {
        StringBuilder sb = new StringBuilder();
        for (Element t : ts) sb.append(t.getTextContent());
        return sb.toString();
    }

    public static void apply(Element p, List<Element> ts, String newText, String ns, String runName, String tName, String endName) {
        String old = text(ts);
        if (old.equals(newText)) return;
        int max = Math.min(old.length(), newText.length());
        int pre = 0;
        while (pre < max && old.charAt(pre) == newText.charAt(pre)) pre++;
        int suf = 0;
        while (suf < max - pre && old.charAt(old.length() - 1 - suf) == newText.charAt(newText.length() - 1 - suf)) suf++;
        int delStart = pre, delEnd = old.length() - suf;
        String ins = newText.substring(pre, newText.length() - suf);

        // when replacing text, the replacement joins the run holding the first replaced character
        Element target = null;
        int off = 0;
        int acc = 0;
        if (delEnd > delStart) {
            for (Element t : ts) {
                int len = t.getTextContent().length();
                if (delStart < acc + len) { target = t; off = delStart - acc; break; }
                acc += len;
            }
        }
        // delete
        acc = 0;
        for (Element t : ts) {
            String s = t.getTextContent();
            int a = acc, b = acc + s.length();
            acc = b;
            int from = Math.max(a, delStart), to = Math.min(b, delEnd);
            if (from < to) t.setTextContent(s.substring(0, from - a) + s.substring(to - a));
        }
        if (ins.isEmpty()) return;
        // pure insertion: join the run that holds the preceding character
        if (target == null) {
            acc = 0;
            for (Element t : ts) {
                int len = t.getTextContent().length();
                if (delStart <= acc + len) { target = t; off = delStart - acc; break; }
                acc += len;
            }
        }
        if (target == null) {
            if (!ts.isEmpty()) { target = ts.get(ts.size() - 1); off = target.getTextContent().length(); }
            else {
                Element r = Xml.create(p, ns, runName);
                target = Xml.create(p, ns, tName);
                r.appendChild(target);
                Element end = endName == null ? null : Xml.kid(p, ns, endName);
                if (end != null) p.insertBefore(r, end); else p.appendChild(r);
                off = 0;
            }
        }
        String s = target.getTextContent();
        target.setTextContent(s.substring(0, off) + ins + s.substring(off));
        target.setAttributeNS(Xml.XML_NS, "xml:space", "preserve");
    }
}
