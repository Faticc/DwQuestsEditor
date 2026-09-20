package ru.dw.qeditor.assets;

import ru.dw.qeditor.json.JsonObject;
import ru.dw.qeditor.json.JsonParser;
import ru.dw.qeditor.util.ForgeName;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
        final String search;

        Entry(String key, String name, String itemId) {
            this.key = key;
            this.name = name == null ? "" : name;
            this.itemId = itemId == null ? "" : itemId;
            int c = key.indexOf(':');
            this.mod = c > 0 ? key.substring(0, c) : key;
            this.search = (this.name + " " + key + " " + this.itemId).toLowerCase(Locale.ROOT);
        }
    }

    private static ItemRegistry instance;

    private final Map<String, Entry> byKey = new LinkedHashMap<>();
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

    public int size() { return all.size(); }

    public List<Entry> entries() { return Collections.unmodifiableList(all); }

    public Set<String> mods() { return Collections.unmodifiableSet(mods); }

    public void reload() {
        byKey.clear();
        all.clear();
        mods.clear();
        dirCache.clear();
        imageCache.clear();
        pathCache.clear();
        fallback = null;
        loadError = null;

        File json = Config.get().itemsJson();
        if (json == null || !json.isFile()) {
            loadError = "Не найден items.json (укажите папку icon_exporter в настройках)";
            return;
        }
        try {
            String text = new String(Files.readAllBytes(json.toPath()), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseObject(text);
            for (String key : root.keys()) {
                JsonObject o = root.obj(key);
                if (o == null) continue;
                Entry e = new Entry(key, o.str("itemName", key), o.str("itemId", ""));
                byKey.put(key, e);
                all.add(e);
                mods.add(e.mod);
            }
        } catch (IOException | RuntimeException e) {
            loadError = "Ошибка чтения items.json: " + e.getMessage();
        }
    }

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

    /** Иконка предмета 64x64 (или заглушка, если не найдена). */
    public BufferedImage icon(String forgeString) {
        String path = iconPath(forgeString);
        if (path == null) return fallbackIcon();
        BufferedImage img = imageCache.get(path);
        if (img != null) return img;
        File img_dir = Config.get().iconsImgDir();
        if (img_dir == null) return fallbackIcon();
        try {
            img = ImageIO.read(new File(img_dir, path));
        } catch (IOException e) {
            img = null;
        }
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
        File img = Config.get().iconsImgDir();
        if (img != null) {
            File d = new File(img, dir);
            String[] list = d.list();
            if (list != null)
                for (String n : list)
                    if (n.toLowerCase(Locale.ROOT).endsWith(".png")) names.add(n.substring(0, n.length() - 4));
        }
        dirCache.put(dir, names);
        return names;
    }

    public BufferedImage fallbackIcon() {
        if (fallback != null) return fallback;
        File img = Config.get().iconsImgDir();
        if (img != null) {
            File f = new File(img, "minecraft/bedrock.png");
            if (f.isFile()) {
                try { fallback = ImageIO.read(f); } catch (IOException ignored) { }
            }
        }
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
