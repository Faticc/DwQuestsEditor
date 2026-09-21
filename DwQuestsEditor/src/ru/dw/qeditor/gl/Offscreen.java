package ru.dw.qeditor.gl;

import org.lwjgl.system.MemoryUtil;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Кадровый буфер в текстуру — им пользуются проверки отрисовки: окно при этом
 * можно держать скрытым, а результат сохранять в PNG и сравнивать глазами.
 * Раньше ту же роль играли картинки Java2D, в которые рисовал редактор.
 */
public final class Offscreen {

    private final int fbo;
    private final int color;
    private final int width;
    private final int height;

    private Offscreen(int fbo, int color, int width, int height) {
        this.fbo = fbo;
        this.color = color;
        this.width = width;
        this.height = height;
    }

    public static Offscreen create(int width, int height) {
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

        int fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        if (status != GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("Кадровый буфер не собрался: " + status);
        return new Offscreen(fbo, tex, width, height);
    }

    public int width() { return width; }

    public int height() { return height; }

    public void bind(int clearColor) {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, width, height);
        glDisable(GL_SCISSOR_TEST);
        glClearColor((clearColor >> 16 & 255) / 255f, (clearColor >> 8 & 255) / 255f,
                (clearColor & 255) / 255f, (clearColor >>> 24) / 255f);
        glClear(GL_COLOR_BUFFER_BIT);
    }

    public void unbind() { glBindFramebuffer(GL_FRAMEBUFFER, 0); }

    /** Забрать картинку обратно в память — только для проверок, это медленно. */
    public BufferedImage read() {
        ByteBuffer buf = MemoryUtil.memAlloc(width * height * 4);
        try {
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glPixelStorei(GL_PACK_ALIGNMENT, 4);
            glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, buf);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);

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
