package ru.dw.qeditor.app;

import ru.dw.qeditor.gui.Popup;
import ru.dw.qeditor.gui.Theme;
import ru.dw.qeditor.gui.Ui;

import static ru.dw.qeditor.gl.Keys.GLFW_KEY_ESCAPE;

/**
 * Модальное окно: затемнение, рамка, заголовок, крестик и Esc.
 *
 * <p>Окон GLFW для диалогов не заводим: своё окно — это свой контекст, свой
 * цикл событий и мигание при открытии. Диалог здесь — просто верхний слой
 * того же кадра, который забирает себе ввод.
 */
public abstract class Dialog {

    protected String title;
    protected float width = 460;
    protected float height = 320;
    private boolean closed;
    private boolean closeOnEscape = true;
    /** Свой всплывающий список: тот, что у главного окна, лежит ниже диалога. */
    private final Popup popup = new Popup();

    protected Dialog(String title) {
        this.title = title;
    }

    protected Popup popup() { return popup; }

    public void close() { closed = true; }

    public boolean isClosed() { return closed; }

    protected void setCloseOnEscape(boolean v) { closeOnEscape = v; }

    /** Вызывается раз в кадр последним слоем. */
    public final void draw(Ui ui) {
        ui.beginOverlay();
        // раскрытый список забирает ввод у всего окна, включая крестик
        popup.blockIfOpen(ui);
        ui.rect(0, 0, ui.width(), ui.height(), Theme.SCRIM);

        // на узком экране (телефон) поля вокруг окна дороже, чем вид
        float margin = Math.min(ui.width(), ui.height()) < 600 ? 8 : 40;
        float w = Math.min(width, ui.width() - margin);
        float h = Math.min(height, ui.height() - margin);
        float x = Math.round((ui.width() - w) / 2);
        float y = Math.round((ui.height() - h) / 2);

        ui.roundRect(x + 2, y + 4, w, h, Theme.RADIUS, 0x70000000);
        ui.roundRectBorder(x, y, w, h, Theme.RADIUS, Theme.SURFACE, Theme.BORDER_STRONG, 1);

        float titleH = 40;
        ui.text(title, x + 16, y + (titleH - ui.font(Theme.FONT_LARGE, true).height()) / 2,
                Theme.TEXT, Theme.FONT_LARGE, true);
        // крестик узкой кнопке не влезал и подменялся многоточием
        if (ui.button(x + w - 40, y + 8, 32, 24, "✕", Ui.GHOST, true)) close();
        ui.hSeparator(x, y + titleH, w);

        content(ui, x + 16, y + titleH + 12, w - 32, h - titleH - 24);

        // Esc сначала закрывает раскрытый список, и только потом сам диалог
        boolean popupWasOpen = popup.isOpen();
        popup.draw(ui);
        // щелчок мимо окна не закрывает: слишком легко потерять набранное
        if (closeOnEscape && !popupWasOpen && ui.keyPressed(GLFW_KEY_ESCAPE)) close();
        ui.endOverlay();
    }

    /** Содержимое: координаты уже внутри рамки. */
    protected abstract void content(Ui ui, float x, float y, float w, float h);

    /** Ряд кнопок по правому краю снизу; возвращает номер нажатой либо −1. */
    protected int buttons(Ui ui, float x, float y, float w, String... labels) {
        // четыре кнопки по 110 в узкое окно не влезают — тогда ужимаем
        float bw = Math.min(110, (w + 8) / labels.length - 8);
        float bx = x + w - labels.length * (bw + 8) + 8;
        int result = -1;
        for (int i = 0; i < labels.length; i++) {
            int kind = i == labels.length - 1 ? Ui.PRIMARY : Ui.NORMAL;
            if (ui.button(bx, y, bw, Theme.ROW_LARGE, labels[i], kind, true)) result = i;
            bx += bw + 8;
        }
        return result;
    }
}
