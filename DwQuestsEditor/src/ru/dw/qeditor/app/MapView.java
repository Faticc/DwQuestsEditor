package ru.dw.qeditor.app;

import ru.dw.qeditor.assets.McFont;
import ru.dw.qeditor.gl.GlWindow;
import ru.dw.qeditor.gl.Input;
import ru.dw.qeditor.gui.Popup;
import ru.dw.qeditor.gui.Ui;
import ru.dw.qeditor.model.Defs;
import ru.dw.qeditor.model.Parent;
import ru.dw.qeditor.model.Quest;
import ru.dw.qeditor.model.QuestTab;
import ru.dw.qeditor.render.GuiPainter;
import ru.dw.qeditor.render.LinkRenderer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_BRACKET;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_BRACKET;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_UP;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_MIDDLE;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;

/**
 * Поле редактора: сетка, перетаскивание узлов с мультивыделением, рисование и
 * удаление зависимостей, зум и панорама, контекстное меню.
 *
 * <p>Узлы рисуются тем же {@link GuiPainter}, что и просмотр, поэтому вид
 * совпадает с игрой; анимация выключена (кадр нода фиксирован).
 *
 * <p>Порт {@code ui/EditorCanvas} на свой тулкит: вся логика мыши та же,
 * изменился только источник событий — вместо подписки на {@code MouseAdapter}
 * состояние жестов сверяется с {@link Input} раз в кадр. Из-за этого жест
 * («тяну узлы», «тяну рамку») приходится держать в поле: между кадрами
 * события уже не связаны друг с другом ничем, кроме нашей памяти.
 */
public final class MapView {

    /** Шаг сетки мода: одна клетка displayX = 24 пикселя GUI. */
    public static final int CELL = 24;
    private static final int STATIC_TICK = 7;

    /** Сколько подписей выделения показывать, прежде чем они превратятся в кашу. */
    private static final int MAX_SELECTION_LABELS = 12;
    /** Подписи рисуются постоянным размером на экране, чтобы не мылились от зума. */
    private static final float LABEL_DEVICE_SCALE = 2f;

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

    /** Шаг сетки в клетках; дробный шаг нужен для плотных веток (0.5, 0.25). */
    private float gridSize = 1;
    private float gridOffsetX;
    private float gridOffsetY;
    private boolean showGrid = true;
    private boolean snap = true;
    /** Подписи у всех узлов сразу — по умолчанию нет, только курсор и выделение. */
    private boolean showNames;
    /** Узел под курсором: его подпись видно всегда. */
    private String hovered;
    private boolean showLinks = true;
    // HIDDEN-связи в игре не рисуются вовсе, но в редакторе их надо видеть —
    // как в веб-редакторе, тонкой линией, чтобы не забивали карту
    private boolean showHiddenLinks = true;

    /** Прямоугольник поля в прошлом кадре — по нему считают «вписать» и центровку. */
    private float areaX, areaY, areaW = 800, areaH = 600;
    /** Границы видимой области в координатах мира — считаются раз на кадр. */
    private float viewLeft, viewTop, viewRight, viewBottom;

    /**
     * Насколько надо увести курсор, чтобы нажатие стало перетаскиванием.
     * Без этого щелчок по квесту уже сдвигал его: первый же кадр притягивал
     * узел к сетке.
     */
    private static final float DRAG_SLOP = 4f;

    // жесты
    private boolean dragNodes;
    /** Кнопку нажали на узле, но порог сдвига ещё не пройден. */
    private boolean dragPending;
    private boolean panning;
    private boolean rubber;
    private String linkFrom;
    /** Точка нажатия — в координатах поля (логических точках). */
    private float pressX, pressY;
    private float pressWorldX, pressWorldY;
    private float lastWorldX, lastWorldY;
    private float rubberX, rubberY, rubberW, rubberH;
    private String dragBeforeJson;
    private final Map<String, float[]> dragOrigin = new HashMap<>();

