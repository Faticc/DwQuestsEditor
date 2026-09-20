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

    /** Папка с вкладками, открытая в прошлый раз. */
    public String lastFolder = "";
    /** Сколько версий хранить на каждую вкладку. */
    public int backupKeep = 60;
    /** Делать копию при каждом сохранении. */
    public boolean backupOnSave = true;
    /** Период автоматической копии при несохранённых правках, минут (0 — выключено). */
    public int autoBackupMinutes = 5;

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
        lastFolder = p.getProperty("lastFolder", "");
        backupOnSave = Boolean.parseBoolean(p.getProperty("backupOnSave", "true"));
        try {
            backupKeep = Integer.parseInt(p.getProperty("backupKeep", "60"));
        } catch (NumberFormatException e) {
            backupKeep = 60;
        }
        try {
            autoBackupMinutes = Integer.parseInt(p.getProperty("autoBackupMinutes", "5"));
        } catch (NumberFormatException e) {
            autoBackupMinutes = 5;
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
        p.setProperty("lastFolder", lastFolder);
        p.setProperty("backupKeep", String.valueOf(backupKeep));
        p.setProperty("backupOnSave", String.valueOf(backupOnSave));
        p.setProperty("autoBackupMinutes", String.valueOf(autoBackupMinutes));
        try (FileOutputStream out = new FileOutputStream(file)) {
            p.store(new java.io.OutputStreamWriter(out, java.nio.charset.StandardCharsets.UTF_8),
                    "DwQuests Editor — пути к ресурсам клиента");
        } catch (IOException ignored) { }
    }

    /**
     * Ресурсы, положенные рядом с программой в каталоге {@code runtime/}:
     * иконки предметов и урезанные jar-ы с текстурами и шрифтом. Это позволяет
     * работать на машине, где клиент Minecraft не установлен.
     */
    private boolean autodetectRuntime() {
        File dir = runtimeDir();
        if (dir == null) return false;

        File icons = new File(dir, "icons");
        File mods = new File(dir, "mods");
        boolean found = false;
        if (isBlank(iconsDir) && new File(icons, "json/items.json").isFile()) {
            iconsDir = icons.getAbsolutePath();
            found = true;
        }
        if (isBlank(dwQuestsJar)) {
            File f = new File(mods, "DwQuests-assets.jar");
            if (f.isFile()) { dwQuestsJar = f.getAbsolutePath(); found = true; }
        }
        if (isBlank(dwCoreJar)) {
            File f = new File(mods, "DwCore-assets.jar");
            if (f.isFile()) { dwCoreJar = f.getAbsolutePath(); found = true; }
        }
        if (isBlank(mcClientJar)) {
            File f = new File(mods, "minecraft-font.jar");
            if (f.isFile()) { mcClientJar = f.getAbsolutePath(); found = true; }
        }
        return found;
    }

    /** Каталог runtime рядом с программой либо на уровень выше (репозиторий). */
    public File runtimeDir() {
        File base = file.getParentFile();
        for (int i = 0; i < 3 && base != null; i++) {
            File candidate = new File(base, "runtime");
            if (candidate.isDirectory()) return candidate;
            base = base.getParentFile();
        }
        return null;
    }

    /** Заполняет пустые пути: сначала из runtime рядом с программой, потом из клиента. */
    public void autodetect() {
        autodetectRuntime();
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
