package ru.dw.qeditor.ui;

import ru.dw.qeditor.assets.McFont;
import ru.dw.qeditor.model.Defs;
import ru.dw.qeditor.model.Parent;
import ru.dw.qeditor.model.Quest;
import ru.dw.qeditor.model.QuestTab;
import ru.dw.qeditor.render.GuiPainter;
import ru.dw.qeditor.render.LinkRenderer;

import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Поле редактора: сетка, перетаскивание узлов с мультивыделением, рисование и
 * удаление зависимостей, зум и панорама, контекстное меню.
 *
 * <p>Узлы рисуются тем же {@link GuiPainter}, что и просмотр, поэтому вид
 * совпадает с игрой; анимация выключена (кадр нода фиксирован).
 */
public class EditorCanvas extends JComponent {

    /** Шаг сетки мода: одна клетка displayX = 24 пикселя GUI. */
    public static final int CELL = 24;
    private static final int STATIC_TICK = 7;

    public interface Callbacks {
        void questDoubleClicked(String questID);

        void requestAddQuest(float x, float y);

        void requestCopy();

        void requestPaste(float x, float y);

        void requestDelete();
    }

    private final EditorModel model;
    private Callbacks callbacks;

    private float camX;
    private float camY;
    private float zoom = 1f;

    private int gridSize = 1;
    private float gridOffsetX;
    private float gridOffsetY;
    private boolean showGrid = true;
    private boolean snap = true;
    private boolean showNames = true;

    // перетаскивание
    private boolean dragNodes;
    private boolean panning;
    private boolean rubber;
    private String linkFrom;
    private Point pressPoint;
    private float pressWorldX, pressWorldY;
    private float lastWorldX, lastWorldY;
    private Rectangle rubberRect;
    private String dragBeforeJson;
    private final Map<String, float[]> dragOrigin = new HashMap<>();

    private BufferedImage buffer;

