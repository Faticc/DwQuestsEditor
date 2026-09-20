package ru.dw.qeditor.assets;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/** Пути к ресурсам клиента. Хранятся рядом с программой в dwquests-editor.properties. */
public final class Config {

    private static final String FILE_NAME = "dwquests-editor.properties";

    private static final String[] CLIENT_GUESSES = {
            "C:/Users/User/McSkill/clients/Industrial_1.7.10",
            System.getProperty("user.home") + "/McSkill/clients/Industrial_1.7.10"
    };

    public String iconsDir = "";
    public String dwQuestsJar = "";
    public String dwCoreJar = "";
    public String mcClientJar = "";
    public String lastDir = "";

    /** Настройки помощника: исполняемый файл Claude Code CLI и модель. */
    public String claudeExe = "claude";
    public String claudeModel = "sonnet";
    /** Перечитывать открытый файл, если его изменили снаружи (CLI, другой редактор). */
    public boolean watchFile = true;
    public int guiScale = 2;

    private final File file;

    private static Config instance;

    public static Config get() {
        if (instance == null) {
            instance = new Config();
            instance.load();
        }
        return instance;
    }

    private Config() {
        File dir;
        try {
            File jar = new File(Config.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            dir = jar.isFile() ? jar.getParentFile() : jar;
        } catch (Exception e) {
            dir = new File(".");
        }
        this.file = new File(dir, FILE_NAME);
    }

    public File propertiesFile() { return file; }

    private void load() {
        Properties p = new Properties();
        if (file.isFile()) {
            try (FileInputStream in = new FileInputStream(file)) {
                p.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException ignored) { }
        }
        iconsDir = p.getProperty("iconsDir", "");
        dwQuestsJar = p.getProperty("dwQuestsJar", "");
        dwCoreJar = p.getProperty("dwCoreJar", "");
        mcClientJar = p.getProperty("mcClientJar", "");
        lastDir = p.getProperty("lastDir", "");
        claudeExe = p.getProperty("claudeExe", "claude");
        claudeModel = p.getProperty("claudeModel", "sonnet");
        watchFile = Boolean.parseBoolean(p.getProperty("watchFile", "true"));
        try {
            guiScale = Integer.parseInt(p.getProperty("guiScale", "2"));
        } catch (NumberFormatException e) {
            guiScale = 2;
        }
        autodetect();
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("iconsDir", iconsDir);
        p.setProperty("dwQuestsJar", dwQuestsJar);
        p.setProperty("dwCoreJar", dwCoreJar);
        p.setProperty("mcClientJar", mcClientJar);
        p.setProperty("lastDir", lastDir);
        p.setProperty("claudeExe", claudeExe);
        p.setProperty("claudeModel", claudeModel);
        p.setProperty("watchFile", String.valueOf(watchFile));
        p.setProperty("guiScale", String.valueOf(guiScale));
        try (FileOutputStream out = new FileOutputStream(file)) {
            p.store(new java.io.OutputStreamWriter(out, java.nio.charset.StandardCharsets.UTF_8),
                    "DwQuests Editor — пути к ресурсам клиента");
        } catch (IOException ignored) { }
    }

    /** Заполняет пустые пути, если клиент лежит на обычном месте. */
    public void autodetect() {
        for (String guess : CLIENT_GUESSES) {
            File client = new File(guess);
            if (!client.isDirectory()) continue;
            if (isBlank(iconsDir)) {
                File icons = new File(client, "icon_exporter");
                if (new File(icons, "img").isDirectory()) iconsDir = icons.getAbsolutePath();
            }
            File mods = new File(client, "mods");
            if (isBlank(dwQuestsJar)) dwQuestsJar = findJar(mods, "DwQuests");
            if (isBlank(dwCoreJar)) dwCoreJar = findJar(mods, "DwCore");
            break;
        }
        if (isBlank(mcClientJar)) {
            File[] candidates = {
                    new File(System.getProperty("user.home") + "/.gradle/caches/retro_futura_gradle/mc-vanilla/1.7.10/client.jar"),
                    new File(System.getProperty("user.home") + "/AppData/Roaming/.minecraft/versions/1.7.10/1.7.10.jar")
            };
            for (File c : candidates) if (c.isFile()) { mcClientJar = c.getAbsolutePath(); break; }
        }
    }

    private static String findJar(File dir, String prefix) {
        if (!dir.isDirectory()) return "";
        File[] list = dir.listFiles((d, n) -> n.startsWith(prefix) && n.toLowerCase().endsWith(".jar"));
        if (list == null || list.length == 0) return "";
        File best = list[0];
        for (File f : list) if (f.length() > best.length()) best = f;
        return best.getAbsolutePath();
    }

    public static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

    public File iconsImgDir() { return isBlank(iconsDir) ? null : new File(iconsDir, "img"); }

    public File itemsJson() { return isBlank(iconsDir) ? null : new File(new File(iconsDir, "json"), "items.json"); }
}
