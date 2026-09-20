package ru.dw.qeditor.assets;

import ru.dw.qeditor.util.ForgeName;

import java.awt.image.BufferedImage;

/**
 * Единая точка получения «как выглядит предмет» по строке мода
 * {@code mod:name:meta=count{nbt}}: иконка, имя и количество. Учитывает гифты
 * (иконка собирается из слоёв, имя берётся из NBT).
 */
public final class ItemLook {

    private ItemLook() { }

    public static BufferedImage icon(String forgeString) {
        if (forgeString == null || forgeString.isEmpty()) return null;
        if (Gifts.isGift(forgeString)) {
            BufferedImage img = Gifts.icon(forgeString);
            if (img != null) return img;
        }
        return ItemRegistry.get().icon(forgeString);
    }

    /** Имя предмета без цветовых кодов — так его показывает мод в списках. */
    public static String name(String forgeString) {
        if (forgeString == null || forgeString.isEmpty()) return "";
        ForgeName fn = ForgeName.parse(forgeString);
        if (fn.isCommand()) return fn.command;
        if (fn.nbt != null) {
            String nbtName = NbtLite.displayName(fn.nbt);
            if (nbtName != null && !nbtName.isEmpty()) return ItemRegistry.stripColor(nbtName);
        }
        if (Gifts.isGift(forgeString)) return Gifts.texture(forgeString).ru;
        String n = ItemRegistry.get().displayName(forgeString);
        return n == null || n.isEmpty() ? fn.id : ItemRegistry.stripColor(n);
    }

    public static int count(String forgeString) {
        ForgeName fn = ForgeName.parse(forgeString);
        return fn.hasCount ? fn.count : 1;
    }
}
