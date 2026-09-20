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

    private float vx(int screenX) { return screenX / preview.getGuiScale(); }

    private float vy(int screenY) { return screenY / preview.getGuiScale(); }

    @Override
    protected void paintComponent(Graphics g) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (buffer == null || buffer.getWidth() != w || buffer.getHeight() != h)
            // поле закрашивается целиком, прозрачность не нужна: непрозрачный буфер
            // и рисуется быстрее, и выводится на экран простым копированием
            buffer = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        long start = System.nanoTime();
        preview.render(buffer);
        g.drawImage(buffer, 0, 0, null);
        adjustRate((System.nanoTime() - start) / 1_000_000);
    }
}
