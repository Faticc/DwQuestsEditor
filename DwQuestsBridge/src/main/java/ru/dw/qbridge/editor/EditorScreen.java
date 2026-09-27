package ru.dw.qbridge.editor;

import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * Экран редактора — такой же экран игры, как книга квестов: поверх мира, в
 * окне Minecraft, мир и сеть при этом живут дальше.
 *
 * <p>Весь интерфейс рисует редактор сам ({@code ru.dw.qeditor.app.App}), экран
 * только передаёт ему кадры и ввод. Стандартную обработку клавиш экрана
 * (Esc закрывает сразу) не используем: Esc сначала достаётся диалогам, меню
 * и полям редактора, а закрытие спрашивает про несохранённое.
 */
public final class EditorScreen extends GuiScreen {

    @Override
    public void initGui() {
        // автоповтор нужен полям ввода и стрелкам на поле
        Keyboard.enableRepeatEvents(true);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void handleMouseInput() {
        EditorHost.get().window().mouseEvent();
    }

    @Override
    public void handleKeyboardInput() {
        EditorHost.get().window().keyEvent();
    }

    @Override
    protected void keyTyped(char c, int key) {
        // всё разбирает редактор — см. handleKeyboardInput
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        EditorHost.get().frame();
    }
}