    /** Поворот группы за ручку над рамкой выделения. */
    private GroupRotate rotation;
    /** Угол от центра до точки нажатия — от него отсчитывается поворот. */
    private double rotStartAngle;
    private double rotDegrees;

    /** Ручка поворота висит над рамкой на столько точек экрана. */
    private static final float HANDLE_GAP = 22f;
    private static final float HANDLE_R = 6f;
    /** С Shift угол прилипает к этому шагу. */
    private static final float ROTATE_SNAP = 15f;

    /** Что делает каждый пункт открытого контекстного меню. */
    private final Object menuId = new Object();
    private List<Runnable> menuActions = new ArrayList<>();

    /** Первый кадр ещё не знает размера поля — «вписать» ждёт его. */
    private boolean fitPending = true;

    public MapView(EditorModel model) {
        this.model = model;
    }

    public void setCallbacks(Callbacks callbacks) { this.callbacks = callbacks; }

    // ------------------------------------------------------------------ настройки

    public float getGridSize() { return gridSize; }

    /** Шаг мельче 1/16 клетки бессмыслен: в игре координата — это 24 пикселя. */
    public void setGridSize(float gridSize) {
        this.gridSize = Math.max(0.0625f, Math.min(16f, gridSize));
    }

    public void setGridOffset(float x, float y) { gridOffsetX = x; gridOffsetY = y; }

    public float getGridOffsetX() { return gridOffsetX; }

    public float getGridOffsetY() { return gridOffsetY; }

    public boolean isShowGrid() { return showGrid; }

    public void setShowGrid(boolean v) { showGrid = v; }

    public boolean isSnap() { return snap; }

    public void setSnap(boolean v) { snap = v; }

    public boolean isShowNames() { return showNames; }

    public void setShowNames(boolean v) { showNames = v; }

    public boolean isShowLinks() { return showLinks; }

    public void setShowLinks(boolean v) { showLinks = v; }

    public boolean isShowHiddenLinks() { return showHiddenLinks; }

    public void setShowHiddenLinks(boolean v) { showHiddenLinks = v; }

    public float getZoom() { return zoom; }

    public void setZoom(float z) { zoom = Math.max(0.25f, Math.min(4f, z)); }

    /** Показать всю вкладку целиком (выполнится в ближайшем кадре). */
    public void fitToContent() { fitPending = true; }

