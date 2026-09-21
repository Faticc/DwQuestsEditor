package ru.dw.qeditor.ai;

import ru.dw.qeditor.json.JsonArray;
import ru.dw.qeditor.json.JsonObject;
import ru.dw.qeditor.json.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Обращение к локальному Claude Code CLI (`claude -p`).
 *
 * <p>Ключ API не нужен: используется та же учётка, под которой работает
 * консольный `claude`. Запрос уходит в stdin, ход работы читается из stdout
 * построчно ({@code --output-format stream-json}).
 *
 * <p>Главное здесь — что CLI запускается урезанным. По умолчанию он тащит в
 * каждый запрос свой системный промпт, два десятка инструментов, все
 * MCP-серверы и скиллы пользователя: на «привет» уходило 37 тыс. токенов.
 * Свой короткий системный промпт, пять файловых инструментов и пустой список
 * MCP дают 3,6 тыс. — вдесятеро меньше и на пару секунд быстрее.
 */
public class ClaudeClient {

    /** Инструменты помощника: только файлы рабочего каталога. */
    public static final String TOOLS = "Read,Edit,Write,Glob,Grep";
    public static final String SYSTEM_FILE = "system.md";
    private static final String MCP_FILE = "mcp.json";

    public static final class Result {
        public final boolean ok;
        public final String text;
        public final String error;
        public final double costUsd;
        public final long durationMs;
        public final int turns;
        /** Токены: новые на входе, прочитанные из кэша, записанные в кэш, на выходе. */
        public final long inTokens, cacheRead, cacheWrite, outTokens;

        Result(boolean ok, String text, String error, double costUsd, long durationMs, int turns,
               long inTokens, long cacheRead, long cacheWrite, long outTokens) {
            this.ok = ok;
            this.text = text;
            this.error = error;
            this.costUsd = costUsd;
            this.durationMs = durationMs;
            this.turns = turns;
            this.inTokens = inTokens;
            this.cacheRead = cacheRead;
            this.cacheWrite = cacheWrite;
            this.outTokens = outTokens;
        }

        static Result fail(String error, long ms) {
            return new Result(false, null, error, 0, ms, 0, 0, 0, 0, 0);
        }
    }

    private String executable = defaultExecutable();
    private String model = "sonnet";
    private String effort;
    private int timeoutSeconds = 600;
    private volatile Process current;

    public static String defaultExecutable() { return "claude"; }

    public void setExecutable(String executable) { this.executable = executable; }

    public String getExecutable() { return executable; }

    public void setModel(String model) { this.model = model; }

    public String getModel() { return model; }

    /** low / medium / high либо null — как решит CLI. */
    public void setEffort(String effort) { this.effort = effort; }

    public void setTimeoutSeconds(int s) { this.timeoutSeconds = Math.max(10, s); }

    /** Есть ли CLI в системе. */
    public String probeVersion() {
        try {
            List<String> cmd = wrap(new ArrayList<>(List.of(executable, "--version")));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(20, TimeUnit.SECONDS);
            return out.trim();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Прервать запрос. На Windows CLI запущен через {@code cmd.exe /c}, и
     * {@code destroy()} убил бы одну оболочку — сам claude работал бы дальше
     * и тратил токены. Поэтому гасится всё дерево процессов.
     */
    public void cancel() {
        Process p = current;
        if (p == null) return;
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }

    /**
     * Аргументы запуска — их же показывает окно помощника, чтобы было видно,
     * что именно уходит.
     *
     * @param resume продолжить разговор с этим id; иначе начать новый с id
     *               {@code session}
     */
    public List<String> arguments(String session, boolean resume) {
        List<String> a = new ArrayList<>(List.of(
                executable, "-p",
                "--output-format", "stream-json", "--verbose",
                "--model", model,
                // свой системный промпт вместо огромного промпта Claude Code
                "--system-prompt-file", SYSTEM_FILE,
                "--tools", TOOLS,
                "--allowedTools", TOOLS,
                "--permission-mode", "acceptEdits",
                // без MCP-серверов, скиллов, хуков и CLAUDE.md пользователя:
                // всё это уходило бы в каждый запрос
                "--strict-mcp-config", "--mcp-config", MCP_FILE,
                "--disable-slash-commands",
                "--setting-sources", "project"));
        if (effort != null) a.addAll(List.of("--effort", effort));
        if (session != null) a.addAll(List.of(resume ? "--resume" : "--session-id", session));
        return a;
    }

    /**
     * Отправить запрос и дождаться ответа. Блокирует поток — вызывать из
     * фонового. {@code events} получает ход работы по мере поступления.
     *
     * @param dir     рабочий каталог: файлы вкладки и system.md
     * @param system  системный промпт — ляжет в {@code dir/system.md}
     */
    public Result ask(File dir, String system, String prompt, String session, boolean resume,
                      Consumer<String> events) {
        long start = System.currentTimeMillis();
        Process p = null;
        Thread watchdog = null;
        try {
            Files.writeString(new File(dir, SYSTEM_FILE).toPath(), system, StandardCharsets.UTF_8);
            Files.writeString(new File(dir, MCP_FILE).toPath(), "{\"mcpServers\":{}}", StandardCharsets.UTF_8);

            ProcessBuilder pb = new ProcessBuilder(wrap(arguments(session, resume)));
            pb.redirectErrorStream(true);
            pb.directory(dir);
            p = pb.start();
            current = p;

            final Process proc = p;
            watchdog = new Thread(() -> {
                try {
                    if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                        events.accept("Превышено время ожидания (" + timeoutSeconds + " с) — прерываю");
                        cancel();
                    }
                } catch (InterruptedException ignored) {
                    // ответ пришёл раньше
                }
            }, "claude-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();

            try (Writer w = new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8)) {
                w.write(prompt);
            }

            Stream stream = new Stream(events);
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) stream.line(line);
            }
            p.waitFor(10, TimeUnit.SECONDS);
            long ms = System.currentTimeMillis() - start;
            if (stream.result != null) return stream.toResult(ms);
            String out = stream.plain.toString().trim();
            return Result.fail(out.isEmpty() ? "CLI завершился без ответа (код " + exit(p) + ")" : out, ms);
        } catch (IOException e) {
            return Result.fail("Не удалось запустить «" + executable + "»: " + e.getMessage(),
                    System.currentTimeMillis() - start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail("Прервано", System.currentTimeMillis() - start);
        } finally {
            current = null;
            if (watchdog != null) watchdog.interrupt();
        }
    }

