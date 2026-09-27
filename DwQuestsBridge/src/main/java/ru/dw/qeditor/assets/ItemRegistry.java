package ru.dw.qeditor.assets;

import ru.dw.qeditor.json.JsonObject;
import ru.dw.qeditor.json.JsonParser;
import ru.dw.qeditor.util.ForgeName;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Метаданные предметов (json/items.json) и иконки (img/&lt;mod&gt;/&lt;name&gt;[_meta].png)
 * из экспортёра иконок клиента.
 *
 * Имена файлов экранированы так же, как их писал экспортёр: каждый недопустимый
 * в имени файла символ заменён на $индекс$ (см. ILLEGAL).
 */
public final class ItemRegistry {

    private static final char[] ILLEGAL = {'\\', '/', ':', '*', '?', '"', '<', '>', '|'};

    public static class Entry {
        public final String key;
        public final String name;
        public final String itemId;
        public final String mod;
        /** Имя файла картинки, если по ключу его не вывести (вариант с NBT). */
        public final String icon;
        final String search;

        Entry(String key, String name, String itemId) {
            this(key, name, itemId, null);
        }

        Entry(String key, String name, String itemId, String icon) {
            this.key = key;
            this.name = name == null ? "" : name;
            this.itemId = itemId == null ? "" : itemId;
            this.icon = icon;
            int c = key.indexOf(':');
            this.mod = c > 0 ? key.substring(0, c) : key;
            this.search = (this.name + " " + key + " " + this.itemId).toLowerCase(Locale.ROOT);
        }

        /** Вариант, который отличается от обычного предмета только NBT. */
        public boolean hasNbt() { return key.indexOf('{') > 0; }
    }

    private static ItemRegistry instance;

    /** Откуда читаются items.json и картинки иконок. */
    private AssetPack pack = AssetPack.empty("не открыто");
    private final Map<String, Entry> byKey = new LinkedHashMap<>();
    /** "mod:name:meta|приведённый NBT" -> путь картинки внутри img/. */
    private final Map<String, String> iconByNbt = new HashMap<>();
    private final List<Entry> all = new ArrayList<>();
    private final Set<String> mods = new TreeSet<>();
    /** mod-каталог -> список имён png в нём (без расширения). */
    private final Map<String, Set<String>> dirCache = new HashMap<>();
    private final Map<String, BufferedImage> imageCache = new HashMap<>();
    private final Map<String, String> pathCache = new HashMap<>();
    private BufferedImage fallback;
    private String loadError;

    public static ItemRegistry get() {
        if (instance == null) {
            instance = new ItemRegistry();
            instance.reload();
        }
        return instance;
    }

    public String loadError() { return loadError; }

    /** Откуда загрузились иконки — для диагностики. */
    public String describe() { return pack.describe(); }

    public int size() { return all.size(); }

    public List<Entry> entries() { return Collections.unmodifiableList(all); }

    public Set<String> mods() { return Collections.unmodifiableSet(mods); }

