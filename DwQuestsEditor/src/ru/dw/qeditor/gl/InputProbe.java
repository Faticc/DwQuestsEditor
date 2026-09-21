package ru.dw.qeditor.gl;

import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

/**
 * Подмена ввода для проверок механики: те же события, что шлёт GLFW, но руками.
 *
 * <p>Сами приёмники в {@link Input} доступны только своему пакету — их зовут
 * обратные вызовы окна, и снаружи это не должно выглядеть частью
 * интерфейса. Проверкам же нужен способ «нажать и потянуть», поэтому вот он,
 * отдельно и с честным названием.
 */
public final class InputProbe {

    private InputProbe() { }

    public static void move(Input in, float x, float y) { in.onMouseMove(x, y); }

    public static void button(Input in, int button, boolean down) {
        in.onMouseButton(button, down ? GLFW_PRESS : GLFW_RELEASE, 0);
    }

    public static void scroll(Input in, float dy) { in.onScroll(0, dy); }

    /** Конец кадра: события прожиты, нажатия-отпускания сброшены. */
    public static void endFrame(Input in) { in.endFrame(); }
}
