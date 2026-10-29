package ru.dw.qeditor.android;

import ru.dw.qeditor.gl.Input;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static ru.dw.qeditor.gl.Keys.GLFW_MOUSE_BUTTON_LEFT;
import static ru.dw.qeditor.gl.Keys.GLFW_MOUSE_BUTTON_MIDDLE;
import static ru.dw.qeditor.gl.Keys.GLFW_MOUSE_BUTTON_RIGHT;
import static ru.dw.qeditor.gl.Keys.GLFW_PRESS;
import static ru.dw.qeditor.gl.Keys.GLFW_RELEASE;

/**
 * Пальцы → мышь и клавиатура редактора.
 *
 * <p>Интерфейс редактора написан под мышь: щелчок, двойной щелчок, ПКМ,
 * колесо, средняя кнопка, Shift и Ctrl. Перекраивать под пальцы каждый виджет
 * незачем — здесь жесты превращаются в те же события, что слало окно GLFW:
 *
 * <ul>
 *   <li>тап — щелчок ЛКМ, два тапа рядом — двойной щелчок;</li>
 *   <li>долгое нажатие — ПКМ (на карте — сразу, в панелях — по отпусканию);</li>
 *   <li>в панелях тяга пальцем — колесо (прокрутка списков), а задержать палец и
 *       потом тянуть — тяга мышью (разделители, ползунки, выделение текста);</li>
 *   <li>на карте тяга — тяга ЛКМ (перенос квестов, рамка выделения), два
 *       пальца — средняя кнопка (панорама) и щелчки колеса по разведению (зум);</li>
 *   <li>в просмотре два пальца — колесо: вертикально листают панель квеста и
 *       список вкладок, разведение — зум карты.</li>
 * </ul>
 *
 * <p>Окно редактора разбирает ввод раз в кадр, и нажатие с отпусканием в одном
 * кадре оно может не заметить. Поэтому события копятся очередью с «границами
 * кадра»: за кадр отдаётся всё до ближайшей границы. По той же причине между
 * набранными символами и клавишами (Backspace от клавиатуры телефона) тоже
 * ставится граница — поле ввода разбирает за кадр сначала клавиши, потом текст.
 *
 * <p>Сырые касания приходят из потока интерфейса Android, а разбираются здесь,
 * в потоке отрисовки, — так вся логика жестов однопоточная. Android-классов
 * тут нет: тот же разбор гоняется проверкой на компьютере.
 */
public final class TouchInput {

    /** Что под пальцем — от этого зависит, чем станет тяга. */
    public enum Zone { UI, MAP, PREVIEW }

    /** Раскладку знает окно приложения — оно и отвечает, что где. */
    public interface Zones {
        Zone at(float x, float y);
    }

    public static final int DOWN = 0;
    public static final int MOVE = 1;
    public static final int UP = 2;
    public static final int POINTER_DOWN = 3;
    public static final int POINTER_UP = 4;
    public static final int CANCEL = 5;

    /** Снимок касания: действие, время и все пальцы (в пикселях окна). */
    public record Touch(int action, double time, float[] xs, float[] ys) {
        float cx() {
            float s = 0;
            for (float x : xs) s += x;
            return s / xs.length;
        }

        float cy() {
            float s = 0;
            for (float y : ys) s += y;
            return s / ys.length;
        }

        float spread() {
            if (xs.length < 2) return 0;
            float dx = xs[0] - xs[1], dy = ys[0] - ys[1];
            return (float) Math.sqrt(dx * dx + dy * dy);
        }
    }

    // ------------------------------------------------------------------ события для окна

    private static final int OP_MOVE = 0;
    private static final int OP_PRESS = 1;
    private static final int OP_RELEASE = 2;
    private static final int OP_SCROLL = 3;
    private static final int OP_BARRIER = 4;
    private static final int OP_LEAVE = 5;
    private static final int OP_KEY = 6;
    private static final int OP_CHAR = 7;

    private record Op(int type, float x, float y, int a, int b, int c) { }

