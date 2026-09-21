package ru.dw.qeditor.gl;

import ru.dw.qeditor.assets.ItemLook;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Мост между картинками ресурсов и видеокартой.
 *
 * <p>Ресурсы мода по-прежнему читаются как {@code BufferedImage} — ImageIO
 * достаёт их прямо из jar-ов, — но дальше каждая картинка ровно один раз
 * становится текстурой. Ключ — сама ссылка на картинку: {@code ModAssets}
 * отдаёт один и тот же объект, поэтому сравнение по ссылке и дешевле, и точнее
 * сравнения путей.
 *
 * <p>Фильтрация у одной и той же картинки бывает разной: шрифт и элементы GUI
 * мода должны оставаться «пиксельными» (в игре это GL_NEAREST), а иконки и
 * ноды — сглаженными. Поэтому вариант текстуры выбирается флагами.
 *
 * <p>Иконки предметов живут отдельно: их сотни, и каждая своей текстурой
 * означала бы сотни вызовов отрисовки на кадр — они пакуются в атлас.
 */
public final class Textures {

    private static final Map<BufferedImage, GlTexture[]> CACHE = new IdentityHashMap<>();
    private static final Map<String, Atlas.Region> ITEMS = new HashMap<>();
    private static Atlas itemAtlas;

    private Textures() { }

    /** «Пиксельная» текстура: как GL_NEAREST в игре. */
    public static GlTexture pixel(BufferedImage img) { return get(img, false, false); }

    /** Сглаженная текстура с уровнями уменьшения. */
    public static GlTexture smooth(BufferedImage img) { return get(img, true, false); }

    /** Повторяющаяся «пиксельная» текстура — на ней держится параллакс фона карты. */
    public static GlTexture pixelRepeating(BufferedImage img) { return get(img, false, true); }

    /** Повторяющаяся сглаженная текстура. */
    public static GlTexture repeating(BufferedImage img) { return get(img, true, true); }

    private static GlTexture get(BufferedImage img, boolean smooth, boolean repeat) {
        if (img == null) return null;
        GlTexture[] slot = CACHE.computeIfAbsent(img, i -> new GlTexture[4]);
        int idx = (smooth ? 2 : 0) | (repeat ? 1 : 0);
        if (slot[idx] == null) slot[idx] = GlTexture.of(img, smooth, repeat);
        return slot[idx];
    }

    /** Иконка предмета по строке мода. null, если предмет неизвестен. */
    public static Atlas.Region item(String forgeName) {
        if (forgeName == null || forgeName.isEmpty()) return null;
        Atlas.Region r = ITEMS.get(forgeName);
        if (r == null) {
            BufferedImage img = ItemLook.icon(forgeName);
            r = img == null ? null : itemAtlas().add(img);
            // отрицательный ответ тоже запоминаем: иначе каждый кадр будет
            // заново искать иконку по файлам
            ITEMS.put(forgeName, r == null ? MISSING : r);
            return r;
        }
        return r == MISSING ? null : r;
    }

    private static final Atlas.Region MISSING = new Atlas.Region(null, 0, 0, 0, 0, 0, 0);

    private static Atlas itemAtlas() {
        if (itemAtlas == null) itemAtlas = new Atlas(2048, 4, true, 1);
        return itemAtlas;
    }

    /** Раз в кадр: досчитать уровни уменьшения у пополнившихся атласов. */
    public static void refresh() {
        if (itemAtlas != null) itemAtlas.refresh();
    }

    /** После смены путей к ресурсам всё загруженное больше не годится. */
    public static void clear() {
        for (GlTexture[] slot : CACHE.values())
            for (GlTexture t : slot) if (t != null) t.dispose();
        CACHE.clear();
        ITEMS.clear();
        if (itemAtlas != null) {
            itemAtlas.dispose();
            itemAtlas = null;
        }
    }
}
