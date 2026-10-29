package ru.dw.qeditor.devtools;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;

import ru.dw.qeditor.android.Platform;
import ru.dw.qeditor.android.TouchInput;
import ru.dw.qeditor.app.App;
import ru.dw.qeditor.assets.AssetPack;
import ru.dw.qeditor.assets.Config;
import ru.dw.qeditor.assets.ItemRegistry;
import ru.dw.qeditor.assets.ModAssets;
import ru.dw.qeditor.gl.Batch;
import ru.dw.qeditor.gl.GlWindow;
import ru.dw.qeditor.gl.Offscreen;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static ru.dw.qeditor.gl.Keys.GLFW_PRESS;
import static ru.dw.qeditor.gl.Keys.GLFW_RELEASE;

/**
 * Окно Android-сборки на компьютере: то же {@code App} и тот же разбор
 * касаний ({@link TouchInput}), только рисует LWJGL в скрытом окне, а кадры
 * уходят в PNG. Эмулятора Android без аппаратной виртуализации не запустить,
 * а так проверяются раскладка на экране телефона и жесты.
 *
 * <pre>
 * PhoneProbe &lt;папка снимков&gt; &lt;ширина&gt; &lt;высота&gt; &lt;плотность&gt; &lt;сценарий&gt; [вкладка.json]
 * </pre>
 *
 * Сценарий — строки команд (координаты в dp, время идёт по 16 мс на кадр):
 * <pre>
 * frames N | tap X Y | double X Y | long X Y | drag X1 Y1 X2 Y2 [шагов]
 * pinch CX CY D1 D2 | pan2 CX CY DX DY | type ТЕКСТ | key КОД [МОДЫ] | shot ИМЯ
 * </pre>
 */
public final class PhoneProbe {

    private static double now = 100;
    private static float density;

