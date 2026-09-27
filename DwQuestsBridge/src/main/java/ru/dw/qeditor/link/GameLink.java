package ru.dw.qeditor.link;

import ru.dw.qeditor.assets.Config;
import ru.dw.qeditor.json.JsonObject;
import ru.dw.qeditor.json.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Связь с запущенной игрой через мод DwQuestsBridge.
 *
 * <p>Мод поднимает в клиенте маленький HTTP-мост на 127.0.0.1 и кладёт порт и
 * ключ в {@code <клиент>/dwq_export/bridge.json} — поэтому подключение обычно
 * не требует вообще никаких настроек: достаточно, чтобы игра была запущена.
 *
 * <p>Что это даёт: вкладки берутся прямо из памяти клиента (без файлов и прав),
 * недостающая иконка дорисовывается игрой по запросу (в том числе иконка
 * варианта с NBT — цветка Botany, пчелы), а готовая вкладка уходит обратно в
 * игру тем же путём, что и руками: буфер обмена плюс {@code /dwq tab paste}.
 */
public final class GameLink {

    private static final String[] CLIENT_GUESSES = {
            "C:/Users/User/McSkill/clients/Industrial_1.7.10",
            System.getProperty("user.home") + "/McSkill/clients/Industrial_1.7.10"
    };

    /** Папки, внутри которых лаунчеры держат клиенты — по одному в подпапке. */
    private static final String[] LAUNCHER_ROOTS = {
            System.getProperty("user.home") + "/McSkill/clients",
            System.getenv("APPDATA") == null ? "" : System.getenv("APPDATA") + "/.gribland"
    };

    private static GameLink instance;

    public static GameLink get() {
        if (instance == null) instance = new GameLink();
        return instance;
    }

    private String baseUrl = "";
    private String token = "";
    private volatile String clientDir = "";
    private volatile boolean connected;
    private volatile String status = "не подключено";
    private volatile String player = "";
    private volatile int tabCount;
    private volatile int questCount;

    private GameLink() { }

    // ------------------------------------------------------------- мост в процессе

    /**
     * Мост в том же процессе. Редактор живёт в игре, и ходить к ней по HTTP
     * незачем — а из главного потока и нельзя: сервер ждал бы главный поток,
     * а тот ждал бы ответа сервера. Мод подставляет сюда разбор запросов
     * напрямую ({@code ru.dw.qbridge.bridge.Api}); из главного потока задания
     * там выполняются сразу, из фоновых — в очереди тика, как и раньше.
     */
    public interface Local {
        /** Ответ: код как у HTTP, тело и (у иконки) путь её файла. */
        Response call(String path, java.util.Map<String, String> query, String body) throws Exception;
    }

    public record Response(int code, byte[] data, String iconPath) { }

    private static volatile Local local;

    public static void useLocal(Local handler) { local = handler; }

    public static boolean isLocal() { return local != null; }

    public boolean connected() { return connected; }

    public String status() { return status; }

    public String player() { return player; }

    public int tabCount() { return tabCount; }

    public int questCount() { return questCount; }

    public String baseUrl() { return baseUrl; }

    // ------------------------------------------------------------- подключение

    /**
     * Найти мост и проверить связь. Возвращает false, если не вышло.
     *
     * <p>Клиентов с модом бывает несколько (McSkill, GribLand…), и у каждого свой
     * ключ, а порт по умолчанию общий — 25599. Поэтому bridge.json, найденный
     * первым, может принадлежать клиенту, который сейчас не запущен, а на порту
     * отвечает другой: мост тогда отказывает «нужен ключ». Перебираем все
     * найденные рукопожатия, свежие первыми, пока один ключ не подойдёт.
     */
    public synchronized boolean connect() {
        probing = true;
        try {
            return probe();
        } finally {
            probing = false;
        }
    }

    private boolean probe() {
        connected = false;
        if (local != null) {
            baseUrl = "в процессе игры";
            clientDir = Config.get().clientDir;
            return ping();
        }
        Config cfg = Config.get();
        if (cfg.linkPort > 0 && !Config.isBlank(cfg.linkToken)) {
            baseUrl = "http://127.0.0.1:" + cfg.linkPort;
            token = cfg.linkToken.trim();
            clientDir = "";
            return ping();
        }
        List<File> handshakes = findHandshakes();
        if (handshakes.isEmpty()) {
            status = "не найден мост: запущена ли игра с модом DwQuestsBridge?";
            return false;
        }
        String firstError = null;
        for (File handshake : handshakes) {
            if (!readHandshake(handshake)) continue;
            if (ping()) return true;
            if (firstError == null) firstError = status;
        }
        status = firstError != null ? firstError : "bridge.json не читается";
        return false;
    }

