package ru.dw.qbridge.bridge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import ru.dw.qbridge.DwqBridge;
import ru.dw.qbridge.client.Chat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Локальный мост: редактор ходит сюда по HTTP и получает данные прямо из
 * работающего клиента — вкладки, список предметов и дорисованные иконки.
 *
 * <p>Слушается только 127.0.0.1, и каждый запрос требует ключ из конфига:
 * страница в браузере умеет стучаться на localhost, а ключа у неё нет.
 *
 * <p>Сами запросы ничего в игре не трогают: всё, что смотрит в игру или рисует,
 * уходит заданием в главный поток ({@code ClientTicker}) — GL и карта квестов
 * принадлежат ему.
 */
public final class BridgeServer {

    private static final BridgeServer INSTANCE = new BridgeServer();

    private HttpServer server;

    private BridgeServer() {}

    public static BridgeServer get() {
        return INSTANCE;
    }

    public boolean running() {
        return server != null;
    }

    public int port() {
        return server == null ? 0 : server.getAddress().getPort();
    }

    public synchronized void start() {
        if (server != null) return;
        int port = DwqBridge.config.port;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8);
            server.createContext("/", new Dispatch());
            server.setExecutor(Executors.newFixedThreadPool(2, new ThreadFactory() {
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "DwQBridge HTTP");
                    t.setDaemon(true);
                    return t;
                }
            }));
            server.start();
            writeHandshake(port);
            DwqBridge.LOG.info("мост слушает 127.0.0.1:" + port);
        } catch (IOException e) {
            server = null;
            DwqBridge.LOG.error("мост не поднялся на порту " + port, e);
            Chat.error("мост не поднялся на порту " + port + ": " + e.getMessage());
        }
    }

    public synchronized void stop() {
        if (server == null) return;
        server.stop(0);
        server = null;
        new File(DwqBridge.outDir, "bridge.json").delete();
        DwqBridge.LOG.info("мост остановлен");
    }

    /**
     * Порт и ключ кладутся в файл выгрузки — так редактору достаточно знать путь
     * к клиенту, чтобы подключиться, и ключ не приходится вводить руками.
     */
    private void writeHandshake(int port) {
        try {
            File file = new File(DwqBridge.dir(""), "bridge.json");
            Writer w = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
            try {
                w.write("{\"port\":" + port + ",\"token\":\"" + DwqBridge.config.token + "\"}");
            } finally {
                w.close();
            }
        } catch (Exception e) {
            DwqBridge.LOG.warn("bridge.json не записался: " + e);
        }
    }

    // ------------------------------------------------------------------ запросы

    private final class Dispatch implements HttpHandler {

        public void handle(HttpExchange ex) throws IOException {
            try {
                String path = ex.getRequestURI().getPath();
                Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());

                if (!authorized(ex, query)) {
                    send(ex, 403, "application/json; charset=utf-8",
                            "{\"error\":\"нужен ключ: заголовок X-Dwq-Token\"}".getBytes("UTF-8"));
                    return;
                }
                Api.Reply reply = Api.shared().handle(path, query, body(ex));
                if (reply.iconPath != null) ex.getResponseHeaders().set("X-Dwq-Icon-Path", reply.iconPath);
                send(ex, reply.code, reply.type, reply.data);
            } catch (Throwable t) {
                DwqBridge.LOG.warn("запрос " + ex.getRequestURI() + " упал", t);
                try {
                    send(ex, 500, "application/json; charset=utf-8",
                            ("{\"error\":" + Api.quote(String.valueOf(t.getMessage() == null ? t : t.getMessage()))
                                    + "}").getBytes("UTF-8"));
                } catch (IOException ignored) {
                    // соединение уже закрыто — ничего не поделать
                }
            } finally {
                ex.close();
            }
        }
    }

    private static boolean authorized(HttpExchange ex, Map<String, String> query) {
        String header = ex.getRequestHeaders().getFirst("X-Dwq-Token");
        String token = header != null ? header : query.get("token");
        return DwqBridge.config.token.equals(token);
    }

    static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new HashMap<String, String>();
        if (raw == null || raw.isEmpty()) return out;
        String[] pairs = raw.split("&");
        for (int i = 0; i < pairs.length; i++) {
            int eq = pairs[i].indexOf('=');
            try {
                if (eq < 0) out.put(URLDecoder.decode(pairs[i], "UTF-8"), "");
                else out.put(URLDecoder.decode(pairs[i].substring(0, eq), "UTF-8"),
                        URLDecoder.decode(pairs[i].substring(eq + 1), "UTF-8"));
            } catch (Exception ignored) {
                // кривая процентная кодировка — параметр просто пропускаем
            }
        }
        return out;
    }

    static String body(HttpExchange ex) throws IOException {
        InputStream in = ex.getRequestBody();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), "UTF-8");
    }

    static void send(HttpExchange ex, int code, String type, byte[] data) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, data.length);
        OutputStream os = ex.getResponseBody();
        try {
            os.write(data);
        } finally {
            os.close();
        }
    }
}
