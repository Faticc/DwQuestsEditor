package ru.dw.qeditor.assets;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Настройки редактора — {@code <клиент>/config/dwquests-editor.properties}.
 *
 * <p>Редактор живёт в игре, поэтому путей к ресурсам в настройках больше нет:
 * текстуры мода и шрифт отдаёт сама игра, а иконки предметов она же и рисует.
 * Консольный {@code QuestCli} запускается без игры — ему файл ищется рядом с
 * классами, как раньше.
 */
public final class Config {

    private static final String FILE_NAME = "dwquests-editor.properties";

    private static final String[] CLIENT_GUESSES = {
            "C:/Users/User/McSkill/clients/Industrial_1.7.10",
            System.getProperty("user.home") + "/McSkill/clients/Industrial_1.7.10"
    };

    /**
     * Папка иконок, дорисованных игрой: {@code img/} и {@code json/items.json}.
     * В игре — {@code <клиент>/dwq_export/icons}, та же, куда пишет выгрузка мода.
     */
    public String iconsDir = "";
    public String lastDir = "";

    /** Перечитывать открытый файл, если его изменили снаружи (CLI, другой редактор). */
    public boolean watchFile = true;
    public int guiScale = 2;
    /** Масштаб интерфейса редактора в процентах; 0 — по масштабу экрана системы. */
    public int uiScale = 0;

    /** Папка с вкладками, открытая в прошлый раз. */
    public String lastFolder = "";
    /** Сколько версий хранить на каждую вкладку. */
    public int backupKeep = 60;
    /** Делать копию при каждом сохранении. */
    public boolean backupOnSave = true;
    /** Перед сохранением показать, что изменится в файле. */
    public boolean reviewBeforeSave = true;
    /** Период автоматической копии при несохранённых правках, минут (0 — выключено). */
    public int autoBackupMinutes = 5;

    /** Папка клиента Minecraft. В игре — её собственная, для CLI — угадывается. */
    public String clientDir = "";
    /** Порт и ключ моста вручную (для CLI); пустые — берутся из bridge.json. */
    public int linkPort = 0;
    public String linkToken = "";
    /** Просить игру дорисовать иконку, которой ещё нет в папке иконок. */
    public boolean linkAutoIcons = true;

    private final File file;

    private static Config instance;
    /** Папка клиента, если редактор запущен в игре; иначе null. */
    private static File gameClientDir;

    public static Config get() {
        if (instance == null) {
            instance = new Config();
            instance.load();
        }
        return instance;
    }

    /** Редактор внутри игры: настройки в её {@code config/}, иконки — в её {@code dwq_export/}. */
    public static void initInGame(File clientDir) {
        gameClientDir = clientDir.getAbsoluteFile();
        instance = null;
    }

    public static boolean inGame() { return gameClientDir != null; }

    private Config() {
        this.file = gameClientDir != null
                ? new File(new File(gameClientDir, "config"), FILE_NAME)
                : new File(programDir(), FILE_NAME);
    }

    /** Каталог, где лежат классы редактора (jar мода или дерево классов). */
    public static File programDir() {
        try {
            File jar = new File(Config.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return jar.isFile() ? jar.getParentFile() : jar;
        } catch (Exception e) {
            return new File(".");
        }
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
        lastDir = p.getProperty("lastDir", "");
        watchFile = Boolean.parseBoolean(p.getProperty("watchFile", "true"));
        guiScale = intOr(p.getProperty("guiScale"), 2);
        uiScale = intOr(p.getProperty("uiScale"), 0);
        lastFolder = p.getProperty("lastFolder", "");
        backupOnSave = Boolean.parseBoolean(p.getProperty("backupOnSave", "true"));
        reviewBeforeSave = Boolean.parseBoolean(p.getProperty("reviewBeforeSave", "true"));
        backupKeep = intOr(p.getProperty("backupKeep"), 60);
        autoBackupMinutes = intOr(p.getProperty("autoBackupMinutes"), 5);
        clientDir = p.getProperty("clientDir", "");
        linkPort = intOr(p.getProperty("linkPort"), 0);
        linkToken = p.getProperty("linkToken", "");
        linkAutoIcons = Boolean.parseBoolean(p.getProperty("linkAutoIcons", "true"));
        autodetect();
    }

    private static int intOr(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public void save() {
        Properties p = new Properties();
        // в игре обе папки задаются самой игрой — в файл их писать незачем
        if (!inGame()) {
            p.setProperty("iconsDir", iconsDir);
            p.setProperty("clientDir", clientDir);
        }
        p.setProperty("lastDir", lastDir);
        p.setProperty("watchFile", String.valueOf(watchFile));
        p.setProperty("guiScale", String.valueOf(guiScale));
        p.setProperty("uiScale", String.valueOf(uiScale));
        p.setProperty("lastFolder", lastFolder);
        p.setProperty("backupKeep", String.valueOf(backupKeep));
        p.setProperty("backupOnSave", String.valueOf(backupOnSave));
        p.setProperty("reviewBeforeSave", String.valueOf(reviewBeforeSave));
        p.setProperty("autoBackupMinutes", String.valueOf(autoBackupMinutes));
        p.setProperty("linkPort", String.valueOf(linkPort));
        p.setProperty("linkToken", linkToken);
        p.setProperty("linkAutoIcons", String.valueOf(linkAutoIcons));
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            try (FileOutputStream out = new FileOutputStream(file)) {
                p.store(new java.io.OutputStreamWriter(out, java.nio.charset.StandardCharsets.UTF_8),
                        "DwQuests Editor");
            }
        } catch (IOException ignored) { }
    }

    /**
     * В игре папки известны точно. Для консоли — установленный клиент по
     * догадке: оттуда CLI берёт мост и выгруженные иконки.
     */
    private void autodetect() {
        if (gameClientDir != null) {
            clientDir = gameClientDir.getPath();
            iconsDir = new File(new File(gameClientDir, "dwq_export"), "icons").getPath();
            return;
        }
        if (isBlank(clientDir)) {
            for (String guess : CLIENT_GUESSES) {
                if (new File(new File(guess), "mods").isDirectory()) {
                    clientDir = guess;
                    break;
                }
            }
        }
        if (isBlank(iconsDir) && !isBlank(clientDir)) {
            File icons = new File(new File(clientDir, "dwq_export"), "icons");
            if (new File(icons, "img").isDirectory()) iconsDir = icons.getPath();
        }
    }

    public static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

    public File iconsImgDir() { return isBlank(iconsDir) ? null : new File(iconsDir, "img"); }

    public File itemsJson() { return isBlank(iconsDir) ? null : new File(new File(iconsDir, "json"), "items.json"); }

    /** Куда класть иконки, дорисованные игрой, — в папку иконок, чтобы в следующий раз они нашлись сами. */
    public File iconsWriteDir() {
        if (!isBlank(iconsDir)) return new File(iconsDir);
        return new File(programDir(), "icons");
    }
}