    private final ArrayDeque<Op> ops = new ArrayDeque<>();
    /** Касания и клавиши из потока Android — до разбора. */
    private final List<Object> incoming = new ArrayList<>();
    private int lastQueued = -1;

    // ------------------------------------------------------------------ состояние жеста

    private enum State {
        IDLE,
        /** Палец в панели: тап, прокрутка или тяга — ещё не ясно. */
        PENDING_UI,
        /** Палец на карте или в просмотре: ЛКМ ещё не нажата (ждём, не придёт ли второй). */
        PENDING_PRESS,
        /** ЛКМ нажата и тянется. */
        DRAGGING,
        /** Тяга пальцем в панели крутит колесо. */
        SCROLLING,
        /** Два пальца на карте: средняя кнопка и зум. */
        TWO_MAP,
        /** Два пальца в просмотре: прокрутка или зум колесом. */
        TWO_PREVIEW,
        /** Жест закончен (ПКМ или второй палец ушёл) — ждём, пока поднимут все. */
        DONE
    }

    private final Zones zones;
    private float density = 1f;
    private float uiScale = 1f;

    private State state = State.IDLE;
    private Zone zone = Zone.UI;
    private float downX, downY;
    private double downTime;
    private boolean movedFar;
    private float lastX, lastY;
    private float scrollRemainder;

    private float twoLastCx, twoLastCy;
    private float pinchBase;
    private float twoStartCx, twoStartCy, twoStartSpread;
    /** В просмотре: 0 — ещё не решили, 1 — листаем, 2 — зум. */
    private int previewMode;
    private float previewScrollAcc;

    private double lastTapTime = -10;
    private float lastTapX, lastTapY;

    /** Шаг зума карты — как щелчок колеса в MapView (×1,1). */
    private static final float MAP_ZOOM_STEP = 1.1f;
    /** В просмотре щелчок колеса меняет зум на четверть — разводить надо заметнее. */
    private static final float PREVIEW_ZOOM_STEP = 1.25f;
    /** Ui прокручивает на 48 точек за щелчок колеса. */
    private static final float WHEEL_POINTS = 48f;

    public TouchInput(Zones zones) { this.zones = zones; }

    /** Точек на dp — пороги жестов задаются в dp. */
    public void setDensity(float density) { this.density = Math.max(0.5f, density); }

    private float dp(float v) { return v * density; }

    // ------------------------------------------------------------------ вход (любой поток)

    public void touch(Touch t) {
        synchronized (incoming) { incoming.add(t); }
    }

    public void key(int key, int scancode, int action, int mods) {
        synchronized (incoming) { incoming.add(new Op(OP_KEY, 0, 0, key, action, mods)); }
    }

    public void character(int codepoint) {
        synchronized (incoming) { incoming.add(new Op(OP_CHAR, 0, 0, codepoint, 0, 0)); }
    }

    /** Настоящая мышь (планшет, DeX) — без жестов, как есть. */
    public void mouseMove(float x, float y) {
        synchronized (incoming) { incoming.add(new Op(OP_MOVE, x, y, 0, 0, 0)); }
    }

    public void mouseButton(int button, boolean pressed, int mods) {
        synchronized (incoming) {
            incoming.add(new Op(pressed ? OP_PRESS : OP_RELEASE, 0, 0, button, mods, 0));
        }
    }

    public void wheel(float dx, float dy) {
        synchronized (incoming) { incoming.add(new Op(OP_SCROLL, dx, dy, 0, 0, 0)); }
    }

    // ------------------------------------------------------------------ выход (поток отрисовки)

    /**
     * Разобрать накопленное и отдать окну события одного кадра.
     *
     * @param mods    залипшие модификаторы (кнопки «Shift»/«Ctrl» на панели)
     * @param uiScale точек экрана на логическую точку интерфейса
     * @return нужен ли ещё кадр: в очереди остались события или идёт жест
     */
    public boolean pump(Input in, int mods, float uiScale, double now) {
        this.uiScale = uiScale;
        List<Object> batch;
        synchronized (incoming) {
            batch = new ArrayList<>(incoming);
            incoming.clear();
        }
        for (Object o : batch) {
            if (o instanceof Touch t) handle(t, mods);
            else queueRaw((Op) o);
        }
        timers(now, mods);

        while (!ops.isEmpty()) {
            Op op = ops.poll();
            if (op.type == OP_BARRIER) break;
            apply(in, op);
        }
        return !ops.isEmpty() || state != State.IDLE;
    }

