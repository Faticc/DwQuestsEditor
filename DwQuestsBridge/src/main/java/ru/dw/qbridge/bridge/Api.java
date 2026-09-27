package ru.dw.qbridge.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import ru.dw.qbridge.DwqBridge;
import ru.dw.qbridge.client.ClientTicker;
import ru.dw.qbridge.export.IconRenderer;
import ru.dw.qbridge.export.Names;
import ru.dw.qbridge.export.Stacks;
import ru.dw.qbridge.quests.QuestProgress;
import ru.dw.qbridge.quests.Quests;
import ru.dw.qbridge.quests.TabJson;
import ru.dw.qbridge.quests.TabBatchUpload;
import ru.dw.qbridge.quests.TabUpload;
import ru.dw.qbridge.task.ExportTasks;
import ru.sky.dw.quests.common.quest.QuestTab;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Разбор запросов моста. Всё, что смотрит в игру, уходит в главный поток.
 *
 * <p>Запрос — это путь, параметры и тело, ответ — {@link Reply}; самого HTTP
 * здесь нет. Поэтому тот же разбор обслуживает и HTTP-мост (консоль, внешние
 * программы), и редактор внутри игры, который зовёт его напрямую
 * ({@link #shared()}): из главного потока задания выполняются сразу, без
 * очереди, и ожидания самого себя не получается.
 */
public final class Api {

    /** Ответ: код, тип и тело; у иконки ещё путь её файла в выгрузке. */
    public static final class Reply {
        public final int code;
        public final String type;
        public final byte[] data;
        public String iconPath;

        Reply(int code, String type, byte[] data) {
            this.code = code;
            this.type = type;
            this.data = data;
        }
    }

    private static final Api SHARED = new Api();

    public static Api shared() {
        return SHARED;
    }

    /** Дольше этого запрос ждать не будет: клиент мог уйти в меню или подвиснуть. */
    private static final long TIMEOUT_MS = 15000L;

    private IconRenderer renderer;

    void dispose() {
        final IconRenderer r = renderer;
        renderer = null;
        if (r == null) return;
        try {
            ClientTicker.get().call(new Callable<Object>() {
                public Object call() {
                    r.dispose();
                    return null;
                }
            }, 2000L);
        } catch (Exception ignored) {
            // клиент уже закрывается — буфер всё равно уйдёт вместе с контекстом
        }
    }

    public Reply handle(String path, Map<String, String> query, String body) throws Exception {
        if (path.equals("/ping")) {
            return json(ping());
        } else if (path.equals("/tabs")) {
            return json(tabs());
        } else if (path.equals("/tab")) {
            return tab(query);
        } else if (path.equals("/icon")) {
            return icon(query);
        } else if (path.equals("/item")) {
            return json(item(query.get("stack")));
        } else if (path.equals("/items")) {
            return json(items());
        } else if (path.equals("/export")) {
            return json(ExportTasks.startFromBridge(query));
        } else if (path.equals("/status")) {
            return json(status());
        } else if (path.equals("/stop")) {
            ClientTicker.get().stop();
            return json(status());
        } else if (path.equals("/paste")) {
            return paste(query, body);
        } else if (path.equals("/paste-all")) {
            return pasteAll(body);
        } else if (path.equals("/command")) {
            final String text = body;
            return progressAction(new Callable<String>() {
                public String call() { return QuestProgress.command(text); }
            });
        } else if (path.equals("/claim")) {
            // один квест — ?quest=, несколько — телом по строке, без них — вся вкладка
            final String tab = query.get("tab"), quest = query.get("quest");
            if (tab == null) throw new IllegalArgumentException("нужен параметр tab");
            if (quest != null) {
                return progressAction(new Callable<String>() {
                    public String call() { return QuestProgress.claim(tab, quest); }
                });
            } else {
                final List<String> ids = new ArrayList<String>();
                if (body != null) {
                    for (String line : body.split("\n")) if (!line.trim().isEmpty()) ids.add(line.trim());
                }
                return progressAction(new Callable<String>() {
                    public String call() { return QuestProgress.claimMany(tab, ids); }
                });
            }
        } else if (path.equals("/claim-all")) {
            return progressAction(new Callable<String>() {
                public String call() { return QuestProgress.claimAll(); }
            });
        } else if (path.equals("/progress")) {
            requireQuests();
            final String tab = query.get("tab");
            if (tab == null || tab.isEmpty()) throw new IllegalArgumentException("нужен параметр tab");
            return json(ClientTicker.get().call(new Callable<String>() {
                public String call() { return QuestProgress.progress(tab); }
            }, TIMEOUT_MS));
        } else {
            return new Reply(404, JSON, "{\"error\":\"нет такого запроса\"}".getBytes("UTF-8"));
        }
    }

    private static final String JSON = "application/json; charset=utf-8";

    // ------------------------------------------------------------------- ответы

    private String ping() throws Exception {
        return ClientTicker.get().call(new Callable<String>() {
            public String call() throws Exception {
                StringWriter sw = new StringWriter();
                JsonWriter w = writer(sw);
                w.beginObject();
                w.name("mod").value("dwqbridge");
                w.name("minecraft").value("1.7.10");
                Minecraft mc = Minecraft.getMinecraft();
                w.name("player").value(mc.thePlayer == null ? null : mc.thePlayer.getCommandSenderName());
                w.name("inWorld").value(mc.thePlayer != null);
                w.name("dwquests").value(Quests.modPresent());
                if (Quests.modPresent()) {
                    w.name("tabs").value(Quests.tabs().size());
                    w.name("quests").value(Quests.questCount());
                }
                w.name("outDir").value(DwqBridge.outDir.getAbsolutePath());
                w.endObject();
                w.flush();
                return sw.toString();
            }
        }, TIMEOUT_MS);
    }

    private String tabs() throws Exception {
        requireQuests();
        return ClientTicker.get().call(new Callable<String>() {
            public String call() throws Exception {
                StringWriter sw = new StringWriter();
                JsonWriter w = writer(sw);
                w.beginObject().name("tabs").beginArray();
                for (QuestTab tab : Quests.tabs()) {
                    w.beginObject();
                    w.name("tabID").value(tab.getTabID());
                    w.name("sortIndex").value(tab.getSortIndex());
                    w.name("iconItem").value(TabJson.stackName(tab.getIconStack()));
                    w.name("quests").value(tab.getQuestsCount());
                    w.endObject();
                }
                w.endArray().endObject();
                w.flush();
                return sw.toString();
            }
        }, TIMEOUT_MS);
    }

    private Reply tab(Map<String, String> query) throws Exception {
        requireQuests();
        final String id = query.get("id");
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("нужен параметр id");
        String json = ClientTicker.get().call(new Callable<String>() {
            public String call() {
                QuestTab tab = Quests.tab(id);
                if (tab == null) throw new IllegalArgumentException("нет вкладки '" + id + "'");
                return TabJson.write(tab);
            }
        }, TIMEOUT_MS);
        return json(json);
    }

    /**
     * Иконка предмета по строке мода — включая NBT.
     *
     * <p>Это и есть ответ на цветы Botany: заранее нарисовать все сочетания
     * генома нельзя, а нарисовать тот единственный, что стоит в квесте, —
     * можно прямо на лету.
     */
    private Reply icon(Map<String, String> query) throws Exception {
        final String text = query.get("stack");
        if (text == null || text.isEmpty()) throw new IllegalArgumentException("нужен параметр stack");
        final int size = parseInt(query.get("size"), DwqBridge.config.iconSize);

        byte[] png = ClientTicker.get().call(new Callable<byte[]>() {
            public byte[] call() throws IOException {
                ItemStack stack = Stacks.parse(text);
                if (stack == null) return null;
                if (renderer == null || renderer.size() != size) {
                    if (renderer != null) renderer.dispose();
                    renderer = new IconRenderer(size);
                }
                BufferedImage img = renderer.render(stack);
                if (img == null) return null;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(img, "png", out);
                return out.toByteArray();
            }
        }, TIMEOUT_MS);

        if (png == null) {
            return new Reply(404, JSON,
                    ("{\"error\":\"нет такого предмета или он не рисуется\",\"stack\":" + quote(text) + "}")
                            .getBytes("UTF-8"));
        }
        Reply reply = new Reply(200, "image/png", png);
        reply.iconPath = iconPathHeader(text);
        return reply;
    }

    private String iconPathHeader(String text) {
        try {
            ItemStack stack = Stacks.parse(text);
            String path = stack == null ? null : Names.iconPath(stack);
            return path == null ? "" : path;
        } catch (Throwable t) {
            return "";
        }
    }

    private String item(final String text) throws Exception {
        if (text == null || text.isEmpty()) throw new IllegalArgumentException("нужен параметр stack");
        return ClientTicker.get().call(new Callable<String>() {
            public String call() throws Exception {
                ItemStack stack = Stacks.parse(text);
                StringWriter sw = new StringWriter();
                JsonWriter w = writer(sw);
                w.beginObject();
                w.name("stack").value(text);
                w.name("found").value(stack != null);
                if (stack != null) {
                    w.name("id").value(Names.id(stack));
                    w.name("full").value(Names.full(stack));
                    w.name("itemName").value(Names.displayName(stack));
                    w.name("itemId").value(Names.numericId(stack));
                    w.name("icon").value(Names.iconPath(stack));
                }
                w.endObject();
                w.flush();
                return sw.toString();
            }
        }, TIMEOUT_MS);
    }

    /** Весь реестр предметов одним ответом — редактору для поиска без выгрузки. */
    private String items() throws Exception {
        return ClientTicker.get().call(new Callable<String>() {
            public String call() throws Exception {
                ru.dw.qbridge.export.StackScan scan = new ru.dw.qbridge.export.StackScan().addRegistry();
                StringWriter sw = new StringWriter();
                JsonWriter w = writer(sw);
                w.beginObject();
                for (Map.Entry<String, ItemStack> e : scan.result().entrySet()) {
                    ItemStack stack = e.getValue();
                    String key = Names.full(stack);
                    if (key == null) continue;
                    w.name(key).beginObject();
                    w.name("itemName").value(Names.displayName(stack));
                    w.name("itemId").value(Names.numericId(stack));
                    w.name("icon").value(e.getKey());
                    w.endObject();
                }
                w.endObject();
                w.flush();
                return sw.toString();
            }
        }, 60000L);
    }

    private String status() throws Exception {
        StringWriter sw = new StringWriter();
        JsonWriter w = writer(sw);
        w.beginObject();
        String name = ClientTicker.get().busyName();
        w.name("busy").value(name != null);
        if (name != null) {
            w.name("job").value(name);
            w.name("fraction").value(ClientTicker.get().fraction());
        }
        w.endObject();
        w.flush();
        return sw.toString();
    }

    private Reply paste(Map<String, String> query, final String body) throws Exception {
        requireQuests();
        final String id = query.get("id");
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("нужен параметр id");
        if (body == null || body.trim().isEmpty()) throw new IllegalArgumentException("пустое тело запроса");
        final boolean clipboardOnly = "1".equals(query.get("clipboard"));

        String result = ClientTicker.get().call(new Callable<String>() {
            public String call() {
                if (clipboardOnly) {
                    TabUpload.toClipboard(body);
                    return "{\"ok\":true,\"clipboard\":true}";
                }
                TabUpload.paste(id, body);
                return "{\"ok\":true,\"sent\":true}";
            }
        }, TIMEOUT_MS);
        return json(result);
    }

    /**
     * {@code POST /paste-all}, тело — {@code [{"tabID":…,"json":"…"}, …]}.
     * Вкладки уходят по одной, каждая ждёт подтверждения сервера
     * ({@link TabBatchUpload}); ход виден в {@code /status}.
     */
    private Reply pasteAll(String body) throws Exception {
        requireQuests();
        if (body == null || body.trim().isEmpty()) throw new IllegalArgumentException("пустое тело запроса");
        final List<String[]> tabs = new ArrayList<String[]>();
        for (JsonElement e : new JsonParser().parse(body).getAsJsonArray()) {
            JsonObject o = e.getAsJsonObject();
            String id = o.get("tabID").getAsString();
            String json = o.get("json").getAsString();
            if (id.isEmpty() || json.trim().isEmpty()) throw new IllegalArgumentException("пустая вкладка в списке");
            if (json.getBytes("UTF-8").length > TabUpload.MAX_BYTES)
                throw new IllegalArgumentException("вкладка '" + id + "' больше 10 МБ");
            tabs.add(new String[]{id, json});
        }
        if (tabs.isEmpty()) throw new IllegalArgumentException("список вкладок пуст");
        ClientTicker.get().call(new Callable<Void>() {
            public Void call() {
                if (Minecraft.getMinecraft().thePlayer == null) throw new IllegalStateException("игрок не в мире");
                ClientTicker.get().start(new TabBatchUpload(tabs));
                return null;
            }
        }, TIMEOUT_MS);
        return json("{\"ok\":true,\"started\":\"paste\",\"tabs\":" + tabs.size() + "}");
    }

    /** Сколько слушать чат после действия: сервер отвечает за тик-другой, но бывает и занят. */
    private static final long REPLY_MS = 1500L;

    /**
     * Действие с прогрессом своего игрока ({@link QuestProgress}): выполнить в
     * главном потоке, подождать ответа сервера и вернуть, что он написал в чат, —
     * иначе редактор не узнает ни про нехватку прав, ни про опечатку в команде.
     */
    private Reply progressAction(Callable<String> action) throws Exception {
        requireQuests();
        String done = ClientTicker.get().call(action, TIMEOUT_MS);
        Thread.sleep(REPLY_MS);
        List<String> replies = QuestProgress.takeReplies();
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"done\":").append(quote(done)).append(",\"replies\":[");
        for (int i = 0; i < replies.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(quote(replies.get(i)));
        }
        return json(sb.append("]}").toString());
    }

    // ------------------------------------------------------------------- мелочи

    private static void requireQuests() {
        if (!Quests.modPresent()) throw new IllegalStateException("в клиенте нет мода DwQuests");
    }

    private static Reply json(String text) throws IOException {
        return new Reply(200, JSON, text.getBytes("UTF-8"));
    }

    private static JsonWriter writer(StringWriter sw) {
        JsonWriter w = new JsonWriter(sw);
        w.setSerializeNulls(true);
        return w;
    }

    static int parseInt(String text, int fallback) {
        if (text == null) return fallback;
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }
}
