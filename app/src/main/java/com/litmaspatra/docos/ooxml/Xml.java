package com.litmaspatra.docos.ooxml;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Small DOM helpers shared by the Office formats. */
public final class Xml {
    private Xml() { }

    public static final String XML_NS = "http://www.w3.org/XML/1998/namespace";

    public static Document parse(byte[] b) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) { }
        try { f.setExpandEntityReferences(false); } catch (Exception ignored) { }
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(b));
    }

    public static byte[] write(Document d) throws Exception {
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        try { d.setXmlStandalone(true); } catch (Exception ignored) { }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        t.transform(new DOMSource(d), new StreamResult(o));
        return o.toByteArray();
    }

    public static boolean is(Node n, String ns, String local) {
        return n instanceof Element && local.equals(n.getLocalName()) && (ns == null || ns.equals(n.getNamespaceURI()));
    }

    public static List<Element> kids(Node parent, String ns, String local) {
        List<Element> out = new ArrayList<>();
        if (parent == null) return out;
        for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) if (is(c, ns, local)) out.add((Element) c);
        return out;
    }

    public static List<Element> kids(Node parent) {
        List<Element> out = new ArrayList<>();
        if (parent == null) return out;
        for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) if (c instanceof Element) out.add((Element) c);
        return out;
    }

    public static Element kid(Node parent, String ns, String local) {
        if (parent == null) return null;
        for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) if (is(c, ns, local)) return (Element) c;
        return null;
    }

    /** Descendant lookup by path of local names, e.g. path(e, A, "spPr", "xfrm"). */
    public static Element path(Element e, String ns, String... names) {
        Element cur = e;
        for (String n : names) { cur = kid(cur, ns, n); if (cur == null) return null; }
        return cur;
    }

    public static String attr(Element e, String ns, String local) {
        if (e == null) return null;
        if (ns == null) return e.hasAttribute(local) ? e.getAttribute(local) : null;
        return e.hasAttributeNS(ns, local) ? e.getAttributeNS(ns, local) : null;
    }

    public static int intAttr(Element e, String ns, String local, int def) {
        String s = attr(e, ns, local);
        if (s == null) return def;
        try { return (int) Math.round(Double.parseDouble(s.trim())); } catch (NumberFormatException x) { return def; }
    }

    public static double dblAttr(Element e, String ns, String local, double def) {
        String s = attr(e, ns, local);
        if (s == null) return def;
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException x) { return def; }
    }

    public static Element create(Element like, String ns, String local) {
        String p = like.getPrefix();
        return like.getOwnerDocument().createElementNS(ns, p == null || p.isEmpty() ? local : p + ":" + local);
    }
}