    public static void main(String[] args) throws Exception {
        File outDir = new File(args[0]);
        int w = Integer.parseInt(args[1]), h = Integer.parseInt(args[2]);
        density = Float.parseFloat(args[3]);
        List<String> script = Files.readAllLines(new File(args[4]).toPath());
        File tab = args.length > 5 ? new File(args[5]) : null;
        outDir.mkdirs();

        File repo = new File(System.getProperty("dwq.repo", "../.."));
        File data = new File(outDir, "appdata");
        data.mkdirs();
        Config.init(data);
        Config.get().askedStorage = true;
        ModAssets.useGamePack(AssetPack.dir(new File(repo, "runtime/game")));
        ItemRegistry.useEmbedded(AssetPack.dir(new File(repo, "runtime/icons")));
        File folder = tab != null ? tab.getParentFile() : new File(outDir, "tabs");
        Platform.set(new DesktopPlatform(folder, new File(repo, "DwQuestsAndroid/src/main/assets")));

        if (!GLFW.glfwInit()) throw new IllegalStateException("GLFW не запустился");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long handle = GLFW.glfwCreateWindow(64, 64, "probe", 0, 0);
        GLFW.glfwMakeContextCurrent(handle);
        GL.createCapabilities();

        PhoneWindow window = new PhoneWindow(w, h, density);
        Batch batch = new Batch();
        App app = new App(window, batch);
        if (tab != null) app.openFile(tab);
        TouchInput touch = new TouchInput(app);
        touch.setDensity(density);
        Offscreen target = Offscreen.create(w, h);

        Runnable frame = () -> {
            touch.pump(window.input(), app.stickyMods(), window.contentScale(), now);
            target.bind(0xFF000000);
            app.drawFrame();
            target.unbind();
            window.input().endFrame();
            now += 0.016;
        };
        Consumer<Integer> frames = n -> {
            for (int i = 0; i < n; i++) frame.run();
        };
        frames.accept(3);

        for (String raw : script) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split("\\s+");
            switch (p[0]) {
                case "frames" -> frames.accept(Integer.parseInt(p[1]));
                case "tap" -> {
                    touch.touch(t(TouchInput.DOWN, f(p[1]), f(p[2])));
                    frames.accept(2);
                    touch.touch(t(TouchInput.UP, f(p[1]), f(p[2])));
                    frames.accept(8);
                }
                case "double" -> {
                    for (int i = 0; i < 2; i++) {
                        touch.touch(t(TouchInput.DOWN, f(p[1]), f(p[2])));
                        frames.accept(2);
                        touch.touch(t(TouchInput.UP, f(p[1]), f(p[2])));
                        frames.accept(4);
                    }
                    frames.accept(6);
                }
                case "long" -> {
                    touch.touch(t(TouchInput.DOWN, f(p[1]), f(p[2])));
                    frames.accept(45);
                    touch.touch(t(TouchInput.UP, f(p[1]), f(p[2])));
                    frames.accept(8);
                }
                case "drag" -> {
                    float x1 = f(p[1]), y1 = f(p[2]), x2 = f(p[3]), y2 = f(p[4]);
                    int steps = p.length > 5 ? Integer.parseInt(p[5]) : 12;
                    touch.touch(t(TouchInput.DOWN, x1, y1));
                    frames.accept(1);
                    for (int i = 1; i <= steps; i++) {
                        touch.touch(t(TouchInput.MOVE, x1 + (x2 - x1) * i / steps, y1 + (y2 - y1) * i / steps));
                        frames.accept(1);
                    }
                    touch.touch(t(TouchInput.UP, x2, y2));
                    frames.accept(10);
                }
                case "pinch", "pan2" -> {
                    float cx = f(p[1]), cy = f(p[2]);
                    boolean pinch = p[0].equals("pinch");
                    float a = pinch ? f(p[3]) : dp(60), b = pinch ? f(p[4]) : dp(60);
                    float dx = pinch ? 0 : f(p[3]), dy = pinch ? 0 : f(p[4]);
                    touch.touch(t(TouchInput.DOWN, cx - a / 2, cy));
                    frames.accept(1);
                    touch.touch(two(TouchInput.POINTER_DOWN, cx, cy, a));
                    frames.accept(2);
                    for (int i = 1; i <= 20; i++) {
                        float d = a + (b - a) * i / 20;
                        touch.touch(two(TouchInput.MOVE, cx + dx * i / 20, cy + dy * i / 20, d));
                        frames.accept(2);
                    }
                    frames.accept(20);
                    touch.touch(new TouchInput.Touch(TouchInput.POINTER_UP, now, new float[]{cx + dx - b / 2},
                            new float[]{cy + dy}));
                    frames.accept(1);
                    touch.touch(t(TouchInput.UP, cx + dx - b / 2, cy + dy));
                    frames.accept(10);
                }
                case "type" -> {
                    String text = line.substring(5);
                    for (int i = 0; i < text.length(); i++) touch.character(text.charAt(i));
                    frames.accept(4);
                }
                case "key" -> {
                    int key = Integer.parseInt(p[1]);
                    int mods = p.length > 2 ? Integer.parseInt(p[2]) : 0;
                    touch.key(key, 0, GLFW_PRESS, mods);
                    touch.key(key, 0, GLFW_RELEASE, mods);
                    frames.accept(4);
                }
                case "shot" -> {
                    frames.accept(2);
                    File out = new File(outDir, p[1] + ".png");
                    ImageIO.write(target.read(), "png", out);
                    System.out.println("снимок " + out.getName());
                }
                default -> System.out.println("непонятная команда: " + line);
            }
        }
        System.out.println(window.title);
        GLFW.glfwTerminate();
    }

    private static float dp(float v) { return v * density; }

    private static float f(String s) { return dp(Float.parseFloat(s)); }

    private static TouchInput.Touch t(int action, float x, float y) {
        return new TouchInput.Touch(action, now, new float[]{x}, new float[]{y});
    }

    private static TouchInput.Touch two(int action, float cx, float cy, float spread) {
        return new TouchInput.Touch(action, now, new float[]{cx - spread / 2, cx + spread / 2},
                new float[]{cy, cy});
    }

    /** Окно размером с экран телефона; масштаб — как у AndroidWindow. */
    static final class PhoneWindow extends GlWindow {
        final int w, h;
        final float density;
        String clip;
        String title = "";

        PhoneWindow(int w, int h, float density) {
            this.w = w;
            this.h = h;
            this.density = density;
        }

        @Override public int width() { return w; }

        @Override public int height() { return h; }

        @Override
        public float contentScale() {
            int pct = Config.get().uiScale;
            float smallestDp = Math.min(w, h) / density;
            float factor = pct > 0 ? pct / 100f : smallestDp < 600 ? 0.85f : 1f;
            return Math.max(1f, density * factor);
        }

        @Override public String clipboard() { return clip; }

        @Override public void setClipboard(String s) { clip = s; }

        @Override public void setTitle(String t) { title = t; }

        @Override public void close() { System.out.println("окно закрыто"); }
    }

    /** Телефон без телефона: папка вкладок — та, что дали, доступ к памяти — есть. */
    static final class DesktopPlatform implements Platform {
        final File folder;
        final File assets;

        DesktopPlatform(File folder, File assets) {
            this.folder = folder;
            this.assets = assets;
        }

        @Override public boolean hasStorageAccess() { return true; }

        @Override public void requestStorageAccess() { }

        @Override public File defaultFolder() { return folder; }

        @Override
        public List<Place> places() {
            List<Place> out = new ArrayList<>();
            out.add(new Place("DwQuests", folder));
            out.add(new Place("Загрузки", folder.getParentFile() == null ? folder : folder.getParentFile()));
            out.add(new Place("Память", new File("/")));
            out.add(new Place("Приложение", folder));
            return out;
        }

        @Override public void share(File file, String mime, String title) { System.out.println("поделиться " + file); }

        @Override public void importFiles(File targetDir, Consumer<List<File>> done) { done.accept(List.of()); }

        @Override public void exportFile(File file, String mime) { System.out.println("экспорт " + file); }

        @Override
        public InputStream asset(String path) {
            try {
                return new FileInputStream(new File(assets, path));
            } catch (Exception e) {
                return null;
            }
        }

        @Override public void showKeyboard(boolean show) { System.out.println("клавиатура: " + show); }

        @Override public void setImmersive(boolean on) { }

        @Override public void quit() { System.out.println("выход"); }

        @Override public String describe() { return "проба на компьютере"; }
    }
}
