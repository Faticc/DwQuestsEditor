package ru.dw.qeditor.app;

import ru.dw.qeditor.gl.Input;
import ru.dw.qeditor.gui.Theme;
import ru.dw.qeditor.gui.Ui;
import ru.dw.qeditor.render.GamePreview;
import ru.dw.qeditor.render.GuiPainter;

import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;

/**
 * Вкладка «Просмотр (как в игре)»: рисует {@link GamePreview} в своём
 * прямоугольнике окна и переводит туда мышь.
 *
 * <p>Просмотр живёт в виртуальных пикселях GUI — как сам мод, где один пиксель
 * интерфейса при guiScale = 2 занимает на экране четыре. Поэтому перед его
 * отрисовкой масштаб рисователя меняется с масштаба интерфейса редактора на
 * {@code guiScale}, а координаты мыши переводятся обратно.
 *
 * <p>Быстрого режима (рисовать вдвое мельче и растягивать) больше нет: он был
 * нужен, пока кадр собирал процессор.
 */
public final class PreviewView {

    /** Один игровой тик = 1/20 секунды: так совпадает скорость анимации с игрой. */
    private static final double TICK = 1.0 / 20.0;

    private final GamePreview preview = new GamePreview();
    /** Масштаб окна мода, как его задал пользователь (аналог guiScale в игре). */
    private float guiScale = 2f;
    private double lastTick;
    private Runnable onQuestOpened;

    public GamePreview preview() { return preview; }

    public void setOnQuestOpened(Runnable r) { this.onQuestOpened = r; }

    public float getGuiScale() { return guiScale; }

    /** Масштаб целый, как guiScale в игре: дробный «шакалит» пиксельные текстуры. */
    public void setGuiScale(float scale) {
        this.guiScale = Math.max(1f, Math.min(4f, Math.round(scale)));
    }

    public void draw(Ui ui, float x, float y, float w, float h) {
        if (w < 8 || h < 8) return;

        // анимация идёт по времени, а не по кадрам: кадров может быть и 200 в
        // секунду, а «волна» на линиях должна ползти со скоростью игры
        double now = ui.time();
        if (lastTick == 0) lastTick = now;
        int ticks = (int) Math.min(4, (now - lastTick) / TICK);
        for (int i = 0; i < ticks; i++) preview.tickAnimation();
        if (ticks > 0) lastTick += ticks * TICK;

        float uiScale = ui.scale();
        int pixelW = Math.max(1, Math.round(w * uiScale));
        int pixelH = Math.max(1, Math.round(h * uiScale));

        handleInput(ui, x, y, w, h, uiScale);

        GuiPainter p = ui.painter();
        ui.pushClip(x, y, w, h);
        p.push();
        p.translate(x, y);
        // начало — на целом пикселе экрана: шрифт и текстуры мода рисуются
        // GL_NEAREST, и полпикселя сдвига делают их «мыльными»
        p.snap();
        // после этого единица рисователя — виртуальный пиксель GUI мода
        p.scale(guiScale / uiScale);
        preview.setGuiScale(guiScale);
        preview.render(p, pixelW, pixelH);
        p.pop();
        ui.popClip();
    }

    private void handleInput(Ui ui, float x, float y, float w, float h, float uiScale) {
        Input in = ui.in();
        boolean hot = ui.over(x, y, w, h);
        float vx = (ui.mx() - x) * uiScale / guiScale;
        float vy = (ui.my() - y) * uiScale / guiScale;

        if (hot) {
            preview.onMouseMove(vx, vy);
        } else if (!in.leftDown()) {
            preview.onMouseExit();
        }

        if (hot && in.scrollY != 0) {
            // в моде колесо приходит «щелчками»: вверх — отрицательное вращение
            preview.onWheel(vx, vy, in.scrollY > 0 ? -1 : 1);
            in.consumeScroll();
        }

        if (hot && (in.leftPressed() || in.pressed(GLFW_MOUSE_BUTTON_RIGHT))) {
            String before = preview.getOpenQuestID();
            preview.onMousePress(vx, vy, in.leftPressed() ? 1 : 2);
            String after = preview.getOpenQuestID();
            if (onQuestOpened != null && after != null && !after.equals(before)) onQuestOpened.run();
        }
        // тяга карты продолжается и за краем области — как в любом окне
        if (in.down(GLFW_MOUSE_BUTTON_LEFT)) preview.onMouseDrag(vx, vy);
        else preview.onMouseRelease();
    }

    /** Подпись под областью просмотра — что показывать и чем управлять. */
    public void drawHint(Ui ui, float x, float y, float w) {
        ui.textIn("колесо — зум карты и прокрутка панели, тяга — панорама, ЛКМ по узлу — панель квеста",
                x, y, w, Theme.ROW, Theme.TEXT_DISABLED, Ui.LEFT, Theme.FONT_SMALL, false);
    }
}
