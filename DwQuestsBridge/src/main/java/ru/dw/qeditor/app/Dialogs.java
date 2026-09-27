package ru.dw.qeditor.app;

import ru.dw.qeditor.gui.Theme;
import ru.dw.qeditor.gui.Ui;
import ru.dw.qeditor.gui.Widgets;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import static ru.dw.qeditor.gl.Keys.GLFW_KEY_ENTER;
import static ru.dw.qeditor.gl.Keys.GLFW_KEY_KP_ENTER;

/** Готовые модальные окна: сообщение, вопрос, ввод строки, длинный текст. */
public final class Dialogs {

    private Dialogs() { }

    /** Сообщение с одной кнопкой. */
    public static Dialog message(String title, String text) {
        return new Message(title, text, new String[]{"Понятно"}, null);
    }

    /**
     * Вопрос с несколькими ответами.
     *
     * @param onResult номер нажатой кнопки; −1, если окно просто закрыли
     */
    public static Dialog confirm(String title, String text, String[] buttons, IntConsumer onResult) {
        return new Message(title, text, buttons, onResult);
    }

    private static final class Message extends Dialog {
        private final String text;
        private final String[] buttons;
        private final IntConsumer onResult;

        Message(String title, String text, String[] buttons, IntConsumer onResult) {
            super(title);
            this.text = text;
            this.buttons = buttons;
            this.onResult = onResult;
            this.width = 520;
        }

        @Override
        protected void content(Ui ui, float x, float y, float w, float h) {
            List<String> lines = ui.wrap(text, w, Theme.FONT, false);
            float ly = y;
            for (String l : lines) {
                ui.text(l, x, ly, Theme.TEXT);
                ly += ui.lineHeight();
            }
            height = 40 + 24 + Math.max(lines.size() * ui.lineHeight(), 40) + Theme.ROW_LARGE + 16;

            int pressed = buttons(ui, x, y + h - Theme.ROW_LARGE, w, buttons);
            boolean enter = ui.keyPressed(GLFW_KEY_ENTER) || ui.keyPressed(GLFW_KEY_KP_ENTER);
            if (enter) pressed = buttons.length - 1;
            if (pressed >= 0) {
                if (onResult != null) onResult.accept(pressed);
                close();
            }
        }
    }

    /** Ввод одной строки. */
    public static Dialog prompt(String title, String label, String initial, Consumer<String> onOk) {
        return new Prompt(title, label, initial, onOk);
    }

    private static final class Prompt extends Dialog {
        private final String label;
        private String value;
        private final Consumer<String> onOk;
        private boolean focusDone;

        Prompt(String title, String label, String initial, Consumer<String> onOk) {
            super(title);
            this.label = label;
            this.value = initial == null ? "" : initial;
            this.onOk = onOk;
            this.width = 520;
            this.height = 190;
        }

        @Override
        protected void content(Ui ui, float x, float y, float w, float h) {
            Object id = this + "value";
            if (!focusDone) {
                ui.focus(id);
                focusDone = true;
            }
            Widgets.caption(ui, label, x, y);
            value = ui.field(id, x, y + 18, w, Theme.ROW_LARGE, value, null);

            boolean enter = ui.keyPressed(GLFW_KEY_ENTER) || ui.keyPressed(GLFW_KEY_KP_ENTER);
            int pressed = buttons(ui, x, y + h - Theme.ROW_LARGE, w, "Отмена", "Готово");
            if (pressed == 1 || enter) {
                onOk.accept(value);
                close();
            } else if (pressed == 0) {
                close();
            }
        }
    }

    /** Длинный текст с прокруткой — JSON вкладки, справка, диагностика. */
    public static Dialog text(String title, String body) {
        return new TextView(title, body, null);
    }

    /** То же, но с кнопкой «Копировать». */
    public static Dialog text(String title, String body, Runnable onCopy) {
        return new TextView(title, body, onCopy);
    }

    private static final class TextView extends Dialog {
        private final String body;
        private final Runnable onCopy;

        TextView(String title, String body, Runnable onCopy) {
            super(title);
            this.body = body;
            this.onCopy = onCopy;
            this.width = 860;
            this.height = 640;
        }

        @Override
        protected void content(Ui ui, float x, float y, float w, float h) {
            float bottom = onCopy == null ? 0 : Theme.ROW_LARGE + 10;
            float areaH = h - bottom;
            List<String> lines = ui.wrap(body, w - 24, Theme.FONT_SMALL, false);
            float lineH = ui.font(Theme.FONT_SMALL, false).height();
            float content = lines.size() * lineH + 16;

            ui.roundRectBorder(x, y, w, areaH, Theme.RADIUS_SMALL, Theme.SURFACE_2, Theme.BORDER, 1);
            Object id = this + "scroll";
            ui.beginScroll(id, x + 1, y + 1, w - 2, areaH - 2, w - 2, content);
            float ly = 8;
            float from = ui.scrollY(id);
            for (String l : lines) {
                if (ly > from - lineH && ly < from + areaH)
                    ui.text(l, 10, ly, Theme.TEXT, Theme.FONT_SMALL, false);
                ly += lineH;
            }
            ui.endScroll(id, x + 1, y + 1, w - 2, areaH - 2, w - 2, content);

            if (onCopy != null && buttons(ui, x, y + h - Theme.ROW_LARGE, w, "Копировать") == 0) onCopy.run();
        }
    }
}
