package ru.dw.qbridge.export;

import cpw.mods.fml.common.Loader;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import ru.dw.qbridge.DwqBridge;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Что вообще надо нарисовать.
 *
 * <p>Ключ — путь картинки ({@code мод/имя[_мета][~хеш].png}), поэтому варианты,
 * отличающиеся только NBT, не затирают друг друга. Именно на этом ломался старый
 * icon_exporter: у всех цветов Botany один id {@code Botany:itemFlower}, и в
 * выгрузке от них оставалась одна картинка.
 */
public final class StackScan {

    /** путь картинки -> стек */
    private final Map<String, ItemStack> found = new LinkedHashMap<String, ItemStack>();
    private int skipped;

    public Map<String, ItemStack> result() {
        return found;
    }

    public int size() {
        return found.size();
    }

    public int skipped() {
        return skipped;
    }

    public StackScan add(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return this;
        String path;
        try {
            path = Names.iconPath(stack);
        } catch (Throwable t) {
            skipped++;
            return this;
        }
        if (path == null) {
            skipped++;
            return this;
        }
        if (!found.containsKey(path)) {
            ItemStack copy = stack.copy();
            copy.stackSize = 1;
            found.put(path, copy);
        }
        return this;
    }

    public StackScan addAll(Iterable<ItemStack> stacks) {
        for (ItemStack s : stacks) add(s);
        return this;
    }

    /**
     * Весь реестр предметов: у каждого спрашиваем варианты по всем его вкладкам
     * творчества. Варианты, которые мод показывает только в одной вкладке, иначе
     * бы потерялись, а {@code null} добирает то, что вообще не в творчестве.
     */
    @SuppressWarnings("unchecked")
    public StackScan addRegistry() {
        List<ItemStack> buffer = new ArrayList<ItemStack>();
        for (Iterator<?> it = Item.itemRegistry.iterator(); it.hasNext(); ) {
            Object obj = it.next();
            if (!(obj instanceof Item)) continue;
            Item item = (Item) obj;

            buffer.clear();
            CreativeTabs[] tabs = null;
            try {
                tabs = item.getCreativeTabs();
            } catch (Throwable ignored) {
                // мод может считать вкладки на лету и падать без мира
            }
            boolean any = false;
            if (tabs != null) {
                for (int i = 0; i < tabs.length; i++) {
                    any |= subItems(item, tabs[i], buffer);
                }
            }
            if (!any) subItems(item, item.getCreativeTab(), buffer);
            if (buffer.isEmpty()) {
                // предмет вне творчества — берём хотя бы мету 0
                try {
                    buffer.add(new ItemStack(item, 1, 0));
                } catch (Throwable ignored) {
                    skipped++;
                }
            }
            addAll(buffer);
        }
        return this;
    }

    @SuppressWarnings("unchecked")
    private boolean subItems(Item item, CreativeTabs tab, List<ItemStack> out) {
        try {
            int before = out.size();
            item.getSubItems(item, tab, out);
            return out.size() > before;
        } catch (Throwable t) {
            skipped++;
            DwqBridge.LOG.warn("getSubItems упал у " + item + ": " + t);
            return false;
        }
    }

    /** Список NEI, если он есть, — там попадаются стеки с NBT. */
    public StackScan addNei() {
        if (!Loader.isModLoaded("NotEnoughItems")) return this;
        try {
            if (!NeiStacks.ready()) {
                DwqBridge.LOG.info("NEI ещё не собрал список предметов — пропускаю");
                return this;
            }
            addAll(NeiStacks.all());
        } catch (Throwable t) {
            DwqBridge.LOG.warn("список NEI прочитать не вышло: " + t);
        }
        return this;
    }

    /** Строки предметов как их пишут во вкладке: {@code mod:name[:meta][{nbt}]}. */
    public StackScan addStrings(Iterable<String> forgeNames) {
        for (String s : forgeNames) {
            ItemStack stack = Stacks.parse(s);
            if (stack == null) skipped++;
            else add(stack);
        }
        return this;
    }
}
