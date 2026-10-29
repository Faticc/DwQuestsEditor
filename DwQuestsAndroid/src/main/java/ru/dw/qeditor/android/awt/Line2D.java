package ru.dw.qeditor.android.awt;

/** Расстояние от точки до отрезка — единственное, что редактор берёт у Line2D. */
public final class Line2D {

    private Line2D() { }

    public static double ptSegDist(double x1, double y1, double x2, double y2, double px, double py) {
        double dx = x2 - x1, dy = y2 - y1;
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : ((px - x1) * dx + (py - y1) * dy) / len2;
        t = Math.max(0, Math.min(1, t));
        double cx = x1 + t * dx - px, cy = y1 + t * dy - py;
        return Math.sqrt(cx * cx + cy * cy);
    }
}
