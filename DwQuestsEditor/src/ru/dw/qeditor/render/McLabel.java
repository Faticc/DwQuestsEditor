package ru.dw.qeditor.render;

import ru.dw.qeditor.assets.McFont;
import ru.dw.qeditor.assets.ModAssets;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Порт ru.sky.dw.core.client.api.gui.elements.Label — блок текста с
 * необязательным заголовком, поддержкой {@code <BR>}, {@code <RI>} и
 * {@code <IMG>mod:textures/…:u:v:w:h[:scale]</IMG>}.
 *
 * <p>Высота считается ровно как в моде (включая его особенности: сырые записи
 * {@code <IMG>} тоже попадают в подсчёт), иначе разъедется вся панель квеста.
 */
public class McLabel {

    /** Label.getFontHeight() = FontRenderer.FONT_HEIGHT + 1. */
    public static final int FONT_HEIGHT = McFont.FONT_HEIGHT + 1;

    private final List<String> inserts = new ArrayList<>();
    private int imagesCount;

    private float x;
    private float y;
    private int width;
    private int height;
    private final int padding;

    private boolean centered;
    private int textColor = -1;
    private String labelName;
    private int labelNamePadding = 5;
    private int labelNameColor = -1;
    private boolean background = true;
    private float fontScale = 1f;

    public McLabel(float x, float y, int width) {
        this(x, y, width, 10);
    }