    public boolean idle() { return state == State.IDLE && ops.isEmpty(); }

    private void apply(Input in, Op op) {
        switch (op.type) {
            case OP_MOVE -> {
                in.mouseInside = true;
                in.onMouseMove(op.x, op.y);
            }
            case OP_PRESS -> {
                in.mouseInside = true;
                in.onMouseButton(op.a, GLFW_PRESS, op.b);
            }
            case OP_RELEASE -> in.onMouseButton(op.a, GLFW_RELEASE, op.b);
            case OP_SCROLL -> in.onScroll(op.x, op.y);
            case OP_LEAVE -> in.mouseInside = false;
            case OP_KEY -> in.onKey(op.a, 0, op.b, op.c);
            case OP_CHAR -> in.onChar(op.a);
            default -> { }
        }
    }

    private void queue(Op op) {
        ops.add(op);
        lastQueued = op.type;
    }

    private void barrier() {
        if (lastQueued != OP_BARRIER && !ops.isEmpty()) queue(new Op(OP_BARRIER, 0, 0, 0, 0, 0));
    }

    private void queueRaw(Op op) {
        if (op.type == OP_KEY || op.type == OP_CHAR) {
            queueKeyboard(op);
            return;
        }
        // нажатие и отпускание кнопки мыши — в разных кадрах, как у жестов
        if (op.type == OP_PRESS || op.type == OP_RELEASE) barrier();
        queue(op);
    }

    private void queueKeyboard(Op op) {
        // поле ввода за кадр разбирает сначала клавиши, потом текст: смешивать нельзя
        if ((op.type == OP_KEY && lastQueued == OP_CHAR) || (op.type == OP_CHAR && lastQueued == OP_KEY))
            barrier();
        queue(op);
    }

    private void move(float x, float y) { queue(new Op(OP_MOVE, x, y, 0, 0, 0)); }

    private void press(int button, int mods) { queue(new Op(OP_PRESS, 0, 0, button, mods, 0)); }

    private void release(int button, int mods) { queue(new Op(OP_RELEASE, 0, 0, button, mods, 0)); }

    private void leave() {
        barrier();
        queue(new Op(OP_LEAVE, 0, 0, 0, 0, 0));
    }

    // ------------------------------------------------------------------ жесты

    private void handle(Touch t, int mods) {
        switch (t.action) {
            case DOWN -> down(t, mods);
            case MOVE -> moved(t, mods);
            case POINTER_DOWN -> secondFinger(t, mods);
            case POINTER_UP -> fingerLifted(t, mods);
            case UP -> up(t, mods);
            default -> cancel(mods);
        }
    }

    private void down(Touch t, int mods) {
        float x = t.xs[0], y = t.ys[0];
        // второй тап рядом с первым ставим ровно в ту же точку: Input узнаёт
        // двойной щелчок по разбросу в пять пикселей, а палец так не попадает
        if (t.time - lastTapTime < 0.4 && Math.hypot(x - lastTapX, y - lastTapY) < dp(24)) {
            x = lastTapX;
            y = lastTapY;
        }
        downX = lastX = x;
        downY = lastY = y;
        downTime = t.time;
        movedFar = false;
        zone = zones.at(x, y);
        barrier();
        move(x, y);
        state = zone == Zone.UI ? State.PENDING_UI : State.PENDING_PRESS;
    }

