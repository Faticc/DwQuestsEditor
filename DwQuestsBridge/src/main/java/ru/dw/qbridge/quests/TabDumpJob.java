package ru.dw.qbridge.quests;

import com.google.gson.stream.JsonWriter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import ru.dw.qbridge.DwqBridge;
import ru.dw.qbridge.client.Chat;
import ru.dw.qbridge.client.Job;
import ru.dw.qbridge.export.Names;
import ru.sky.dw.quests.common.quest.QuestTab;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Выгрузка всех вкладок в файлы.
 *
 * <p>Два способа, и они дают разное.
 * <ul>
 *   <li><b>raw</b> — {@code /dwq tab copy}: сервер кладёт в буфер обмена исходный
 *       файл вкладки. Это точная копия, со всем, чего нет в пакете синхронизации
 *       (broadcast, порядок наград, исходные строки предметов). Требует прав на
 *       сервере: команда {@code /dwq} — второго уровня.</li>
 *   <li><b>local</b> — сборка JSON из памяти клиента ({@link TabJson}). Прав не
 *       требует вообще, но часть полей восстановлена, а не считана.</li>
 * </ul>
 * В режиме auto сначала пробуется raw, и если сервер не ответил (нет прав) —
 * пишется local.
 */
public final class TabDumpJob implements Job {

    public enum Mode { RAW, LOCAL, AUTO }

    /** Сколько ждать ответ сервера на /dwq tab copy. */
    private static final long WAIT_MS = 3000L;

    private final File dir;
    private final Mode mode;
    private final List<String> tabIds = new ArrayList<String>();
    private final List<String[]> written = new ArrayList<String[]>();
    private final String savedClipboard;
    private final String sentinel;

    private Iterator<String> it;
    private String current;
    private long waitStarted;
    private boolean waiting;
    private int done;
    private int rawCount;
    private int localCount;
    private int failed;

    public TabDumpJob(File dir, Mode mode) {
        this.dir = dir;
        this.mode = mode;
        for (QuestTab tab : Quests.tabs()) tabIds.add(tab.getTabID());
        this.it = tabIds.iterator();
        this.savedClipboard = safeClipboard();
        this.sentinel = "dwqbridge:" + System.nanoTime();
    }

    @Override
    public String name() {
        return "Выгрузка вкладок (" + tabIds.size() + ")";
    }

    @Override
    public float fraction() {
        return tabIds.isEmpty() ? 1f : (float) done / (float) tabIds.size();
    }

    @Override
    public boolean step(long budgetMs) throws Exception {
        if (waiting) {
            String clip = safeClipboard();
            if (clip != null && !clip.equals(sentinel) && clip.trim().startsWith("{")) {
                writeRaw(current, clip);
                waiting = false;
                done++;
            } else if (System.currentTimeMillis() - waitStarted > WAIT_MS) {
                waiting = false;
                done++;
                if (mode == Mode.RAW) {
                    failed++;
                    Chat.warn("вкладка '" + current + "': сервер не ответил (нужны права /dwq)");
                } else {
                    writeLocal(current);
                }
            }
            return false;
        }

        if (!it.hasNext()) {
            finish();
            return true;
        }

        current = it.next();
        if (mode == Mode.LOCAL) {
            writeLocal(current);
            done++;
            return false;
        }

        // Ответ придёт пакетом через какое-то время — ждём его в следующих тиках.
        GuiScreen.setClipboardString(sentinel);
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) throw new IllegalStateException("игрок не в мире");
        mc.thePlayer.sendChatMessage("/dwq tab copy " + current.replace(' ', '_'));
        waiting = true;
        waitStarted = System.currentTimeMillis();
        return false;
    }

    private void writeRaw(String tabID, String json) {
        write(tabID, json, "raw");
        rawCount++;
    }

    private void writeLocal(String tabID) {
        QuestTab tab = Quests.tab(tabID);
        if (tab == null) {
            failed++;
            return;
        }
        write(tabID, TabJson.write(tab), "local");
        localCount++;
    }

    private void write(String tabID, String json, String source) {
        File out = new File(dir, Names.tabFile(tabID));
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) throw new RuntimeException("нет папки " + dir);
            Writer w = new OutputStreamWriter(new FileOutputStream(out), "UTF-8");
            try {
                w.write(json);
            } finally {
                w.close();
            }
            written.add(new String[]{tabID, out.getName(), source, String.valueOf(json.length())});
        } catch (Exception e) {
            failed++;
            DwqBridge.LOG.warn("вкладка '" + tabID + "' не записалась: " + e);
        }
    }

    private void finish() throws Exception {
        restoreClipboard();
        File index = new File(dir, "index.json");
        Writer w = new OutputStreamWriter(new FileOutputStream(index), "UTF-8");
        try {
            JsonWriter json = new JsonWriter(w);
            json.setIndent("  ");
            json.beginObject();
            json.name("tabs").beginArray();
            for (String[] row : written) {
                json.beginObject();
                json.name("tabID").value(row[0]);
                json.name("file").value(row[1]);
                json.name("source").value(row[2]);
                json.name("chars").value(Long.parseLong(row[3]));
                json.endObject();
            }
            json.endArray();
            json.endObject();
            json.flush();
        } finally {
            w.close();
        }
    }

    @Override
    public String summary() {
        return "точных копий " + rawCount + ", собрано из памяти " + localCount
                + (failed > 0 ? ", не вышло " + failed : "") + " → " + dir;
    }

    @Override
    public void abort() {
        restoreClipboard();
    }

    private void restoreClipboard() {
        try {
            String now = safeClipboard();
            // не затираем то, что человек скопировал сам, пока шла выгрузка
            if (savedClipboard != null && now != null && (now.equals(sentinel) || now.startsWith("{\"tabID\""))) {
                GuiScreen.setClipboardString(savedClipboard);
            }
        } catch (Throwable ignored) {
            // буфер обмена бывает занят другим приложением
        }
    }

    private static String safeClipboard() {
        try {
            return GuiScreen.getClipboardString();
        } catch (Throwable t) {
            return null;
        }
    }
}
