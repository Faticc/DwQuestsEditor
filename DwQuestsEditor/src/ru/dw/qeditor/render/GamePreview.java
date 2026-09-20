package ru.dw.qeditor.render;

import ru.dw.qeditor.assets.ItemLook;
import ru.dw.qeditor.assets.McFont;
import ru.dw.qeditor.assets.ModAssets;
import ru.dw.qeditor.model.Defs;
import ru.dw.qeditor.model.Parent;
import ru.dw.qeditor.model.Quest;
import ru.dw.qeditor.model.QuestTab;
import ru.dw.qeditor.model.Task;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Порт игрового окна DwQuests (GuiStarAtlas + UIQuestBrowser + TabButtons +
 * QuestPanel) на Java2D. Рисует вкладку так, как её увидит игрок.
 *
 * <p>Прогресс игрока в редакторе неизвестен, поэтому он моделируется режимом
 * {@link Mode}: цвет узла и содержимое панели зависят от того, выполнен ли
 * квест, — в игре это решает PlayerData.
 */
public class GamePreview {

    /** Чем «заполнить» неизвестный прогресс игрока. */
    public enum Mode {
        COMPLETED("Все выполнены"),
        ACTIVE("Все активны"),
        MIXED("Смешанный");

        public final String ru;

        Mode(String ru) { this.ru = ru; }

        @Override public String toString() { return ru; }
    }

    private static final int NODE_SIZE = 24;
    private static final int CELL = 24;
    private static final int SCROLL_STEP = 40;

    private QuestTab tab;
    private Mode mode = Mode.COMPLETED;
    private float guiScale = 2f;

    /** Размер «виртуального» экрана в пикселях GUI. */
    private int screenW = 640;
    private int screenH = 400;

    private int tick;
    private float zoomFactor = 1f;
    private float guiMapX;
    private float guiMapY;
    private boolean mapInitialized;

    private float mouseX = -1000;
    private float mouseY = -1000;
    private boolean dragging;
    private float dragLastX;
    private float dragLastY;

    private String openQuestID;
    private int panelScroll;
    private int panelContentHeight;

    private final Map<String, Float> hoverScale = new HashMap<>();
    /** Экранные прямоугольники узлов последнего кадра — для попадания мышью. */
    private final Map<String, float[]> nodeHit = new HashMap<>();

    // раскладка (пересчитывается в layout())
    private int guiLeft, guiTop, guiWidth, guiHeight;
    private int bx, by, bw, bh;
    private int tabX, tabY, tabW, tabH;

    // карта
    private float mapLeftOffset, mapTopOffset, mapRightOffset, mapBottomOffset;
    private float guiMapLeft, guiMapTop, guiMapRight, guiMapBottom, guiMapWidth, guiMapHeight;

    // ------------------------------------------------------------------ состояние

    public void setTab(QuestTab tab) {
        boolean changed = this.tab != tab;
        this.tab = tab;
        if (changed) {
            hoverScale.clear();
            openQuestID = null;
            panelScroll = 0;
            mapInitialized = false;
        }
    }

    public QuestTab getTab() { return tab; }

    public Mode getMode() { return mode; }

    public void setMode(Mode mode) { this.mode = mode; }

    public float getGuiScale() { return guiScale; }

    public void setGuiScale(float guiScale) { this.guiScale = Math.max(1f, Math.min(4f, guiScale)); }

    public float getZoom() { return zoomFactor; }

    public String getOpenQuestID() { return openQuestID; }

    public void openQuest(String questID) {
        openQuestID = questID;
        panelScroll = 0;
    }

    public void closePanel() {
        openQuestID = null;
        panelScroll = 0;
    }

    public void tickAnimation() {
        tick++;
        McFont.get().tickObfuscation();
    }

    /** Размер окна просмотра в физических пикселях. */
    public void setViewport(int pixelWidth, int pixelHeight) {
        screenW = Math.max(120, (int) (pixelWidth / guiScale));
        screenH = Math.max(100, (int) (pixelHeight / guiScale));
    }

    /** Поставить карту так, чтобы содержимое вкладки оказалось по центру. */
    public void centerMap() {
        layout();
        calculateMap();
        guiMapX = (minX() * CELL + maxX() * CELL + CELL) / 2f - bw * zoomFactor / 2f;
        guiMapY = (minY() * CELL + maxY() * CELL + CELL) / 2f - bh * zoomFactor / 2f;
        clampMap();
        mapInitialized = true;
    }

    // ------------------------------------------------------------------ раскладка

    private void layout() {
        guiWidth = screenW - 20;
        guiHeight = screenH - 20;
        guiLeft = (screenW - guiWidth) / 2;
        guiTop = (screenH - guiHeight) / 2;

        bx = guiLeft + 170;
        by = guiTop + 20;
        bw = Math.max(40, guiWidth - 190);
        bh = Math.max(40, guiHeight - 40);

        tabX = guiLeft + 20;
        tabY = guiTop + 20;
        tabW = 150;
        tabH = Math.max(40, guiHeight - 40);
    }

    private float minX() { return tab == null ? 0 : tab.minX(); }