    private void moved(Touch t, int mods) {
        if (t.xs.length >= 2 && (state == State.TWO_MAP || state == State.TWO_PREVIEW)) {
            twoFingers(t);
            return;
        }
        float x = t.xs[0], y = t.ys[0];
        boolean far = Math.hypot(x - downX, y - downY) > dp(8);
        switch (state) {
            case PENDING_UI -> {
                if (!far) return;
                movedFar = true;
                if (t.time - downTime >= 0.3) {
                    // палец подержали и повели — это тяга мышью
                    barrier();
                    press(GLFW_MOUSE_BUTTON_LEFT, mods);
                    barrier();
                    move(x, y);
                    state = State.DRAGGING;
                } else {
                    state = State.SCROLLING;
                    scrollRemainder = 0;
                    lastY = downY;
                    scrollBy(y - lastY);
                    lastY = y;
                }
            }
            case PENDING_PRESS -> {
                if (!far) return;
                movedFar = true;
                // тяга по карте: ЛКМ там, где коснулись, и дальше за пальцем
                barrier();
                press(GLFW_MOUSE_BUTTON_LEFT, mods);
                barrier();
                move(x, y);
                state = State.DRAGGING;
            }
            case DRAGGING -> {
                // дрожь пальца — не тяга: иначе квест под пальцем сдвигался бы от тапа
                if (!movedFar && !far) return;
                movedFar = true;
                move(x, y);
            }
            case SCROLLING -> {
                scrollBy(y - lastY);
                lastY = y;
            }
            default -> { }
        }
        lastX = x;
    }

    /** Тяга пальцем в панели — колесо; дробные щелчки Ui понимает. */
    private void scrollBy(float dyPixels) {
        float units = dyPixels / uiScale / WHEEL_POINTS + scrollRemainder;
        if (Math.abs(units) < 0.02f) {
            scrollRemainder = units;
            return;
        }
        scrollRemainder = 0;
        barrier();
        queue(new Op(OP_SCROLL, 0, units, 0, 0, 0));
        barrier();
    }

    private void secondFinger(Touch t, int mods) {
        if (t.xs.length < 2) return;
        if (zone == Zone.MAP || zone == Zone.PREVIEW) {
            if (state == State.DRAGGING) {
                // первый палец уже нажал ЛКМ — отпускаем, иначе квест уедет за пальцами
                barrier();
                release(GLFW_MOUSE_BUTTON_LEFT, mods);
            } else if (state != State.PENDING_PRESS) {
                return;
            }
            twoStartCx = twoLastCx = t.cx();
            twoStartCy = twoLastCy = t.cy();
            twoStartSpread = pinchBase = Math.max(1, t.spread());
            barrier();
            move(twoLastCx, twoLastCy);
            if (zone == Zone.MAP) {
                barrier();
                press(GLFW_MOUSE_BUTTON_MIDDLE, 0);
                state = State.TWO_MAP;
            } else {
                previewMode = 0;
                previewScrollAcc = 0;
                state = State.TWO_PREVIEW;
            }
        }
    }

    private void twoFingers(Touch t) {
        float cx = t.cx(), cy = t.cy(), spread = Math.max(1, t.spread());
        if (state == State.TWO_MAP) {
            move(cx, cy);
            // разведение — щелчками колеса: MapView меняет зум на ×1,1 за щелчок
            int clicks = 0;
            while (spread / pinchBase > MAP_ZOOM_STEP) {
                pinchBase *= MAP_ZOOM_STEP;
                clicks++;
            }
            while (pinchBase / spread > MAP_ZOOM_STEP) {
                pinchBase /= MAP_ZOOM_STEP;
                clicks--;
            }
            if (clicks != 0) {
                // пока держится средняя кнопка, карта только двигается и колесо
                // не слушает — на время щелчков кнопку отпускаем
                barrier();
                release(GLFW_MOUSE_BUTTON_MIDDLE, 0);
                for (int i = 0; i < Math.abs(clicks); i++) wheelClick(Math.signum(clicks));
                press(GLFW_MOUSE_BUTTON_MIDDLE, 0);
            }
        } else {
            move(cx, cy);
            if (previewMode == 0) {
                float travel = (float) Math.hypot(cx - twoStartCx, cy - twoStartCy);
                float pinch = Math.abs(spread - twoStartSpread);
                if (Math.max(travel, pinch) > dp(12)) previewMode = pinch > travel ? 2 : 1;
            }
            if (previewMode == 1) {
                // щелчок колеса на каждые 24 точки: панель квеста листается щелчками
                previewScrollAcc += (cy - twoLastCy) / uiScale;
                while (Math.abs(previewScrollAcc) >= 24) {
                    float s = Math.signum(previewScrollAcc);
                    previewScrollAcc -= s * 24;
                    wheelClick(s);
                }
            } else if (previewMode == 2) {
                // в просмотре щелчок колеса вверх приближает (zoomFactor мода — «во сколько
                // раз видно больше», 1 — ближе всего)
                while (spread / pinchBase > PREVIEW_ZOOM_STEP) {
                    pinchBase *= PREVIEW_ZOOM_STEP;
                    wheelClick(1);
                }
                while (pinchBase / spread > PREVIEW_ZOOM_STEP) {
                    pinchBase /= PREVIEW_ZOOM_STEP;
                    wheelClick(-1);
                }
            }
        }
        twoLastCx = cx;
        twoLastCy = cy;
    }

