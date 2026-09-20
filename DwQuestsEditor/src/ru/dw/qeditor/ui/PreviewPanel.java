package ru.dw.qeditor.ui;

import ru.dw.qeditor.render.GamePreview;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;

/** Вкладка «Просмотр (как в игре)»: рисует {@link GamePreview} и пробрасывает мышь. */
public class PreviewPanel extends JComponent {

    private final GamePreview preview = new GamePreview();
    private final Timer timer;
    private BufferedImage buffer;
    private Runnable onQuestOpened;

    /** Масштаб окна мода, как его задал пользователь (аналог guiScale в игре). */
    private float guiScale = 2f;
    /**
     * Во сколько раз рисовать мельче экрана. 1 — пиксель в пиксель; 0.5 —
     * вчетверо меньше точек, потом растягиваем. Раскладка при этом та же,
     * меняется только чёткость кругов и линий.
     */
    private float renderScale = 1f;

    public PreviewPanel() {
        setFocusable(true);
        // 20 кадров в секунду = один игровой тик на кадр: так совпадает скорость
        // анимации нодов и «волны» на линиях.
        timer = new Timer(50, e -> {
            preview.tickAnimation();
            repaint();
        });

        MouseAdapter ma = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                preview.onMouseMove(vx(e.getX()), vy(e.getY()));
            }

            @Override
            public void mouseExited(MouseEvent e) { preview.onMouseExit(); }

            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                String before = preview.getOpenQuestID();
                preview.onMousePress(vx(e.getX()), vy(e.getY()),
                        e.getButton() == MouseEvent.BUTTON1 ? 1 : e.getButton());
                if (onQuestOpened != null && preview.getOpenQuestID() != null
                        && !preview.getOpenQuestID().equals(before)) {
                    onQuestOpened.run();
                }
                repaint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                preview.onMouseDrag(vx(e.getX()), vy(e.getY()));
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) { preview.onMouseRelease(); }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                preview.onWheel(vx(e.getX()), vy(e.getY()), e.getWheelRotation());
                repaint();
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        addMouseWheelListener(ma);
    }

    /**
     * На больших вкладках кадр рисуется дольше игрового тика. Гнать таймер в
     * этом случае бессмысленно — он лишь забивает очередь событий, поэтому
     * интервал подстраивается под реальное время отрисовки.
     */
    private void adjustRate(long frameMs) {
        int want = frameMs > 60 ? 150 : frameMs > 30 ? 100 : 50;
        if (timer.getDelay() != want) timer.setDelay(want);
    }

    public GamePreview preview() { return preview; }

    public void setOnQuestOpened(Runnable r) { this.onQuestOpened = r; }

    public void start() { timer.start(); }

    public void stop() { timer.stop(); }

    public boolean isRunning() { return timer.isRunning(); }

    /** Экранные координаты → пиксели GUI. Считаем по заданному масштабу, а не
     *  по внутреннему: при быстром режиме тот вдвое меньше. */
    private float vx(int screenX) { return screenX / guiScale; }

    private float vy(int screenY) { return screenY / guiScale; }

    public void setGuiScale(float scale) {
        this.guiScale = Math.max(1f, Math.min(4f, scale));
        buffer = null;
        repaint();
    }

    public float getGuiScale() { return guiScale; }

    public boolean isFastMode() { return renderScale < 1f; }

    /** Быстрый режим: рисуем вдвое мельче и растягиваем. */
    public void setFastMode(boolean fast) {
        renderScale = fast ? 0.5f : 1f;
        buffer = null;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        int bw = Math.max(1, Math.round(w * renderScale));
        int bh = Math.max(1, Math.round(h * renderScale));
        if (buffer == null || buffer.getWidth() != bw || buffer.getHeight() != bh)
            // поле закрашивается целиком, прозрачность не нужна: непрозрачный буфер
            // и рисуется быстрее, и выводится на экран простым копированием
            buffer = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_RGB);

        long start = System.nanoTime();
        preview.setGuiScale(guiScale * renderScale);
        preview.render(buffer);
        if (bw == w && bh == h) {
            g.drawImage(buffer, 0, 0, null);
        } else {
            ((java.awt.Graphics2D) g).setRenderingHint(
                    java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(buffer, 0, 0, w, h, null);
        }
        adjustRate((System.nanoTime() - start) / 1_000_000);
    }
}
