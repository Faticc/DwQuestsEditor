package ru.dw.qeditor.ai;

import ru.dw.qeditor.json.JsonObject;
import ru.dw.qeditor.json.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Обращение к локальному Claude Code CLI (`claude -p`).
 *
 * <p>Ключ API не нужен: используется та же учётка, под которой работает
 * консольный `claude`. Запрос уходит в stdin, ответ читается из stdout в
 * формате {@code --output-format json}.
 */
public class ClaudeClient {

    public static final class Result {
        public final boolean ok;
        public final String text;
        public final String error;
        public final double costUsd;
        public final long durationMs;

        Result(boolean ok, String text, String error, double costUsd, long durationMs) {
            this.ok = ok;
            this.text = text;
            this.error = error;
            this.costUsd = costUsd;
            this.durationMs = durationMs;
        }
    }

    private String executable = defaultExecutable();
    private String model = "sonnet";
    private int timeoutSeconds = 300;
    private File workingDir;
    private Process current;

    public static String defaultExecutable() { return "claude"; }

    public void setExecutable(String executable) { this.executable = executable; }

    public String getExecutable() { return executable; }

    public void setModel(String model) { this.model = model; }

    public String getModel() { return model; }

    public void setTimeoutSeconds(int s) { this.timeoutSeconds = Math.max(10, s); }

    public void setWorkingDir(File dir) { this.workingDir = dir; }

    /** Есть ли CLI в системе. */
    public String probeVersion() {
        try {
            List<String> cmd = wrap(new ArrayList<>(List.of(executable, "--version")));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = readAll(p);
            p.waitFor(20, TimeUnit.SECONDS);
            return out.trim();
        } catch (Exception e) {
            return null;
        }
    }

    public void cancel() {
        Process p = current;
        if (p != null) p.destroy();
    }

    /** Отправить запрос и дождаться ответа. Блокирует поток — вызывать не из EDT. */
    public Result ask(String prompt) {
        long start = System.currentTimeMillis();
        try {
            List<String> cmd = wrap(new ArrayList<>(List.of(
                    executable, "-p", "--output-format", "json", "--model", model)));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            if (workingDir != null && workingDir.isDirectory()) pb.directory(workingDir);

            Process p = pb.start();
            current = p;
            try (Writer w = new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8)) {
                w.write(prompt);
            }
            String raw = readAll(p);
            boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            current = null;
            long ms = System.currentTimeMillis() - start;

            if (!finished) {
                p.destroy();
                return new Result(false, null, "Превышено время ожидания (" + timeoutSeconds + " с)", 0, ms);
            }

            Object parsed;
            try {
                parsed = JsonParser.parse(raw.trim());
            } catch (RuntimeException e) {
                // CLI мог напечатать обычный текст (например, сообщение об ошибке)
                return new Result(p.exitValue() == 0, raw.trim(),
                        p.exitValue() == 0 ? null : raw.trim(), 0, ms);
            }

            if (parsed instanceof JsonObject) {
                JsonObject o = (JsonObject) parsed;
                String result = o.str("result", null);
                boolean isError = o.bool("is_error", false);
                double cost = 0;
                Object c = o.get("total_cost_usd");
                if (c instanceof Number) cost = ((Number) c).doubleValue();
                if (result == null) result = raw.trim();
                return new Result(!isError, result, isError ? result : null, cost, ms);
            }
            return new Result(true, raw.trim(), null, 0, ms);
        } catch (IOException e) {
            return new Result(false, null, "Не удалось запустить «" + executable + "»: " + e.getMessage(),
                    0, System.currentTimeMillis() - start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, null, "Прервано", 0, System.currentTimeMillis() - start);
        } finally {
            current = null;
        }
    }

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

    private static String readAll(Process p) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /**
     * Достаёт JSON-объект из ответа модели: снимает обрамление ```json,
     * иначе берёт текст от первой «{» до парной ей «}».
     */
    public static String extractJson(String text) {
        if (text == null) return null;
        String s = text.trim();
        int fence = s.indexOf("```");
        if (fence >= 0) {
            int nl = s.indexOf('\n', fence);
            int close = s.indexOf("```", nl < 0 ? fence + 3 : nl);
            if (nl > 0 && close > nl) s = s.substring(nl + 1, close).trim();
        }
        int start = s.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return s.substring(start, i + 1);
        }
        return null;
    }
}
