package ru.dw.qbridge.export;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResource;
import net.minecraft.util.ResourceLocation;
import ru.dw.qbridge.DwqBridge;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Выгрузка ресурсов: текстуры и языковые файлы модов, шрифт Minecraft и
 * отдельные картинки, на которые ссылаются описания квестов.
 *
 * <p>На выходе — дерево {@code game/assets/<домен>/…}, ровно то, что читает
 * {@code ModAssets} редактора. Раньше это вытаскивали из jar-ов руками.
 *
 * <p>Текстуры берутся двумя путями. Целиком по мод-домену — из его jar-а
 * (перечислить ресурсы домена через менеджер ресурсов нельзя). Поимённо — через
 * менеджер ресурсов игры, потому что он видит и наборы ресурсов поверх мода.
 */
public final class AssetExport {

    public static class Result {
        public int files;
        public int bytes;
        public int missing;

        void add(int size) {
            files++;
            bytes += size;
        }

        public String toString() {
            return files + " файлов, " + (bytes / 1024) + " КБ"
                    + (missing > 0 ? ", не нашлось " + missing : "");
        }
    }

    private AssetExport() {}

    /** Все ресурсы модов по их id ({@code DwQuests}, {@code DwCore}). */
    public static Result fromMods(File gameDir, String[] modIds, Result result) {
        for (int i = 0; i < modIds.length; i++) {
            ModContainer container = find(modIds[i]);
            if (container == null) {
                DwqBridge.LOG.warn("мод не загружен: " + modIds[i]);
                result.missing++;
                continue;
            }
            File source = container.getSource();
            if (source == null || !source.isFile()) {
                DwqBridge.LOG.warn("у мода " + modIds[i] + " нет jar-а: " + source);
                result.missing++;
                continue;
            }
            copyAssets(source, gameDir, result);
        }
        return result;
    }

    private static ModContainer find(String modId) {
        for (ModContainer c : Loader.instance().getModList()) {
            if (c.getModId().equalsIgnoreCase(modId)) return c;
        }
        return null;
    }

    private static void copyAssets(File jar, File gameDir, Result result) {
        ZipFile zip = null;
        try {
            zip = new ZipFile(jar);
            for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements(); ) {
                ZipEntry entry = en.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (!name.startsWith("assets/")) continue;
                InputStream in = zip.getInputStream(entry);
                try {
                    result.add(write(new File(gameDir, name), in));
                } finally {
                    in.close();
                }
            }
        } catch (IOException e) {
            DwqBridge.LOG.warn("не прочитался jar " + jar + ": " + e);
            result.missing++;
        } finally {
            if (zip != null) try { zip.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Шрифт: таблица ширин и страницы глифов. Страниц кириллицы в клиенте может
     * не быть вовсе — тогда просто пропускаем, {@code getResource} кидает.
     */
    public static Result font(File gameDir, Result result) {
        copyResource(new ResourceLocation("minecraft", "font/glyph_sizes.bin"), gameDir, result, true);
        copyResource(new ResourceLocation("minecraft", "textures/font/ascii.png"), gameDir, result, true);
        for (int page = 0; page < 256; page++) {
            String name = String.format(Locale.ROOT, "textures/font/unicode_page_%02x.png", page);
            copyResource(new ResourceLocation("minecraft", name), gameDir, result, false);
        }
        return result;
    }

    /** Поимённые ресурсы: картинки из описаний квестов и прочее. */
    public static Result resources(File gameDir, Collection<ResourceLocation> locations, Result result) {
        for (ResourceLocation loc : locations) {
            copyResource(loc, gameDir, result, true);
        }
        return result;
    }

    private static void copyResource(ResourceLocation loc, File gameDir, Result result, boolean complain) {
        File out = new File(gameDir, "assets/" + loc.getResourceDomain() + "/" + loc.getResourcePath());
        try {
            IResource res = Minecraft.getMinecraft().getResourceManager().getResource(loc);
            InputStream in = res.getInputStream();
            try {
                result.add(write(out, in));
            } finally {
                in.close();
            }
        } catch (IOException e) {
            if (complain) {
                result.missing++;
                DwqBridge.LOG.warn("нет ресурса " + loc);
            }
        } catch (Throwable t) {
            result.missing++;
            DwqBridge.LOG.warn("ресурс " + loc + " не прочитался: " + t);
        }
    }

    private static int write(File out, InputStream in) throws IOException {
        File parent = out.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("нет папки " + parent);
        OutputStream os = new FileOutputStream(out);
        int total = 0;
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                total += n;
            }
        } finally {
            os.close();
        }
        return total;
    }
}