    private static String exit(Process p) {
        try {
            return String.valueOf(p.exitValue());
        } catch (IllegalThreadStateException e) {
            return "?";
        }
    }

    /** Разбор stream-json: строка за строкой, по событию на строку. */
    private static final class Stream {
        final Consumer<String> events;
        final StringBuilder plain = new StringBuilder();
        JsonObject result;

        Stream(Consumer<String> events) { this.events = events; }

        void line(String line) {
            String s = line.trim();
            if (s.isEmpty()) return;
            JsonObject o;
            try {
                o = JsonParser.parseObject(s);
            } catch (RuntimeException e) {
                // не JSON — сообщение самого CLI (например, об ошибке запуска)
                plain.append(s).append('\n');
                events.accept(s);
                return;
            }
            String type = o.str("type", "");
            switch (type) {
                case "system" -> {
                    if ("init".equals(o.str("subtype", "")))
                        events.accept("· сеанс " + o.str("session_id", "?") + ", модель " + o.str("model", "?"));
                }
                case "assistant" -> {
                    JsonObject m = o.obj("message");
                    JsonArray content = m == null ? null : m.arr("content");
                    if (content != null) for (Object c : content.list()) {
                        if (c instanceof JsonObject b) describe(b);
                    }
                }
                case "user" -> {
                    JsonObject m = o.obj("message");
                    JsonArray content = m == null ? null : m.arr("content");
                    if (content != null) for (Object c : content.list()) {
                        if (c instanceof JsonObject b && b.bool("is_error", false))
                            events.accept("  ! " + shorten(text(b.get("content")), 300));
                    }
                }
                case "result" -> result = o;
                default -> { }
            }
        }

        private void describe(JsonObject b) {
            String t = b.str("type", "");
            if (t.equals("text")) {
                String text = b.str("text", "").trim();
                if (!text.isEmpty()) events.accept(text);
            } else if (t.equals("tool_use")) {
                JsonObject in = b.obj("input");
                String name = b.str("name", "?");
                String what = in == null ? "" : first(in, "file_path", "pattern", "path");
                String mark = switch (name) {
                    case "Read" -> "→";
                    case "Edit" -> "✎";
                    case "Write" -> "+";
                    default -> "?";
                };
                events.accept(mark + " " + name + " " + relative(what));
            }
        }

        Result toResult(long ms) {
            JsonObject u = result.obj("usage");
            boolean error = result.bool("is_error", false);
            String text = result.str("result", "");
            String err = error ? (text.isEmpty() ? result.str("subtype", "ошибка") : text) : null;
            return new Result(!error, text, err, num(result.get("total_cost_usd")), ms,
                    (int) num(result.get("num_turns")),
                    u == null ? 0 : (long) num(u.get("input_tokens")),
                    u == null ? 0 : (long) num(u.get("cache_read_input_tokens")),
                    u == null ? 0 : (long) num(u.get("cache_creation_input_tokens")),
                    u == null ? 0 : (long) num(u.get("output_tokens")));
        }
    }

    private static String first(JsonObject o, String... keys) {
        for (String k : keys) {
            String v = o.str(k, null);
            if (v != null) return v;
        }
        return "";
    }

    /** Путь без рабочего каталога — в журнале видно «quests/Х.json», а не весь путь. */
    private static String relative(String path) {
        String p = path.replace('\\', '/');
        int i = p.indexOf("/" + TabWorkspace.QUESTS_DIR + "/");
        if (i >= 0) return p.substring(i + 1);
        int slash = p.lastIndexOf('/');
        return slash >= 0 && p.contains(":") ? p.substring(slash + 1) : p;
    }

    private static String text(Object content) {
        if (content instanceof String s) return s;
        if (content instanceof JsonArray a) {
            StringBuilder sb = new StringBuilder();
            for (Object x : a.list()) if (x instanceof JsonObject o) sb.append(o.str("text", ""));
            return sb.toString();
        }
        return String.valueOf(content);
    }

    private static String shorten(String s, int max) {
        s = s.replace('\n', ' ');
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static double num(Object o) { return o instanceof Number n ? n.doubleValue() : 0; }

    /** На Windows claude — это .cmd, запускать надо через оболочку. */
    private static List<String> wrap(List<String> cmd) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) return cmd;
        List<String> out = new ArrayList<>();
        out.add("cmd.exe");
        out.add("/c");
        out.addAll(cmd);
        return out;
    }
}
