package ru.dw.qeditor.devtools;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;

import ru.dw.qeditor.assets.AssetPack;
import ru.dw.qeditor.assets.Config;
import ru.dw.qeditor.assets.ItemRegistry;
import ru.dw.qeditor.assets.ModAssets;
import ru.dw.qeditor.gl.Batch;
import ru.dw.qeditor.gl.Offscreen;
import ru.dw.qeditor.gl.Textures;
import ru.dw.qeditor.model.Defs;
import ru.dw.qeditor.render.GuiPainter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.function.Consumer;

/**
 * Иконка приложения для Android — тот же узел квеста, что рисует карта
 * (раньше так делал {@code util/AppIcon} для Windows).
 *
 * <p>Кладёт в {@code res/}: обычную иконку плиткой для всех плотностей и
 * передний план адаптивной иконки (Android 8+ сам обрезает её под форму
 * лаунчера, фон — цвет из {@code colors.xml}).
 *
 * <pre>IconProbe &lt;каталог res&gt;</pre>
 */
public final class IconProbe {

    private static final String[] DENSITIES = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
    private static final float[] FACTORS = {1f, 1.5f, 2f, 3f, 4f};

    public static void main(String[] args) throws Exception {
        File res = new File(args[0]);
        File repo = new File(System.getProperty("dwq.repo", "../.."));
        File tmp = new File(System.getProperty("java.io.tmpdir"), "dwq-icon");
        tmp.mkdirs();
        Config.init(tmp);
        ModAssets.useGamePack(AssetPack.dir(new File(repo, "runtime/game")));
        ItemRegistry.useEmbedded(AssetPack.dir(new File(repo, "runtime/icons")));

        GLFW.glfwInit();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long handle = GLFW.glfwCreateWindow(64, 64, "icon", 0, 0);
        GLFW.glfwMakeContextCurrent(handle);
        GL.createCapabilities();
        Batch batch = new Batch();

        for (int i = 0; i < DENSITIES.length; i++) {
            File dir = new File(res, "mipmap-" + DENSITIES[i]);
            dir.mkdirs();
            // обычная иконка: 48 dp, узел на тёмной плитке
            int legacy = Math.round(48 * FACTORS[i]);
            ImageIO.write(frame(batch, legacy, 64, p -> {
                p.roundRect(2, 2, 60, 60, 14, 0xFF141A22, 0xFF2C5364, 1.5f);
                star(p, 32, 1.5f);
            }), "png", new File(dir, "ic_launcher.png"));
            // передний план адаптивной: 108 dp, видимая часть — круг в 66 dp посередине
            int adaptive = Math.round(108 * FACTORS[i]);
            ImageIO.write(frame(batch, adaptive, 108, p -> star(p, 54, 1.45f)),
                    "png", new File(dir, "ic_launcher_foreground.png"));
        }
        GLFW.glfwTerminate();
        System.out.println("иконки записаны в " + res.getAbsolutePath());
    }

    /** Узел квеста размера 24×24 GUI-пикселя с центром в (c, c), увеличенный в k раз. */
    private static void star(GuiPainter p, float c, float k) {
        p.push();
        p.translate(c, c);
        p.scale(k);
        p.questStar(-12, -12, 24, 24, 1f, Defs.Star.of(Defs.Rarity.LEGENDARY),
                Defs.NodeType.NORMAL, "minecraft:book", 7);
        p.pop();
    }

    private static BufferedImage frame(Batch batch, int size, float units, Consumer<GuiPainter> draw) {
        Offscreen target = Offscreen.create(size, size);
        // первый проход кладёт иконку предмета в атлас, второй рисует её уже из текстуры
        for (int pass = 0; pass < 2; pass++) {
            target.bind(0x00000000);
            batch.begin(size, size);
            GuiPainter p = GuiPainter.of(batch, size / units, size, size);
            draw.accept(p);
            p.dispose();
            batch.end();
            target.unbind();
            Textures.refresh();
        }
        BufferedImage img = target.read();
        target.dispose();
        return img;
    }
}
