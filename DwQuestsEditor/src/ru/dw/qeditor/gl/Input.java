package ru.dw.qeditor.gl;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Ввод за кадр. Интерфейс рисуется в режиме «немедленного» вывода: виджеты
 * не подписываются на события, а каждый кадр смотрят, что произошло с момента
 * прошлого, — поэтому события копятся здесь и в конце кадра сбрасываются.
 */
public final class Input {

    /** Нажатие или отпускание клавиши с модификаторами на момент события. */
    public record KeyEvent(int key, int scancode, int action, int mods) {
        public boolean ctrl() { return (mods & GLFW_MOD_CONTROL) != 0; }

        public boolean shift() { return (mods & GLFW_MOD_SHIFT) != 0; }

        public boolean alt() { return (mods & GLFW_MOD_ALT) != 0; }
    }

    public float mouseX;
    public float mouseY;
    /** Насколько курсор сместился за кадр — нужно перетаскиванию. */
    public float mouseDX;
    public float mouseDY;
    public float scrollX;
    public float scrollY;
    public boolean mouseInside = true;

    private final boolean[] down = new boolean[8];
    private final boolean[] pressed = new boolean[8];
    private final boolean[] released = new boolean[8];
    /** Где нажали кнопку — по этому считается перетаскивание и двойной щелчок. */
    private final float[] pressX = new float[8];
    private final float[] pressY = new float[8];

    private final List<KeyEvent> keys = new ArrayList<>();
    private final StringBuilder text = new StringBuilder();
    private final List<String> droppedFiles = new ArrayList<>();

    private int mods;
    private double lastClickTime;
    private float lastClickX, lastClickY;
    private boolean doubleClick;

    // ------------------------------------------------------------------ опрос

    public boolean down(int button) { return button < down.length && down[button]; }

    public boolean pressed(int button) { return button < pressed.length && pressed[button]; }

    public boolean released(int button) { return button < released.length && released[button]; }

    public float pressX(int button) { return pressX[button]; }

    public float pressY(int button) { return pressY[button]; }

    public boolean leftPressed() { return pressed(GLFW_MOUSE_BUTTON_LEFT); }

    public boolean leftDown() { return down(GLFW_MOUSE_BUTTON_LEFT); }

    public boolean leftReleased() { return released(GLFW_MOUSE_BUTTON_LEFT); }

    public boolean rightPressed() { return pressed(GLFW_MOUSE_BUTTON_RIGHT); }

    public boolean middleDown() { return down(GLFW_MOUSE_BUTTON_MIDDLE); }

    public boolean doubleClick() { return doubleClick; }

    public boolean ctrl() { return (mods & GLFW_MOD_CONTROL) != 0; }

    public boolean shift() { return (mods & GLFW_MOD_SHIFT) != 0; }

    public boolean alt() { return (mods & GLFW_MOD_ALT) != 0; }

    public List<KeyEvent> keys() { return keys; }

    /** Символы, набранные за кадр (уже с учётом раскладки и Shift). */
    public String text() { return text.toString(); }

    public List<String> droppedFiles() { return droppedFiles; }

    /** Нажата ли клавиша в этом кадре (с автоповтором). */
    public boolean keyPressed(int key) {
        for (KeyEvent e : keys)
            if (e.key == key && (e.action == GLFW_PRESS || e.action == GLFW_REPEAT)) return true;
        return false;
    }

    public boolean keyPressed(int key, int modMask) {
        for (KeyEvent e : keys)
            if (e.key == key && (e.action == GLFW_PRESS || e.action == GLFW_REPEAT)
                    && (e.mods & modMask) == modMask) return true;
        return false;
    }

    /** Убрать событие, чтобы его не увидели остальные виджеты кадра. */
    public void consumeKey(int key) {
        keys.removeIf(e -> e.key == key);
    }

    public void consumeText() { text.setLength(0); }

    public void consumeScroll() { scrollX = scrollY = 0; }

    public void consumeMouse() {
        for (int i = 0; i < pressed.length; i++) pressed[i] = released[i] = false;
        doubleClick = false;
    }

    // ------------------------------------------------------------------ события

    void onMouseMove(float x, float y) {
        mouseDX += x - mouseX;
        mouseDY += y - mouseY;
        mouseX = x;
        mouseY = y;
    }

    void onMouseButton(int button, int action, int mods) {
        if (button < 0 || button >= down.length) return;
        this.mods = mods;
        if (action == GLFW_PRESS) {
            down[button] = true;
            pressed[button] = true;
            pressX[button] = mouseX;
            pressY[button] = mouseY;
            if (button == GLFW_MOUSE_BUTTON_LEFT) {
                double now = glfwGetTime();
                if (now - lastClickTime < 0.4
                        && Math.abs(mouseX - lastClickX) < 5 && Math.abs(mouseY - lastClickY) < 5) {
                    doubleClick = true;
                    lastClickTime = 0;
                } else {
                    lastClickTime = now;
                }
                lastClickX = mouseX;
                lastClickY = mouseY;
            }
        } else if (action == GLFW_RELEASE) {
            down[button] = false;
            released[button] = true;
        }
    }

    void onScroll(float dx, float dy) {
        scrollX += dx;
        scrollY += dy;
    }

    void onKey(int key, int scancode, int action, int mods) {
        this.mods = mods;
        keys.add(new KeyEvent(key, scancode, action, mods));
    }

    void onChar(int codepoint) {
        // управляющие символы приходят отдельными событиями клавиш
        if (codepoint >= 32 && codepoint != 127) text.appendCodePoint(codepoint);
    }

    void onDrop(List<String> files) { droppedFiles.addAll(files); }

    /** Вызывается в конце кадра: события кадра прожиты. */
    void endFrame() {
        for (int i = 0; i < pressed.length; i++) pressed[i] = released[i] = false;
        keys.clear();
        text.setLength(0);
        droppedFiles.clear();
        scrollX = scrollY = 0;
        mouseDX = mouseDY = 0;
        doubleClick = false;
    }
}