    private float minY() { return tab == null ? 0 : tab.minY(); }

    private float maxX() { return tab == null ? 0 : tab.maxX(); }

    private float maxY() { return tab == null ? 0 : tab.maxY(); }

    /** UIQuestBrowser.calculateMap */
    private void calculateMap() {
        float offsetX = minX() < 0 ? Math.abs(minX() * CELL) : 0f;
        float offsetY = minY() < 0 ? Math.abs(minY() * CELL) : 0f;
        mapLeftOffset = bw / 2f * zoomFactor + offsetX;
        mapTopOffset = bh / 2f * zoomFactor + offsetY;
        mapRightOffset = bw / 2f * zoomFactor - offsetX;
        mapBottomOffset = bh / 2f * zoomFactor - offsetY;
        guiMapLeft = minX() * CELL + offsetX - mapLeftOffset;
        guiMapTop = minY() * CELL + offsetY - mapTopOffset;
        guiMapRight = maxX() * CELL + CELL + offsetX + mapRightOffset;
        guiMapBottom = maxY() * CELL + CELL + offsetY + mapBottomOffset;
        guiMapWidth = guiMapRight - guiMapLeft;
        guiMapHeight = guiMapBottom - guiMapTop;
    }

    /** UIBrowser.dragElements — ограничение прокрутки карты. */
    private void clampMap() {
        if (guiMapWidth > bw * zoomFactor && guiMapHeight > bh) {
            if (guiMapX < guiMapLeft) guiMapX = guiMapLeft;
            if (guiMapY < guiMapTop) guiMapY = guiMapTop;
            if (guiMapX + bw * zoomFactor >= guiMapRight) guiMapX = guiMapRight - bw * zoomFactor;
            if (guiMapY + bh * zoomFactor >= guiMapBottom) guiMapY = guiMapBottom - bh * zoomFactor;
        } else {
            guiMapX = (guiMapWidth - bw * zoomFactor) / 2f - mapLeftOffset;
            guiMapY = (guiMapHeight - bh * zoomFactor) / 2f - mapTopOffset;
        }
    }

    // ------------------------------------------------------------------ отрисовка

    public void render(BufferedImage target) {
        setViewport(target.getWidth(), target.getHeight());
        layout();
        calculateMap();
        if (!mapInitialized) {
            guiMapX = (minX() * CELL + maxX() * CELL + CELL) / 2f - bw * zoomFactor / 2f;
            guiMapY = (minY() * CELL + maxY() * CELL + CELL) / 2f - bh * zoomFactor / 2f;
            mapInitialized = true;
        }
        clampMap();

        GuiPainter p = GuiPainter.forImage(target, guiScale);
        try {
            p.rectOpaque(0, 0, screenW, screenH, 0xFF0D0D12);
            p.guiBackground(guiLeft, guiTop, guiWidth, guiHeight);
            drawBrowser(p);
            drawTabButtons(p);
            if (openQuestID != null && tab != null && tab.quests.containsKey(openQuestID)) {
                drawQuestPanel(p, tab.quests.get(openQuestID));
            }
        } finally {
            p.dispose();
        }
    }

    private void drawBrowser(GuiPainter p) {
        nodeHit.clear();
        if (tab == null) return;

        p.push();
        p.clip(bx, by, bw, bh);
        drawMapBackground(p, 1f);

        p.push();
        p.translate(bx, by);
        p.scale(1f / zoomFactor);
        drawLinks(p);
        drawQuestButtons(p, false);
        drawQuestButtons(p, true);
        p.pop();
        p.pop();

        p.push();
        p.translate(bx, by);
        p.insideShadowRect(0, 0, bw, bh, 10);
        p.pop();

        drawScrollBars(p);
    }

    /** UIBrowser.drawBackground: фон вкладки + пять слоёв Stars с параллаксом. */
    private void drawMapBackground(GuiPainter p, float extraScale) {
        BufferedImage bg = ModAssets.get().tabBackground(tab == null ? null : tab.tabID);
        BufferedImage stars = ModAssets.get().stars();

        int textureWidth = 256;
        int textureHeight = 256;
        float backgroundZoom = 1.5f;
        float scaleFactor = 1f;
        float scaledWidth = textureWidth;
        float scaledHeight = textureHeight;
        float guiAspectRatio = (float) bw / bh;
        if (guiAspectRatio > 1f) {
            scaleFactor = bw / scaledWidth;
            scaledHeight = (float) Math.ceil(scaledHeight / guiAspectRatio);
        } else if (guiAspectRatio < 1f) {
            scaleFactor = bh / scaledHeight;
            scaledWidth = (float) Math.ceil(scaledWidth * guiAspectRatio);
        }
        float zoomedWidth = scaledWidth / backgroundZoom;
        float zoomedHeight = scaledHeight / backgroundZoom;

        float guiMapRatio = guiMapHeight == 0 ? 1f : guiMapWidth / guiMapHeight;
        float mapW = guiMapRatio < 1f ? guiMapWidth / guiMapRatio : guiMapWidth;
        float mapH = guiMapRatio > 1f ? guiMapHeight * guiMapRatio : guiMapHeight;
        float denomX = mapW - bw * zoomFactor;
        float denomY = mapH - bh * zoomFactor;
        float uBack = denomX == 0 ? 0 : (guiMapX + mapLeftOffset) / denomX * (textureWidth - zoomedWidth);
        float vBack = denomY == 0 ? 0 : (guiMapY + mapTopOffset) / denomY * (textureHeight - zoomedHeight);

        p.push();
        p.translate(bx, by);
        if (extraScale != 1f) p.scale(extraScale);
        p.scale(backgroundZoom);
        p.scale(scaleFactor);
        p.texturedModalRect(bg, 0, 0, uBack, vBack, zoomedWidth, zoomedHeight);
        float[] layers = {1.33f, 1.77f, 2.37f, 3.16f, 4.21f};
        for (float s : layers) {
            p.scale(0.75f);
            p.texturedModalRect(stars, 0, 0, uBack, vBack, zoomedWidth * s, zoomedHeight * s);
        }
        p.pop();
    }