    private boolean ping() {
        try {
            JsonObject info = JsonParser.parseObject(get("/ping", null));
            player = info.str("player", "");
            tabCount = info.i("tabs", 0);
            questCount = info.i("quests", 0);
            boolean quests = info.bool("dwquests", false);
            connected = true;
            status = "игра на связи"
                    + (player.isEmpty() ? " (не в мире)" : ": " + player)
                    + (quests ? ", вкладок " + tabCount + ", квестов " + questCount : ", мода квестов нет");
            return true;
        } catch (Exception e) {
            status = "мост не отвечает: " + e.getMessage();
            return false;
        }
    }

    public void disconnect() {
        connected = false;
        status = "отключено";
    }

    /** Папка клиента, чей мост ответил; пустая, если порт и ключ заданы вручную. */
    public String clientDir() { return clientDir; }

    private boolean readHandshake(File handshake) {
        try {
            JsonObject o = JsonParser.parseObject(new String(readAll(new FileInputStream(handshake)),
                    StandardCharsets.UTF_8));
            int port = o.i("port", 0);
            String key = o.str("token", "");
            if (port <= 0 || key.isEmpty()) return false;
            baseUrl = "http://127.0.0.1:" + port;
            token = key;
            clientDir = handshake.getParentFile().getParentFile().getPath();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Все bridge.json, до которых удалось дотянуться: указанная в настройках
     * папка, затем папки клиентов лаунчеров. Мост удаляет свой файл при выходе,
     * но после вылета игры он остаётся — поэтому свежие идут первыми.
     */
    private List<File> findHandshakes() {
        Set<File> found = new LinkedHashSet<>();
        Config cfg = Config.get();
        if (!Config.isBlank(cfg.clientDir)) addHandshake(found, new File(cfg.clientDir));
        for (String guess : CLIENT_GUESSES) addHandshake(found, new File(guess));
        for (String root : LAUNCHER_ROOTS) {
            File[] dirs = new File(root).listFiles(File::isDirectory);
            if (dirs == null) continue;
            for (File dir : dirs) addHandshake(found, dir);
        }
        List<File> list = new ArrayList<>(found);
        list.sort(Comparator.comparingLong(File::lastModified).reversed());
        return list;
    }

    private static void addHandshake(Set<File> found, File clientDir) {
        File f = new File(clientDir, "dwq_export/bridge.json");
        if (f.isFile()) found.add(f.getAbsoluteFile());
    }

    // ------------------------------------------------------------------ запросы

    public String get(String path, String query) throws IOException {
        return new String(bytes(path, query, "GET", null), StandardCharsets.UTF_8);
    }

    public String post(String path, String query, String body) throws IOException {
        return new String(bytes(path, query, "POST", body), StandardCharsets.UTF_8);
    }

    public byte[] bytes(String path, String query) throws IOException {
        return bytes(path, query, "GET", null);
    }

    /** Идёт перебор мостов в connect() — повторять запрос с переподключением нельзя. */
    private volatile boolean probing;

    private byte[] bytes(String path, String query, String method, String body) throws IOException {
        if (baseUrl.isEmpty() && !connect() && baseUrl.isEmpty()) throw new IOException("мост не найден");
        try {
            return request(path, query, method, body);
        } catch (ForbiddenException e) {
            // Ключ не подошёл — скорее всего, запустили другой клиент на том же
            // порту. Один раз ищем мост заново и повторяем.
            if (probing || !connect()) throw e;
            return request(path, query, method, body);
        }
    }

    private static final class ForbiddenException extends IOException {
        ForbiddenException(String message) { super(message); }
    }

    private byte[] request(String path, String query, String method, String body) throws IOException {
        if (local != null) return requestLocal(path, query, body);
        URL url = new URL(baseUrl + path + (query == null || query.isEmpty() ? "" : "?" + query));
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod(method);
        con.setRequestProperty("X-Dwq-Token", token);
        con.setConnectTimeout(2000);
        // Игра отвечает в конце тика, а на выгрузке тик может быть занят --
        // поэтому терпения больше, чем обычно у HTTP.
        con.setReadTimeout(30000);
        if (body != null) {
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            con.setDoOutput(true);
            con.setFixedLengthStreamingMode(data.length);
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            OutputStream out = con.getOutputStream();
            try {
                out.write(data);
            } finally {
                out.close();
            }
        }
        int code = con.getResponseCode();
        InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
        byte[] data = in == null ? new byte[0] : readAll(in);
        if (code >= 400) {
            String text = new String(data, StandardCharsets.UTF_8);
            connected = code != 403 && connected;
            if (code == 403) throw new ForbiddenException(shortError(code, text));
            throw new IOException(shortError(code, text));
        }
        return data;
    }

    private byte[] requestLocal(String path, String query, String body) throws IOException {
        java.util.Map<String, String> params = new java.util.HashMap<>();
        if (query != null && !query.isEmpty()) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                String k = eq < 0 ? pair : pair.substring(0, eq);
                String v = eq < 0 ? "" : pair.substring(eq + 1);
                params.put(java.net.URLDecoder.decode(k, StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(v, StandardCharsets.UTF_8));
            }
        }
        Response r;
        try {
            r = local.call(path, params, body);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            // так же, как HTTP-мост отвечает на упавший запрос: текстом ошибки
            throw new IOException(e.getMessage() == null ? String.valueOf(e) : e.getMessage(), e);
        }
        if (r.code() >= 400) throw new IOException(shortError(r.code(), new String(r.data(), StandardCharsets.UTF_8)));
        return r.data();
    }

    private static String shortError(int code, String text) {
        try {
            JsonObject o = JsonParser.parseObject(text);
            String message = o.str("error", "");
            if (!message.isEmpty()) return message;
        } catch (RuntimeException ignored) {
            // ответ не json — покажем как есть
        }
        return "HTTP " + code + (text.isEmpty() ? "" : ": " + text);
    }

    static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    public static String enc(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    // --------------------------------------------------------------- готовое

    /** Список вкладок в игре. */
    public JsonObject tabs() throws IOException {
        return JsonParser.parseObject(get("/tabs", null));
    }

    /** Вкладка из памяти клиента в формате редактора. */
    public String tab(String tabID) throws IOException {
        return get("/tab", "id=" + enc(tabID));
    }

    /** PNG иконки предмета — с учётом NBT. */
    public byte[] icon(String forgeString) throws IOException {
        return bytes("/icon", "stack=" + enc(forgeString));
    }

    /** Отправить вкладку в игру: мод положит её в буфер и выполнит /dwq tab paste. */
    public String paste(String tabID, String json) throws IOException {
        return post("/paste", "id=" + enc(tabID), json);
    }

    /**
     * Отправить несколько вкладок: мост заливает их по одной и каждую ждёт
     * подтверждения сервера, ход — в {@link #jobStatus()}.
     *
     * @param tabs пары {tabID, json}
     */
    public String pasteAll(List<String[]> tabs) throws IOException {
        ru.dw.qeditor.json.JsonArray arr = new ru.dw.qeditor.json.JsonArray();
        for (String[] t : tabs) arr.add(new JsonObject().put("tabID", t[0]).put("json", t[1]));
        return post("/paste-all", null, ru.dw.qeditor.json.JsonWriter.write(arr, false));
    }

    /** Положить вкладку только в буфер обмена игры. */
    public String toClipboard(String tabID, String json) throws IOException {
        return post("/paste", "id=" + enc(tabID) + "&clipboard=1", json);
    }

    /** Запустить выгрузку в самой игре: what = icons | assets | tabs. */
    public String export(String what, String scope) throws IOException {
        String query = "what=" + enc(what) + (scope == null ? "" : "&scope=" + enc(scope));
        return post("/export", query, "");
    }

    // ------------------------------------------------- прогресс своего игрока

    /**
     * Команда {@code /dwq complete…} / {@code /dwq clear…} из чата игры. Мост
     * пропускает только их и только на ник своего игрока, а в ответе отдаёт,
     * что сервер написал в чат ({@code replies}).
     */
    public JsonObject command(String text) throws IOException {
        return JsonParser.parseObject(post0("/command", null, text));
    }

    /** Кнопка книги квестов «Забрать награду». */
    public JsonObject claim(String tabID, String questID) throws IOException {
        return JsonParser.parseObject(post0("/claim", "tab=" + enc(tabID) + "&quest=" + enc(questID), ""));
    }

    /** «Забрать награду» у нескольких квестов вкладки; пустой список — у всех выполненных в ней. */
    public JsonObject claimMany(String tabID, List<String> questIDs) throws IOException {
        return JsonParser.parseObject(post0("/claim", "tab=" + enc(tabID), String.join("\n", questIDs)));
    }

    /** Кнопка книги квестов «Забрать все награды». */
    public JsonObject claimAll() throws IOException {
        return JsonParser.parseObject(post0("/claim-all", null, ""));
    }

    /** Состояние квестов вкладки у своего игрока: completed / rewarded / active. */
    public JsonObject progress(String tabID) throws IOException {
        try {
            return JsonParser.parseObject(get("/progress", "tab=" + enc(tabID)));
        } catch (IOException e) {
            throw older(e);
        }
    }

    /** Запросы прогресса появились в мосте позже прочих — у старого на них 404. */
    private String post0(String path, String query, String body) throws IOException {
        try {
            return post(path, query, body);
        } catch (IOException e) {
            throw older(e);
        }
    }

    private static IOException older(IOException e) {
        if (!"нет такого запроса".equals(e.getMessage())) return e;
        return new IOException("мост в игре старой версии и этого не умеет — соберите DwQuestsBridge "
                + "(build.bat, install.bat) и перезапустите игру");
    }

    public JsonObject jobStatus() throws IOException {
        return JsonParser.parseObject(get("/status", null));
    }
}
