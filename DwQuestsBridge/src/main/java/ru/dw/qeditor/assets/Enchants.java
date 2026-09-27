package ru.dw.qeditor.assets;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Зачарования ванильного Minecraft 1.7.10: числовой id, русское имя и предел
 * уровня.
 *
 * <p>В NBT зачарование — это {@code {id:<short>,lvl:<short>}} в списке
 * {@code ench} (у зачарованной книги — {@code StoredEnchantments}), то есть
 * голые числа. Держать их в голове незачем, поэтому здесь таблица: в списке
 * видно имя, а в файл уходит число.
 *
 * <p>Моды добавляют свои id — их тут нет и быть не может, поэтому номер всегда
 * можно вписать руками.
 */
public final class Enchants {

    private Enchants() { }

    public record Ench(int id, String ru, int maxLevel) { }

    private static final Map<Integer, Ench> BY_ID = new LinkedHashMap<>();

    private static void add(int id, String ru, int max) {
        BY_ID.put(id, new Ench(id, ru, max));
    }

    static {
        add(0, "Защита", 4);
        add(1, "Огнеупорность", 4);
        add(2, "Мягкое падение", 4);
        add(3, "Взрывоустойчивость", 4);
        add(4, "Защита от снарядов", 4);
        add(5, "Подводное дыхание", 3);
        add(6, "Родство с водой", 1);
        add(7, "Шипы", 3);

        add(16, "Острота", 5);
        add(17, "Небесная кара", 5);
        add(18, "Бич членистоногих", 5);
        add(19, "Отдача", 2);
        add(20, "Заговор огня", 2);
        add(21, "Мародёрство", 3);

        add(32, "Эффективность", 5);
        add(33, "Шёлковое касание", 1);
        add(34, "Прочность", 3);
        add(35, "Удача", 3);

        add(48, "Сила", 5);
        add(49, "Отбрасывание", 2);
        add(50, "Воспламенение", 1);
        add(51, "Бесконечность", 1);

        add(61, "Везучий рыбак", 3);
        add(62, "Приманка", 3);
    }

    public static List<Ench> all() { return new ArrayList<>(BY_ID.values()); }

    /** Имя по номеру; для модовых — «Зачарование 123». */
    public static String name(int id) {
        Ench e = BY_ID.get(id);
        return e == null ? "Зачарование " + id : e.ru();
    }

    public static int maxLevel(int id) {
        Ench e = BY_ID.get(id);
        return e == null ? 10 : e.maxLevel();
    }

    /** Номер по имени из списка; −1, если такого нет. */
    public static int idOf(String ru) {
        for (Ench e : BY_ID.values()) if (e.ru().equals(ru)) return e.id();
        return -1;
    }
}
