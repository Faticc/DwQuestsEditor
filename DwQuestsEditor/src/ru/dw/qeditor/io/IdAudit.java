package ru.dw.qeditor.io;

import ru.dw.qeditor.assets.Gifts;
import ru.dw.qeditor.assets.PlasticBag;
import ru.dw.qeditor.assets.ItemRegistry;
import ru.dw.qeditor.assets.NbtLite;
import ru.dw.qeditor.model.Quest;
import ru.dw.qeditor.model.QuestTab;
import ru.dw.qeditor.model.Task;
import ru.dw.qeditor.util.ForgeName;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Сверка id предметов вкладки с базой.
 *
 * <p>Затем, что в игре {@code ForgeNameUtils.getForgeItem} на любой незнакомый
 * id молча отдаёт бедрок: в интерфейсе появляются серые кубы, и по ним не
 * понять, опечатка это, отсутствующий мод или просто устаревшая база иконок.
 * Здесь всё это собрано в один список — с тем, где именно id встречается.
 *
 * <p>Заглядывает и внутрь гифтов: их содержимое мод достаёт тем же способом,
 * поэтому неверный id в слоте гифта тоже превращается в бедрок.
 */
public final class IdAudit {

    private IdAudit() { }

    /** Где встретился id. */
    public record Use(String id, String where) { }

    public static final class Report {
        /** id, которых нет в базе предметов, — в игре станут бедроком. */
        public final Map<String, List<String>> unknown = new TreeMap<>();
        /** id, что известны, но без иконки нужной меты — вопрос только к виду. */
        public final Map<String, List<String>> noIcon = new TreeMap<>();
        public int total;
        public int distinct;

        public boolean clean() { return unknown.isEmpty() && noIcon.isEmpty(); }
    }

    public static Report check(QuestTab tab) {
        List<Use> uses = collect(tab);
        Report r = new Report();
        r.total = uses.size();

        Map<String, List<String>> byId = new LinkedHashMap<>();
        for (Use u : uses) byId.computeIfAbsent(u.id(), k -> new ArrayList<>()).add(u.where());
        r.distinct = byId.size();

        ItemRegistry reg = ItemRegistry.get();
        for (Map.Entry<String, List<String>> e : byId.entrySet()) {
            ForgeName fn = ForgeName.parse(e.getKey());
            if (fn.isCommand() || fn.isGift() || fn.isScript()) continue;
            boolean known = reg.entry(fn.id) != null
                    || (fn.metaRaw != null && reg.entry(fn.mod + ":" + fn.name) != null);
            if (!known) r.unknown.put(e.getKey(), e.getValue());
            else if (!reg.hasIcon(e.getKey())) r.noIcon.put(e.getKey(), e.getValue());
        }
        return r;
    }

    /** Все строки предметов вкладки с пометкой, откуда они. */
    public static List<Use> collect(QuestTab tab) {
        List<Use> out = new ArrayList<>();
        add(out, tab.iconItem, "иконка вкладки");
        for (Quest q : tab.quests.values()) {
            add(out, q.iconItem, q.questID + ": иконка");
            for (Map.Entry<Integer, Task> te : q.tasks.entrySet()) {
                Task t = te.getValue();
                add(out, t.forgeName, q.questID + ": задача " + te.getKey());
            }
            for (int i = 0; i < q.rewards.size(); i++) {
                String reward = q.rewards.get(i);
                String where = q.questID + ": награда " + (i + 1);
                add(out, reward, where);
                if (Gifts.isGift(reward)) addGiftItems(out, reward, where);
                if (PlasticBag.isBag(reward)) addBagItems(out, reward, where);
            }
        }
        return out;
    }

    /** Содержимое пластикового пакета MFR; гифты в нём проверяются вглубь. */
    private static void addBagItems(List<Use> out, String reward, String where) {
        int n = 0;
        for (String item : PlasticBag.items(reward)) {
            n++;
            String inside = where + " → пакет, предмет " + n;
            add(out, item, inside);
            if (Gifts.isGift(item)) addGiftItems(out, item, inside);
        }
    }

    /** Содержимое гифта: слоты лежат в NBT как ID + отдельный Tag. */
    private static void addGiftItems(List<Use> out, String reward, String where) {
        ForgeName fn = ForgeName.parse(reward);
        if (fn.nbt == null) return;
        NbtLite.Compound c = NbtLite.parse(fn.nbt);
        if (c == null) return;
        NbtLite.ListTag items = c.list("Items");
        if (items == null) return;
        int n = 0;
        for (Object o : items) {
            if (!(o instanceof NbtLite.Compound)) continue;
            NbtLite.Compound item = (NbtLite.Compound) o;
            String id = item.string("ID");
            if (id == null) continue;
            n++;
            String inside = where + " → гифт, предмет " + n;
            add(out, id, inside);
            // вложенный гифт: его содержимое проверяется так же
            NbtLite.Compound tag = item.comp("Tag");
            if (tag != null && Gifts.isGift(id)) addGiftItems(out, id + NbtLite.write(tag), inside);
        }
    }

    private static void add(List<Use> out, String forgeString, String where) {
        if (forgeString == null || forgeString.trim().isEmpty()) return;
        ForgeName fn = ForgeName.parse(forgeString);
        if (fn.isCommand()) return;
        out.add(new Use(fn.id, where));
    }

    /** Отчёт, который не стыдно показать человеком: по модам и с примерами. */
    public static String text(Report r) {
        StringBuilder sb = new StringBuilder();
        sb.append("Проверено строк предметов: ").append(r.total)
                .append(", разных id: ").append(r.distinct).append('\n');
        if (r.clean()) {
            sb.append("\nВсе id нашлись в базе — бедрока в игре быть не должно.\n");
            return sb.toString();
        }

        if (!r.unknown.isEmpty()) {
            sb.append("\n--- Нет в базе предметов (в игре будет бедрок): ")
                    .append(r.unknown.size()).append(" ---\n");
            sb.append(byMod(r.unknown));
        }
        if (!r.noIcon.isEmpty()) {
            sb.append("\n--- Есть в базе, но нет картинки (вид в редакторе): ")
                    .append(r.noIcon.size()).append(" ---\n");
            sb.append(byMod(r.noIcon));
        }
        sb.append("\nЕсли мод стоит на сервере, а id тут нет — база предметов устарела:\n")
                .append("нужен свежий выгруз icon_exporter (Настройки → папка с иконками).\n");
        return sb.toString();
    }

    private static String byMod(Map<String, List<String>> ids) {
        Map<String, List<String>> byMod = new TreeMap<>();
        for (String id : ids.keySet()) {
            String mod = id.contains(":") ? id.substring(0, id.indexOf(':')) : "?";
            byMod.computeIfAbsent(mod, k -> new ArrayList<>()).add(id);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : byMod.entrySet()) {
            sb.append("  ").append(e.getKey()).append(" (").append(e.getValue().size()).append("):\n");
            List<String> sorted = new ArrayList<>(e.getValue());
            sorted.sort(Comparator.naturalOrder());
            for (String id : sorted) {
                List<String> where = ids.get(id);
                sb.append("    ").append(id).append("  — ").append(where.size()).append(" раз: ")
                        .append(String.join("; ", where.subList(0, Math.min(3, where.size()))));
                if (where.size() > 3) sb.append("; …");
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}
