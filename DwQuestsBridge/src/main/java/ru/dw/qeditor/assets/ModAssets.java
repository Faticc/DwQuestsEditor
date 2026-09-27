package ru.dw.qeditor.assets;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Ресурсы мода: фоны вкладок и картинки описаний (DwQuests), звёзды, ноды и
 * Default-фон (DwCore), шрифт Minecraft.
 *
 * <p>Редактор живёт в игре, поэтому ресурсы — её собственные: набор с путями
 * вида {@code assets/<домен>/…} читает менеджер ресурсов Minecraft (с учётом
 * пакетов ресурсов), его подставляет мод через {@link #useGamePack}. Без игры
 * (консоль) набор пустой — рисовать там нечего.
 */
public final class ModAssets {

    private static ModAssets instance;

    public static ModAssets get() {
        if (instance == null) instance = new ModAssets();
        return instance;
    }

    /** Ресурсы игры — подставляет мод при запуске редактора. */
    private static AssetPack gamePack;

    public static void useGamePack(AssetPack pack) {
        gamePack = pack;
        if (instance != null) instance.reopen();
    }

    private AssetPack pack = AssetPack.empty("не открыто");
    private final Map<String, BufferedImage> cache = new HashMap<>();
    private final List<String> problems = new ArrayList<>();
    private ModAssets() { open(); }

    public List<String> problems() { return Collections.unmodifiableList(problems); }

    /** Откуда загрузились ресурсы — для диагностики. */
    public String describe() { return pack.describe(); }

    public void reopen() {
        close();
        cache.clear();
        problems.clear();
        open();
    }

    private void open() {
        if (gamePack != null) {
            pack = gamePack;
            return;
        }
        pack = AssetPack.empty("нет игры");
        problems.add("Ресурсы мода отдаёт игра, а редактор запущен без неё");
    }

    public void close() {
        if (pack != gamePack) pack.close();
        pack = AssetPack.empty("закрыто");
    }

    public boolean hasQuestsAssets() { return pack.has("assets/dwquests/lang/ru_RU.lang"); }

    public boolean hasCoreAssets() { return pack.has("assets/dwcore/textures/misc/nodes.png"); }

    public boolean hasFont() { return pack.has("assets/minecraft/font/glyph_sizes.bin"); }

    // ------------------------------------------------------------ низкий уровень

    public byte[] bytes(String entry) { return pack.bytes(entry); }

    private BufferedImage image(String entry, String cacheKey) {
        BufferedImage img = cache.get(cacheKey);
        if (img != null) return img;
        byte[] data = pack.bytes(entry);
        if (data == null) return null;
        try {
            img = ImageIO.read(new ByteArrayInputStream(data));
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
            BufferedImage img = image(
                    "assets/dwquests/textures/gui/backgrounds/" + tabID + ".png", "bg:" + tabID);
            if (img != null) return img;
        }
        return defaultBackground();
    }

    public boolean hasTabBackground(String tabID) {
        return tabID != null && pack.has("assets/dwquests/textures/gui/backgrounds/" + tabID + ".png");
    }

    /** Список вкладок, для которых в моде есть фон. */
    public List<String> knownTabBackgrounds() {
        String prefix = "assets/dwquests/textures/gui/backgrounds/";
        TreeSet<String> sorted = new TreeSet<>();
        for (String n : pack.list(prefix, ".png"))
            sorted.add(n.substring(prefix.length(), n.length() - 4));
        return new ArrayList<>(sorted);
    }

    public BufferedImage defaultBackground() {
        return image("assets/dwcore/textures/gui/backgrounds/Default.png", "bg:__default");
    }

    public BufferedImage stars() {
        return image("assets/dwcore/textures/gui/backgrounds/Stars.png", "stars");
    }

    public BufferedImage uiElements() {
        return image("assets/dwcore/textures/gui/UIElements.png", "ui");
    }

    public BufferedImage nodes() {
        return image("assets/dwcore/textures/misc/nodes.png", "nodes");
    }

    public BufferedImage nodes2() {
        return image("assets/dwcore/textures/misc/nodes2.png", "nodes2");
    }

    public BufferedImage killTaskIcon() {
        return image("assets/dwquests/textures/gui/KillTaskIcon.png", "killicon");
    }

    /** Текстура слоя гифта: gifts/&lt;texture&gt;_&lt;id&gt;_&lt;pass&gt;.png */
    public BufferedImage giftLayer(String texture, int id, int pass) {
        String key = "gift:" + texture + ":" + id + ":" + pass;
        return image(
                "assets/dwquests/textures/items/gifts/" + texture + "_" + id + "_" + pass + ".png", key);
    }

    /** Картинка из описания квеста: тег &lt;IMG&gt;mod:textures/...:u:v:w:h[:scale]&lt;/IMG&gt;. */
    public BufferedImage modTexture(String modID, String path) {
        String domain = modID == null ? "" : modID.toLowerCase();
        return image("assets/" + domain + "/" + path, "tex:" + domain + ":" + path);
    }
}
