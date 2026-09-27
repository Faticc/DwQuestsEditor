package ru.dw.qbridge.editor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import ru.dw.qeditor.assets.Config;
import ru.dw.qeditor.gl.GlWindow;
import ru.dw.qeditor.gl.Keys;

/**
 * Окно редактора — это окно самой игры.
 *
 * <p>Размер — кадр Minecraft, буфер обмена — тот же, что у чата
 * ({@link GuiScreen#setClipboardString}, проверен на заливке вкладок), ввод
 * приходит событиями LWJGL 2 от экрана ({@link EditorScreen}) и переводится
 * в коды GLFW, с которыми работает интерфейс.
 */
final class McWindow extends GlWindow {

    private final Minecraft mc = Minecraft.getMinecraft();

    @Override public int width() { return Math.max(1, mc.displayWidth); }

    @Override public int height() { return Math.max(1, mc.displayHeight); }

    /**
     * Масштаб интерфейса: из настроек, иначе по высоте рабочего стола. Узнать
     * масштаб Windows отсюда нельзя (AWT в игре без экрана), а высота экрана
     * даёт то же самое для обычных сочетаний: 1080 — 100 %, 1440 — 125 %, 4K — 200 %.
     */
    @Override
    public float contentScale() {
        int percent = Config.get().uiScale;
        if (percent >= 50) return percent / 100f;
        int h = desktopHeight();
        if (h <= 1200) return 1f;
        if (h <= 1600) return 1.25f;
        if (h <= 2200) return 1.5f;
        return 2f;
    }

    private int desktopHeight = -1;

    private int desktopHeight() {
        if (desktopHeight < 0) {
            try {
                DisplayMode mode = Display.getDesktopDisplayMode();
                desktopHeight = mode == null ? 1080 : mode.getHeight();
            } catch (Throwable t) {
                desktopHeight = 1080;
            }
        }
        return desktopHeight;
    }

    @Override
    public String clipboard() {
        String s = GuiScreen.getClipboardString();
        return s == null ? "" : s;
    }

    @Override public void setClipboard(String s) { GuiScreen.setClipboardString(s == null ? "" : s); }

    @Override public boolean isFullscreen() { return mc.isFullScreen(); }

    @Override public void toggleFullscreen() { mc.toggleFullscreen(); }

    @Override
    public void close() {
        if (mc.currentScreen instanceof EditorScreen) mc.displayGuiScreen(null);
    }

    // ------------------------------------------------------------------ ввод

    /** Координаты мыши LWJGL 2 — в пикселях кадра, но снизу вверх. */
    void mouseEvent() {
        float x = Mouse.getEventX();
        float y = height() - Mouse.getEventY() - 1;
        input.onMouseMove(x, y);
        int button = Mouse.getEventButton();
        if (button >= 0) {
            input.onMouseButton(button, Mouse.getEventButtonState() ? Keys.GLFW_PRESS : Keys.GLFW_RELEASE,
                    KeyMap.mods());
        }
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            // LWJGL 2 считает щелчок колеса за 120, GLFW — за единицу
            float steps = Math.abs(wheel) >= 120 ? wheel / 120f : Math.signum(wheel);
            input.onScroll(0, steps);
        }
    }

    void keyEvent() {
        int key = Keyboard.getEventKey();
        char c = Keyboard.getEventCharacter();
        boolean down = Keyboard.getEventKeyState();
        int glfw = KeyMap.toGlfw(key);
        if (glfw != Keys.GLFW_KEY_UNKNOWN) {
            int action = !down ? Keys.GLFW_RELEASE : Keyboard.isRepeatEvent() ? Keys.GLFW_REPEAT : Keys.GLFW_PRESS;
            input.onKey(glfw, key, action, KeyMap.mods());
        }
        // Набранный текст приходит отдельно от клавиш (у lwjgl3ify — событием
        // с кодом 0), с учётом раскладки и Shift; Ctrl+буква текстом не считается.
        if (down && c >= 32 && c != 127 && (KeyMap.mods() & Keys.GLFW_MOD_CONTROL) == 0) input.onChar(c);
    }

    /** Мышь могла уйти между событиями (например, окно потеряло фокус) — сверяемся раз в кадр. */
    void syncMouse() {
        float x = Mouse.getX();
        float y = height() - Mouse.getY() - 1;
        if (x != input.mouseX || y != input.mouseY) input.onMouseMove(x, y);
    }

    void endFrame() {
        wantCursor = CURSOR_ARROW;
        input.endFrame();
    }
}
