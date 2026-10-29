package ru.dw.qeditor.android.awt;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Чтение и запись PNG силами Android вместо {@code javax.imageio.ImageIO}.
 *
 * <p>Читается без умножения на альфу ({@code inPremultiplied = false}):
 * иначе полупрозрачные края иконок, пройдя туда и обратно, теряют цвет.
 */
public final class ImageIO {

    private ImageIO() { }

    public static BufferedImage read(InputStream in) throws IOException {
        byte[] data = readAll(in);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPremultiplied = false;
        o.inScaled = false;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (bmp == null) return null;
        try {
            return fromBitmap(bmp);
        } finally {
            bmp.recycle();
        }
    }

    public static BufferedImage read(File file) throws IOException {
        try (InputStream in = new java.io.FileInputStream(file)) {
            return read(in);
        }
    }

    public static BufferedImage fromBitmap(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB, px);
    }

    public static Bitmap toBitmap(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.setPremultiplied(false);
        // построчно: картинка вкладки бывает в десятки миллионов точек, и вторая
        // полная копия массива ради одного вызова — лишние сотни мегабайт
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            bmp.setPixels(row, 0, w, 0, y, w, 1);
        }
        return bmp;
    }

    public static boolean write(BufferedImage img, String format, OutputStream out) throws IOException {
        if (!"png".equalsIgnoreCase(format)) return false;
        Bitmap bmp = toBitmap(img);
        try {
            return bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally {
            bmp.recycle();
        }
    }

    public static boolean write(BufferedImage img, String format, File file) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            return write(img, format, out);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
