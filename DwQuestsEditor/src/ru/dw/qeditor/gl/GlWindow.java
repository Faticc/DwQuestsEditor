package ru.dw.qeditor.gl;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Окно GLFW с контекстом OpenGL 3.3 core и весь ввод к нему.
 *
 * <p>Заменяет собой {@code JFrame}: тут же полноэкранный режим, курсоры,
 * буфер обмена и перетаскивание файлов — всё, за чем раньше ходили в Swing.
 */
public final class GlWindow {

    public static final int CURSOR_ARROW = 0;
    public static final int CURSOR_HAND = 1;
    public static final int CURSOR_TEXT = 2;
    public static final int CURSOR_RESIZE_H = 3;
    public static final int CURSOR_RESIZE_V = 4;
    public static final int CURSOR_MOVE = 5;

    private static boolean glfwReady;

    private final long handle;
    private final Input input = new Input();
    private final long[] cursors = new long[6];

    private int width;
    private int height;
    private float contentScale = 1f;
    /** Кадровый буфер на пиксель окна: на части систем они не совпадают. */
    private float pixelRatio = 1f;
    private boolean fullscreen;
    private int[] windowed = new int[4];
    private int wantCursor = CURSOR_ARROW;
    private int currentCursor = -1;
    private Runnable onCloseRequest;

    public GlWindow(String title, int width, int height, boolean visible) {
        initGlfw();

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_FALSE);
        glfwWindowHint(GLFW_SAMPLES, 0);

        handle = glfwCreateWindow(width, height, title, NULL, NULL);
        if (handle == NULL) throw new IllegalStateException("Не удалось создать окно GLFW");

        this.width = width;
        this.height = height;

        glfwMakeContextCurrent(handle);
        GL.createCapabilities();
        glfwSwapInterval(1);

        installCallbacks();
        updateSizes();

