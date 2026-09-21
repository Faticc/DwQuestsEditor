package ru.dw.qeditor.gl;

import org.lwjgl.system.MemoryUtil;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Текстура OpenGL.
 *
 * <p>Картинки по-прежнему читаются AWT (ImageIO умеет png из jar-ов мода и
 * ничего к окну не привязывает), но дальше живут только на видеокарте: тонировка,
 * масштабирование и обводка, которые раньше считались на процессоре и кэшировались
 * сотнями {@code BufferedImage}, теперь делает шейдер.
 */
public final class GlTexture {

    private final int id;
    private final int width;
    private final int height;

    private GlTexture(int id, int width, int height) {
        this.id = id;
        this.width = width;
        this.height = height;
    }

    public int id() { return id; }

    public int width() { return width; }

    public int height() { return height; }

    /** Обычная текстура: билинейная фильтрация, края зажимаются. */
    public static GlTexture of(BufferedImage img) {
        return of(img, true, false);
    }

    /**
     * @param smooth  true — GL_LINEAR, false — GL_NEAREST («пиксельный» вид)
     * @param repeat  true — GL_REPEAT (на этом держится параллакс фона карты)
     */
    public static GlTexture of(BufferedImage img, boolean smooth, boolean repeat) {
        int w = img.getWidth();
        int h = img.getHeight();
        ByteBuffer buf = toRgba(img);
        try {
            int id = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, id);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, smooth ? GL_LINEAR_MIPMAP_LINEAR : GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, smooth ? GL_LINEAR : GL_NEAREST);
            int wrap = repeat ? GL_REPEAT : GL_CLAMP_TO_EDGE;
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrap);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrap);
            if (smooth) glGenerateMipmap(GL_TEXTURE_2D);
            return new GlTexture(id, w, h);
        } finally {
            MemoryUtil.memFree(buf);
        }
    }

    /** Пустая текстура под атлас — заполняется кусками через {@link #upload}. */
    public static GlTexture empty(int w, int h, boolean smooth) {
        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, smooth ? GL_LINEAR : GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, smooth ? GL_LINEAR : GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        return new GlTexture(id, w, h);
    }

    /** Положить картинку в область атласа. */
    public void upload(BufferedImage img, int x, int y) {
        ByteBuffer buf = toRgba(img);
        try {
            glBindTexture(GL_TEXTURE_2D, id);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
            glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, img.getWidth(), img.getHeight(),
                    GL_RGBA, GL_UNSIGNED_BYTE, buf);
        } finally {
            MemoryUtil.memFree(buf);
        }
    }

    /** Один пиксель — им закрашиваются сплошные прямоугольники, когда нужен «текстурный» путь. */
    public static GlTexture white() {
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 0xFFFFFFFF);
        return of(img, false, false);
    }

    /**
     * ARGB-картинка AWT → RGBA-байты для GL. Память нативная: GL читает её
     * напрямую, без копии в драйвере.
     */
    private static ByteBuffer toRgba(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] px = pixels(img);
        ByteBuffer buf = MemoryUtil.memAlloc(w * h * 4);
        for (int i = 0; i < w * h; i++) {
            int p = px[i];
            buf.put((byte) (p >> 16 & 255));
            buf.put((byte) (p >> 8 & 255));
            buf.put((byte) (p & 255));
            buf.put((byte) (p >>> 24));
        }
        buf.flip();
        return buf;
    }

    private static int[] pixels(BufferedImage img) {
        int type = img.getType();
        if ((type == BufferedImage.TYPE_INT_ARGB || type == BufferedImage.TYPE_INT_RGB)
                && img.getRaster().getDataBuffer() instanceof DataBufferInt db) {
            int[] data = db.getData();
            if (type == BufferedImage.TYPE_INT_RGB) {
                int[] out = new int[data.length];
                for (int i = 0; i < data.length; i++) out[i] = data[i] | 0xFF000000;
                return out;
            }
            return data;
        }
        return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
    }

    public void dispose() { glDeleteTextures(id); }
}