    /** UIBrowser.drawForeground — полоски прокрутки по краям карты. */
    private void drawScrollBars(GuiPainter p) {
        int scrollColor = -11184811;
        if (guiMapWidth > 0) {
            float horizontalSize = bw * zoomFactor / guiMapWidth * bw;
            float n = normalize(guiMapX, guiMapLeft, guiMapRight - bw * zoomFactor);
            p.hLine(bx + (bw - horizontalSize) * n, by + bh - 1f, (int) horizontalSize, scrollColor);
        }
        if (guiMapHeight > 0) {
            float verticalSize = bh * zoomFactor / guiMapHeight * bh;
            float n = normalize(guiMapY, guiMapTop, guiMapBottom - bh * zoomFactor);
            p.vLine(bx + bw - 1f, by + (bh - verticalSize) * n, (int) verticalSize, scrollColor);
        }
    }

    private static float normalize(float v, float min, float max) {
        if (max - min == 0) return 0f;
        float n = (v - min) / (max - min);
        return n < 0 ? 0 : (n > 1 ? 1 : n);
    }

    // ------------------------------------------------------------------ узлы и связи

    private void drawQuestButtons(GuiPainter p, boolean foreground) {
        for (Quest q : tab.quests.values()) {
            float x = q.displayX * CELL - guiMapX;
            float y = q.displayY * CELL - guiMapY;
            float sf = scaleFactorOf(q);
            if (!(x >= -CELL * sf && y >= -CELL * sf
                    && x - 6f * sf <= bw * zoomFactor && y - 6f * sf <= bh * zoomFactor)) continue;

            boolean active = !questCompleted(q);
            if (!foreground) {
                Defs.Rarity rarity = active ? Defs.Rarity.COMMON : q.rarity;
                p.questStar(x, y, NODE_SIZE, NODE_SIZE, sf, Defs.Star.of(rarity),
                        active ? null : q.nodeType, q.iconItem, tick);
                nodeHit.put(q.questID, new float[]{x, y, sf});
            } else if (isHovered(q, x, y, sf)) {
                String text = q.displayNameOrID() + " [" + completedTaskCount(q) + "/" + q.tasksCount() + "]";
                int w = McFont.get().width(text) + 10;
                p.push();
                p.translate(x + 12f, y + 12f);
                p.rect(8f, -26f, w, 18f, 1140850688);
                p.text(text, 13, -21, active ? -1 : q.rarity.color, true);
                p.pop();
            }
        }
    }

    /** QuestButton.drawBackground — плавное увеличение узла под курсором. */
    private float scaleFactorOf(Quest q) {
        float cur = hoverScale.getOrDefault(q.questID, 1f);
        return cur * q.iconSize.scale;
    }

    private void updateHoverScales() {
        if (tab == null) return;
        for (Quest q : tab.quests.values()) {
            float cur = hoverScale.getOrDefault(q.questID, 1f);
            float x = q.displayX * CELL - guiMapX;
            float y = q.displayY * CELL - guiMapY;
            boolean hovered = openQuestID == null && isHovered(q, x, y, cur * q.iconSize.scale);
            cur += hovered ? 0.05f : -0.05f;
            cur = Math.max(1f, Math.min(1.25f, cur));
            hoverScale.put(q.questID, cur);
        }
    }

    /** QuestButton.mouseIsHovered в координатах карты. */
    private boolean isHovered(Quest q, float x, float y, float scaleFactor) {
        float mX = (mouseX - bx) * zoomFactor;
        float mY = (mouseY - by) * zoomFactor;
        if (mouseX < bx || mouseX > bx + bw || mouseY < by || mouseY > by + bh) return false;
        float radius = NODE_SIZE * scaleFactor / 2f;
        float dx = mX - (x + NODE_SIZE / 2f);
        float dy = mY - (y + NODE_SIZE / 2f);
        return dx * dx + dy * dy <= radius * radius;
    }

