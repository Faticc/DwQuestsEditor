package ru.dw.qeditor.gl;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

import org.lwjgl.system.MemoryUtil;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Кадровый буфер в текстуру. Им пользуются проверки отрисовки — окно при этом
 * можно держать скрытым, а результат сохранять в PNG и сравнивать глазами, —
 * и предпросмотр описания: он рисуется крупно и резко, а на экран кладётся
 * уменьшенной картинкой.
 */
@Lwjgl3Aware
public final class Offscreen {

    private final int fbo;
    private final int color;
    private final int width;
    private final int height;
    private final boolean mipmaps;
    private final GlTexture handle;

    private Offscreen(int fbo, int color, int width, int height, boolean mipmaps) {
        this.fbo = fbo;
        this.color = color;
        this.width = width;
        this.height = height;
        this.mipmaps = mipmaps;
        this.handle = GlTexture.wrap(color, width, height);
    }

    public static Offscreen create(int width, int height) {
        return create(width, height, false);
    }

    /**
     * @param mipmaps держать уровни уменьшения. Нужны, если картинку потом
     *                уменьшают больше чем вдвое: линейная фильтрация берёт
     *                четыре текселя из скольких угодно, и без уровней
     *                уменьшенная картинка рябит.
     */
    public static Offscreen create(int width, int height, boolean mipmaps) {
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER,
                mipmaps ? GL_LINEAR_MIPMAP_LINEAR : GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        if (mipmaps) glGenerateMipmap(GL_TEXTURE_2D);

        int previous = glGetInteger(GL_FRAMEBUFFER_BINDING);
        int fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        glBindFramebuffer(GL_FRAMEBUFFER, previous);
        if (status != GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("Кадровый буфер не собрался: " + status);
        return new Offscreen(fbo, tex, width, height, mipmaps);
    }

    public int width() { return width; }

    public int height() { return height; }

    public int fbo() { return fbo; }

    /** Нарисованное — как текстура. Владение остаётся за буфером. */
    public GlTexture texture() { return handle; }

    /** Пересчитать уровни уменьшения — после того, как в буфер нарисовали. */
    public void buildMipmaps() {
        if (!mipmaps) return;
        glBindTexture(GL_TEXTURE_2D, color);
        glGenerateMipmap(GL_TEXTURE_2D);
    }

    /**
     * Буфер, в который рисовали до {@link #bind}. В игре это не ноль, а
     * кадровый буфер Minecraft — вернуть надо именно его, иначе кадр игры
     * уйдёт мимо экрана.
     */
    private int previous;

    public void bind(int clearColor) {
        previous = glGetInteger(GL_FRAMEBUFFER_BINDING);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, width, height);
        glDisable(GL_SCISSOR_TEST);
        glClearColor((clearColor >> 16 & 255) / 255f, (clearColor >> 8 & 255) / 255f,
                (clearColor & 255) / 255f, (clearColor >>> 24) / 255f);
        glClear(GL_COLOR_BUFFER_BIT);
    }

    public void unbind() { glBindFramebuffer(GL_FRAMEBUFFER, previous); }

    /** Залить цветом уже выбранный буфер — им пользуется {@link Batch#pushTarget}. */
    public static void clear(int argb) {
        glClearColor((argb >> 16 & 255) / 255f, (argb >> 8 & 255) / 255f,
                (argb & 255) / 255f, (argb >>> 24) / 255f);
        glClear(GL_COLOR_BUFFER_BIT);
    }

    /** Забрать картинку обратно в память — только для проверок, это медленно. */
    public BufferedImage read() {
        ByteBuffer buf = MemoryUtil.memAlloc(width * height * 4);
        try {
            int was = glGetInteger(GL_FRAMEBUFFER_BINDING);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glPixelStorei(GL_PACK_ALIGNMENT, 4);
            glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, buf);
            glBindFramebuffer(GL_FRAMEBUFFER, was);

            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                // GL отдаёт строки снизу вверх
                int src = (height - 1 - y) * width * 4;
                for (int x = 0; x < width; x++) {
                    int i = src + x * 4;
                    row[x] = (buf.get(i + 3) & 255) << 24 | (buf.get(i) & 255) << 16
                            | (buf.get(i + 1) & 255) << 8 | (buf.get(i + 2) & 255);
                }
                img.setRGB(0, y, width, 1, row, 0, width);
            }
            return img;
        } finally {
            MemoryUtil.memFree(buf);
        }
    }

    public void dispose() {
        glDeleteFramebuffers(fbo);
        glDeleteTextures(color);
    }
}