    public McLabel(float x, float y, int width, int padding) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.padding = padding;
        this.height = padding * 2;
    }

    // -------------------------------------------------------------- свойства

    public float getX() { return x; }

    public float getY() { return y; }

    public void setY(float y) { this.y = y; }

    public void setX(float x) { this.x = x; }

    public int getWidth() { return width; }

    public void setWidth(int width) { this.width = width; }

    public int getHeight() { return height; }

    public McLabel setFontScale(float fontScale) { this.fontScale = fontScale; return this; }

    public McLabel setCentered(boolean centered) { this.centered = centered; return this; }

    public McLabel setTextColor(int color) { this.textColor = color; return this; }

    public McLabel disableBackground() { this.background = false; return this; }

    public McLabel setLabelName(String labelName) { return setLabelName(labelName, 5); }

    public McLabel setLabelName(String labelName, int labelNamePadding) {
        this.labelName = labelName;
        this.labelNamePadding = labelNamePadding;
        if (hasLabelName()) updateHeight();
        return this;
    }

    public McLabel setLabelNameColor(int color) { this.labelNameColor = color; return this; }

    public boolean hasLabelName() { return labelName != null && !labelName.isEmpty(); }

    public boolean isEmpty() { return inserts.isEmpty(); }

    // -------------------------------------------------------------- текст

    public void setText(String text) {
        int w = width - 2 * padding;
        inserts.clear();
        inserts.addAll(listFormattedStringToWidth(text == null ? "" : text, w));
        updateHeight();
    }

    private List<String> listFormattedStringToWidth(String text, int width) {
        text = text.replaceFirst("<BR>", "   ");
        text = text.replaceAll("<BR>", "\n\n   ");
        text = text.replaceAll("<RI>", "   ");
        imagesCount = 0;
        boolean foundImage = true;

        while (foundImage) {
            foundImage = false;
            if (text.contains("<IMG>")) {
                int i1 = text.indexOf("<IMG>");
                int i2 = text.indexOf("</IMG>");
                if (i1 >= 0 && i2 > i1) {
                    String s = text.substring(i1, i2) + "</IMG>";
                    inserts.add(s);
                    text = text.replaceFirst(Pattern.quote(s), Matcher.quoteReplacement("\n@" + imagesCount + "@\n"));
                    imagesCount++;
                    foundImage = true;
                }
            }
        }
        return McFont.get().wrap(text, (int) (width / fontScale));
    }

    public void updateHeight() {
        int h = padding * 2;
        for (String line : inserts) {
            Img img = parseImageRef(line);
            if (img != null) {
                h += (int) (img.h * img.scale) + 10;
                h -= FONT_HEIGHT * 2;
            }
            h += (int) (FONT_HEIGHT * fontScale);
        }
        if (hasLabelName()) h += labelNamePadding * 2 + FONT_HEIGHT;
        height = h - 2;
    }

    // -------------------------------------------------------------- отрисовка

    public void draw(GuiPainter p) {
        drawBackground(p);
        drawForeground(p);
    }

    public void drawBackground(GuiPainter p) {
        if (!background) return;
        if (hasLabelName()) {
            int w = McFont.get().width(labelName) + labelNamePadding * 2;
            int h = labelNamePadding * 2 + FONT_HEIGHT - 1;
            p.rect(x + 5f, y, w, h, -2013265920);
            p.hLine(x + 5f, y + h - 1f, w, -2013265920);
            p.rect(x, y + h, width, height - 1 - h, 1426063360);
        } else {
            p.rect(x, y, width, height - 1, 1426063360);
        }
    }

    public void drawForeground(GuiPainter p) {
        if (inserts.isEmpty()) return;
        float startX = (int) x + padding;
        float startY = (int) y + padding;
        if (hasLabelName()) {
            startY += padding + FONT_HEIGHT - 1;
            p.text(labelName, (int) x + labelNamePadding + 5, (int) y + labelNamePadding, labelNameColor, true);
        }

        p.push();
        p.translate(startX, startY);
        p.scale(fontScale);
        renderSplitString(p, 0, 0, (int) ((width - padding * 2) / fontScale), textColor);
        p.pop();
    }

    private void renderSplitString(GuiPainter p, int x, int y, int width, int color) {
        for (int i = imagesCount; i < inserts.size(); i++) {
            String line = inserts.get(i);
            Img img = parseImageRef(line);
            if (img != null) {
                BufferedImage tex = ModAssets.get().modTexture(img.modID, img.texture);
                if (tex != null) {
                    p.push();
                    p.translate(x + width / 2f - img.w / 2f * img.scale, y + 4f);
                    p.scale(img.scale);
                    p.texturedRect(tex, 0, 0, img.u, img.v, img.w, img.h, img.w, img.h);
                    p.pop();
                }
                y = (int) (y + (img.h * img.scale + 10f - FONT_HEIGHT));
            } else if (centered) {
                p.textCentered(line, x + width / 2f, y, color, true);
            } else {
                p.text(line, x, y, color, true);
            }
            y += FONT_HEIGHT;
        }
    }

    /**
     * Строка-ссылка вида {@code @N@} → разобранный тег {@code <IMG>} из inserts.
     * Возвращает null, если это обычная строка текста.
     */
    private Img parseImageRef(String line) {
        if (line == null || !line.contains("@")) return null;
        int i1 = line.indexOf('@');
        int i2 = line.indexOf('@', i1 + 1);
        if (i1 < 0 || i2 <= i1) return null;
        int index;
        try {
            index = Integer.parseInt(line.substring(i1 + 1, i2));
        } catch (NumberFormatException e) {
            return null;
        }
        if (index < 0 || index >= inserts.size()) return null;
        String s2 = inserts.get(index);
        if (s2 == null || !s2.contains("<IMG>")) return null;

        String cont = s2.replace("<IMG>", "").replace("</IMG>", "");
        String[] d = cont.split(":");
        if (d.length < 6 || d.length > 7) return null;
        try {
            Img img = new Img();
            img.modID = d[0];
            img.texture = d[1];
            img.u = Integer.parseInt(d[2]);
            img.v = Integer.parseInt(d[3]);
            img.w = Integer.parseInt(d[4]);
            img.h = Integer.parseInt(d[5]);
            img.scale = d.length == 7 ? Float.parseFloat(d[6]) : 1f;
            int maxWidth = width - padding * 2;
            if (img.w * img.scale > maxWidth) img.scale = (float) maxWidth / img.w;
            return img;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final class Img {
        String modID;
        String texture;
        int u, v, w, h;
        float scale;
    }
}
