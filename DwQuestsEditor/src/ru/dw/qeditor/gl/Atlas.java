package ru.dw.qeditor.gl;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Атлас: много мелких картинок на одной текстуре. Нужен, чтобы не рвать пакет
 * вершин на каждую иконку, — переключение текстуры это отдельный вызов
 * отрисовки, а иконок на вкладке бывает под четыре сотни.
 *
 * <p>Упаковка «полками»: картинки кладутся рядами слева направо, высота ряда —
 * по самой высокой в нём. Для одинаковых иконок и глифов этого более чем
 * достаточно, а сложный упаковщик тут только мешал бы.
 */
public final class Atlas {

    /** Кусок атласа: страница и координаты внутри неё. */
    public record Region(GlTexture page, float u1, float v1, float u2, float v2, int width, int height) {
        public boolean valid() { return page != null; }
    }

    private final int pageSize;
    private final int padding;
    private final boolean smooth;
    private final int maxMipLevel;
    private final List<GlTexture> pages = new ArrayList<>();
    private final List<Boolean> dirty = new ArrayList<>();

    private int shelfX;
    private int shelfY;
    private int shelfHeight;

    /**
     * @param maxMipLevel сколько уровней уменьшения держать; 0 — без них.
     *                    Больше двух брать нельзя: на мелких уровнях соседние
     *                    картинки начинают подмешиваться друг в друга.
     */
    public Atlas(int pageSize, int padding, boolean smooth, int maxMipLevel) {
        this.pageSize = pageSize;
        this.padding = padding;
        this.smooth = smooth;
        this.maxMipLevel = Math.min(2, maxMipLevel);
    }

    public Region add(BufferedImage img) {
        if (img == null) return null;
        int w = img.getWidth();
        int h = img.getHeight();
        if (w <= 0 || h <= 0 || w + padding * 2 > pageSize || h + padding * 2 > pageSize) return null;

        if (pages.isEmpty()) newPage();
        if (shelfX + w + padding > pageSize) {          // не влезает в ряд — следующий ряд
            shelfX = padding;
            shelfY += shelfHeight + padding;
            shelfHeight = 0;
        }
        if (shelfY + h + padding > pageSize) newPage(); // и страница кончилась

        GlTexture page = pages.get(pages.size() - 1);
        page.upload(img, shelfX, shelfY);
        dirty.set(pages.size() - 1, Boolean.TRUE);

        Region r = new Region(page,
                shelfX / (float) pageSize, shelfY / (float) pageSize,
                (shelfX + w) / (float) pageSize, (shelfY + h) / (float) pageSize, w, h);

        shelfX += w + padding;
        shelfHeight = Math.max(shelfHeight, h);
        return r;
    }

    private void newPage() {
        GlTexture page = GlTexture.empty(pageSize, pageSize, smooth);
        if (maxMipLevel > 0) {
            glBindTexture(GL_TEXTURE_2D, page.id());
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, maxMipLevel);
        }
        pages.add(page);
        dirty.add(Boolean.TRUE);
        shelfX = padding;
        shelfY = padding;
        shelfHeight = 0;
    }

    /** Пересобрать уровни уменьшения у изменившихся страниц — раз в кадр. */
    public void refresh() {
        if (maxMipLevel == 0) return;
        for (int i = 0; i < pages.size(); i++) {
            if (!dirty.get(i)) continue;
            glBindTexture(GL_TEXTURE_2D, pages.get(i).id());
            glGenerateMipmap(GL_TEXTURE_2D);
            dirty.set(i, Boolean.FALSE);
        }
    }

    public int pageCount() { return pages.size(); }

    public void dispose() {
        for (GlTexture t : pages) t.dispose();
        pages.clear();
        dirty.clear();
    }
}
