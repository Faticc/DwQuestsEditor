package ru.dw.qeditor.android;

import android.content.res.AssetManager;

import ru.dw.qeditor.assets.AssetPack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Ресурсы мода и иконки предметов, встроенные в APK ({@code runtime/} репозитория).
 *
 * <p>Обходить каталоги через {@link AssetManager#list} на двадцати с лишним
 * тысячах иконок долго, поэтому сборка кладёт рядом список всех записей
 * ({@code dwq-index.txt}), а он раскладывается по каталогам один раз.
 * Общий код просит перечислить ровно один уровень каталога — так и отвечаем.
 */
public final class ApkAssets implements AssetPack {

    private static final String INDEX = "dwq-index.txt";

    private static Set<String> all;
    private static Map<String, List<String>> byDir;

    private final AssetManager assets;
    /** Подкаталог APK: {@code game/} — ресурсы мода, {@code icons/} — иконки. */
    private final String root;

    public ApkAssets(AssetManager assets, String root) {
        this.assets = assets;
        this.root = root.isEmpty() || root.endsWith("/") ? root : root + "/";
        loadIndex(assets);
    }

    private static synchronized void loadIndex(AssetManager assets) {
        if (all != null) return;
        all = new HashSet<>();
        byDir = new HashMap<>();
        try (InputStream in = assets.open(INDEX)) {
            String text = new String(readAll(in), StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                String n = line.trim();
                if (n.isEmpty()) continue;
                all.add(n);
                int slash = n.lastIndexOf('/');
                String dir = slash < 0 ? "" : n.substring(0, slash);
                byDir.computeIfAbsent(dir, k -> new ArrayList<>()).add(n.substring(slash + 1));
            }
        } catch (IOException ignored) {
            // без списка останутся прямые обращения по пути
        }
    }

    @Override
    public boolean has(String path) {
        return all.contains(root + path);
    }

    @Override
    public byte[] bytes(String path) {
        if (!all.isEmpty() && !all.contains(root + path)) return null;
        try (InputStream in = assets.open(root + path)) {
            return readAll(in);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public List<String> list(String prefix, String suffix) {
        int slash = prefix.lastIndexOf('/');
        String dir = slash < 0 ? "" : prefix.substring(0, slash);
        String start = slash < 0 ? prefix : prefix.substring(slash + 1);
        String full = (root + dir).replaceAll("/$", "");
        List<String> names = byDir.getOrDefault(full, Collections.emptyList());
        List<String> out = new ArrayList<>();
        for (String n : names) {
            if (!n.startsWith(start)) continue;
            if (!suffix.isEmpty() && !n.toLowerCase(Locale.ROOT).endsWith(suffix)) continue;
            out.add(dir.isEmpty() ? n : dir + "/" + n);
        }
        return out;
    }

    @Override
    public String describe() {
        return "встроено в приложение (" + root + ", записей: " + all.size() + ")";
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
