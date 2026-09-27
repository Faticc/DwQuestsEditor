package ru.dw.qbridge.editor;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResource;
import net.minecraft.util.ResourceLocation;
import ru.dw.qeditor.assets.AssetPack;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Ресурсы редактора — это ресурсы игры.
 *
 * <p>Путь {@code assets/<домен>/<путь>} читается менеджером ресурсов Minecraft,
 * поэтому фоны вкладок, звёзды, ноды и шрифт — ровно те, что видит игрок,
 * с учётом включённых пакетов ресурсов. Раньше их приходилось вытаскивать из
 * jar-ов в {@code runtime/game}.
 *
 * <p>Перечислить ресурсы менеджер не умеет, а редактору это нужно в одном
 * месте — список фонов вкладок. Для перечисления просматриваются сами jar-ы
 * (и папки) модов.
 */
final class GamePack implements AssetPack {

    private final Map<String, List<String>> listings = new HashMap<>();

    private static ResourceLocation location(String path) {
        // assets/<домен>/<путь>
        if (!path.startsWith("assets/")) return null;
        int slash = path.indexOf('/', 7);
        if (slash < 0) return null;
        return new ResourceLocation(path.substring(7, slash), path.substring(slash + 1));
    }

    /** Есть ли ресурс — спрашивается и из кадра, а менеджер ищет по всем пакетам заново. */
    private final Map<String, Boolean> present = new HashMap<>();

    @Override
    public synchronized boolean has(String path) {
        Boolean known = present.get(path);
        if (known != null) return known;
        boolean found = false;
        ResourceLocation loc = location(path);
        if (loc != null) {
            try {
                IResource r = Minecraft.getMinecraft().getResourceManager().getResource(loc);
                r.getInputStream().close();
                found = true;
            } catch (IOException | RuntimeException e) {
                found = false;
            }
        }
        present.put(path, found);
        return found;
    }

    @Override
    public byte[] bytes(String path) {
        ResourceLocation loc = location(path);
        if (loc == null) return null;
        try (InputStream in = Minecraft.getMinecraft().getResourceManager().getResource(loc).getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(16384);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public synchronized List<String> list(String prefix, String suffix) {
        String key = prefix + "|" + suffix;
        List<String> cached = listings.get(key);
        if (cached != null) return cached;
        TreeSet<String> found = new TreeSet<>();
        for (ModContainer mod : Loader.instance().getActiveModList()) {
            File src = mod.getSource();
            if (src == null) continue;
            if (src.isFile()) scanJar(src, prefix, suffix, found);
            else if (src.isDirectory()) scanDir(src, prefix, suffix, found);
        }
        List<String> out = new ArrayList<>(found);
        listings.put(key, out);
        return out;
    }

    /** Один уровень под префиксом — как у папки в {@link AssetPack.Dir}. */
    private static boolean matches(String name, String prefix, String suffix) {
        if (!name.startsWith(prefix) || name.indexOf('/', prefix.lastIndexOf('/') + 1) >= 0) return false;
        return suffix.isEmpty() || name.toLowerCase(Locale.ROOT).endsWith(suffix);
    }

    private static void scanJar(File jar, String prefix, String suffix, TreeSet<String> found) {
        try (ZipFile zip = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> e = zip.entries();
            while (e.hasMoreElements()) {
                ZipEntry entry = e.nextElement();
                if (!entry.isDirectory() && matches(entry.getName(), prefix, suffix)) found.add(entry.getName());
            }
        } catch (IOException ignored) {
            // не zip (или занят) — в нём и искать нечего
        }
    }

    private static void scanDir(File root, String prefix, String suffix, TreeSet<String> found) {
        int slash = prefix.lastIndexOf('/');
        String dir = slash < 0 ? "" : prefix.substring(0, slash);
        String[] names = new File(root, dir).list();
        if (names == null) return;
        for (String n : names) {
            String full = dir.isEmpty() ? n : dir + "/" + n;
            if (matches(full, prefix, suffix)) found.add(full);
        }
    }

    @Override public String describe() { return "ресурсы игры"; }
}
