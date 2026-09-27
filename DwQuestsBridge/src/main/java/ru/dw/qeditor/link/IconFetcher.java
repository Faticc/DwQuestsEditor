package ru.dw.qeditor.link;

import ru.dw.qeditor.assets.Config;
import ru.dw.qeditor.assets.ItemRegistry;
import ru.dw.qeditor.gl.GlWindow;
import ru.dw.qeditor.util.ForgeName;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Дорисовка недостающих иконок силами игры.
 *
 * <p>Поле редактора рисуется кадрами, и ходить в игру прямо из кадра нельзя —
 * окно бы замирало на каждый запрос. Поэтому кадр только оставляет заявку, а
 * ходит за картинкой отдельный поток; когда картинка пришла, он будит окно
 * ({@link GlWindow#postEmptyEvent()}), и следующий кадр рисует уже её.
 *
 * <p>Это и есть ответ на предметы, у которых вид зависит от NBT: заранее
 * выгрузить все цветы Botany нельзя (сочетаний генома слишком много), а
 * попросить игру нарисовать тот единственный, что стоит в квесте, — можно.
 * Пришедшая картинка тут же кладётся в папку иконок, так что второй раз
 * спрашивать уже не придётся.
 *
 * <p>В игре поток не нужен: экран редактора сам рисует заказанные иконки в
 * начале кадра, пока не выйдет отведённое время ({@link #pump}). Иначе каждая
 * иконка ждала бы своего тика — двадцать в секунду, и вкладка на четыреста
 * квестов собиралась бы полминуты.
 */
public final class IconFetcher implements ItemRegistry.Extra {

    private static final IconFetcher INSTANCE = new IconFetcher();

    /** Сколько заявок держать в очереди: иначе первый же кадр карты закажет тысячу. */
    private static final int MAX_QUEUE = 64;

    /** Нарисовать иконку прямо сейчас — в главном потоке игры. Null — не рисуется. */
    public interface Direct {
        BufferedImage render(String forgeString);
    }

    private volatile Direct direct;

    /** Писать дорисованное на диск — не в кадре: PNG жмётся заметно дольше, чем рисуется. */
    private final java.util.concurrent.ExecutorService saver =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "dwq-icon-save");
                t.setDaemon(true);
                return t;
            });

    public static IconFetcher get() { return INSTANCE; }

    private final Deque<String> queue = new ArrayDeque<>();
    private final Set<String> queued = new HashSet<>();
    private final Map<String, BufferedImage> ready = new HashMap<>();
    /** Что игра нарисовать не смогла — второй раз не спрашиваем. */
    private final Set<String> failed = new HashSet<>();
    private Thread worker;
    private volatile int done;
    private volatile String lastError = "";

    private IconFetcher() { }

    public void useDirect(Direct renderer) { direct = renderer; }

    /**
     * Нарисовать заказанное за кадр, пока не выйдет время. Зовётся из главного
     * потока до кадра редактора — состояние GL тогда игровое, как у слота
     * инвентаря.
     */
    public void pump(long budgetNanos) {
        Direct d = direct;
        if (d == null) return;
        long until = System.nanoTime() + budgetNanos;
        while (System.nanoTime() < until) {
            String next;
            synchronized (this) {
                next = queue.poll();
            }
            if (next == null) return;
            BufferedImage image = null;
            try {
                image = d.render(next);
            } catch (RuntimeException e) {
                lastError = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
            }
            synchronized (this) {
                queued.remove(next);
                if (image == null) failed.add(next);
                else {
                    ready.put(next, image);
                    done++;
                }
            }
            if (image != null) {
                final BufferedImage img = image;
                saver.execute(() -> {
                    try {
                        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                        ImageIO.write(img, "png", out);
                        // реестр трогает только главный поток: картинка и так
                        // лежит в ready, а файл найдётся при следующем открытии
                        save(next, out.toByteArray(), false);
                    } catch (Exception ignored) {
                        // на диск не легло — в памяти картинка уже есть
                    }
                });
            }
        }
    }

    public synchronized int pending() { return queue.size(); }

    public int fetched() { return done; }

    public String lastError() { return lastError; }

    /** Готовая картинка или null. */
    public synchronized BufferedImage peek(String forgeString) {
        return ready.get(forgeString);
    }

    public synchronized boolean hopeless(String forgeString) {
        return failed.contains(forgeString);
    }

    /** Забыть неудачи — после переподключения или входа в мир. */
    public synchronized void retryFailed() {
        failed.clear();
        lastError = "";
    }

    public synchronized void forget() {
        queue.clear();
        queued.clear();
        ready.clear();
        failed.clear();
    }

    /**
     * Спрос из кадра: если картинка уже пришла — отдать, иначе поставить в
     * очередь и вернуть null (кадр нарисует что есть).
     *
     * @param haveRough нашлась хотя бы общая картинка предмета
     */
    @Override
    public BufferedImage icon(String forgeString, boolean haveRough) {
        if (!Config.get().linkAutoIcons || !GameLink.get().connected()) return null;
        String key = key(forgeString);
        if (key == null) return null;
        BufferedImage img = peek(key);
        if (img != null) return img;
        request(key);
        return null;
    }

    /** Количество на вид не влияет — просить одно и то же по разным ключам незачем. */
    private static String key(String forgeString) {
        ForgeName fn = ForgeName.parse(forgeString);
        if (fn.isCommand() || fn.mod.isEmpty() || fn.name.isEmpty()) return null;
        return fn.nbt == null ? fn.id : fn.id + fn.nbt;
    }

    /** Заказать иконку. Вызывается из кадра — не блокирует. */
    public synchronized void request(String forgeString) {
        if (forgeString == null || forgeString.isEmpty()) return;
        if (ready.containsKey(forgeString) || failed.contains(forgeString) || queued.contains(forgeString)) return;
        if (queue.size() >= MAX_QUEUE) return;
        queue.add(forgeString);
        queued.add(forgeString);
        if (direct == null) ensureWorker();
        notifyAll();
    }

    private void ensureWorker() {
        if (worker != null && worker.isAlive()) return;
        worker = new Thread(new Runnable() {
            public void run() { loop(); }
        }, "dwq-icon-fetch");
        worker.setDaemon(true);
        worker.start();
    }

    private void loop() {
        while (true) {
            String next;
            synchronized (this) {
                long waitUntil = System.currentTimeMillis() + 15000L;
                while (queue.isEmpty()) {
                    long left = waitUntil - System.currentTimeMillis();
                    if (left <= 0) {
                        worker = null;     // простаиваем — поток не держим
                        return;
                    }
                    try {
                        wait(left);
                    } catch (InterruptedException e) {
                        worker = null;
                        return;
                    }
                }
                next = queue.poll();
            }
            fetch(next);
        }
    }

    private void fetch(String forgeString) {
        BufferedImage image = null;
        try {
            byte[] png = GameLink.get().icon(forgeString);
            image = ImageIO.read(new ByteArrayInputStream(png));
            if (image != null) save(forgeString, png, true);
        } catch (Exception e) {
            lastError = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
        }
        synchronized (this) {
            queued.remove(forgeString);
            if (image == null) {
                failed.add(forgeString);
            } else {
                ready.put(forgeString, image);
                done++;
            }
        }
        // Кадры рисуются только по событиям: без этого новая иконка появилась бы
        // лишь когда человек шевельнёт мышью.
        GlWindow.postEmptyEvent();
    }

    /** Кладём рядом с остальными иконками — в следующий раз найдётся без игры. */
    private void save(String forgeString, byte[] png, boolean notice) {
        try {
            String path = ItemRegistry.iconFileFor(forgeString);
            if (path == null) return;
            File out = new File(new File(Config.get().iconsWriteDir(), "img"), path);
            File parent = out.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) return;
            try (FileOutputStream os = new FileOutputStream(out)) {
                os.write(png);
            }
            if (notice) ItemRegistry.get().noticeNewIcon(path);
        } catch (Exception ignored) {
            // не записалось — не беда, картинка уже есть в памяти
        }
    }
}