    public EditorCanvas(EditorModel model) {
        this.model = model;
        setFocusable(true);
        setBackground(new Color(0x10, 0x12, 0x18));
        setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));

        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { onPress(e); }

            @Override public void mouseDragged(MouseEvent e) { onDrag(e); }

            @Override public void mouseReleased(MouseEvent e) { onRelease(e); }

            @Override public void mouseMoved(MouseEvent e) { onMove(e); }

            @Override public void mouseWheelMoved(MouseWheelEvent e) { onWheel(e); }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    String id = questAt(worldX(e.getX()), worldY(e.getY()));
                    if (id != null && callbacks != null) callbacks.questDoubleClicked(id);
                }
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        addMouseWheelListener(ma);

        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DELETE:
                        if (callbacks != null) callbacks.requestDelete();
                        break;
                    case KeyEvent.VK_LEFT: nudge(-1, 0); break;
                    case KeyEvent.VK_RIGHT: nudge(1, 0); break;
                    case KeyEvent.VK_UP: nudge(0, -1); break;
                    case KeyEvent.VK_DOWN: nudge(0, 1); break;
                    default:
                }
            }
        });
    }

    public void setCallbacks(Callbacks callbacks) { this.callbacks = callbacks; }

    // ------------------------------------------------------------------ настройки

    public int getGridSize() { return gridSize; }

    public void setGridSize(int gridSize) { this.gridSize = Math.max(1, gridSize); repaint(); }

    public void setGridOffset(float x, float y) { gridOffsetX = x; gridOffsetY = y; repaint(); }

    public float getGridOffsetX() { return gridOffsetX; }

    public float getGridOffsetY() { return gridOffsetY; }

    public boolean isShowGrid() { return showGrid; }

    public void setShowGrid(boolean v) { showGrid = v; repaint(); }

    public boolean isSnap() { return snap; }

    public void setSnap(boolean v) { snap = v; }

    public boolean isShowNames() { return showNames; }

    public void setShowNames(boolean v) { showNames = v; repaint(); }

    public float getZoom() { return zoom; }

    public void setZoom(float z) {
        zoom = Math.max(0.25f, Math.min(4f, z));
        repaint();
    }

    /** Показать всю вкладку целиком. */
    public void fitToContent() {
        QuestTab tab = model.tab();
        if (tab.quests.isEmpty()) {
            camX = -getWidth() / 2f;
            camY = -getHeight() / 2f;
            zoom = 1f;
            repaint();
            return;
        }
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (Quest q : tab.quests.values()) {
            minX = Math.min(minX, q.displayX * CELL);
            minY = Math.min(minY, q.displayY * CELL);
            maxX = Math.max(maxX, q.displayX * CELL + CELL);
            maxY = Math.max(maxY, q.displayY * CELL + CELL);
        }
        float pad = 60;
        minX -= pad; minY -= pad; maxX += pad; maxY += pad;
        float zx = getWidth() / Math.max(1f, maxX - minX);
        float zy = getHeight() / Math.max(1f, maxY - minY);
        zoom = Math.max(0.25f, Math.min(2f, Math.min(zx, zy)));
        camX = (minX + maxX) / 2f - getWidth() / (2f * zoom);
        camY = (minY + maxY) / 2f - getHeight() / (2f * zoom);
        repaint();
    }

    public void centerOn(String questID) {
        Quest q = model.tab().quests.get(questID);
        if (q == null) return;
        camX = q.displayX * CELL + CELL / 2f - getWidth() / (2f * zoom);
        camY = q.displayY * CELL + CELL / 2f - getHeight() / (2f * zoom);
        repaint();
    }

    // ------------------------------------------------------------------ координаты

    private float worldX(int screenX) { return screenX / zoom + camX; }

    private float worldY(int screenY) { return screenY / zoom + camY; }

    private float snapCell(float worldPixels, float offset) {
        float cell = worldPixels / CELL;
        if (!snap) return cell;
        float g = gridSize;
        return Math.round((cell - offset) / g) * g + offset;
    }

    /** questID узла под точкой мира либо null. */
    public String questAt(float wx, float wy) {
        QuestTab tab = model.tab();
        String best = null;
        float bestD = Float.MAX_VALUE;
        for (Quest q : tab.quests.values()) {
            float cx = q.displayX * CELL + CELL / 2f;
            float cy = q.displayY * CELL + CELL / 2f;
            float r = CELL * q.iconSize.scale / 2f + 2;
            float dx = wx - cx, dy = wy - cy;
            float d = dx * dx + dy * dy;
            if (d <= r * r && d < bestD) { bestD = d; best = q.questID; }
        }
        return best;
    }

    /** Зависимость под точкой: {childID, parentID} либо null. */
    private String[] linkAt(float wx, float wy) {
        QuestTab tab = model.tab();
        float tol = Math.max(4f, 6f / zoom);
        for (Quest q : tab.quests.values()) {
            for (Parent p : q.parents) {
                if (p.questID == null) continue;
                if (p.tabID != null && !p.tabID.isEmpty() && !p.tabID.equals(tab.tabID)) continue;
                Quest pq = tab.quests.get(p.questID);
                if (pq == null) continue;
                float x1 = pq.displayX * CELL + 12f;
                float y1 = pq.displayY * CELL + 12f;
                float x2 = q.displayX * CELL + 12f;
                float y2 = q.displayY * CELL + 12f;
                if (LinkRenderer.distanceTo(x1, y1, x2, y2, p.lineType, wx, wy) <= tol)
                    return new String[]{q.questID, p.questID};
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ отрисовка

    @Override
    protected void paintComponent(Graphics g0) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (buffer == null || buffer.getWidth() != w || buffer.getHeight() != h)
            buffer = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

        GuiPainter p = GuiPainter.forImage(buffer, zoom);
        try {
            p.rectOpaque(camX, camY, w / zoom, h / zoom, 0xFF12171E);
            p.translate(-camX, -camY);
            if (showGrid) drawGrid(p, w, h);
            drawLinks(p);
            drawNodes(p);
            drawPendingLink(p);
        } finally {
            p.dispose();
        }

        Graphics2D g = (Graphics2D) g0;
        g.drawImage(buffer, 0, 0, null);
        if (rubber && rubberRect != null) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setColor(new Color(0x3D, 0x7E, 0xB8, 60));
            g.fillRect(rubberRect.x, rubberRect.y, rubberRect.width, rubberRect.height);
            g.setColor(new Color(0x7A, 0xC3, 0xF5));
            g.drawRect(rubberRect.x, rubberRect.y, rubberRect.width, rubberRect.height);
        }
    }

    private void drawGrid(GuiPainter p, int w, int h) {
        float step = CELL * gridSize;
        float left = camX, top = camY;
        float right = camX + w / zoom, bottom = camY + h / zoom;
        if (step * zoom < 5) return;

        float ox = gridOffsetX * CELL, oy = gridOffsetY * CELL;
        int minI = (int) Math.floor((left - ox) / step);
        int maxI = (int) Math.ceil((right - ox) / step);
        int minJ = (int) Math.floor((top - oy) / step);
        int maxJ = (int) Math.ceil((bottom - oy) / step);

        float px = 1f / zoom;
        // крупная клетка каждые 4 шага — линиями, мелкая — точками на пересечениях
        int major = 4;
        for (int i = minI; i <= maxI; i++) {
            if (Math.floorMod(i, major) != 0) continue;
            float x = i * step + ox;
            p.rect(x, top, px, bottom - top, 0x14FFFFFF);
        }
        for (int j = minJ; j <= maxJ; j++) {
            if (Math.floorMod(j, major) != 0) continue;
            float y = j * step + oy;
            p.rect(left, y, right - left, px, 0x14FFFFFF);
        }

        if (step * zoom >= 10) {
            float dot = Math.max(px, 1.5f / zoom);
            for (int i = minI; i <= maxI; i++) {
                for (int j = minJ; j <= maxJ; j++) {
                    if (Math.floorMod(i, major) == 0 && Math.floorMod(j, major) == 0) continue;
                    p.rect(i * step + ox - dot / 2, j * step + oy - dot / 2, dot, dot, 0x33FFFFFF);
                }
            }
        }

        // оси координат — заметнее остального
        p.rect(0, top, px, bottom - top, 0x3D4FA8CC);
        p.rect(left, 0, right - left, px, 0x3D4FA8CC);
    }

    private void drawLinks(GuiPainter p) {
        QuestTab tab = model.tab();
        for (Quest q : tab.quests.values()) {
            for (Parent parent : q.parents) {
                if (parent.questID == null) continue;
                if (parent.tabID != null && !parent.tabID.isEmpty() && !parent.tabID.equals(tab.tabID)) continue;
                Quest pq = tab.quests.get(parent.questID);
                if (pq == null) continue;
                float x1 = pq.displayX * CELL + 12f;
                float y1 = pq.displayY * CELL + 12f;
                float x2 = q.displayX * CELL + 12f;
                float y2 = q.displayY * CELL + 12f;
                int color = Defs.Star.of(q.rarity).mainColor;
                int parentColor = Defs.Star.of(pq.rarity).mainColor;
                if (parent.parentType == Defs.ParentType.HIDDEN) {
                    color = 0x555555;
                    parentColor = 0x555555;
                } else if (parent.parentType == Defs.ParentType.FAKE) {
                    color = ru.dw.qeditor.render.Fx.lerpRgb(color, 0x888888, 0.5f);
                    parentColor = ru.dw.qeditor.render.Fx.lerpRgb(parentColor, 0x888888, 0.5f);
                }
                LinkRenderer.draw(p, x1, y1, x2, y2, color, parentColor, false,
                        parent.lineType, 0, Math.max(1f, 2.5f / zoom));
            }
        }
    }

    private void drawNodes(GuiPainter p) {
        QuestTab tab = model.tab();
        for (Quest q : tab.quests.values()) {
            float x = q.displayX * CELL;
            float y = q.displayY * CELL;
            boolean selected = model.selection().contains(q.questID);

            p.questStar(x, y, CELL, CELL, q.iconSize.scale, Defs.Star.of(q.rarity),
                    q.nodeType, q.iconItem, STATIC_TICK);

            if (selected) {
                float r = CELL * q.iconSize.scale / 2f + 5;
                float cx = x + CELL / 2f, cy = y + CELL / 2f;
                java.awt.Graphics2D g = p.g2();
                Object aa = g.getRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING);
                java.awt.Stroke st = g.getStroke();
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g.setStroke(new java.awt.BasicStroke(Math.max(1f, 1.5f / zoom)));
                g.setColor(GuiPainter.awt(0xFF4FA8CC));
                g.draw(new java.awt.geom.Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
                g.setStroke(st);
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                        aa == null ? java.awt.RenderingHints.VALUE_ANTIALIAS_OFF : aa);
            }
            if (showNames && zoom >= 0.5f) {
                String name = q.displayNameOrID();
                float ty = y + CELL + 3;
                int w = McFont.get().width(name);
                p.rect(x + CELL / 2f - w / 2f - 3, ty - 2, w + 6, McFont.FONT_HEIGHT + 4, 0xB0101418);
                p.textCentered(name, x + CELL / 2f, ty, selected ? 0xFF7FD4F5 : 0xFFD6DEE6, false);
            }
        }
    }

    private void drawPendingLink(GuiPainter p) {
        if (linkFrom == null) return;
        Quest from = model.tab().quests.get(linkFrom);
        if (from == null) return;
        float x1 = from.displayX * CELL + 12f;
        float y1 = from.displayY * CELL + 12f;
        LinkRenderer.draw(p, x1, y1, lastWorldX, lastWorldY,
                0xFFFFFF, Defs.Star.of(from.rarity).mainColor, false,
                Defs.LineType.DEFAULT, 0, Math.max(1f, 2.5f / zoom));
    }

    // ------------------------------------------------------------------ мышь

    private void onMove(MouseEvent e) {
        lastWorldX = worldX(e.getX());
        lastWorldY = worldY(e.getY());
        String id = questAt(lastWorldX, lastWorldY);
        setToolTipText(id == null ? null : tooltipFor(id));
        setCursor(Cursor.getPredefinedCursor(id == null ? Cursor.DEFAULT_CURSOR : Cursor.HAND_CURSOR));
    }

    private String tooltipFor(String id) {
        Quest q = model.tab().quests.get(id);
        if (q == null) return null;
        StringBuilder sb = new StringBuilder("<html><b>").append(escape(q.displayNameOrID())).append("</b><br>");
        sb.append("id: ").append(escape(id)).append("<br>");
        sb.append("(").append(fmt(q.displayX)).append(", ").append(fmt(q.displayY)).append(")<br>");
        sb.append(q.rarity.ru).append(", задач: ").append(q.tasks.size())
                .append(", наград: ").append(q.rewards.size());
        return sb.append("</html>").toString();
    }

    private void onPress(MouseEvent e) {
        requestFocusInWindow();
        pressPoint = e.getPoint();
        pressWorldX = worldX(e.getX());
        pressWorldY = worldY(e.getY());
        lastWorldX = pressWorldX;
        lastWorldY = pressWorldY;
        String hit = questAt(pressWorldX, pressWorldY);

        if (SwingUtilities.isMiddleMouseButton(e) || e.isAltDown()) {
            panning = true;
            return;
        }

        if (SwingUtilities.isRightMouseButton(e)) {
            if (e.isShiftDown()) {
                String[] link = linkAt(pressWorldX, pressWorldY);
                if (link != null) {
                    removeLink(link[0], link[1]);
                    return;
                }
            }
            showMenu(e, hit);
            return;
        }

        if (!SwingUtilities.isLeftMouseButton(e)) return;

        if (e.isShiftDown() && hit != null) {
            linkFrom = hit;
            return;
        }

        if (hit != null) {
            if (e.isControlDown()) {
                model.toggleSelect(hit);
            } else if (!model.selection().contains(hit)) {
                model.select(hit);
            }
            if (model.selection().contains(hit)) {
                dragNodes = true;
                dragBeforeJson = model.tab().toJsonString();
                dragOrigin.clear();
                for (String id : model.selection()) {
                    Quest q = model.tab().quests.get(id);
                    if (q != null) dragOrigin.put(id, new float[]{q.displayX, q.displayY});
                }
            }
        } else {
            if (!e.isControlDown()) model.clearSelection();
            rubber = true;
            rubberRect = new Rectangle(e.getX(), e.getY(), 0, 0);
        }
        repaint();
    }

    private void onDrag(MouseEvent e) {
        float wx = worldX(e.getX());
        float wy = worldY(e.getY());

        if (panning && pressPoint != null) {
            camX -= (e.getX() - pressPoint.x) / zoom;
            camY -= (e.getY() - pressPoint.y) / zoom;
            pressPoint = e.getPoint();
            repaint();
            return;
        }
        if (linkFrom != null) {
            lastWorldX = wx;
            lastWorldY = wy;
            repaint();
            return;
        }
        if (rubber && pressPoint != null) {
            rubberRect = new Rectangle(
                    Math.min(pressPoint.x, e.getX()), Math.min(pressPoint.y, e.getY()),
                    Math.abs(e.getX() - pressPoint.x), Math.abs(e.getY() - pressPoint.y));
            repaint();
            return;
        }
        if (dragNodes) {
            float dxCells = (wx - pressWorldX) / CELL;
            float dyCells = (wy - pressWorldY) / CELL;
            for (Map.Entry<String, float[]> en : dragOrigin.entrySet()) {
                Quest q = model.tab().quests.get(en.getKey());
                if (q == null) continue;
                float nx = en.getValue()[0] + dxCells;
                float ny = en.getValue()[1] + dyCells;
                if (snap) {
                    nx = Math.round((nx - gridOffsetX) / gridSize) * (float) gridSize + gridOffsetX;
                    ny = Math.round((ny - gridOffsetY) / gridSize) * (float) gridSize + gridOffsetY;
                }
                q.displayX = nx;
                q.displayY = ny;
            }
            model.touch();
            repaint();
        }
    }

    private void onRelease(MouseEvent e) {
        float wx = worldX(e.getX());
        float wy = worldY(e.getY());

        if (linkFrom != null) {
            String target = questAt(wx, wy);
            if (target != null && !target.equals(linkFrom)) addLink(target, linkFrom);
            linkFrom = null;
            repaint();
        }
        if (rubber) {
            rubber = false;
            if (rubberRect != null && (rubberRect.width > 2 || rubberRect.height > 2)) {
                float x1 = worldX(rubberRect.x), y1 = worldY(rubberRect.y);
                float x2 = worldX(rubberRect.x + rubberRect.width), y2 = worldY(rubberRect.y + rubberRect.height);
                for (Quest q : model.tab().quests.values()) {
                    float cx = q.displayX * CELL + CELL / 2f;
                    float cy = q.displayY * CELL + CELL / 2f;
                    if (cx >= x1 && cx <= x2 && cy >= y1 && cy <= y2) model.addSelect(q.questID);
                }
            }
            rubberRect = null;
            repaint();
        }
        if (dragNodes) {
            dragNodes = false;
            if (dragBeforeJson != null) model.commitDrag(dragBeforeJson, "Перемещение квестов");
            dragBeforeJson = null;
        }
        panning = false;
        pressPoint = null;
    }

    private void onWheel(MouseWheelEvent e) {
        float before = zoom;
        float factor = e.getWheelRotation() < 0 ? 1.1f : 1f / 1.1f;
        float next = Math.max(0.25f, Math.min(4f, zoom * factor));
        if (next == before) return;
        float wx = worldX(e.getX());
        float wy = worldY(e.getY());
        zoom = next;
        camX = wx - e.getX() / zoom;
        camY = wy - e.getY() / zoom;
        repaint();
    }

    private void nudge(int dx, int dy) {
        if (model.selection().isEmpty()) return;
        model.edit("Сдвиг квестов", () -> {
            for (String id : model.selection()) {
                Quest q = model.tab().quests.get(id);
                if (q == null) continue;
                q.displayX += dx * gridSize;
                q.displayY += dy * gridSize;
            }
        });
        repaint();
    }

    // ------------------------------------------------------------------ зависимости

    private void addLink(String childID, String parentID) {
        Quest child = model.tab().quests.get(childID);
        if (child == null) return;
        for (Parent p : child.parents) if (parentID.equals(p.questID)) return;
        model.edit("Добавление зависимости", () -> child.parents.add(new Parent(parentID)));
    }

    private void removeLink(String childID, String parentID) {
        Quest child = model.tab().quests.get(childID);
        if (child == null) return;
        model.edit("Удаление зависимости", () -> child.parents.removeIf(p -> parentID.equals(p.questID)));
        repaint();
    }

    // ------------------------------------------------------------------ меню

    private void showMenu(MouseEvent e, String hit) {
        JPopupMenu menu = new JPopupMenu();
        float cellX = snapCell(pressWorldX, gridOffsetX);
        float cellY = snapCell(pressWorldY, gridOffsetY);

        JMenuItem add = new JMenuItem("Добавить квест здесь");
        add.addActionListener(a -> { if (callbacks != null) callbacks.requestAddQuest(cellX, cellY); });
        menu.add(add);

        if (hit != null) {
            if (!model.selection().contains(hit)) model.select(hit);
            JMenuItem copy = new JMenuItem("Копировать квест" + (model.selection().size() > 1
                    ? " (" + model.selection().size() + ")" : ""));
            copy.addActionListener(a -> { if (callbacks != null) callbacks.requestCopy(); });
            menu.add(copy);

            JMenuItem del = new JMenuItem("Удалить квест" + (model.selection().size() > 1
                    ? " (" + model.selection().size() + ")" : ""));
            del.addActionListener(a -> { if (callbacks != null) callbacks.requestDelete(); });
            menu.add(del);
        }

        JMenuItem paste = new JMenuItem("Вставить квест здесь");
        paste.addActionListener(a -> { if (callbacks != null) callbacks.requestPaste(cellX, cellY); });
        menu.add(paste);

        if (hit != null) {
            menu.addSeparator();
            Quest q = model.tab().quests.get(hit);
            JMenuItem open = new JMenuItem("Открыть в просмотре");
            open.addActionListener(a -> { if (callbacks != null) callbacks.questDoubleClicked(hit); });
            menu.add(open);

            if (q != null && !q.parents.isEmpty()) {
                JPopupMenu sub = new JPopupMenu();
                List<JMenuItem> items = new ArrayList<>();
                for (Parent p : new ArrayList<>(q.parents)) {
                    JMenuItem mi = new JMenuItem("Убрать зависимость от «" + p.questID + "»");
                    mi.addActionListener(a -> removeLink(hit, p.questID));
                    items.add(mi);
                }
                for (JMenuItem mi : items) menu.add(mi);
                sub.setVisible(false);
            }
        }
        menu.show(this, e.getX(), e.getY());
    }

    private static String fmt(float f) {
        return f == Math.rint(f) ? String.valueOf((long) f) : String.valueOf(f);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
