package ru.dw.qeditor.assets;

import ru.dw.qeditor.model.Defs;
import ru.dw.qeditor.util.ForgeName;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Иконки гифтов (ItemGift). Предмет рисуется в несколько проходов: мета задаёт
 * текстуру, NBT-поле Layers — номер картинки в каждом слое.
 */
public final class Gifts {

    private Gifts() { }

    private static final Map<String, BufferedImage> CACHE = new HashMap<>();

    public static boolean isGift(String forgeString) {
        return forgeString != null && forgeString.startsWith("DwQuests:Gift");
    }

    /** Слои из NBT; если их нет или не хватает — нули. */
    public static int[] layers(String nbt, Defs.GiftTexture texture) {
        int[] out = new int[texture.passCount()];
        NbtLite.Compound c = NbtLite.parse(nbt);
        if (c != null) {
            NbtLite.ListTag list = c.list("Layers");
            if (list != null) {
                for (int i = 0; i < out.length && i < list.size(); i++) {
                    Object v = list.get(i);
                    int id = v instanceof NbtLite.Num ? ((NbtLite.Num) v).intValue(0) : 0;
                    out[i] = Math.max(0, Math.min(texture.passes[i] - 1, id));
                }
            }
        }
        return out;
    }

    /** Текстура гифта по строке награды (мета предмета = ordinal). */
    public static Defs.GiftTexture texture(String forgeString) {
        ForgeName fn = ForgeName.parse(forgeString);
        return Defs.GiftTexture.of(fn.meta < 0 ? 0 : fn.meta);
    }

    /** Готовая 64×64 иконка гифта: слои наложены друг на друга. */
    public static BufferedImage icon(String forgeString) {
        ForgeName fn = ForgeName.parse(forgeString);
        Defs.GiftTexture tex = Defs.GiftTexture.of(fn.meta < 0 ? 0 : fn.meta);
        return icon(tex, layers(fn.nbt, tex));
    }

    public static BufferedImage icon(Defs.GiftTexture texture, int[] layers) {
        StringBuilder key = new StringBuilder(texture.name());
        for (int l : layers) key.append(':').append(l);
        BufferedImage cached = CACHE.get(key.toString());
        if (cached != null) return cached;

        List<BufferedImage> imgs = new ArrayList<>();
        for (int pass = 0; pass < texture.passCount(); pass++) {
            int id = pass < layers.length ? layers[pass] : 0;
            BufferedImage img = ModAssets.get().giftLayer(texture.name(), id, pass);
            if (img != null) imgs.add(img);
        }
        if (imgs.isEmpty()) return null;

        int w = 0, h = 0;
        for (BufferedImage i : imgs) { w = Math.max(w, i.getWidth()); h = Math.max(h, i.getHeight()); }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        for (BufferedImage i : imgs) g.drawImage(i, 0, 0, w, h, null);
        g.dispose();

        if (CACHE.size() > 256) CACHE.clear();
        CACHE.put(key.toString(), out);
        return out;
    }

    public static void clearCache() { CACHE.clear(); }
}
