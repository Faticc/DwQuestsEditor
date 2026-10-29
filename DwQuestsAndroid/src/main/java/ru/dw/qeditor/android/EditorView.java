package ru.dw.qeditor.android;

import android.annotation.SuppressLint;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;
import android.text.InputType;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import ru.dw.qeditor.app.App;
import ru.dw.qeditor.gl.Batch;
import ru.dw.qeditor.gl.Textures;
import ru.dw.qeditor.gl.UiFont;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import static ru.dw.qeditor.gl.Keys.*;

/**
 * Поверхность GLES 3.0, на которой живёт редактор, — то, чем на компьютере
 * было окно GLFW.
 *
 * <p>Кадры рисуются по требованию: касание, клавиша, ответ системы. Без
 * остановки — только пока идёт анимация (просмотр как в игре) или жест;
 * в покое — раз в полсекунды, чтобы мигал курсор в поле и шли фоновые
 * проверки (файл изменён снаружи, автокопия).
 *
 * <p>Касания и клавиши отсюда уходят в {@link TouchInput}, а разбираются в
 * потоке отрисовки — там же, где их читает интерфейс.
 */
@SuppressLint("ViewConstructor")
final class EditorView extends GLSurfaceView implements GLSurfaceView.Renderer {

    /** GL-объекты из прошлого контекста (кэши текстур, шрифтов) уже недействительны. */
    private static boolean contextSeen;

    private final MainActivity activity;
    private final AndroidWindow window;
    private final TouchInput touch;
    /** Окно редактора живёт в потоке отрисовки; до первого кадра его нет. */
    private App app;
    private Batch batch;
    private final List<Consumer<App>> pending = new ArrayList<>();
    private boolean keyboardShown;
    private boolean tickScheduled;
    /** Сколько символов составного ввода клавиатуры уже отдано полю. */
    private int composing;

    private final Runnable tick = () -> {
        tickScheduled = false;
        requestRender();
    };

    EditorView(MainActivity activity) {
        super(activity);
        this.activity = activity;
        this.window = new AndroidWindow(activity, () -> activity.runOnUiThread(activity::quitEditor));
        this.touch = new TouchInput((x, y) -> app == null ? TouchInput.Zone.UI : app.at(x, y));
        touch.setDensity(window.density());

        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        setPreserveEGLContextOnPause(true);
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    /** Сделать что-то с окном редактора в его потоке (когда оно появится). */
    void post(Consumer<App> action) {
        queueEvent(() -> {
            if (app != null) action.accept(app);
            else pending.add(action);
        });
        requestRender();
    }

    /**
     * Сделать что-то с окном редактора сейчас, если оно уже есть, и в любом
     * случае позвать {@code then} — для того, кто ждёт ответа (уход в фон).
     */
    void postNow(Consumer<App> action, Runnable then) {
        queueEvent(() -> {
            try {
                if (app != null) action.accept(app);
            } finally {
                then.run();
            }
        });
        requestRender();
    }

    TouchInput touch() { return touch; }

    // ------------------------------------------------------------------ отрисовка

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        if (contextSeen) {
            // Контекст пересоздан (система его отняла или Activity новая):
            // текстуры, атласы и буферы прошлого контекста мертвы. Удаление
            // чужих номеров до того, как что-то создано, безвредно.
            if (app != null) app.onPause();
            Textures.clear();
            UiFont.clear();
            app = null;
        }
        contextSeen = true;
        batch = new Batch();
        app = new App(window, batch);
        for (Consumer<App> a : pending) a.accept(app);
        pending.clear();
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        window.resize(w, h);
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        if (app == null) return;
        // часы те же, что у событий касания (uptimeMillis): по ним считаются долгие нажатия
        double now = SystemClock.uptimeMillis() / 1000.0;
        boolean more = touch.pump(window.input(), app.stickyMods(), window.contentScale(), now);
        app.drawFrame();
        window.input().endFrame();

        boolean wantKeyboard = app.wantsKeyboard();
        if (wantKeyboard != keyboardShown) {
            keyboardShown = wantKeyboard;
            activity.runOnUiThread(() -> activity.showKeyboard(wantKeyboard));
        }

        if (more || app.animating()) {
            requestRender();
        } else if (!tickScheduled) {
            tickScheduled = true;
            postDelayed(tick, 500);
        }
    }

