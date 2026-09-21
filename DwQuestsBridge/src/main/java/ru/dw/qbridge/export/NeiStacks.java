package ru.dw.qbridge.export;

import codechicken.nei.ItemList;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Список предметов NEI — второй источник вариантов с NBT.
 *
 * <p>Моды регистрируют в NEI то, чего нет в {@code getSubItems}: например,
 * заряженные инструменты и отдельные виды генетики. Класс вынесен отдельно,
 * чтобы обращение к NEI происходило только после проверки, что мод загружен —
 * иначе выгрузка не работала бы в клиенте без NEI.
 */
final class NeiStacks {

    private NeiStacks() {}

    static boolean ready() {
        return ItemList.loadFinished && ItemList.items != null && !ItemList.items.isEmpty();
    }

    static List<ItemStack> all() {
        List<ItemStack> out = new ArrayList<ItemStack>();
        List<ItemStack> items = ItemList.items;
        if (items == null) return out;
        // список живёт в своём потоке и подменяется целиком — копируем ссылку выше
        for (int i = 0; i < items.size(); i++) {
            ItemStack s = items.get(i);
            if (s != null && s.getItem() != null) out.add(s);
        }
        return out;
    }
}