    private void drawLinks(GuiPainter p) {
        for (Quest q : tab.quests.values()) {
            if (q.parents.isEmpty()) continue;
            boolean childActive = !questCompleted(q);
            for (Parent parent : q.parents) {
                if (parent.parentType == Defs.ParentType.HIDDEN) continue;
                if (parent.tabID != null && !parent.tabID.isEmpty() && !parent.tabID.equals(tab.tabID)) continue;
                Quest pq = tab.quests.get(parent.questID);
                if (pq == null) continue;

                boolean lineShine = !childActive;
                if (parent.parentType == Defs.ParentType.FAKE) lineShine = lineShine && questCompleted(pq);

                float x1 = pq.displayX * CELL - guiMapX + 12f;
                float y1 = pq.displayY * CELL - guiMapY + 12f;
                float x2 = q.displayX * CELL - guiMapX + 12f;
                float y2 = q.displayY * CELL - guiMapY + 12f;
                int color = lineShine ? Defs.Star.of(q.rarity).mainColor : -13421773;
                int parentColor = lineShine ? Defs.Star.of(pq.rarity).mainColor : -5592406;
                LinkRenderer.draw(p, x1, y1, x2, y2, color, parentColor, lineShine,
                        parent.lineType, tick, 3.5f / zoomFactor);
            }
        }
    }

    // ------------------------------------------------------------------ левая колонка

    private void drawTabButtons(GuiPainter p) {
        int tabsCount = tab == null ? 0 : 1;
        int contentHeight = Math.max(210 + tabsCount * 30, tabH);

        p.push();
        p.clip(tabX, tabY, tabW + 14, tabH);

        int buttonsBackY = 180;
        p.rect(tabX, tabY, tabW, buttonsBackY, 570425344);
        p.rect(tabX, tabY + buttonsBackY, tabW, contentHeight - buttonsBackY, 1426063360);
        p.vLine(tabX + tabW - 1f, tabY, contentHeight, 1140850688);
        p.lineShadow(tabX, tabY + buttonsBackY, tabW, 5, 0);

        float centerX = tabX + tabW / 2f;
        int total = tab == null ? 0 : tab.quests.size();
        int completed = 0;
        if (tab != null) for (Quest q : tab.quests.values()) if (questCompleted(q)) completed++;

        if (tab != null) {
            p.textCentered(tab.tabID + ":", centerX, tabY + 15, -1, true);
            p.textCentered(completed + " / " + total, centerX, tabY + 30, -1, true);
        }
        progressBar(p, tabX + 10f, tabY + 45f, tabW - 20, 10, completed, total);

        p.textCentered("Общий прогресс:", centerX, tabY + 70, -1, true);
        p.textCentered(completed + " / " + total, centerX, tabY + 85, -1, true);
        progressBar(p, tabX + 10f, tabY + 100f, tabW - 20, 10, completed, total);

        button(p, tabX + 10f, tabY + 120f, tabW - 20, 20, "Сдать все квесты", "minecraft:paper", true, false);
        button(p, tabX + 10f, tabY + 150f, tabW - 20, 20, "Забрать все награды", "minecraft:hopper", true, false);

        if (tab != null) {
            button(p, tabX + 10f + 10f, tabY + 185f, tabW - 8, 20, tab.tabID, tab.iconItem, true, true);
        }

        p.pop();

        p.lineShadow(tabX, tabY, tabW, 5, 0);
        p.lineShadow(tabX, tabY, 5 + (contentHeight > tabH ? 3 : 0), tabH, -90);
        p.lineShadow(tabX, tabY + tabH - 5f, tabW, 5, 180);
    }

    /** UIProgressBar */
    private void progressBar(GuiPainter p, float x, float y, int width, int height, int progress, int max) {
        width = Math.max(width, 10);
        height = Math.max(height, 5);
        p.rect(x, y, width, height, 1140850688);
        p.hLine(x, y, width, 1140850688);
        p.hLine(x, y + height - 1f, width, 1157627903);
        p.vLine(x, y + 1f, height - 2, 1140850688);
        p.vLine(x + width - 1f, y + 1f, height - 2, 1157627903);

        if (max > 0) {
            float ix = x + 2f, iy = y + 2f;
            int iw = width - 4, ih = height - 4;
            int barLength = progress * iw / max;
            if (barLength > 0) {
                p.gradient(ix, iy, barLength, ih, -8847402, -5701768, -45);
                p.hLine(ix, iy, barLength, -1);
                p.hLine(ix, iy + ih - 1f, barLength, -2013265920);
                p.vLine(ix, iy + 1f, ih - 2, -1);
                p.vLine(ix + barLength - 1f, iy + 1f, ih - 2, -2013265920);
            }
        }
    }

