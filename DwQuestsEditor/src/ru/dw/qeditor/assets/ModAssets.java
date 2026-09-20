package ru.dw.qeditor.assets;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Доступ к ресурсам прямо из jar-ов клиента: фоны вкладок и картинки описаний
 * (DwQuests), звёзды/ноды/Default-фон (DwCore), шрифт Minecraft (client.jar).
 */
public final class ModAssets {

    private static ModAssets instance;

    public static ModAssets get() {
        if (instance == null) instance = new ModAssets();
        return instance;
    }

    private ZipFile quests;
    private ZipFile core;
    private ZipFile mc;
    private final Map<String, BufferedImage> cache = new HashMap<>();
    private final List<String> problems = new ArrayList<>();

    private ModAssets() { open(); }

    public List<String> problems() { return Collections.unmodifiableList(problems); }

    public void reopen() {
        close();
        cache.clear();
        problems.clear();
        open();
    }

    private void open() {
        Config c = Config.get();
        quests = openZip(c.dwQuestsJar, "DwQuests");
        core = openZip(c.dwCoreJar, "DwCore");
        mc = openZip(c.mcClientJar, "client.jar (Minecraft 1.7.10)");
    }

    private ZipFile openZip(String path, String label) {
        if (Config.isBlank(path)) {
            problems.add("Не указан путь: " + label);
            return null;
        }
        try {
            return new ZipFile(path);
        } catch (IOException e) {
            problems.add("Не удалось открыть " + label + ": " + e.getMessage());
            return null;
        }
    }

    public void close() {
        closeQuiet(quests);
        closeQuiet(core);
        closeQuiet(mc);
        quests = core = mc = null;
    }

    private static void closeQuiet(ZipFile z) {
        if (z != null) try { z.close(); } catch (IOException ignored) { }
    }

    public boolean hasQuestsJar() { return quests != null; }

    public boolean hasCoreJar() { return core != null; }

    public boolean hasMcJar() { return mc != null; }

    // ------------------------------------------------------------ низкий уровень

    public byte[] bytes(ZipFile zip, String entry) {
        if (zip == null) return null;
        ZipEntry e = zip.getEntry(entry);
        if (e == null) return null;
        try (InputStream in = zip.getInputStream(e)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.max(64, (int) e.getSize()));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (IOException ex) {
            return null;
        }
    }

    public byte[] mcBytes(String entry) { return bytes(mc, entry); }

    private BufferedImage image(ZipFile zip, String entry, String cacheKey) {
        if (zip == null) return null;
        BufferedImage img = cache.get(cacheKey);
        if (img != null) return img;
        ZipEntry e = zip.getEntry(entry);
        if (e == null) return null;
        try (InputStream in = zip.getInputStream(e)) {
            img = ImageIO.read(in);
        } catch (IOException ex) {
            img = null;
        }
        if (img != null) cache.put(cacheKey, img);
        return img;
    }

    // ------------------------------------------------------------ ресурсы мода

    /** Фон вкладки: assets/dwquests/textures/gui/backgrounds/&lt;tabID&gt;.png, иначе Default из DwCore. */
    public BufferedImage tabBackground(String tabID) {
        if (tabID != null && !tabID.isEmpty()) {
            BufferedImage img = image(quests, "assets/dwquests/textures/gui/backgrounds/" + tabID + ".png", "bg:" + tabID);
            if (img != null) return img;
        }
        return defaultBackground();
    }

    public boolean hasTabBackground(String tabID) {
        return quests != null && tabID != null
                && quests.getEntry("assets/dwquests/textures/gui/backgrounds/" + tabID + ".png") != null;
    }

    /** Список вкладок, для которых в моде есть фон. */
    public List<String> knownTabBackgrounds() {
        List<String> out = new ArrayList<>();
        if (quests == null) return out;
        String prefix = "assets/dwquests/textures/gui/backgrounds/";
        Enumeration<? extends ZipEntry> e = quests.entries();
        TreeSet<String> sorted = new TreeSet<>();
        while (e.hasMoreElements()) {
            String n = e.nextElement().getName();
            if (n.startsWith(prefix) && n.endsWith(".png")) sorted.add(n.substring(prefix.length(), n.length() - 4));
        }
        out.addAll(sorted);
        return out;
    }

    public BufferedImage defaultBackground() {
        return image(core, "assets/dwcore/textures/gui/backgrounds/Default.png", "bg:__default");
    }

    public BufferedImage stars() {
        return image(core, "assets/dwcore/textures/gui/backgrounds/Stars.png", "stars");
    }

    public BufferedImage uiElements() {
        return image(core, "assets/dwcore/textures/gui/UIElements.png", "ui");
    }

    public BufferedImage nodes() {
        return image(core, "assets/dwcore/textures/misc/nodes.png", "nodes");
    }

    public BufferedImage nodes2() {
        return image(core, "assets/dwcore/textures/misc/nodes2.png", "nodes2");
    }

    public BufferedImage killTaskIcon() {
        return image(quests, "assets/dwquests/textures/gui/KillTaskIcon.png", "killicon");
    }

    /** Текстура слоя гифта: gifts/&lt;texture&gt;_&lt;id&gt;_&lt;pass&gt;.png */
    public BufferedImage giftLayer(String texture, int id, int pass) {
        String key = "gift:" + texture + ":" + id + ":" + pass;
        return image(quests, "assets/dwquests/textures/items/gifts/" + texture + "_" + id + "_" + pass + ".png", key);
    }

    /** Картинка из описания квеста: тег &lt;IMG&gt;mod:textures/...:u:v:w:h[:scale]&lt;/IMG&gt;. */
    public BufferedImage modTexture(String modID, String path) {
        String domain = modID == null ? "" : modID.toLowerCase();
        String entry = "assets/" + domain + "/" + path;
        ZipFile zip = "dwcore".equals(domain) ? core : quests;
        BufferedImage img = image(zip, entry, "tex:" + domain + ":" + path);
        if (img == null) img = image(quests, entry, "texq:" + domain + ":" + path);
        if (img == null) img = image(core, entry, "texc:" + domain + ":" + path);
        if (img == null) img = image(mc, entry, "texm:" + domain + ":" + path);
        return img;
    }
}
