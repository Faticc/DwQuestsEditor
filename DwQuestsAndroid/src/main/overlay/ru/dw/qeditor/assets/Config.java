package ru.dw.qeditor.assets;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Настройки редактора на Android — {@code <данные приложения>/dwquests-editor.properties}.
 *
 * <p>Те же поля, что у настроек в игре, чтобы общий код ничего не замечал.
 * Отличается то, откуда всё берётся: ресурсы мода и иконки встроены в APK,
 * поэтому путей к ним нет, а {@link #uiScale} считается от плотности экрана
 * телефона, а не в пикселях: 100 — системный размер, 80 — помельче.
 */
public final class Config {

    private static final String FILE_NAME = "dwquests-editor.properties";

    /** Не используется: иконки встроены. Поле оставлено для общего кода. */
    public String iconsDir = "";
    public String lastDir = "";

    /** Перечитывать открытый файл, если его изменили снаружи (другим приложением). */
    public boolean watchFile = true;
    public int guiScale = 2;
    /** Размер интерфейса в процентах от системного; 0 — подобрать по экрану. */
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
    /** Про доступ к памяти телефона уже спросили — второй раз не надоедать. */
    public boolean askedStorage = false;

    /** Мост с игрой в Android-сборке не работает — поля только для общего кода. */
    public String clientDir = "";
    public int linkPort = 0;
    public String linkToken = "";
    public boolean linkAutoIcons = false;

    private final File file;

    private static Config instance;
    /** Каталог данных приложения — задаёт Activity до первого обращения. */
    private static File dataDir = new File(".");

    public static Config get() {
        if (instance == null) {
            instance = new Config();
            instance.load();
        }
        return instance;
    }

    /** Где лежат настройки и черновики: внутренний каталог приложения. */
    public static void init(File appDataDir) {
        dataDir = appDataDir.getAbsoluteFile();
        instance = null;
    }

    public static boolean inGame() { return false; }

    private Config() {
        this.file = new File(dataDir, FILE_NAME);
    }

    public static File programDir() { return dataDir; }

    public File propertiesFile() { return file; }

    private void load() {
        Properties p = new Properties();
        if (file.isFile()) {
            try (FileInputStream in = new FileInputStream(file)) {
                p.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException ignored) { }
        }
        lastDir = p.getProperty("lastDir", "");
        watchFile = Boolean.parseBoolean(p.getProperty("watchFile", "true"));
        guiScale = intOr(p.getProperty("guiScale"), 2);
        uiScale = intOr(p.getProperty("uiScale"), 0);
        lastFolder = p.getProperty("lastFolder", "");
        backupOnSave = Boolean.parseBoolean(p.getProperty("backupOnSave", "true"));
        reviewBeforeSave = Boolean.parseBoolean(p.getProperty("reviewBeforeSave", "true"));
        backupKeep = intOr(p.getProperty("backupKeep"), 60);
        autoBackupMinutes = intOr(p.getProperty("autoBackupMinutes"), 5);
        askedStorage = Boolean.parseBoolean(p.getProperty("askedStorage", "false"));
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
        p.setProperty("lastDir", lastDir);
        p.setProperty("watchFile", String.valueOf(watchFile));
        p.setProperty("guiScale", String.valueOf(guiScale));
        p.setProperty("uiScale", String.valueOf(uiScale));
        p.setProperty("lastFolder", lastFolder);
        p.setProperty("backupKeep", String.valueOf(backupKeep));
        p.setProperty("backupOnSave", String.valueOf(backupOnSave));
        p.setProperty("reviewBeforeSave", String.valueOf(reviewBeforeSave));
        p.setProperty("autoBackupMinutes", String.valueOf(autoBackupMinutes));
        p.setProperty("askedStorage", String.valueOf(askedStorage));
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            try (FileOutputStream out = new FileOutputStream(file)) {
                p.store(new java.io.OutputStreamWriter(out, java.nio.charset.StandardCharsets.UTF_8),
                        "DwQuests Editor");
            }
        } catch (IOException ignored) { }
    }

    public static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

    public File iconsImgDir() { return null; }

    public File itemsJson() { return null; }

    public File iconsWriteDir() { return new File(dataDir, "icons"); }
}
