package com.litmaspatra.docos.ooxml;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class Zip {
    private Zip() { }

    public static byte[] read(ZipFile zf, String name) throws IOException {
        ZipEntry e = zf.getEntry(name);
        if (e == null) return null;
        InputStream in = zf.getInputStream(e);
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream(Math.max(1024, (int) Math.min(e.getSize(), 1 << 26)));
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        } finally { in.close(); }
    }

    public static byte[] read(File f, String name) throws IOException {
        ZipFile zf = new ZipFile(f);
        try { return read(zf, name); } finally { zf.close(); }
    }

    /** Copies src to dst, replacing the given parts and dropping the listed ones. */
    public static void rewrite(File src, File dst, Map<String, byte[]> replace, Set<String> remove) throws IOException {
        ZipFile zf = new ZipFile(src);
        ZipOutputStream zo = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(dst)));
        try {
            byte[] buf = new byte[16384];
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (remove != null && remove.contains(n)) continue;
                zo.putNextEntry(new ZipEntry(n));
                if (!e.isDirectory()) {
                    if (replace != null && replace.containsKey(n)) zo.write(replace.get(n));
                    else {
                        InputStream in = zf.getInputStream(e);
                        try { int k; while ((k = in.read(buf)) > 0) zo.write(buf, 0, k); } finally { in.close(); }
                    }
                }
                zo.closeEntry();
            }
        } finally {
            try { zo.close(); } finally { zf.close(); }
        }
    }
}
