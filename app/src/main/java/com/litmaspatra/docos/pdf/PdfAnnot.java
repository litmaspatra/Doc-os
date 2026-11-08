package com.litmaspatra.docos.pdf;

/** One annotation, in "display points": origin top-left of the page as shown, 1 unit = 1/72 inch. */
public final class PdfAnnot {
    public static final int INK = 0, TEXT = 1, RECTS = 2;

    public int page, kind;
    public float[][] strokes;      // INK: polylines as x,y pairs
    public float[] rects;          // RECTS: l,t,r,b quadruples
    public float lw = 2f;          // INK line width
    public int color = 0xFF000000; // RGB, alpha channel ignored
    public float alpha = 1f;
    public boolean flat;           // highlighter: butt caps

    // TEXT
    public float u, v, w, h;       // box
    public String text = "";
    public float fs = 14f;
    public int[] px;               // ARGB raster used when writing the appearance stream
    public int pw, ph;

    public int objNum = -1;        // >=0 when loaded from the file; -1 new; -2 loaded but not removable
    public int gen;

    public Object cache;           // UI cache (Path / layout)

    public boolean fromFile() { return objNum != -1; }
}
