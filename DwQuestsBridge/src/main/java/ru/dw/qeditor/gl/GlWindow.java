package ru.dw.qeditor.gl;

/**
 * Окно, в котором живёт редактор, — всё, что интерфейсу нужно от него знать.
 *
 * <p>Раньше это было собственное окно GLFW. Теперь редактор — экран внутри
 * Minecraft, и окно принадлежит игре: кадры, ввод и буфер обмена даёт она
 * ({@code ru.dw.qbridge.editor.McWindow}). Интерфейс от этого не меняется —
 * те же размеры в пикселях кадра, тот же {@link Input} с кодами GLFW
 * ({@link Keys}), те же курсоры.
 */
public abstract class GlWindow {

    public static final int CURSOR_ARROW = 0;
    public static final int CURSOR_HAND = 1;
    public static final int CURSOR_TEXT = 2;
    public static final int CURSOR_RESIZE_H = 3;
    public static final int CURSOR_RESIZE_V = 4;
    public static final int CURSOR_MOVE = 5;

    private static final long START = System.nanoTime();

    protected final Input input = new Input();
    protected int wantCursor = CURSOR_ARROW;
    private Runnable onCloseRequest;

    /** Секунды с запуска — по ним мигает курсор и считается двойной щелчок. */
    public static double time() { return (System.nanoTime() - START) / 1e9; }

    /**
     * Разбудить окно, если оно ждёт событий. Игра рисует кадры и так, без
     * остановки, поэтому будить некого; вызовы оставлены, чтобы код, который
     * приносит картинку из другого потока, не знал, где он работает.
     */
    public static void postEmptyEvent() { }

    public Input input() { return input; }

    /** Ширина кадра в пикселях. */
    public abstract int width();

    /** Высота кадра в пикселях. */
    public abstract int height();

    /** Во сколько раз интерфейс крупнее 100 % (влияет на размер шрифта). */
    public abstract float contentScale();

    public abstract String clipboard();

    public abstract void setClipboard(String s);

    /** Заголовок: в игре это строка состояния, отдельного заголовка у экрана нет. */
    public void setTitle(String title) { }

    public boolean isFullscreen() { return false; }

    /** Полный экран самой игры — как по F11 в Minecraft. */
    public void toggleFullscreen() { }

    /** Закрыть редактор и вернуться в игру. */
    public abstract void close();

    /** Курсор ставится раз в кадр: смена курсора не бесплатна. */
    public void cursor(int cursor) { wantCursor = cursor; }

    public void setOnCloseRequest(Runnable r) { this.onCloseRequest = r; }

    /** Просьба закрыть окно — сначала спросим про несохранённое. */
    public void requestClose() {
        if (onCloseRequest != null) onCloseRequest.run();
        else close();
    }
}
