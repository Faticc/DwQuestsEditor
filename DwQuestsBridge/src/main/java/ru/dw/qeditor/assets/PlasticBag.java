package ru.dw.qeditor.assets;

import ru.dw.qeditor.util.ForgeName;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Пластиковый пакет MineFactoryReloaded — пять слотов без настроек.
 *
 * <p>Содержимое лежит в NBT предмета так, как его пишет сам пакет:
 * {@code Inventory:{Slot0:{Damage:3s,NameInRegistry:"DwQuests:Gift",id:7318s,tag:{…},Count:1b}}}.
 * В редакторе слот — обычная строка мода {@code mod:name[:meta]=count{nbt}}, а при
 * записи раскладывается обратно на Damage / NameInRegistry / id / tag / Count.
 */
public final class PlasticBag {

    private PlasticBag() { }

    public static final String ID = "MineFactoryReloaded:plastic.bag";
    public static final int SLOTS = 5;

    public static boolean isBag(String forgeString) {
        return forgeString != null && ForgeName.parse(forgeString).id.equals(ID);
    }

    /** Слоты пакета строками мода; пустой слот — null. */
    public static String[] slots(String reward) {
        String[] out = new String[SLOTS];
        NbtLite.Compound inv = inventory(reward);
        if (inv == null) return out;
        for (int i = 0; i < SLOTS; i++) {
            NbtLite.Compound s = inv.comp("Slot" + i);
            if (s != null) out[i] = toForge(s);
        }
        return out;
    }

    /** Непустые слоты по порядку — для подсказок и проверки id. */
    public static List<String> items(String reward) {
        List<String> out = new ArrayList<>();
        for (String s : slots(reward)) if (s != null) out.add(s);
        return out;
    }

    /**
     * Строка награды с новым содержимым. Всё прочее из NBT исходного пакета
     * (Accessible, своё имя) и количество пакетов сохраняются.
     */
    public static String build(String base, String[] slots) {
        ForgeName fn = ForgeName.parse(base == null || base.isEmpty() ? ID : base);
        NbtLite.Compound nbt = fn.nbt == null ? null : NbtLite.parse(fn.nbt);
        if (nbt == null) nbt = new NbtLite.Compound();
        if (!nbt.hasKey("Accessible")) nbt.put("Accessible", new NbtLite.Compound());
        // числовые id, которые уже стояли в пакете, — они из игры и надёжнее базы
        Map<String, Object> known = new HashMap<>();
        NbtLite.Compound old = nbt.comp("Inventory");
        if (old != null) {
            for (Object o : old.values()) {
                if (!(o instanceof NbtLite.Compound)) continue;
                NbtLite.Compound s = (NbtLite.Compound) o;
                if (s.get("id") != null) known.put(s.string("NameInRegistry"), s.get("id"));
            }
        }
        NbtLite.Compound inv = new NbtLite.Compound();
        for (int i = 0; i < slots.length && i < SLOTS; i++) {
            if (slots[i] != null) inv.put("Slot" + i, toNbt(slots[i], known));
        }
        nbt.put("Inventory", inv);
        return ForgeName.build(ID, fn.hasCount ? fn.count : 1, NbtLite.write(nbt));
    }

    private static NbtLite.Compound inventory(String reward) {
        if (!isBag(reward)) return null;
        NbtLite.Compound nbt = NbtLite.parse(ForgeName.parse(reward).nbt);
        return nbt == null ? null : nbt.comp("Inventory");
    }

    private static String toForge(NbtLite.Compound s) {
        String name = s.string("NameInRegistry");
        if (name == null || name.isEmpty()) name = "minecraft:air";
        int damage = s.i("Damage", 0);
        int count = Math.max(1, s.i("Count", 1));
        NbtLite.Compound tag = s.comp("tag");
        String id = damage != 0 ? name + ":" + damage : name;
        return ForgeName.build(id, count, tag == null ? null : NbtLite.write(tag));
    }

    private static NbtLite.Compound toNbt(String forge, Map<String, Object> known) {
        ForgeName fn = ForgeName.parse(forge);
        String name = fn.mod + ":" + fn.name;
        NbtLite.Compound s = new NbtLite.Compound();
        s.put("Damage", NbtLite.Num.of(Math.max(0, fn.meta), 's'));
        s.put("NameInRegistry", name);
        // главное — NameInRegistry; числовой id пишется, только если известен:
        // из прежнего пакета либо из выгрузки клиента (на сервере может отличаться)
        Object id = known.get(name);
        if (id == null) id = numericId(fn.id);
        if (id == null) id = numericId(name);
        if (id != null) s.put("id", id);
        NbtLite.Compound tag = fn.nbt == null ? null : NbtLite.parse(fn.nbt);
        if (tag != null) s.put("tag", tag);
        s.put("Count", NbtLite.Num.of(fn.hasCount ? fn.count : 1, 'b'));
        return s;
    }

    /** В базе id хранится как «7318» или «7318:3» — берём число до двоеточия. */
    private static NbtLite.Num numericId(String key) {
        String numeric = ItemRegistry.get().numericId(key);
        int colon = numeric.indexOf(':');
        if (colon >= 0) numeric = numeric.substring(0, colon);
        try {
            return NbtLite.Num.of(Integer.parseInt(numeric.trim()), 's');
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
