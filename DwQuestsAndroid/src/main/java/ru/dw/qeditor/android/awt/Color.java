package ru.dw.qeditor.android.awt;

public final class Color {

    public static final Color WHITE = new Color(255, 255, 255);
    public static final Color BLACK = new Color(0, 0, 0);

    private final int argb;

    public Color(int r, int g, int b) { this(r, g, b, 255); }

    public Color(int r, int g, int b, int a) {
        argb = (a & 255) << 24 | (r & 255) << 16 | (g & 255) << 8 | (b & 255);
    }

    public Color(int rgb) { argb = 0xFF000000 | rgb; }

    public int getRGB() { return argb; }
}
