package ru.dw.qbridge.client;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import ru.dw.qbridge.DwqBridge;

import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Главный поток клиента: очередь заданий с моста и шаг текущей длинной работы.
 *
 * <p>Рисовать иконки и читать данные квестов можно только здесь: контекст OpenGL
 * принадлежит главному потоку, а карты квестов меняются в сетевом потоке. Поэтому
 * запросы HTTP-моста ничего не делают сами — они кладут задание сюда и ждут ответ.
 *
 * <p>Шаг работы вызывается в конце тика: в этот момент кадр ещё не рисуется, а
 * сетевая очередь уже разобрана — клиент не отваливается по таймауту.
 */
public final class ClientTicker {

    private static final ClientTicker INSTANCE = new ClientTicker();

    private final Queue<FutureTask<?>> tasks = new ConcurrentLinkedQueue<FutureTask<?>>();
    private volatile Thread mainThread;
    private volatile Job job;
    private long jobStarted;
    private int lastPercent;

    private ClientTicker() {}

    public static ClientTicker get() {
        return INSTANCE;
    }

    public static void register() {
        FMLCommonHandler.instance().bus().register(INSTANCE);
    }

    // ------------------------------------------------------------- задания моста

    /** Выполнить в главном потоке и дождаться результата. */
    public <T> T call(Callable<T> work, long timeoutMs) throws Exception {
        // Команды /dwqb выполняются уже в главном потоке: если встать в очередь,
        // разбирать её будет некому — получится тупик до истечения таймаута.
        if (Thread.currentThread() == mainThread) return work.call();
        FutureTask<T> task = new FutureTask<T>(work);
        tasks.add(task);
        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(false);
            throw new IllegalStateException("игра не ответила за " + timeoutMs + " мс");
        }
    }

    // -------------------------------------------------------------- длинные работы

    public boolean busy() {
        return job != null;
    }

    public String busyName() {
        Job j = job;
        return j == null ? null : j.name();
    }

    public float fraction() {
        Job j = job;
        return j == null ? 0f : j.fraction();
    }

    public void start(Job newJob) {
        if (job != null) throw new IllegalStateException("уже идёт: " + job.name());
        job = newJob;
        jobStarted = System.currentTimeMillis();
        lastPercent = 0;
        Chat.warn(newJob.name() + " пошла. Игра будет тормозить; прервать — /dwqb stop");
    }

    public void stop() {
        Job j = job;
        if (j == null) return;
        j.abort();
        job = null;
        Chat.warn(j.name() + ": прервано.");
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        mainThread = Thread.currentThread();
        ru.dw.qbridge.editor.EditorHost.get().tick();

        // Задания моста короткие, но их может накопиться пачка — разбираем все.
        FutureTask<?> task;
        while ((task = tasks.poll()) != null) {
            task.run();
        }

        Job j = job;
        if (j == null) return;

        boolean done;
        try {
            done = j.step(DwqBridge.config.tickBudgetMs);
        } catch (Throwable t) {
            DwqBridge.LOG.error(j.name() + " упала", t);
            Chat.error(j.name() + " упала: " + t);
            j.abort();
            job = null;
            return;
        }

        int percent = (int) (j.fraction() * 100f);
        if (percent >= lastPercent + 10) {
            lastPercent = percent - (percent % 10);
            Chat.say("§7" + j.name() + ": " + lastPercent + "%");
        }

        if (done) {
            long seconds = (System.currentTimeMillis() - jobStarted) / 1000L;
            job = null;
            Chat.ok(j.name() + " — готово за " + seconds + " с. " + j.summary());
        }
    }
}