    /** UIButton (+ вертикальные штрихи справа у кнопок вкладок). */
    private void button(GuiPainter p, float x, float y, int width, int height,
                        String text, String iconItem, boolean shadow, boolean tabStyle) {
        boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        float hoverAlpha = hovered ? 1f : 0f;

        if (shadow) p.gradient(x + 2f, y + height, width - 4, 3, -872415232, 0, 0);
        p.gradient(x, y, width, height, -12036765, -14077380, -45);
        if (hoverAlpha > 0) {
            p.gradient(x, y, width, height,
                    Fx.applyAlpha(-8847402, hoverAlpha), Fx.applyAlpha(-5701768, hoverAlpha), -45);
        }
        int colorLight = hovered ? -1 : 1442840575;
        int colorDark = -2013265920;
        p.hLine(x, y, width, colorLight);
        p.hLine(x, y + height - 1f, width, colorDark);
        p.vLine(x, y + 1f, height - 2, colorLight);
        p.vLine(x + width - 1f, y + 1f, height - 2, colorDark);
        if (tabStyle) {
            p.vLine(x + width, y + 1f, height - 2, -13421773);
            p.vLine(x + width + 1f, y + 2f, height - 4, -12303292);
        }

        int stackOffset = 0;
        if (iconItem != null && !iconItem.isEmpty()) {
            stackOffset = Math.max(0, (height - 16) / 2);
            p.item(iconItem, x + stackOffset, y + stackOffset);
        }
        if (text != null && !text.isEmpty()) {
            float centerX = x + (width - McFont.get().width(text)) / 2f;
            if (iconItem != null && !iconItem.isEmpty()) centerX += stackOffset + 8;
            float centerY = y + (height + 2 - McFont.FONT_HEIGHT) / 2f;
            int v = (int) ((1f - hoverAlpha) * 255f);
            p.text(text, (int) centerX, (int) centerY, v << 16 | v << 8 | v, false);
        }
    }

    // ------------------------------------------------------------------ панель квеста

    private int panelW() { return Math.min(400, bw - 20); }

    private int panelH() { return Math.min(400, bh - 20); }

    private int panelX() { return bx + (bw - panelW()) / 2; }

    private int panelY() { return by + (bh - panelH()) / 2; }

    private void drawQuestPanel(GuiPainter p, Quest quest) {
        int pw = panelW();
        int ph = panelH();
        int px = panelX();
        int py = panelY();
        Defs.Star star = Defs.Star.of(quest.rarity);

        // фон: тот же фон карты, увеличенный на 1.25 относительно центра панели
        p.push();
        p.clip(px, py, pw, ph);
        float s = 1.25f;
        p.translate(px + pw / 2f, py + ph / 2f);
        p.scale(s);
        p.translate(-(px + pw / 2f), -(py + ph / 2f));
        drawMapBackground(p, 1f);
        p.pop();

        PanelLayout pl = layoutPanel(quest, px, py, pw);
        panelContentHeight = pl.contentHeight;
        int maxScroll = Math.max(0, pl.contentHeight - ph);
        if (panelScroll > maxScroll) panelScroll = maxScroll;

        p.push();
        p.clip(px, py, pw, ph);
        p.translate(0, -panelScroll);

        pl.name.draw(p);
        pl.description.draw(p);
        pl.tasks.draw(p);
        pl.rewards.draw(p);

        // иконка квеста ×4 с обводкой цветом редкости
        float iconScale = 4f;
        float stackX = px + pw / 2f - 8f * iconScale;
        float stackY = py + 15f;
        p.itemWithOutline(quest.iconItem, stackX, stackY, iconScale, star.mainColor, 1);

        // градиентная линия под названием
        float lineY = py + 85f + pl.name.getHeight();
        int w = pw - 20;
        p.gradient(px + 10f + w / 2f, lineY, w / 2, 2, 0, star.mainColor, 90);
        p.gradient(px + 10f, lineY, w / 2, 2, 0, star.mainColor, -90);

        if (pl.endText != null) {
            pl.endLabel.draw(p);
        } else {
            button(p, pl.buttonX, pl.buttonY, pl.buttonWidth, 20, "Забрать награды", null, false, false);
        }
        p.pop();

        p.insideShadowRect(px, py, pw, ph, 180 << 24, 10);
    }

    private static final class PanelLayout {
        McLabel name;
        McLabel description;
        TasksBlock tasks;
        RewardsBlock rewards;
        McLabel endLabel;
        String endText;
        float buttonX, buttonY;
        int buttonWidth;
        int contentHeight;
    }