    private void doFit() {
        QuestTab tab = model.tab();
        if (tab.quests.isEmpty()) {
            camX = -areaW / 2f;
            camY = -areaH / 2f;
            zoom = 1f;
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
        float zx = areaW / Math.max(1f, maxX - minX);
        float zy = areaH / Math.max(1f, maxY - minY);
        zoom = Math.max(0.25f, Math.min(2f, Math.min(zx, zy)));
        camX = (minX + maxX) / 2f - areaW / (2f * zoom);
        camY = (minY + maxY) / 2f - areaH / (2f * zoom);
    }

    /** Прямоугольник поля в прошлом кадре {x, y, w, h} — нужен проверкам вида. */
    public float[] area() { return new float[]{areaX, areaY, areaW, areaH}; }

    /** Поставить камеру в заданную точку мира — нужно проверке отрисовки. */
    public void panTo(float worldX, float worldY) {
        camX = worldX;
        camY = worldY;
        fitPending = false;
    }

    public void centerOn(String questID) {
        Quest q = model.tab().quests.get(questID);
        if (q == null) return;
        camX = q.displayX * CELL + CELL / 2f - areaW / (2f * zoom);
        camY = q.displayY * CELL + CELL / 2f - areaH / (2f * zoom);
        fitPending = false;
    }

    // ------------------------------------------------------------------ координаты

    /** Точка поля (логические точки от левого верхнего угла) → координата мира. */
    private float worldX(float localX) { return localX / zoom + camX; }

    private float worldY(float localY) { return localY / zoom + camY; }

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

    // ------------------------------------------------------------------ кадр

    public void draw(Ui ui, Popup popup, float x, float y, float w, float h) {
        areaX = x;
        areaY = y;
        areaW = w;
        areaH = h;
        if (fitPending) {
            doFit();
            fitPending = false;
        }

        handleInput(ui, popup, x, y, w, h);

        viewLeft = camX;
        viewTop = camY;
        viewRight = camX + w / zoom;
        viewBottom = camY + h / zoom;

        GuiPainter p = ui.painter();
        ui.pushClip(x, y, w, h);
        // поле закрашивается целиком: под ним ничего нет, а фон мода тёмный
        ui.rect(x, y, w, h, 0xFF12171E);

        p.push();
        p.translate(x, y);
        p.scale(zoom);
        p.translate(-camX, -camY);
        if (showGrid) drawGrid(p);
        if (showLinks) drawLinks(p, ui.scale());
        drawNodes(p);
        drawPendingLink(p);
        p.pop();

        drawRotateGizmo(ui, x, y);

        if (rubber && (rubberW > 2 || rubberH > 2)) {
            ui.rect(x + rubberX, y + rubberY, rubberW, rubberH, 0x3C3D7EB8);
            ui.border(x + rubberX, y + rubberY, rubberW, rubberH, 0xFF7AC3F5);
        }
        ui.popClip();

        if (hovered != null && !dragNodes && !panning && !rubber && linkFrom == null && rotation == null)
            ui.tooltip(tooltipFor(hovered));
    }

    private void drawGrid(GuiPainter p) {
        float step = CELL * gridSize;
        if (step * zoom < 5) return;
        float left = viewLeft, top = viewTop, right = viewRight, bottom = viewBottom;

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
            p.rect(i * step + ox, top, px, bottom - top, 0x14FFFFFF);
        }
        for (int j = minJ; j <= maxJ; j++) {
            if (Math.floorMod(j, major) != 0) continue;
            p.rect(left, j * step + oy, right - left, px, 0x14FFFFFF);
        }

        // точки мелкой сетки рисуем, только когда их немного и они различимы
        long dots = (long) (maxI - minI + 1) * (maxJ - minJ + 1);
        if (step * zoom >= 12 && dots <= 6000) {
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

    private void drawLinks(GuiPainter p, float uiScale) {
        QuestTab tab = model.tab();
        // в игре толщина линии задаётся в пикселях экрана, а не карты
        float width = 1.6f * uiScale;
        boolean gradient = LinkRenderer.gradientWorthIt(zoom);

        for (Quest q : tab.quests.values()) {
            if (q.parents.isEmpty()) continue;
            for (Parent parent : q.parents) {
                if (parent.questID == null) continue;
                if (parent.tabID != null && !parent.tabID.isEmpty()
                        && !parent.tabID.equals(tab.tabID)) continue;
                boolean hidden = parent.parentType == Defs.ParentType.HIDDEN;
                if (hidden && !showHiddenLinks) continue;
                Quest pq = tab.quests.get(parent.questID);
                if (pq == null) continue;

                float x1 = pq.displayX * CELL + 12f;
                float y1 = pq.displayY * CELL + 12f;
                float x2 = q.displayX * CELL + 12f;
                float y2 = q.displayY * CELL + 12f;
                if (!linkVisible(x1, y1, x2, y2)) continue;

                int color = Defs.Star.of(q.rarity).mainColor;
                int parentColor = Defs.Star.of(pq.rarity).mainColor;
                // FAKE в игре рисуется обычной линией, отличается только логикой;
                // HIDDEN в игре не рисуется, здесь показываем его тонким и тусклым
                if (hidden) color = parentColor = 0x46525F;
                LinkRenderer.line(p, x1, y1, x2, y2, color, parentColor, false,
                        parent.lineType, 0, hidden ? width * 0.7f : width, gradient);
            }
        }
    }

    /** Пересекает ли связь видимую область (с запасом на выгиб дуги). */
    private boolean linkVisible(float x1, float y1, float x2, float y2) {
        float dist = Math.max(Math.abs(x1 - x2), Math.abs(y1 - y2));
        float margin = dist * 0.5f + CELL;
        float left = Math.min(x1, x2) - margin;
        float right = Math.max(x1, x2) + margin;
        float top = Math.min(y1, y2) - margin;
        float bottom = Math.max(y1, y2) + margin;
        return right >= viewLeft && left <= viewRight && bottom >= viewTop && top <= viewBottom;
    }

    private void drawNodes(GuiPainter p) {
        QuestTab tab = model.tab();
        for (Quest q : tab.quests.values()) {
            float x = q.displayX * CELL;
            float y = q.displayY * CELL;
            // ореол узла выходит далеко за клетку — берём запас
            float margin = 40f * q.iconSize.scale;
            if (x + CELL + margin < viewLeft || x - margin > viewRight
                    || y + CELL + margin < viewTop || y - margin > viewBottom) continue;

            p.questStar(x, y, CELL, CELL, q.iconSize.scale, Defs.Star.of(q.rarity),
                    q.nodeType, q.iconItem, STATIC_TICK);

            if (model.selection().contains(q.questID)) {
                // кольцо выделения — скруглённый прямоугольник радиусом в полвысоты
                float r = CELL * q.iconSize.scale / 2f + 5;
                float cx = x + CELL / 2f, cy = y + CELL / 2f;
                p.roundRect(cx - r, cy - r, r * 2, r * 2, r, 0, 0xFF4FA8CC,
                        Math.max(1f, 1.5f) / zoom);
            }
        }
        drawLabels(p);
    }

    /**
     * Подписи узлов. Раньше они писались у всех сразу и в координатах карты —
     * при отдалении шрифт сжимался в кашу. Теперь подпись видно только там, где
     * она нужна (курсор, выделение), и рисуется она постоянным размером на
     * экране, поэтому читается на любом зуме.
     */
    private void drawLabels(GuiPainter p) {
        QuestTab tab = model.tab();
        LinkedHashSet<String> show = new LinkedHashSet<>();
        if (showNames) {
            show.addAll(tab.quests.keySet());
        } else if (model.selection().size() <= MAX_SELECTION_LABELS) {
            show.addAll(model.selection());
        }
        if (hovered != null) show.add(hovered);
        if (show.isEmpty()) return;

        for (String id : show) {
            Quest q = tab.quests.get(id);
            if (q == null) continue;
            float x = q.displayX * CELL;
            float y = q.displayY * CELL;
            if (x + CELL < viewLeft || x > viewRight || y + CELL < viewTop || y > viewBottom) continue;

            boolean selected = model.selection().contains(id);
            String name = q.displayNameOrID();
            int w = McFont.get().width(name);

            p.push();
            // компенсируем зум: внутри этой матрицы пиксель шрифта постоянного размера
            p.translate(x + CELL / 2f, y + CELL * q.iconSize.scale / 2f + 6f / zoom);
            p.scale(LABEL_DEVICE_SCALE / zoom);
            p.rect(-w / 2f - 3, -1, w + 6, McFont.FONT_HEIGHT + 4, 0xC8101418);
            p.textCentered(name, 0, 1, selected ? 0xFF7FD4F5 : 0xFFE2E8EF, false);
            p.pop();
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
                Defs.LineType.DEFAULT, 0, 2f / zoom);
    }

    // ------------------------------------------------------------------ мышь

    private void handleInput(Ui ui, Popup popup, float x, float y, float w, float h) {
        Input in = ui.in();
        boolean hot = ui.over(x, y, w, h);
        float lx = ui.mx() - x;
        float ly = ui.my() - y;
        float wx = worldX(lx);
        float wy = worldY(ly);

        takeMenuPick(popup);

        // начатый жест доводится до конца, даже если курсор ушёл за край поля
        if (panning) {
            if (!in.middleDown() && !in.leftDown()) {
                panning = false;
            } else {
                camX -= in.mouseDX / ui.scale() / zoom;
                camY -= in.mouseDY / ui.scale() / zoom;
                ui.cursor(GlWindow.CURSOR_MOVE);
                return;
            }
        }
        if (linkFrom != null) {
            lastWorldX = wx;
            lastWorldY = wy;
            if (!in.leftDown()) {
                String target = questAt(wx, wy);
                if (target != null && !target.equals(linkFrom)) addLink(target, linkFrom);
                linkFrom = null;
            }
            return;
        }
        if (rotation != null) {
            if (ui.keyPressed(GLFW_KEY_ESCAPE)) {
                // отмена: фигура встаёт как была, в историю ничего не пишется
                rotation.apply(model.tab(), 0);
                rotation = null;
                model.commitDrag(dragBeforeJson, "Поворот квестов");
                dragBeforeJson = null;
                return;
            }
            // на кадре отпускания угол не пересчитывается: Shift, отпущенный
            // чуть раньше кнопки, иначе сбросил бы привязку к 15°
            if (in.leftDown()) rotateTo(lx, ly, in.shift());
            else {
                rotation = null;
                if (dragBeforeJson != null) model.commitDrag(dragBeforeJson, "Поворот квестов");
                dragBeforeJson = null;
            }
            ui.cursor(GlWindow.CURSOR_MOVE);
            return;
        }
        if (rubber) {
            rubberX = Math.min(pressX, lx);
            rubberY = Math.min(pressY, ly);
            rubberW = Math.abs(lx - pressX);
            rubberH = Math.abs(ly - pressY);
            if (!in.leftDown()) {
                rubber = false;
                if (rubberW > 2 || rubberH > 2) {
                    float x1 = worldX(rubberX), y1 = worldY(rubberY);
                    float x2 = worldX(rubberX + rubberW), y2 = worldY(rubberY + rubberH);
                    for (Quest q : model.tab().quests.values()) {
                        float cx = q.displayX * CELL + CELL / 2f;
                        float cy = q.displayY * CELL + CELL / 2f;
                        if (cx >= x1 && cx <= x2 && cy >= y1 && cy <= y2) model.addSelect(q.questID);
                    }
                }
            }
            return;
        }
        if (dragPending) {
            if (!in.leftDown()) {
                dragPending = false;
            } else if (Math.abs(lx - pressX) > DRAG_SLOP || Math.abs(ly - pressY) > DRAG_SLOP) {
                dragPending = false;
                dragNodes = true;
                // снимок делается здесь, а не при нажатии: на тысяче квестов
                // сериализация вкладки на каждый щелчок заметна
                dragBeforeJson = model.tab().toJsonString();
                dragOrigin.clear();
                for (String id : model.selection()) {
                    Quest q = model.tab().quests.get(id);
                    if (q != null) dragOrigin.put(id, new float[]{q.displayX, q.displayY});
                }
            } else {
                ui.cursor(GlWindow.CURSOR_HAND);
                return;
            }
        }
        if (dragNodes) {
            dragTo(wx, wy);
            if (!in.leftDown()) {
                dragNodes = false;
                if (dragBeforeJson != null) model.commitDrag(dragBeforeJson, "Перемещение квестов");
                dragBeforeJson = null;
            }
            ui.cursor(GlWindow.CURSOR_MOVE);
            return;
        }

        float[] handle = rotateHandle();
        boolean overHandle = hot && handle != null
                && sq(lx - handle[0]) + sq(ly - handle[1]) <= sq(HANDLE_R + 3);
        String hit = hot && !overHandle ? questAt(wx, wy) : null;
        if (overHandle) ui.cursor(GlWindow.CURSOR_HAND);
        if (hot) {
            lastWorldX = wx;
            lastWorldY = wy;
            if (hit != null) ui.cursor(GlWindow.CURSOR_HAND);
        }
        if (!Objects.equals(hit, hovered)) hovered = hit;

        if (hot && in.scrollY != 0) {
            float factor = in.scrollY > 0 ? 1.1f : 1f / 1.1f;
            float next = Math.max(0.25f, Math.min(4f, zoom * factor));
            if (next != zoom) {
                zoom = next;
                camX = wx - lx / zoom;
                camY = wy - ly / zoom;
            }
            in.consumeScroll();
        }

        if (hot && in.doubleClick() && hit != null) {
            if (callbacks != null) callbacks.questDoubleClicked(hit);
            return;
        }

        if (hot && (in.pressed(GLFW_MOUSE_BUTTON_MIDDLE) || (in.leftPressed() && in.alt()))) {
            panning = true;
            return;
        }

        if (hot && in.pressed(GLFW_MOUSE_BUTTON_RIGHT)) {
            pressWorldX = wx;
            pressWorldY = wy;
            if (in.shift()) {
                String[] link = linkAt(wx, wy);
                if (link != null) {
                    removeLink(link[0], link[1]);
                    return;
                }
            }
            openMenu(ui, popup, hit);
            return;
        }

        if (hot && in.pressed(GLFW_MOUSE_BUTTON_LEFT)) {
            pressX = lx;
            pressY = ly;
            pressWorldX = wx;
            pressWorldY = wy;

            if (overHandle) {
                startRotation(lx, ly);
                return;
            }
            if (in.shift() && hit != null) {
                linkFrom = hit;
                return;
            }
            if (hit != null) {
                if (in.ctrl()) model.toggleSelect(hit);
                else if (!model.selection().contains(hit)) model.select(hit);

                // сам сдвиг начнётся, только если курсор уведут: щелчок по
                // квесту должен его выделять, а не двигать
                if (model.selection().contains(hit)) dragPending = true;
            } else {
                if (!in.ctrl()) model.clearSelection();
                rubber = true;
                rubberX = lx;
                rubberY = ly;
                rubberW = 0;
                rubberH = 0;
            }
            return;
        }

        // клавиши достаются полю, только когда их не забрало поле ввода
        if (ui.inputBlocked() || ui.anyFocus() || ui.keyboardCaptured()) return;
        if (ui.keyPressed(GLFW_KEY_DELETE) && callbacks != null) callbacks.requestDelete();
        if (ui.keyPressed(GLFW_KEY_LEFT)) nudge(-1, 0);
        if (ui.keyPressed(GLFW_KEY_RIGHT)) nudge(1, 0);
        if (ui.keyPressed(GLFW_KEY_UP)) nudge(0, -1);
        if (ui.keyPressed(GLFW_KEY_DOWN)) nudge(0, 1);
        if (ui.keyPressed(GLFW_KEY_LEFT_BRACKET)) rotateSelection(in.shift() ? -1 : -ROTATE_SNAP);
        if (ui.keyPressed(GLFW_KEY_RIGHT_BRACKET)) rotateSelection(in.shift() ? 1 : ROTATE_SNAP);
    }

    // ------------------------------------------------------------------ поворот

    private static float sq(float v) { return v * v; }

    private float toLocalX(float worldPx) { return (worldPx - camX) * zoom; }

    private float toLocalY(float worldPx) { return (worldPx - camY) * zoom; }

    /**
     * Рамка выделения и ручка поворота в точках поля:
     * {ручкаX, ручкаY, левый, верхний, правый, нижний}; null — вертеть нечего.
     */
    private float[] rotateHandle() {
        if (model.selection().size() < 2) return null;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        int n = 0;
        for (String id : model.selection()) {
            Quest q = model.tab().quests.get(id);
            if (q == null) continue;
            float r = CELL * q.iconSize.scale / 2f + 5;
            float cx = q.displayX * CELL + CELL / 2f, cy = q.displayY * CELL + CELL / 2f;
            minX = Math.min(minX, cx - r);
            minY = Math.min(minY, cy - r);
            maxX = Math.max(maxX, cx + r);
            maxY = Math.max(maxY, cy + r);
            n++;
        }
        if (n < 2) return null;
        float l = toLocalX(minX), t = toLocalY(minY), r = toLocalX(maxX), b = toLocalY(maxY);
        // большая группа не влезает в экран — ручка прижимается к краю поля,
        // иначе до неё не дотянуться; угол всё равно считается от точки нажатия
        float m = HANDLE_R + 6;
        float hx = Math.max(m, Math.min(areaW - m, (l + r) / 2f));
        float hy = Math.max(m, Math.min(areaH - m, t - HANDLE_GAP));
        return new float[]{hx, hy, l, t, r, b};
    }

    private void startRotation(float lx, float ly) {
        rotation = new GroupRotate(model.tab(), model.selection());
        if (rotation.isEmpty()) { rotation = null; return; }
        dragBeforeJson = model.tab().toJsonString();
        rotStartAngle = Math.atan2(ly - pivotLocalY(), lx - pivotLocalX());
        rotDegrees = 0;
    }

    private float pivotLocalX() { return toLocalX(rotation.pivotX * CELL + CELL / 2f); }

    private float pivotLocalY() { return toLocalY(rotation.pivotY * CELL + CELL / 2f); }

    private void rotateTo(float lx, float ly, boolean snapAngle) {
        double a = Math.atan2(ly - pivotLocalY(), lx - pivotLocalX());
        double deg = Math.toDegrees(a - rotStartAngle);
        deg = ((deg % 360) + 540) % 360 - 180;      // в пределах −180…180
        if (snapAngle) deg = Math.round(deg / ROTATE_SNAP) * ROTATE_SNAP;
        rotDegrees = deg;
        if (rotation.apply(model.tab(), deg)) model.touch();
    }

    /** Поворот выделения на заданный угол одной правкой — клавиши, меню, панель. */
    public void rotateSelection(double degrees) {
        if (model.selection().size() < 2) return;
        GroupRotate r = new GroupRotate(model.tab(), model.selection());
        model.edit("Поворот квестов", () -> r.apply(model.tab(), degrees));
    }

    private void drawRotateGizmo(Ui ui, float x, float y) {
        if (rotation != null) {
            float px = x + pivotLocalX(), py = y + pivotLocalY();
            ui.line(px - 6, py, px + 6, py, 1.5f, 0xFFFFD166);
            ui.line(px, py - 6, px, py + 6, 1.5f, 0xFFFFD166);
            float mx = ui.mx(), my = ui.my();
            ui.line(px, py, mx, my, 1f, 0x99FFD166);
            String label = String.format(java.util.Locale.ROOT, "%.1f°", rotDegrees);
            float tw = ui.textWidth(label);
            ui.roundRect(mx + 12, my + 10, tw + 10, ui.lineHeight() + 4, 3, 0xE0101418);
            ui.text(label, mx + 17, my + 12, 0xFFFFD166);
            return;
        }
        if (dragNodes || rubber) return;
        float[] h = rotateHandle();
        if (h == null) return;
        ui.border(x + h[2], y + h[3], h[4] - h[2], h[5] - h[3], 0x667AC3F5);
        ui.line(x + h[0], y + h[3], x + h[0], y + h[1] + HANDLE_R, 1f, 0x997AC3F5);
        boolean over = sq(ui.mx() - x - h[0]) + sq(ui.my() - y - h[1]) <= sq(HANDLE_R + 3);
        ui.roundRect(x + h[0] - HANDLE_R, y + h[1] - HANDLE_R, HANDLE_R * 2, HANDLE_R * 2, HANDLE_R,
                over ? 0xFFFFD166 : 0xFF7AC3F5);
    }

    private void dragTo(float wx, float wy) {
        float dxCells = (wx - pressWorldX) / CELL;
        float dyCells = (wy - pressWorldY) / CELL;
        boolean moved = false;
        for (Map.Entry<String, float[]> en : dragOrigin.entrySet()) {
            Quest q = model.tab().quests.get(en.getKey());
            if (q == null) continue;
            float nx = en.getValue()[0] + dxCells;
            float ny = en.getValue()[1] + dyCells;
            if (snap) {
                nx = Math.round((nx - gridOffsetX) / gridSize) * (float) gridSize + gridOffsetX;
                ny = Math.round((ny - gridOffsetY) / gridSize) * (float) gridSize + gridOffsetY;
            }
            if (nx != q.displayX || ny != q.displayY) moved = true;
            q.displayX = nx;
            q.displayY = ny;
        }
        if (moved) model.touch();
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
    }

    private String tooltipFor(String id) {
        Quest q = model.tab().quests.get(id);
        if (q == null) return null;
        return q.displayNameOrID() + "\n"
                + "id: " + id + "\n"
                + "(" + fmt(q.displayX) + ", " + fmt(q.displayY) + ")\n"
                + q.rarity.ru + ", задач: " + q.tasks.size() + ", наград: " + q.rewards.size();
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
    }

    // ------------------------------------------------------------------ меню

    private void openMenu(Ui ui, Popup popup, String hit) {
        float cellX = snapCell(pressWorldX, gridOffsetX);
        float cellY = snapCell(pressWorldY, gridOffsetY);
        if (hit != null && !model.selection().contains(hit)) model.select(hit);
        String count = model.selection().size() > 1 ? " (" + model.selection().size() + ")" : "";

        List<Popup.Item> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        items.add(Popup.Item.of("Добавить квест здесь"));
        actions.add(() -> { if (callbacks != null) callbacks.requestAddQuest(cellX, cellY); });

        if (hit != null) {
            items.add(Popup.Item.of("Копировать квест" + count));
            actions.add(() -> { if (callbacks != null) callbacks.requestCopy(); });
            items.add(Popup.Item.of("Удалить квест" + count));
            actions.add(() -> { if (callbacks != null) callbacks.requestDelete(); });
        }

        items.add(Popup.Item.of("Вставить квест здесь"));
        actions.add(() -> { if (callbacks != null) callbacks.requestPaste(cellX, cellY); });

        if (model.selection().size() > 1) {
            items.add(Popup.Item.line());
            actions.add(null);
            items.add(Popup.Item.of("Повернуть на 90° по часовой"));
            actions.add(() -> rotateSelection(90));
            items.add(Popup.Item.of("Повернуть на 90° против часовой"));
            actions.add(() -> rotateSelection(-90));
            items.add(Popup.Item.of("Повернуть на 180°"));
            actions.add(() -> rotateSelection(180));
        }

        if (hit != null) {
            items.add(Popup.Item.line());
            actions.add(null);
            items.add(Popup.Item.of("Открыть в просмотре"));
            actions.add(() -> { if (callbacks != null) callbacks.questDoubleClicked(hit); });

            Quest q = model.tab().quests.get(hit);
            if (q != null && !q.parents.isEmpty()) {
                items.add(Popup.Item.line());
                actions.add(null);
                for (Parent parent : new ArrayList<>(q.parents)) {
                    String parentID = parent.questID;
                    items.add(Popup.Item.of("Убрать зависимость от «" + parentID + "»"));
                    actions.add(() -> removeLink(hit, parentID));
                }
            }
        }

        menuActions = actions;
        popup.open(menuId, ui.screenMx(), ui.screenMy(), items, 220);
    }

    private void takeMenuPick(Popup popup) {
        int picked = popup.takePick(menuId);
        if (picked >= 0 && picked < menuActions.size()) {
            Runnable r = menuActions.get(picked);
            if (r != null) r.run();
        }
    }

    private static String fmt(float f) {
        return f == Math.rint(f) ? String.valueOf((long) f) : String.valueOf(f);
    }
}