    public void reload() {
        byKey.clear();
        iconByNbt.clear();
        all.clear();
        mods.clear();
        dirCache.clear();
        imageCache.clear();
        pathCache.clear();
        tooltipCache.clear();
        fallback = null;
        loadError = null;
        pack = openPack();

        // в игре список предметов — живой реестр, а не выгрузка: так в нём
        // всё, что стоит в паке сейчас, с русскими именами из самой игры
        byte[] json = live != null ? live.itemsJson() : null;
        if (json == null) json = pack.bytes("json/items.json");
        if (json == null) {
            loadError = live != null ? "Игра не отдала список предметов" : "Не найден items.json";
            return;
        }
        try {
            String text = new String(json, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseObject(text);
            for (String key : root.keys()) {
                JsonObject o = root.obj(key);
                if (o == null) continue;
                String icon = o.str("icon", null);
                Entry e = new Entry(key, o.str("itemName", key), o.str("itemId", ""), icon);
                byKey.put(key, e);
                all.add(e);
                mods.add(e.mod);
                if (icon != null && e.hasNbt()) {
                    // у варианта с NBT ключ — целиком строка предмета; чтобы он
                    // нашёлся и по иначе записанному NBT, помним приведённый вид
                    ForgeName fn = ForgeName.parse(key);
                    iconByNbt.put(fn.id + "|" + NbtLite.canonical(fn.nbt), icon);
                }
            }
        } catch (RuntimeException e) {
            loadError = "Ошибка чтения items.json: " + e.getMessage();
        }
    }

    /**
     * Папка иконок, которые игра уже нарисовала раньше ({@code dwq_export/icons}):
     * с ней карта сразу показывает знакомые предметы, а не ждёт, пока игра
     * дорисует каждую иконку заново.
     */
    private AssetPack openPack() {
        String dir = Config.get().iconsDir;
        if (!Config.isBlank(dir)) return AssetPack.dir(new File(dir));
        return AssetPack.empty("папка иконок не указана");
    }

    // ------------------------------------------------------------------ игра

    /**
     * Живой источник сведений о предметах — сама игра. Подставляет мод;
     * без игры (консоль) его нет, и всё берётся из выгрузки на диске.
     */
    public interface Live {
        /** Весь реестр в формате items.json: ключ → itemName, itemId, icon. */
        byte[] itemsJson();

        /** Описание предмета строками, как во всплывающей подсказке игры (с §-кодами). */
        List<String> tooltip(String forgeString);
    }

    private static Live live;

    public static void setLive(Live source) { live = source; }

    public static boolean isLive() { return live != null; }

    /** Описание предмета из игры: имя первой строкой, дальше строки модов. Пусто — без игры. */
    public List<String> tooltip(String forgeString) {
        if (live == null || forgeString == null || forgeString.isEmpty()) return List.of();
        ForgeName fn = ForgeName.parse(forgeString);
        if (fn.isCommand()) return List.of();
        List<String> cached = tooltipCache.get(forgeString);
        if (cached != null) return cached;
        List<String> lines;
        try {
            lines = live.tooltip(forgeString);
        } catch (RuntimeException e) {
            lines = null;
        }
        if (lines == null) lines = List.of();
        if (tooltipCache.size() > 2000) tooltipCache.clear();
        tooltipCache.put(forgeString, lines);
        return lines;
    }

    private final Map<String, List<String>> tooltipCache = new HashMap<>();

    // ------------------------------------------------------------------ имена

    /** Русское название предмета по строке мода (с учётом =count/{nbt}). */
    public String displayName(String forgeString) {
        if (forgeString == null || forgeString.isEmpty()) return "Галочка";
        ForgeName fn = ForgeName.parse(forgeString);
        if (fn.isCommand()) return "Команда: " + fn.command;
        if (fn.isGift()) {
            String n = NbtLite.giftName(fn.nbt);
            return n == null ? "Гифт" : n.replace("§r", "");
        }
        String nbtName = NbtLite.displayName(fn.nbt);
        if (nbtName != null && !nbtName.isEmpty()) return stripColor(nbtName);

        Entry e = byKey.get(fn.id);
        if (e == null && fn.metaRaw != null) e = byKey.get(fn.mod + ":" + fn.name);
        if (e == null) return "Кастомный ID предмета";
        return e.name;
    }

    /** Числовой id (как в игре) либо пустая строка. */
    public String numericId(String forgeString) {
        ForgeName fn = ForgeName.parse(forgeString);
        Entry e = byKey.get(fn.id);
        if (e == null && fn.metaRaw != null) e = byKey.get(fn.mod + ":" + fn.name);
        return e == null ? "" : e.itemId;
    }

    public Entry entry(String key) { return byKey.get(key); }

    public static String stripColor(String s) {
        if (s == null) return null;
        return s.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
    }

    // ---------------------------------------------------------------- иконки

    /**
     * Откуда брать иконки, которых в выгрузке нет. Подставляется мостом с игрой
     * ({@code link.IconFetcher}): живой клиент умеет нарисовать что угодно, в том
     * числе вариант предмета с NBT. Возвращает null, если картинки пока нет —
     * тогда рисуется то, что есть, а кадром позже появится настоящая.
     */
    public interface Extra {
        BufferedImage icon(String forgeString, boolean haveRough);
    }

    private static Extra extra;

    public static void setExtra(Extra source) { extra = source; }

    /** Иконка предмета 64x64 (или заглушка, если не найдена). */
    public BufferedImage icon(String forgeString) {
        String path = iconPath(forgeString);
        if (extra != null && (path == null || roughNbtMatch(forgeString, path))) {
            BufferedImage fromGame = extra.icon(forgeString, path != null);
            if (fromGame != null) return fromGame;
        }
        if (path == null) return fallbackIcon();
        BufferedImage img = imageCache.get(path);
        if (img != null) return img;
        img = read("img/" + path);
        if (img == null) img = fallbackIcon();
        if (imageCache.size() > 4000) imageCache.clear();
        imageCache.put(path, img);
        return img;
    }

    /** Есть ли точная иконка для этой строки предмета. */
    public boolean hasIcon(String forgeString) { return iconPath(forgeString) != null; }

    /** Относительный путь png внутри img/ либо null. */
    public String iconPath(String forgeString) {
        if (forgeString == null || forgeString.isEmpty()) return null;
        String cached = pathCache.get(forgeString);
        if (cached != null) return cached.isEmpty() ? null : cached;

        ForgeName fn = ForgeName.parse(forgeString);
        String result = null;
        if (!fn.isCommand() && !fn.mod.isEmpty() && !fn.name.isEmpty()) {
            String dir = esc(fn.mod);
            String base = esc(fn.name);
            Set<String> names = dirNames(dir);
            // Вариант с NBT: у него своя картинка с хвостом ~хеш. Сначала
            // указатель из items.json, потом просто файл с таким именем.
            if (fn.nbt != null) {
                String byIndex = iconByNbt.get(fn.id + "|" + NbtLite.canonical(fn.nbt));
                if (byIndex == null) {
                    Entry exact = byKey.get(fn.id + fn.nbt);
                    if (exact != null && exact.icon != null) byIndex = exact.icon;
                }
                if (byIndex == null) {
                    String guess = iconFileFor(forgeString);
                    if (guess != null && pack.has("img/" + guess)) byIndex = guess;
                }
                if (byIndex != null) {
                    pathCache.put(forgeString, byIndex);
                    return byIndex;
                }
            }
            if (!names.isEmpty()) {
                String meta = (fn.metaRaw != null && !fn.metaRaw.equals("*")) ? fn.metaRaw : null;
                if (meta != null && names.contains(base + "_" + meta)) result = dir + "/" + base + "_" + meta + ".png";
                else if (names.contains(base)) result = dir + "/" + base + ".png";
                else {
                    String prefix = base + "_";
                    String best = null;
                    for (String n : names) {
                        if (n.startsWith(prefix)) {
                            if (best == null || n.length() < best.length()
                                    || (n.length() == best.length() && n.compareTo(best) < 0)) best = n;
                        }
                    }
                    if (best != null) result = dir + "/" + best + ".png";
                }
                if (result == null) {
                    // запасной вариант: пробелы заменены подчёркиванием
                    String alt = base.replace(' ', '_');
                    if (names.contains(alt)) result = dir + "/" + alt + ".png";
                }
            }
        }
        pathCache.put(forgeString, result == null ? "" : result);
        return result;
    }

    private Set<String> dirNames(String dir) {
        Set<String> names = dirCache.get(dir);
        if (names != null) return names;
        names = new HashSet<>();
        String prefix = "img/" + dir + "/";
        for (String n : pack.list(prefix, ".png"))
            names.add(n.substring(prefix.length(), n.length() - 4));
        dirCache.put(dir, names);
        return names;
    }

    public BufferedImage fallbackIcon() {
        if (fallback != null) return fallback;
        fallback = read("img/minecraft/bedrock.png");
        if (fallback == null) {
            fallback = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = fallback.createGraphics();
            g.setColor(new java.awt.Color(0x55, 0x55, 0x55));
            g.fillRect(4, 4, 56, 56);
            g.setColor(new java.awt.Color(0x22, 0x22, 0x22));
            g.drawRect(4, 4, 55, 55);
            g.dispose();
        }
        return fallback;
    }

    private BufferedImage read(String path) {
        byte[] data = pack.bytes(path);
        if (data == null) return null;
        try {
            return ImageIO.read(new java.io.ByteArrayInputStream(data));
        } catch (IOException e) {
            return null;
        }
    }

    /** Нашлась только общая картинка предмета, а NBT у него есть — вид может отличаться. */
    private static boolean roughNbtMatch(String forgeString, String path) {
        return path != null && path.indexOf('~') < 0 && forgeString.indexOf('{') > 0;
    }

    /**
     * Имя файла иконки для строки предмета: {@code <мод>/<имя>[_мета][~хеш].png}.
     * Ровно так его считает мод-выгрузчик (DwQuestsBridge, {@code Names.iconPath}),
     * поэтому редактор находит выгруженный вариант с NBT, ничего не спрашивая.
     */
    public static String iconFileFor(String forgeString) {
        ForgeName fn = ForgeName.parse(forgeString);
        if (fn.isCommand() || fn.mod.isEmpty() || fn.name.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append(esc(fn.mod)).append('/').append(esc(fn.name));
        int meta = fn.metaRaw == null ? 0 : fn.meta;
        if (meta > 0 && meta != 32767) sb.append('_').append(meta);
        if (fn.nbt != null) sb.append('~').append(hash8(NbtLite.canonical(fn.nbt)));
        return sb.append(".png").toString();
    }

    /** Первые 8 знаков SHA-1 — так же, как в моде-выгрузчике. */
    public static String hash8(String canonical) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(Character.forDigit((d[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(d[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(canonical.hashCode());
        }
    }

    /** Иконка появилась на диске уже после загрузки — учесть её в поиске. */
    public void noticeNewIcon(String path) {
        int slash = path.indexOf('/');
        if (slash <= 0) return;
        Set<String> names = dirCache.get(path.substring(0, slash));
        if (names != null) names.add(path.substring(slash + 1, path.length() - 4));
        pathCache.clear();
    }

    /** Экранирование имени под файловую систему — так делал экспортёр иконок. */
    public static String esc(String name) {
        if (name == null) return "";
        String out = name;
        for (int i = 0; i < ILLEGAL.length; i++) out = out.replace(String.valueOf(ILLEGAL[i]), "$" + i + "$");
        return out;
    }

    // ---------------------------------------------------------------- поиск

    public List<Entry> search(String query, String modFilter, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Entry> out = new ArrayList<>();
        for (Entry e : all) {
            if (modFilter != null && !modFilter.isEmpty() && !e.mod.equals(modFilter)) continue;
            if (!q.isEmpty() && !e.search.contains(q)) continue;
            out.add(e);
            if (out.size() >= limit) break;
        }
        return out;
    }
}