        if (visible) {
            centerOnScreen();
            glfwShowWindow(handle);
        }
    }

    private static void initGlfw() {
        if (glfwReady) return;
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("Не удалось запустить GLFW");
        glfwReady = true;
    }

    private void installCallbacks() {
        glfwSetCursorPosCallback(handle, (w, x, y) ->
                input.onMouseMove((float) x * pixelRatio, (float) y * pixelRatio));
        glfwSetMouseButtonCallback(handle, (w, b, a, m) -> input.onMouseButton(b, a, m));
        glfwSetScrollCallback(handle, (w, x, y) -> input.onScroll((float) x, (float) y));
        glfwSetKeyCallback(handle, (w, k, s, a, m) -> input.onKey(k, s, a, m));
        glfwSetCharCallback(handle, (w, c) -> input.onChar(c));
        glfwSetCursorEnterCallback(handle, (w, entered) -> input.mouseInside = entered);
        glfwSetDropCallback(handle, (w, count, names) -> {
            List<String> files = new ArrayList<>();
            for (int i = 0; i < count; i++) files.add(org.lwjgl.system.MemoryUtil.memUTF8(
                    org.lwjgl.system.MemoryUtil.memGetAddress(names + i * Long.BYTES)));
            input.onDrop(files);
        });
        glfwSetFramebufferSizeCallback(handle, (w, fw, fh) -> updateSizes());
        glfwSetWindowCloseCallback(handle, w -> {
            // закрытие перехватывается: сначала спросим про несохранённое
            glfwSetWindowShouldClose(handle, false);
            if (onCloseRequest != null) onCloseRequest.run();
        });

        cursors[CURSOR_ARROW] = glfwCreateStandardCursor(GLFW_ARROW_CURSOR);
        cursors[CURSOR_HAND] = glfwCreateStandardCursor(GLFW_HAND_CURSOR);
        cursors[CURSOR_TEXT] = glfwCreateStandardCursor(GLFW_IBEAM_CURSOR);
        cursors[CURSOR_RESIZE_H] = glfwCreateStandardCursor(GLFW_HRESIZE_CURSOR);
        cursors[CURSOR_RESIZE_V] = glfwCreateStandardCursor(GLFW_VRESIZE_CURSOR);
        cursors[CURSOR_MOVE] = glfwCreateStandardCursor(GLFW_CROSSHAIR_CURSOR);
    }

    private void updateSizes() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            glfwGetFramebufferSize(handle, w, h);
            width = Math.max(1, w.get(0));
            height = Math.max(1, h.get(0));

            glfwGetWindowSize(handle, w, h);
            pixelRatio = w.get(0) > 0 ? (float) width / w.get(0) : 1f;

            java.nio.FloatBuffer sx = stack.mallocFloat(1);
            java.nio.FloatBuffer sy = stack.mallocFloat(1);
            glfwGetWindowContentScale(handle, sx, sy);
            contentScale = Math.max(1f, sx.get(0));
        }
    }

    private void centerOnScreen() {
        GLFWVidMode mode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        if (mode == null) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            glfwGetWindowSize(handle, w, h);
            glfwSetWindowPos(handle, (mode.width() - w.get(0)) / 2, (mode.height() - h.get(0)) / 2);
        }
    }

    // ------------------------------------------------------------------ свойства

    public long handle() { return handle; }

    public Input input() { return input; }

    public int width() { return width; }

    public int height() { return height; }

    /** Во сколько раз системный масштаб экрана крупнее 100 % (влияет на размер шрифта). */
    public float contentScale() { return contentScale; }

    public void setTitle(String title) { glfwSetWindowTitle(handle, title); }

    public void setOnCloseRequest(Runnable r) { this.onCloseRequest = r; }

    public boolean shouldClose() { return glfwWindowShouldClose(handle); }

    public void close() { glfwSetWindowShouldClose(handle, true); }

    public boolean focused() { return glfwGetWindowAttrib(handle, GLFW_FOCUSED) == GLFW_TRUE; }

    public boolean minimized() { return glfwGetWindowAttrib(handle, GLFW_ICONIFIED) == GLFW_TRUE; }

    public String clipboard() {
        String s = glfwGetClipboardString(handle);
        return s == null ? "" : s;
    }

    /**
     * Положить текст в буфер обмена.
     *
     * <p>Строка кодируется в свою память, а не в стек LWJGL: у
     * {@code glfwSetClipboardString(long, CharSequence)} она уходит в
     * {@code MemoryStack} на 64 КБ, и вкладка целиком («копировать для
     * /dwq tab paste») роняла редактор с {@code Out of stack space}.
     */
    public void setClipboard(String s) {
        java.nio.ByteBuffer utf8 = org.lwjgl.system.MemoryUtil.memUTF8(s == null ? "" : s, true);
        try {
            org.lwjgl.glfw.GLFW.nglfwSetClipboardString(handle, org.lwjgl.system.MemoryUtil.memAddress(utf8));
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(utf8);
        }
    }

    /** Курсор ставится раз в кадр: смена курсора в GLFW не бесплатна. */
    public void cursor(int cursor) { wantCursor = cursor; }

    public boolean isFullscreen() { return fullscreen; }

    public void toggleFullscreen() {
        if (fullscreen) {
            glfwSetWindowMonitor(handle, NULL, windowed[0], windowed[1], windowed[2], windowed[3], 0);
            fullscreen = false;
        } else {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer x = stack.mallocInt(1);
                IntBuffer y = stack.mallocInt(1);
                IntBuffer w = stack.mallocInt(1);
                IntBuffer h = stack.mallocInt(1);
                glfwGetWindowPos(handle, x, y);
                glfwGetWindowSize(handle, w, h);
                windowed = new int[]{x.get(0), y.get(0), w.get(0), h.get(0)};
            }
            long monitor = glfwGetPrimaryMonitor();
            GLFWVidMode mode = glfwGetVideoMode(monitor);
            if (mode == null) return;
            glfwSetWindowMonitor(handle, monitor, 0, 0, mode.width(), mode.height(), mode.refreshRate());
            fullscreen = true;
        }
        updateSizes();
    }

    // ------------------------------------------------------------------ кадр

    /** Начало кадра: события уже собраны, буфер очищен. */
    public void beginFrame(int clearColor) {
        glViewport(0, 0, width, height);
        glDisable(GL_SCISSOR_TEST);
        glClearColor((clearColor >> 16 & 255) / 255f, (clearColor >> 8 & 255) / 255f,
                (clearColor & 255) / 255f, 1f);
        glClear(GL_COLOR_BUFFER_BIT);
    }

    public void endFrame() {
        if (wantCursor != currentCursor) {
            glfwSetCursor(handle, cursors[wantCursor]);
            currentCursor = wantCursor;
        }
        wantCursor = CURSOR_ARROW;
        glfwSwapBuffers(handle);
        input.endFrame();
    }

    public void pollEvents() { glfwPollEvents(); }

    /**
     * Ждать события до timeout секунд. Редактор — не игра: когда ничего не
     * происходит, кадры рисовать незачем, и процессор с видеокартой отдыхают.
     */
    public void waitEvents(double timeoutSeconds) { glfwWaitEventsTimeout(timeoutSeconds); }

    public static void postEmptyEvent() { glfwPostEmptyEvent(); }

    public void dispose() {
        for (long c : cursors) if (c != NULL) glfwDestroyCursor(c);
        glfwDestroyWindow(handle);
    }

    public static void terminate() {
        glfwTerminate();
        glfwReady = false;
    }
}