    // ------------------------------------------------------------------ касания и мышь

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.isFromSource(InputDevice.SOURCE_MOUSE)) return mouse(e);
        int action;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> action = TouchInput.DOWN;
            case MotionEvent.ACTION_MOVE -> action = TouchInput.MOVE;
            case MotionEvent.ACTION_UP -> action = TouchInput.UP;
            case MotionEvent.ACTION_POINTER_DOWN -> action = TouchInput.POINTER_DOWN;
            case MotionEvent.ACTION_POINTER_UP -> action = TouchInput.POINTER_UP;
            case MotionEvent.ACTION_CANCEL -> action = TouchInput.CANCEL;
            default -> {
                return true;
            }
        }
        // уходящий палец в снимок не входит — остальные жест продолжают
        int skip = action == TouchInput.POINTER_UP ? e.getActionIndex() : -1;
        int n = Math.min(2, e.getPointerCount() - (skip >= 0 ? 1 : 0));
        float[] xs = new float[Math.max(1, n)], ys = new float[Math.max(1, n)];
        int k = 0;
        for (int i = 0; i < e.getPointerCount() && k < xs.length; i++) {
            if (i == skip) continue;
            xs[k] = e.getX(i);
            ys[k] = e.getY(i);
            k++;
        }
        touch.touch(new TouchInput.Touch(action, e.getEventTime() / 1000.0, xs, ys));
        requestRender();
        if (action == TouchInput.DOWN) requestFocus();
        return true;
    }

    private int mouseButtons;

    private boolean mouse(MotionEvent e) {
        touch.mouseMove(e.getX(), e.getY());
        int buttons = e.getButtonState();
        int mods = mods(e.getMetaState());
        int[][] map = {
                {MotionEvent.BUTTON_PRIMARY, GLFW_MOUSE_BUTTON_LEFT},
                {MotionEvent.BUTTON_SECONDARY, GLFW_MOUSE_BUTTON_RIGHT},
                {MotionEvent.BUTTON_TERTIARY, GLFW_MOUSE_BUTTON_MIDDLE}};
        for (int[] m : map) {
            boolean was = (mouseButtons & m[0]) != 0, now = (buttons & m[0]) != 0;
            if (was != now) touch.mouseButton(m[1], now, mods);
        }
        // касание стилусом-мышью без кнопок: DOWN/UP приходят без состояния кнопок
        if (buttons == 0 && e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            touch.mouseButton(GLFW_MOUSE_BUTTON_LEFT, true, mods);
            buttons = MotionEvent.BUTTON_PRIMARY;
        }
        if (e.getActionMasked() == MotionEvent.ACTION_UP && (mouseButtons & MotionEvent.BUTTON_PRIMARY) != 0
                && (buttons & MotionEvent.BUTTON_PRIMARY) != 0) {
            touch.mouseButton(GLFW_MOUSE_BUTTON_LEFT, false, mods);
            buttons = 0;
        }
        mouseButtons = buttons;
        requestRender();
        return true;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        if (e.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_HOVER_MOVE -> {
                    touch.mouseMove(e.getX(), e.getY());
                    requestRender();
                    return true;
                }
                case MotionEvent.ACTION_SCROLL -> {
                    touch.mouseMove(e.getX(), e.getY());
                    touch.wheel(e.getAxisValue(MotionEvent.AXIS_HSCROLL), e.getAxisValue(MotionEvent.AXIS_VSCROLL));
                    requestRender();
                    return true;
                }
                default -> { }
            }
        }
        return super.onGenericMotionEvent(e);
    }

    // ------------------------------------------------------------------ клавиатура

    void sendKey(int key, int mods) {
        touch.key(key, 0, GLFW_PRESS, mods);
        touch.key(key, 0, GLFW_RELEASE, mods);
        requestRender();
    }

    private void typeText(CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') sendKey(GLFW_KEY_ENTER, 0);
            else touch.character(c);
        }
        requestRender();
    }

    private void backspaces(int n) {
        for (int i = 0; i < n; i++) sendKey(GLFW_KEY_BACKSPACE, 0);
    }

    void resetComposing() { composing = 0; }

    @Override
    public boolean onCheckIsTextEditor() { return true; }

    /**
     * Клавиатура телефона говорит не клавишами, а правками текста: «составляю
     * слово», «вот слово целиком», «удали два символа слева». Своего текста
     * у поверхности нет, поэтому правки переводятся в клавиши: составляемое
     * слово стирается Backspace-ами и печатается заново.
     */
    @Override
    public InputConnection onCreateInputConnection(EditorInfo out) {
        out.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        out.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_ACTION_NONE;
        composing = 0;
        return new BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                backspaces(composing);
                composing = 0;
                typeText(text);
                return true;
            }

            @Override
            public boolean setComposingText(CharSequence text, int newCursorPosition) {
                backspaces(composing);
                typeText(text);
                composing = text.length();
                return true;
            }

            @Override
            public boolean finishComposingText() {
                composing = 0;
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int before, int after) {
                backspaces(before);
                for (int i = 0; i < after; i++) sendKey(GLFW_KEY_DELETE, 0);
                return true;
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) onKeyDown(event.getKeyCode(), event);
                else if (event.getAction() == KeyEvent.ACTION_UP) onKeyUp(event.getKeyCode(), event);
                return true;
            }

            @Override
            public boolean performEditorAction(int actionCode) {
                sendKey(GLFW_KEY_ENTER, 0);
                return true;
            }
        };
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_BACK) return false;
        int key = glfwKey(keyCode);
        int mods = mods(e.getMetaState());
        if (key != GLFW_KEY_UNKNOWN) {
            touch.key(key, e.getScanCode(), e.getRepeatCount() > 0 ? GLFW_REPEAT : GLFW_PRESS, mods);
        }
        // символ печатается, только если это не сочетание с Ctrl/Alt
        int ch = e.getUnicodeChar(e.getMetaState());
        if (ch > 0 && (mods & (GLFW_MOD_CONTROL | GLFW_MOD_ALT)) == 0 && keyCode != KeyEvent.KEYCODE_ENTER
                && keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER && keyCode != KeyEvent.KEYCODE_TAB)
            touch.character(ch);
        requestRender();
        return key != GLFW_KEY_UNKNOWN || ch > 0 || super.onKeyDown(keyCode, e);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_BACK) return false;
        int key = glfwKey(keyCode);
        if (key != GLFW_KEY_UNKNOWN) {
            touch.key(key, e.getScanCode(), GLFW_RELEASE, mods(e.getMetaState()));
            requestRender();
            return true;
        }
        return super.onKeyUp(keyCode, e);
    }

    private static int mods(int meta) {
        int m = 0;
        if ((meta & KeyEvent.META_SHIFT_ON) != 0) m |= GLFW_MOD_SHIFT;
        if ((meta & KeyEvent.META_CTRL_ON) != 0) m |= GLFW_MOD_CONTROL;
        if ((meta & KeyEvent.META_ALT_ON) != 0) m |= GLFW_MOD_ALT;
        return m;
    }

    /** Код клавиши Android → код GLFW, которым пользуется интерфейс. */
    private static int glfwKey(int k) {
        if (k >= KeyEvent.KEYCODE_A && k <= KeyEvent.KEYCODE_Z) return GLFW_KEY_A + (k - KeyEvent.KEYCODE_A);
        if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) return GLFW_KEY_0 + (k - KeyEvent.KEYCODE_0);
        if (k >= KeyEvent.KEYCODE_F1 && k <= KeyEvent.KEYCODE_F12) return GLFW_KEY_F1 + (k - KeyEvent.KEYCODE_F1);
        if (k >= KeyEvent.KEYCODE_NUMPAD_0 && k <= KeyEvent.KEYCODE_NUMPAD_9)
            return GLFW_KEY_KP_0 + (k - KeyEvent.KEYCODE_NUMPAD_0);
        return switch (k) {
            case KeyEvent.KEYCODE_SPACE -> GLFW_KEY_SPACE;
            case KeyEvent.KEYCODE_APOSTROPHE -> GLFW_KEY_APOSTROPHE;
            case KeyEvent.KEYCODE_COMMA -> GLFW_KEY_COMMA;
            case KeyEvent.KEYCODE_MINUS -> GLFW_KEY_MINUS;
            case KeyEvent.KEYCODE_PERIOD -> GLFW_KEY_PERIOD;
            case KeyEvent.KEYCODE_SLASH -> GLFW_KEY_SLASH;
            case KeyEvent.KEYCODE_SEMICOLON -> GLFW_KEY_SEMICOLON;
            case KeyEvent.KEYCODE_EQUALS -> GLFW_KEY_EQUAL;
            case KeyEvent.KEYCODE_LEFT_BRACKET -> GLFW_KEY_LEFT_BRACKET;
            case KeyEvent.KEYCODE_BACKSLASH -> GLFW_KEY_BACKSLASH;
            case KeyEvent.KEYCODE_RIGHT_BRACKET -> GLFW_KEY_RIGHT_BRACKET;
            case KeyEvent.KEYCODE_GRAVE -> GLFW_KEY_GRAVE_ACCENT;
            case KeyEvent.KEYCODE_ESCAPE -> GLFW_KEY_ESCAPE;
            case KeyEvent.KEYCODE_ENTER -> GLFW_KEY_ENTER;
            case KeyEvent.KEYCODE_NUMPAD_ENTER -> GLFW_KEY_KP_ENTER;
            case KeyEvent.KEYCODE_TAB -> GLFW_KEY_TAB;
            case KeyEvent.KEYCODE_DEL -> GLFW_KEY_BACKSPACE;
            case KeyEvent.KEYCODE_FORWARD_DEL -> GLFW_KEY_DELETE;
            case KeyEvent.KEYCODE_INSERT -> GLFW_KEY_INSERT;
            case KeyEvent.KEYCODE_DPAD_RIGHT -> GLFW_KEY_RIGHT;
            case KeyEvent.KEYCODE_DPAD_LEFT -> GLFW_KEY_LEFT;
            case KeyEvent.KEYCODE_DPAD_DOWN -> GLFW_KEY_DOWN;
            case KeyEvent.KEYCODE_DPAD_UP -> GLFW_KEY_UP;
            case KeyEvent.KEYCODE_PAGE_UP -> GLFW_KEY_PAGE_UP;
            case KeyEvent.KEYCODE_PAGE_DOWN -> GLFW_KEY_PAGE_DOWN;
            case KeyEvent.KEYCODE_MOVE_HOME -> GLFW_KEY_HOME;
            case KeyEvent.KEYCODE_MOVE_END -> GLFW_KEY_END;
            case KeyEvent.KEYCODE_NUMPAD_DOT -> GLFW_KEY_KP_DECIMAL;
            case KeyEvent.KEYCODE_NUMPAD_DIVIDE -> GLFW_KEY_KP_DIVIDE;
            case KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> GLFW_KEY_KP_MULTIPLY;
            case KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> GLFW_KEY_KP_SUBTRACT;
            case KeyEvent.KEYCODE_NUMPAD_ADD -> GLFW_KEY_KP_ADD;
            case KeyEvent.KEYCODE_SHIFT_LEFT -> GLFW_KEY_LEFT_SHIFT;
            case KeyEvent.KEYCODE_SHIFT_RIGHT -> GLFW_KEY_RIGHT_SHIFT;
            case KeyEvent.KEYCODE_CTRL_LEFT -> GLFW_KEY_LEFT_CONTROL;
            case KeyEvent.KEYCODE_CTRL_RIGHT -> GLFW_KEY_RIGHT_CONTROL;
            case KeyEvent.KEYCODE_ALT_LEFT -> GLFW_KEY_LEFT_ALT;
            case KeyEvent.KEYCODE_ALT_RIGHT -> GLFW_KEY_RIGHT_ALT;
            default -> GLFW_KEY_UNKNOWN;
        };
    }
}
