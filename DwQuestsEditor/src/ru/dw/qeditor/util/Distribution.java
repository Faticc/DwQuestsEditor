package ru.dw.qeditor.util;

import ru.dw.qeditor.assets.Config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Сборка одного самодостаточного jar-а: классы редактора, библиотеки LWJGL
 * с нативными частями и все ассеты внутри.
 *
 * <p>Смысл — отдать человеку один файл. Иконки предметов и ресурсы мода
 * ложатся в {@code dwq-assets/}, рядом пишется список записей
 * ({@code index.txt}): перечислить каталог в classpath штатно нельзя, а
 * редактору это нужно — например, чтобы узнать, у каких вкладок есть фон.
 *
 * <p>Запуск: {@code java -cp out ru.dw.qeditor.util.Distribution}
 * (обычно через {@code package.bat}).
 */
public final class Distribution {

    /** Что не переносим из библиотек: подписи и модульные описания только мешают. */
    private static final Set<String> SKIP_EXACT = new HashSet<>(Arrays.asList(
            "module-info.class", "META-INF/MANIFEST.MF", "META-INF/INDEX.LIST"));

    private Distribution() { }

    public static void main(String[] args) throws IOException {
        if (args.length == 3 && "zip".equals(args[0])) {
            zipFolder(new File(args[1]), new File(args[2]));
            return;
        }
        buildJar();
    }

    /** Заархивировать готовую папку приложения — её и отправляют. */
    private static void zipFolder(File dir, File out) throws IOException {
        if (!dir.isDirectory()) throw new IllegalStateException("Нет папки: " + dir.getAbsolutePath());
        if (out.getParentFile() != null) {
            //noinspection ResultOfMethodCallIgnored
            out.getParentFile().mkdirs();
        }
        long start = System.currentTimeMillis();
        Set<String> written = new HashSet<>();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out.toPath()),
                StandardCharsets.UTF_8)) {
            // внутри архива — папка с тем же именем: так она и распакуется
            zip.setLevel(Deflater.BEST_SPEED);
            addTree(zip, dir, dir.getName() + "/", written, null, false);
        }
        System.out.printf("Готово: %s (%.1f МБ, %d с)%n", out.getAbsolutePath(),
                out.length() / 1e6, (System.currentTimeMillis() - start) / 1000);
    }

    private static void buildJar() throws IOException {
        File project = Config.programDir().getName().equals("out")
                ? Config.programDir().getParentFile()
                : new File(".");
        File classes = new File(project, "out");
        File libs = new File(project, "lib");
        File runtime = new File(project.getParentFile(), "runtime");
        File outDir = new File(project, "dist/jar");
        File jar = new File(outDir, "DwQuestsEditor.jar");

        require(classes, "каталог классов out (сначала build.bat)");
        require(libs, "каталог библиотек lib (mvnw -B dependency:copy-dependencies)");
        require(runtime, "каталог ресурсов runtime");
        //noinspection ResultOfMethodCallIgnored
        outDir.mkdirs();

        long start = System.currentTimeMillis();
        List<String> index = new ArrayList<>();
        Set<String> written = new HashSet<>();

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar.toPath()),
                StandardCharsets.UTF_8)) {
            zip.setLevel(Deflater.BEST_SPEED);

            manifest(zip, written);

            int classCount = addTree(zip, classes, "", written, null, true);
            System.out.println("классов и ресурсов редактора: " + classCount);

            int libCount = 0;
            File[] jars = libs.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".jar"));
            if (jars != null) {
                Arrays.sort(jars);
                for (File lib : jars) libCount += addJar(zip, lib, written);
            }
            System.out.println("записей из библиотек LWJGL: " + libCount);

            // ассеты кладём без сжатия: png и так сжаты, а так сборка быстрее
            zip.setLevel(Deflater.NO_COMPRESSION);
            int game = addTree(zip, new File(runtime, "game"), "dwq-assets/game/", written, index, false);
            int icons = addTree(zip, new File(runtime, "icons"), "dwq-assets/icons/", written, index, false);
            System.out.println("ресурсов мода: " + game + ", иконок и метаданных: " + icons);

            zip.setLevel(Deflater.BEST_SPEED);
            entry(zip, "dwq-assets/index.txt", String.join("\n", index).getBytes(StandardCharsets.UTF_8),
                    written);
        }

        System.out.printf("%nГотово: %s (%.1f МБ, %d с)%n", jar.getAbsolutePath(),
                jar.length() / 1e6, (System.currentTimeMillis() - start) / 1000);
    }

    private static void require(File f, String what) {
        if (!f.isDirectory()) throw new IllegalStateException("Не найден " + what + ": " + f.getAbsolutePath());
    }

    private static void manifest(ZipOutputStream zip, Set<String> written) throws IOException {
        String text = "Manifest-Version: 1.0\r\n"
                + "Main-Class: ru.dw.qeditor.Main\r\n"
                + "Implementation-Title: DwQuests Editor\r\n"
                + "Implementation-Version: 2.0.0\r\n"
                + "\r\n";
        entry(zip, "META-INF/MANIFEST.MF", text.getBytes(StandardCharsets.UTF_8), written);
    }

    /**
     * Переложить дерево файлов в jar.
     *
     * @param index куда собирать список записей (пути без {@code dwq-assets/}), либо null
     * @return сколько файлов добавлено
     */
    private static int addTree(ZipOutputStream zip, File root, String prefix,
                               Set<String> written, List<String> index, boolean classesTree)
            throws IOException {
        if (!root.isDirectory()) return 0;
        int count = 0;
        File[] list = root.listFiles();
        if (list == null) return 0;
        Arrays.sort(list);
        for (File f : list) {
            String name = prefix + f.getName();
            if (f.isDirectory()) {
                count += addTree(zip, f, name + "/", written, index, classesTree);
                continue;
            }
            // настройки и файл списка исходников в дистрибутиве не нужны
            if (classesTree && (f.getName().endsWith(".properties") || f.getName().endsWith(".txt"))) continue;
            entry(zip, name, Files.readAllBytes(f.toPath()), written);
            if (index != null) index.add(name.substring("dwq-assets/".length()));
            count++;
        }
        return count;
    }

    private static int addJar(ZipOutputStream zip, File file, Set<String> written) throws IOException {
        int count = 0;
        try (ZipFile in = new ZipFile(file)) {
            var entries = in.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (SKIP_EXACT.contains(name)) continue;
                if (name.startsWith("META-INF/") && isSignature(name)) continue;
                try (InputStream stream = in.getInputStream(e)) {
                    if (entry(zip, name, stream.readAllBytes(), written)) count++;
                }
            }
        }
        return count;
    }

    private static boolean isSignature(String name) {
        String n = name.toUpperCase(Locale.ROOT);
        return n.endsWith(".SF") || n.endsWith(".DSA") || n.endsWith(".RSA") || n.endsWith(".EC");
    }

    /** Записать запись, если такой ещё нет. */
    private static boolean entry(ZipOutputStream zip, String name, byte[] data, Set<String> written)
            throws IOException {
        if (!written.add(name)) return false;
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
        return true;
    }
}