    /** Один щелчок колеса — отдельным кадром: карта и просмотр считают щелчки по кадрам. */
    private void wheelClick(float dir) {
        barrier();
        queue(new Op(OP_SCROLL, 0, dir, 0, 0, 0));
        barrier();
    }

    private void fingerLifted(Touch t, int mods) {
        if (state == State.TWO_MAP) {
            barrier();
            release(GLFW_MOUSE_BUTTON_MIDDLE, 0);
            state = State.DONE;
        } else if (state == State.TWO_PREVIEW) {
            state = State.DONE;
        }
    }

    private void up(Touch t, int mods) {
        float x = t.xs[0], y = t.ys[0];
        switch (state) {
            case PENDING_UI -> {
                // долгое нажатие в панели — ПКМ, иначе обычный тап
                boolean longPress = t.time - downTime >= 0.5;
                click(longPress ? GLFW_MOUSE_BUTTON_RIGHT : GLFW_MOUSE_BUTTON_LEFT, mods);
                if (!longPress) rememberTap(t.time);
                leave();
            }
            case PENDING_PRESS -> {
                click(GLFW_MOUSE_BUTTON_LEFT, mods);
                rememberTap(t.time);
                leave();
            }
            case DRAGGING -> {
                move(x, y);
                barrier();
                release(GLFW_MOUSE_BUTTON_LEFT, mods);
                if (!movedFar) rememberTap(t.time);
                leave();
            }
            case TWO_MAP -> {
                barrier();
                release(GLFW_MOUSE_BUTTON_MIDDLE, 0);
                leave();
            }
            default -> leave();
        }
        state = State.IDLE;
    }

    private void click(int button, int mods) {
        barrier();
        press(button, mods);
        barrier();
        release(button, mods);
    }

    private void rememberTap(double time) {
        lastTapTime = time;
        lastTapX = downX;
        lastTapY = downY;
    }

    private void cancel(int mods) {
        switch (state) {
            case DRAGGING -> {
                barrier();
                release(GLFW_MOUSE_BUTTON_LEFT, mods);
            }
            case TWO_MAP -> {
                barrier();
                release(GLFW_MOUSE_BUTTON_MIDDLE, 0);
            }
            default -> { }
        }
        leave();
        state = State.IDLE;
    }

    /**
     * Что зависит от времени, а не от движения: ЛКМ на карте жмётся, если
     * второй палец так и не пришёл; долгое нажатие на карте — сразу ПКМ.
     */
    private void timers(double now, int mods) {
        if (state == State.PENDING_PRESS && now - downTime >= 0.09) {
            barrier();
            press(GLFW_MOUSE_BUTTON_LEFT, mods);
            state = State.DRAGGING;
        }
        if (state == State.DRAGGING && zone == Zone.MAP && !movedFar && now - downTime >= 0.5) {
            // палец стоит на карте — меню, как по ПКМ
            barrier();
            release(GLFW_MOUSE_BUTTON_LEFT, mods);
            barrier();
            click(GLFW_MOUSE_BUTTON_RIGHT, mods);
            state = State.DONE;
        }
    }
}