    /** QuestPanel.init() */
    private PanelLayout layoutPanel(Quest quest, int px, int py, int pw) {
        PanelLayout l = new PanelLayout();
        Defs.Star star = Defs.Star.of(quest.rarity);
        int contentHeight = 85;

        l.name = new McLabel(px + 10f, py, pw - 20);
        l.name.setFontScale(2f).setCentered(true).disableBackground();
        l.name.setText(quest.displayNameOrID());
        l.name.setY(py + contentHeight);
        contentHeight += l.name.getHeight() + 15;

        l.description = new McLabel(px + 10f, py, pw - 20);
        l.description.setFontScale(1f);
        l.description.setText(quest.description);
        l.description.setY(py + contentHeight);
        l.description.setLabelName("Описание");
        l.description.setLabelNameColor(star.mainColor);
        contentHeight += l.description.getHeight() + 10;

        l.tasks = new TasksBlock(px + 10f, py + contentHeight, pw - 20, quest);
        l.tasks.setLabelName(quest.tasksType.guiTitle(), star.mainColor);
        contentHeight += l.tasks.getHeight() + 10;

        l.rewards = new RewardsBlock(px + 10f, py + contentHeight, pw - 20, quest, questRewarded(quest));
        l.rewards.setLabelName("Награды", star.mainColor);
        contentHeight += l.rewards.getHeight() + 10;

        l.buttonWidth = Math.min(200, pw - 20);
        l.buttonX = px + (pw - l.buttonWidth) / 2f;
        l.buttonY = py + contentHeight;

        l.endLabel = new McLabel(l.buttonX, py + contentHeight, l.buttonWidth, 6);
        l.endLabel.setCentered(true);

        boolean completed = questCompleted(quest);
        boolean rewarded = questRewarded(quest);
        int endHeight;
        if (completed && rewarded) {
            l.endText = "§aКвест завершён";
            l.endLabel.setText(l.endText);
            l.endLabel.setY(py + contentHeight);
            endHeight = l.endLabel.getHeight();
        } else if (completed) {
            l.endText = null;
            endHeight = 20;
        } else {
            l.endText = "Выполнено задач: " + completedTaskCount(quest) + "/" + quest.tasksCount();
            l.endLabel.setText(l.endText);
            l.endLabel.setY(py + contentHeight);
            endHeight = l.endLabel.getHeight();
        }
        contentHeight += endHeight + 20;
        l.contentHeight = contentHeight - (py - py);
        // contentHeight у мода отсчитывается от верха панели
        l.contentHeight = contentHeight;
        return l;
    }

    // ------------------------------------------------------------------ блок задач

    /** Порт ru.sky.dw.quests.client.gui.elements.Tasks */
    private final class TasksBlock {
        private final float x;
        private final float y;
        private final int width;
        private final int padding = 10;
        private final Quest quest;
        private String labelName;
        private int labelNamePadding = 5;
        private int labelNameColor = -1;
        private int height;

        TasksBlock(float x, float y, int width, Quest quest) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.quest = quest;
            updateHeight();
        }

        void setLabelName(String name, int color) {
            this.labelName = name;
            this.labelNameColor = color;
            updateHeight();
        }

        private boolean hasLabelName() { return labelName != null && !labelName.isEmpty(); }

        void updateHeight() {
            int h = padding * 2;
            if (hasLabelName()) h += labelNamePadding * 2 + McFont.FONT_HEIGHT + 1;
            h += quest.tasks.size() * 20;
            height = h;
        }

        int getHeight() { return height; }

        void draw(GuiPainter p) {
            drawBackground(p);
            drawForeground(p);
        }

        private void drawBackground(GuiPainter p) {
            if (hasLabelName()) {
                int w = McFont.get().width(labelName) + labelNamePadding * 2;
                int h = labelNamePadding * 2 + McFont.FONT_HEIGHT;
                p.rect(x + 5f, y, w, h, -2013265920);
                p.hLine(x + 5f, y + h - 1f, w, -2013265920);
                p.rect(x, y + h, width, height - 1 - h, 1426063360);
            } else {
                p.rect(x, y, width, height - 1, 1426063360);
            }
        }

