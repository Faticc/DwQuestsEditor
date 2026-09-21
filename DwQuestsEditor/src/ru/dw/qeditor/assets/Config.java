package ru.dw.qeditor.assets;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Пути к ресурсам. Хранятся рядом с программой в dwquests-editor.properties.
 *
 * <p>Jar-ов мода редактору больше не нужно: картинки и шрифт вытащены из них
 * в {@code runtime/game/assets/…} и лежат в самом проекте (а в собранном
 * дистрибутиве — внутри его jar-а). Пути остались только как способ подложить
 * свои ресурсы: свежий экспорт иконок или обновлённые текстуры мода.
 */
public final class Config {

    private static final String FILE_NAME = "dwquests-editor.properties";

    private static final String[] CLIENT_GUESSES = {
            "C:/Users/User/McSkill/clients/Industrial_1.7.10",
            System.getProperty("user.home") + "/McSkill/clients/Industrial_1.7.10"
    };

    /** Папка иконок предметов: {@code img/} и {@code json/items.json}. */
    public String iconsDir = "";
    /** Папка ресурсов мода: внутри {@code assets/dwquests}, {@code assets/dwcore}, {@code assets/minecraft}. */
    public String gameDir = "";
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

    /** Папка клиента Minecraft — в ней мод-мост оставляет dwq_export/bridge.json. */
    public String clientDir = "";
    /** Порт и ключ моста вручную; пустые — берутся из bridge.json. */
    public int linkPort = 0;
    public String linkToken = "";
    /** Просить игру дорисовать иконку, которой нет в выгрузке (варианты с NBT). */
    public boolean linkAutoIcons = true;

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
        this.file = new File(programDir(), FILE_NAME);
    }

    /** Каталог, где лежит сама программа (jar или дерево классов). */
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
        gameDir = p.getProperty("gameDir", "");
        lastDir = p.getProperty("lastDir", "");
        claudeExe = p.getProperty("claudeExe", "claude");
        claudeModel = p.getProperty("claudeModel", "sonnet");
        watchFile = Boolean.parseBoolean(p.getProperty("watchFile", "true"));
        guiScale = intOr(p.getProperty("guiScale"), 2);
        lastFolder = p.getProperty("lastFolder", "");
        backupOnSave = Boolean.parseBoolean(p.getProperty("backupOnSave", "true"));
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
        p.setProperty("iconsDir", iconsDir);
        p.setProperty("gameDir", gameDir);
        p.setProperty("lastDir", lastDir);
        p.setProperty("claudeExe", claudeExe);
        p.setProperty("claudeModel", claudeModel);
        p.setProperty("watchFile", String.valueOf(watchFile));
        p.setProperty("guiScale", String.valueOf(guiScale));
        p.setProperty("lastFolder", lastFolder);
        p.setProperty("backupKeep", String.valueOf(backupKeep));
        p.setProperty("backupOnSave", String.valueOf(backupOnSave));
        p.setProperty("autoBackupMinutes", String.valueOf(autoBackupMinutes));
        p.setProperty("clientDir", clientDir);
        p.setProperty("linkPort", String.valueOf(linkPort));
        p.setProperty("linkToken", linkToken);
        p.setProperty("linkAutoIcons", String.valueOf(linkAutoIcons));
        try (FileOutputStream out = new FileOutputStream(file)) {
            p.store(new java.io.OutputStreamWriter(out, java.nio.charset.StandardCharsets.UTF_8),
                    "DwQuests Editor — пути к ресурсам");
        } catch (IOException ignored) { }
    }

    /**
     * Каталог {@code runtime} с ресурсами. Ищем рядом с программой и на пару
     * уровней выше (в репозитории он в корне, а классы — в
     * {@code DwQuestsEditor/out}).
     *
     * <p>Каталог обязан содержать {@code game} или {@code icons}: у собранного
     * через jpackage приложения рядом лежит своя папка {@code runtime} — это
     * встроенная Java, и принять её за ресурсы нельзя.
     */
    public File runtimeDir() {
        File base = programDir();
        for (int i = 0; i < 4 && base != null; i++) {
            File candidate = new File(base, "runtime");
            if (looksLikeRuntime(candidate)) return candidate;
            base = base.getParentFile();
        }
        return null;
    }

    private static boolean looksLikeRuntime(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        return new File(dir, "game/assets").isDirectory() || new File(dir, "icons/img").isDirectory();
    }

    /**
     * Заполняет пустые пути: сначала {@code runtime/} рядом с программой,
     * потом установленный клиент. Если не нашлось ни того, ни другого, пути
     * останутся пустыми — тогда ресурсы возьмутся из самого jar-а
     * (см. {@link AssetPack#hasEmbedded()}).
     */
    public void autodetect() {
        File dir = runtimeDir();
        if (dir != null) {
            if (isBlank(iconsDir) && new File(dir, "icons/json/items.json").isFile())
                iconsDir = new File(dir, "icons").getAbsolutePath();
            if (isBlank(gameDir) && new File(dir, "game/assets").isDirectory())
                gameDir = new File(dir, "game").getAbsolutePath();
        }
        if (isBlank(clientDir)) {
            for (String guess : CLIENT_GUESSES) {
                if (new File(new File(guess), "mods").isDirectory()) {
                    clientDir = guess;
                    break;
                }
            }
        }
        // установленный клиент — последняя надежда: если ресурсы уже вшиты
        // в программу, угадывать чужие пути незачем
        if (isBlank(iconsDir) && !AssetPack.hasEmbedded()) {
            for (String guess : CLIENT_GUESSES) {
                File icons = new File(new File(guess), "icon_exporter");
                if (new File(icons, "img").isDirectory()) {
                    iconsDir = icons.getAbsolutePath();
                    break;
                }
            }
        }
    }

    public static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

    public File iconsImgDir() { return isBlank(iconsDir) ? null : new File(iconsDir, "img"); }

    public File itemsJson() { return isBlank(iconsDir) ? null : new File(new File(iconsDir, "json"), "items.json"); }

    /**
     * Куда класть иконки, дорисованные игрой. Если папка иконок задана — прямо
     * в неё, чтобы в следующий раз они нашлись сами; иначе рядом с программой
     * (ресурсы могут быть вшиты в jar, писать внутрь него некуда).
     */
    public File iconsWriteDir() {
        if (!isBlank(iconsDir)) return new File(iconsDir);
        return new File(programDir(), "icons");
    }
}
