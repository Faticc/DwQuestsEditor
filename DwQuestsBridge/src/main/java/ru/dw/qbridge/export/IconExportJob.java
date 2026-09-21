package ru.dw.qbridge.export;

import com.google.gson.stream.JsonWriter;
import net.minecraft.item.ItemStack;
import ru.dw.qbridge.DwqBridge;
import ru.dw.qbridge.client.Job;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Выгрузка иконок: рисует каждый стек и складывает PNG + items.json так, как
 * их ждёт редактор ({@code icons/img/<мод>/<имя>.png}, {@code icons/json/items.json}).
 *
 * <p>Идёт по кусочкам в тике клиента: полный пак — это больше десяти тысяч
 * картинок, и одним куском это выглядело бы как зависание на минуту (и сервер
 * успел бы выкинуть по таймауту).
 */
public final class IconExportJob implements Job {

    private final File imgDir;
    private final File jsonDir;
    private final IconRenderer renderer;
    private final Map<String, ItemStack> pending;
    private final Iterator<Map.Entry<String, ItemStack>> it;
    private final int total;
    private final boolean skipExisting;
    private final List<String[]> entries = new ArrayList<String[]>();

    private int done;
    private int written;
    private int failed;
    private boolean aborted;

    /** @param skipExisting не перерисовывать то, что уже лежит на диске */
    public IconExportJob(File root, Map<String, ItemStack> stacks, int iconSize, boolean skipExisting) {
        this.imgDir = new File(root, "img");
        this.jsonDir = new File(root, "json");
        this.renderer = new IconRenderer(iconSize);
        this.pending = stacks;
        this.it = stacks.entrySet().iterator();
        this.total = stacks.size();
        this.skipExisting = skipExisting;
    }

    @Override
    public String name() {
        return "Выгрузка иконок (" + total + ")";
    }

    @Override
    public float fraction() {
        return total == 0 ? 1f : (float) done / (float) total;
    }

    @Override
    public boolean step(long budgetMs) throws Exception {
        long deadline = System.currentTimeMillis() + budgetMs;
        while (it.hasNext()) {
            Map.Entry<String, ItemStack> e = it.next();
            one(e.getKey(), e.getValue());
            done++;
            if (System.currentTimeMillis() >= deadline) return false;
        }
        finish();
        return true;
    }

    private void one(String path, ItemStack stack) {
        String id = Names.id(stack);
        String full = Names.full(stack);
        String nbt = stack.hasTagCompound() ? Names.canonical(stack.getTagCompound()) : null;
        // предмет не в реестре (бывает у стеков из NEI) — в items.json ему не место
        if (full != null) {
            entries.add(new String[]{full, id, Names.displayName(stack), Names.numericId(stack), path, nbt});
        }

        File out = new File(imgDir, path);
        if (skipExisting && out.isFile() && out.length() > 0) return;

        BufferedImage img = renderer.render(stack);
        if (img == null) {
            failed++;
            return;
        }
        try {
            File parent = out.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) throw new RuntimeException("нет папки " + parent);
            ImageIO.write(img, "png", out);
            written++;
        } catch (Exception ex) {
            failed++;
            DwqBridge.LOG.warn("не записалась иконка " + path + ": " + ex);
        }
    }

    private void finish() throws Exception {
        write("items.json");
    }

    /**
     * Прерванная выгрузка пишется в отдельный файл: иначе сотня успевших
     * записаться строк затёрла бы полный items.json прошлой выгрузки.
     */
    private void write(String fileName) throws Exception {
        renderer.dispose();
        if (!jsonDir.isDirectory() && !jsonDir.mkdirs()) throw new RuntimeException("нет папки " + jsonDir);

        File file = new File(jsonDir, fileName);
        Writer w = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
        try {
            JsonWriter json = new JsonWriter(w);
            json.setIndent("  ");
            json.beginObject();
            for (int i = 0; i < entries.size(); i++) {
                String[] e = entries.get(i);
                // ключ — полная строка предмета; у обычных предметов это тот же
                // "mod:name[:meta]", что и раньше, так что старые выгрузки читаются
                json.name(e[0]);
                json.beginObject();
                json.name("itemName").value(e[2]);
                json.name("itemId").value(e[3]);
                if (e[5] != null) {
                    // варианту с NBT имя файла по ключу не восстановить — пишем прямо
                    json.name("icon").value(e[4]);
                    json.name("id").value(e[1]);
                    json.name("nbt").value(e[5]);
                }
                json.endObject();
            }
            json.endObject();
            json.flush();
        } finally {
            w.close();
        }
        DwqBridge.LOG.info("иконки: " + written + " записано, " + failed + " не вышло, всего " + total);
    }

    @Override
    public String summary() {
        return "иконок записано " + written + ", пропущено " + (total - written - failed)
                + ", не нарисовалось " + failed + " → " + imgDir.getParentFile();
    }

    @Override
    public void abort() {
        aborted = true;
        renderer.dispose();
        try {
            finishPartial();
        } catch (Exception e) {
            DwqBridge.LOG.warn("частичный items.json не записался: " + e);
        }
    }

    private void finishPartial() throws Exception {
        if (entries.isEmpty()) return;
        DwqBridge.LOG.info("прервано на " + done + " из " + total + ", пишу то, что успели");
        write("items.partial.json");
    }

    public boolean isAborted() {
        return aborted;
    }

    public Map<String, ItemStack> stacks() {
        return pending;
    }
}