        private void drawForeground(GuiPainter p) {
            int startX = (int) x + padding;
            int startY = (int) y + padding;
            if (hasLabelName()) {
                startY += padding + McFont.FONT_HEIGHT + 1;
                p.text(labelName, (int) x + labelNamePadding + 5, (int) y + labelNamePadding, labelNameColor, true);
            }

            boolean questDone = questCompleted(quest);
            int index = 0;
            for (Map.Entry<Integer, Task> e : quest.tasks.entrySet()) {
                int taskID = e.getKey();
                Task task = e.getValue();
                boolean done = taskCompleted(quest, taskID);
                boolean parentsDone = parentsCompleted(quest, task);

                String colorPrefix = done ? "§a" : (questDone ? "§8" : "");
                String countPostfix = task.type == Defs.TaskType.TASK_CONFIRM
                        ? "" : " (" + taskProgress(quest, taskID, task) + "/" + task.requiredCount + ")";
                String text = colorPrefix + task.description(ItemLook.name(task.forgeName)) + countPostfix;

                float slotY = startY + 1 + index * 20;
                if (parentsDone) {
                    p.rect(startX - 1, slotY, 18f, 18f, 1711276032);
                    drawTaskIcon(p, task, startX, startY + 2 + index * 20);
                } else {
                    drawTaskIcon(p, task, startX, startY + 2 + index * 20);
                    p.rect(startX - 1, slotY, 18f, 18f, 1711276032);
                }
                p.rect(startX + 19, slotY, width - 38, 18f, 1140850688);
                if (done) checkmark(p, startX + 4, startY + 6 + index * 20);

                int offsetX = 0;
                if (task.type.hasAction()) {
                    String action = task.type.buttonText();
                    if (!done && parentsDone) {
                        int buttonWidth = McFont.get().width(action) + 20;
                        button(p, startX + 21, startY + 3 + taskID * 20, buttonWidth, 15, action, null, false, false);
                        offsetX += buttonWidth + 2;
                    } else {
                        if (task.type == Defs.TaskType.ITEM_PASS) action = action + ":";
                        text = colorPrefix + action + " " + task.description(ItemLook.name(task.forgeName)) + countPostfix;
                    }
                }

                int color = parentsDone ? -1 : -11184811;
                p.text(text, startX + 25 + offsetX, startY + 7 + index * 20, color, true);
                index++;
            }
        }
    }

    private void drawTaskIcon(GuiPainter p, Task task, float x, float y) {
        switch (task.type.kind) {
            case KILL: {
                BufferedImage icon = ModAssets.get().killTaskIcon();
                if (icon != null) {
                    p.push();
                    p.translate(x, y);
                    p.scale(0.5f);
                    p.texturedRect(icon, 0, 0, 0, 0, 32, 32, 32, 32);
                    p.pop();
                }
                break;
            }
            case CONFIRM:
                p.item("DwQuests:StarAtlas", x, y);
                break;
            case TIME:
                p.item("minecraft:clock", x, y);
                break;
            default:
                p.item(task.forgeName == null || task.forgeName.isEmpty() ? "minecraft:bedrock" : task.forgeName, x, y);
        }
    }

    /** GuiElement.CHECKMARK (UIElements.png, u=50 v=0 24×24) в масштабе 0.5 и зелёным. */
    private void checkmark(GuiPainter p, float x, float y) {
        BufferedImage ui = ModAssets.get().uiElements();
        if (ui == null) return;
        p.push();
        p.translate(x, y);
        p.scale(0.5f);
        p.texturedRect(tintGreen(ui), 0, 0, 50, 0, 24, 24, 1024, 1024);
        p.pop();
    }

    private static BufferedImage greenUi;

    private static BufferedImage tintGreen(BufferedImage src) {
        if (greenUi != null) return greenUi;
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int p = src.getRGB(x, y);
                out.setRGB(x, y, (p & 0xFF00FF00) | ((p >>> 24) << 24));
            }
        }
        greenUi = out;
        return out;
    }

    // ------------------------------------------------------------------ блок наград

    /** Порт ru.sky.dw.quests.client.gui.elements.Rewards */
    private final class RewardsBlock {
        private final float x;
        private final float y;
        private final int width;
        private final int padding = 10;
        private final List<String> rewards = new ArrayList<>();
        private final boolean isRewarded;
        private String labelName;
        private int labelNamePadding = 5;
        private int labelNameColor = -1;
        private int height;

        RewardsBlock(float x, float y, int width, Quest quest, boolean isRewarded) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.isRewarded = isRewarded;
            for (String r : quest.rewards) {
                if (r == null || r.isEmpty()) continue;
                if (r.startsWith("command:")) continue;
                rewards.add(r);
            }
            updateHeight();
        }

        void setLabelName(String name, int color) {
            this.labelName = name;
            this.labelNameColor = color;
            updateHeight();
        }

        private boolean hasLabelName() { return labelName != null && !labelName.isEmpty(); }

        void updateHeight() {
            int h = padding * 2;
            if (hasLabelName()) h += labelNamePadding * 2 + McFont.FONT_HEIGHT + 1;
            h += rewards.isEmpty() ? 7 : rewards.size() * 20;
            height = h;
        }

        int getHeight() { return height; }

        void draw(GuiPainter p) {
            drawBackground(p);
            drawForeground(p);
        }

        private void drawBackground(GuiPainter p) {
            if (hasLabelName()) {
                int w = McFont.get().width(labelName) + labelNamePadding * 2;
                int h = labelNamePadding * 2 + McFont.FONT_HEIGHT;
                p.rect(x + 5f, y, w, h, -2013265920);
                p.hLine(x + 5f, y + h - 1f, w, -2013265920);
                p.rect(x, y + h, width, height - 1 - h, 1426063360);
            } else {
                p.rect(x, y, width, height - 1, 1426063360);
            }
        }

        private void drawForeground(GuiPainter p) {
            int startX = (int) x + padding;
            int startY = (int) y + padding;
            if (hasLabelName()) {
                startY += padding + McFont.FONT_HEIGHT + 1;
                p.text(labelName, (int) x + labelNamePadding + 5, (int) y + labelNamePadding, labelNameColor, true);
            }

            if (rewards.isEmpty()) {
                p.text("Награды отсутствуют", startX, startY - 1, -11184811, true);
                return;
            }
            int index = 0;
            for (String reward : rewards) {
                float rowY = startY + 1 + index * 20;
                p.rect(startX - 1, rowY, 18f, 18f, 1140850688);
                p.rect(startX + 19, rowY, width - 38, 18f, 1140850688);
                p.item(reward, startX, startY + 2 + index * 20);
                String colorPrefix = isRewarded ? "§a" : "";
                String text = colorPrefix + ItemLook.name(reward) + " - " + ItemLook.count(reward) + " шт.";
                p.text(text, startX + 25, startY + 6 + index * 20, -1, true);
                if (isRewarded) checkmark(p, startX + 4, startY + 6 + index * 20);
                index++;
            }
        }
    }

    // ------------------------------------------------------------------ модель прогресса

    private static int hash(String s) {
        int h = 0;
        if (s != null) for (int i = 0; i < s.length(); i++) h = h * 31 + s.charAt(i);
        return Math.abs(h);
    }

    public boolean questCompleted(Quest q) {
        switch (mode) {
            case COMPLETED: return true;
            case ACTIVE: return false;
            default: return hash(q.questID) % 3 != 0;
        }
    }

    public boolean questRewarded(Quest q) {
        if (!questCompleted(q)) return false;
        return mode == Mode.COMPLETED || hash(q.questID) % 3 == 1;
    }

    private boolean taskCompleted(Quest q, int taskID) {
        if (questCompleted(q)) {
            if (q.tasksType != Defs.TasksType.ONE_OF) return true;
            return !q.tasks.isEmpty() && q.tasks.keySet().iterator().next() == taskID;
        }
        return mode == Mode.MIXED && hash(q.questID + "#" + taskID) % 3 == 0;
    }

    private int taskProgress(Quest q, int taskID, Task t) {
        if (taskCompleted(q, taskID)) return t.requiredCount;
        if (mode != Mode.MIXED || t.requiredCount <= 0) return 0;
        return hash(q.questID + "/" + taskID) % t.requiredCount;
    }

    private boolean parentsCompleted(Quest q, Task t) {
        for (int pid : t.parents) if (!taskCompleted(q, pid)) return false;
        return true;
    }

    private int completedTaskCount(Quest q) {
        int n = 0;
        for (int id : q.tasks.keySet()) if (taskCompleted(q, id)) n++;
        return Math.min(n, q.tasksCount());
    }

    // ------------------------------------------------------------------ ввод

    /** Координаты — виртуальные пиксели GUI. */
    public void onMouseMove(float vx, float vy) {
        mouseX = vx;
        mouseY = vy;
        updateHoverScales();
    }

    public void onMouseExit() {
        mouseX = -1000;
        mouseY = -1000;
    }

    /** @return true, если событие обработано превью (нужна перерисовка). */
    public boolean onMousePress(float vx, float vy, int button) {
        mouseX = vx;
        mouseY = vy;
        if (tab == null) return false;

        if (openQuestID != null) {
            int px = panelX(), py = panelY(), pw = panelW(), ph = panelH();
            if (vx < px || vx >= px + pw || vy < py || vy >= py + ph) {
                closePanel();
                return true;
            }
            return false;
        }

        if (button == 1) {
            String hit = questAt(vx, vy);
            if (hit != null) {
                openQuest(hit);
                return true;
            }
        }
        if (vx >= bx && vx < bx + bw && vy >= by && vy < by + bh) {
            dragging = true;
            dragLastX = vx;
            dragLastY = vy;
        }
        return false;
    }

    public void onMouseDrag(float vx, float vy) {
        mouseX = vx;
        mouseY = vy;
        if (!dragging) {
            updateHoverScales();
            return;
        }
        guiMapX -= (vx - dragLastX) * zoomFactor;
        guiMapY -= (vy - dragLastY) * zoomFactor;
        dragLastX = vx;
        dragLastY = vy;
        clampMap();
    }

    public void onMouseRelease() { dragging = false; }

    /** rotation &lt; 0 — колесо от себя (в игре это приближение). */
    public void onWheel(float vx, float vy, int rotation) {
        mouseX = vx;
        mouseY = vy;
        if (openQuestID != null) {
            int maxScroll = Math.max(0, panelContentHeight - panelH());
            panelScroll = Math.max(0, Math.min(maxScroll, panelScroll + rotation * SCROLL_STEP));
            return;
        }
        if (vx < bx || vx >= bx + bw || vy < by || vy >= by + bh) return;

        float old = zoomFactor;
        zoomFactor += rotation > 0 ? 0.25f : -0.25f;
        zoomFactor = Math.max(1f, Math.min(2f, zoomFactor));
        if (zoomFactor != old) {
            guiMapX -= (zoomFactor * bw - old * bw) * 0.5f;
            guiMapY -= (zoomFactor * bh - old * bh) * 0.5f;
            calculateMap();
            clampMap();
        }
    }

    /** questID узла под курсором либо null. */
    public String questAt(float vx, float vy) {
        if (tab == null) return null;
        if (vx < bx || vx >= bx + bw || vy < by || vy >= by + bh) return null;
        float mX = (vx - bx) * zoomFactor;
        float mY = (vy - by) * zoomFactor;
        String best = null;
        float bestDist = Float.MAX_VALUE;
        for (Map.Entry<String, float[]> e : nodeHit.entrySet()) {
            float[] v = e.getValue();
            float radius = NODE_SIZE * v[2] / 2f;
            float dx = mX - (v[0] + NODE_SIZE / 2f);
            float dy = mY - (v[1] + NODE_SIZE / 2f);
            float d = dx * dx + dy * dy;
            if (d <= radius * radius && d < bestDist) {
                bestDist = d;
                best = e.getKey();
            }
        }
        return best;
    }
}
