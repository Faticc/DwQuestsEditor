package ru.dw.qbridge.editor;

import org.lwjgl.input.Keyboard;
import ru.dw.qeditor.gl.Keys;

/**
 * Коды клавиш Minecraft (LWJGL 2, {@link Keyboard}) → коды GLFW, с которыми
 * сравнивает клавиши интерфейс редактора ({@link Keys}).
 *
 * <p>Коды LWJGL 2 привязаны к месту клавиши, а не к букве раскладки, поэтому
 * Ctrl+C работает и на русской раскладке — как и было в окне GLFW.
 */
final class KeyMap {

    private static final int[] TABLE = new int[256];

    static {
        java.util.Arrays.fill(TABLE, Keys.GLFW_KEY_UNKNOWN);
        // буквы: в LWJGL 2 они идут рядами клавиатуры, а в GLFW — по алфавиту
        String letters = "QWERTYUIOPASDFGHJKLZXCVBNM";
        int[] codes = {
                Keyboard.KEY_Q, Keyboard.KEY_W, Keyboard.KEY_E, Keyboard.KEY_R, Keyboard.KEY_T,
                Keyboard.KEY_Y, Keyboard.KEY_U, Keyboard.KEY_I, Keyboard.KEY_O, Keyboard.KEY_P,
                Keyboard.KEY_A, Keyboard.KEY_S, Keyboard.KEY_D, Keyboard.KEY_F, Keyboard.KEY_G,
                Keyboard.KEY_H, Keyboard.KEY_J, Keyboard.KEY_K, Keyboard.KEY_L, Keyboard.KEY_Z,
                Keyboard.KEY_X, Keyboard.KEY_C, Keyboard.KEY_V, Keyboard.KEY_B, Keyboard.KEY_N,
                Keyboard.KEY_M};
        for (int i = 0; i < codes.length; i++) TABLE[codes[i]] = letters.charAt(i);
        // цифры верхнего ряда: KEY_1..KEY_9 подряд, KEY_0 после них
        for (int i = 1; i <= 9; i++) TABLE[Keyboard.KEY_1 + i - 1] = Keys.GLFW_KEY_0 + i;
        TABLE[Keyboard.KEY_0] = Keys.GLFW_KEY_0;
        // F1..F10 подряд, F11 и F12 — отдельно
        for (int i = 0; i < 10; i++) TABLE[Keyboard.KEY_F1 + i] = Keys.GLFW_KEY_F1 + i;
        TABLE[Keyboard.KEY_F11] = Keys.GLFW_KEY_F11;
        TABLE[Keyboard.KEY_F12] = Keys.GLFW_KEY_F12;

        TABLE[Keyboard.KEY_ESCAPE] = Keys.GLFW_KEY_ESCAPE;
        TABLE[Keyboard.KEY_RETURN] = Keys.GLFW_KEY_ENTER;
        TABLE[Keyboard.KEY_NUMPADENTER] = Keys.GLFW_KEY_KP_ENTER;
        TABLE[Keyboard.KEY_TAB] = Keys.GLFW_KEY_TAB;
        TABLE[Keyboard.KEY_BACK] = Keys.GLFW_KEY_BACKSPACE;
        TABLE[Keyboard.KEY_INSERT] = Keys.GLFW_KEY_INSERT;
        TABLE[Keyboard.KEY_DELETE] = Keys.GLFW_KEY_DELETE;
        TABLE[Keyboard.KEY_RIGHT] = Keys.GLFW_KEY_RIGHT;
        TABLE[Keyboard.KEY_LEFT] = Keys.GLFW_KEY_LEFT;
        TABLE[Keyboard.KEY_DOWN] = Keys.GLFW_KEY_DOWN;
        TABLE[Keyboard.KEY_UP] = Keys.GLFW_KEY_UP;
        TABLE[Keyboard.KEY_PRIOR] = Keys.GLFW_KEY_PAGE_UP;
        TABLE[Keyboard.KEY_NEXT] = Keys.GLFW_KEY_PAGE_DOWN;
        TABLE[Keyboard.KEY_HOME] = Keys.GLFW_KEY_HOME;
        TABLE[Keyboard.KEY_END] = Keys.GLFW_KEY_END;
        TABLE[Keyboard.KEY_SPACE] = Keys.GLFW_KEY_SPACE;
        TABLE[Keyboard.KEY_APOSTROPHE] = Keys.GLFW_KEY_APOSTROPHE;
        TABLE[Keyboard.KEY_COMMA] = Keys.GLFW_KEY_COMMA;
        TABLE[Keyboard.KEY_MINUS] = Keys.GLFW_KEY_MINUS;
        TABLE[Keyboard.KEY_PERIOD] = Keys.GLFW_KEY_PERIOD;
        TABLE[Keyboard.KEY_SLASH] = Keys.GLFW_KEY_SLASH;
        TABLE[Keyboard.KEY_SEMICOLON] = Keys.GLFW_KEY_SEMICOLON;
        TABLE[Keyboard.KEY_EQUALS] = Keys.GLFW_KEY_EQUAL;
        TABLE[Keyboard.KEY_LBRACKET] = Keys.GLFW_KEY_LEFT_BRACKET;
        TABLE[Keyboard.KEY_BACKSLASH] = Keys.GLFW_KEY_BACKSLASH;
        TABLE[Keyboard.KEY_RBRACKET] = Keys.GLFW_KEY_RIGHT_BRACKET;
        TABLE[Keyboard.KEY_GRAVE] = Keys.GLFW_KEY_GRAVE_ACCENT;
        TABLE[Keyboard.KEY_NUMPAD0] = Keys.GLFW_KEY_KP_0;
        int[] pad = {Keyboard.KEY_NUMPAD1, Keyboard.KEY_NUMPAD2, Keyboard.KEY_NUMPAD3, Keyboard.KEY_NUMPAD4,
                Keyboard.KEY_NUMPAD5, Keyboard.KEY_NUMPAD6, Keyboard.KEY_NUMPAD7, Keyboard.KEY_NUMPAD8,
                Keyboard.KEY_NUMPAD9};
        for (int i = 0; i < pad.length; i++) TABLE[pad[i]] = Keys.GLFW_KEY_KP_0 + 1 + i;
        TABLE[Keyboard.KEY_DECIMAL] = Keys.GLFW_KEY_KP_DECIMAL;
        TABLE[Keyboard.KEY_DIVIDE] = Keys.GLFW_KEY_KP_DIVIDE;
        TABLE[Keyboard.KEY_MULTIPLY] = Keys.GLFW_KEY_KP_MULTIPLY;
        TABLE[Keyboard.KEY_SUBTRACT] = Keys.GLFW_KEY_KP_SUBTRACT;
        TABLE[Keyboard.KEY_ADD] = Keys.GLFW_KEY_KP_ADD;
        TABLE[Keyboard.KEY_LSHIFT] = Keys.GLFW_KEY_LEFT_SHIFT;
        TABLE[Keyboard.KEY_RSHIFT] = Keys.GLFW_KEY_RIGHT_SHIFT;
        TABLE[Keyboard.KEY_LCONTROL] = Keys.GLFW_KEY_LEFT_CONTROL;
        TABLE[Keyboard.KEY_RCONTROL] = Keys.GLFW_KEY_RIGHT_CONTROL;
        TABLE[Keyboard.KEY_LMENU] = Keys.GLFW_KEY_LEFT_ALT;
        TABLE[Keyboard.KEY_RMENU] = Keys.GLFW_KEY_RIGHT_ALT;
    }

    private KeyMap() { }

    static int toGlfw(int lwjglKey) {
        return lwjglKey >= 0 && lwjglKey < TABLE.length ? TABLE[lwjglKey] : Keys.GLFW_KEY_UNKNOWN;
    }

    /** Модификаторы в битах GLFW — по тому, что зажато сейчас. */
    static int mods() {
        int m = 0;
        if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT)) m |= Keys.GLFW_MOD_SHIFT;
        if (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL)) m |= Keys.GLFW_MOD_CONTROL;
        if (Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU)) m |= Keys.GLFW_MOD_ALT;
        return m;
    }
}
