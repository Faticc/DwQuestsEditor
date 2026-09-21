package ru.dw.qbridge.quests;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import ru.dw.qbridge.client.Chat;

/**
 * Заливка вкладки обратно в игру — штатным путём мода: буфер обмена и
 * {@code /dwq tab paste}.
 *
 * <p>Пакет с готовой вкладкой мы серверу не шлём: команда {@code /dwq} требует
 * второго уровня прав, и правильно, что заливка проходит эту проверку. Здесь
 * мод только избавляет от ручного копирования файла в буфер: сервер попросит
 * буфер сам, когда команда пройдёт по правам.
 */
public final class TabUpload {

    /** Ограничение сервера на размер вкладки ({@code CSPEditTab}). */
    public static final int MAX_BYTES = 10 * 1024 * 1024;

    private TabUpload() {}

    public static void paste(String tabID, String json) {
        if (json == null || json.trim().isEmpty()) throw new IllegalArgumentException("пустая вкладка");
        if (json.getBytes().length > MAX_BYTES) throw new IllegalArgumentException("вкладка больше 10 МБ");
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) throw new IllegalStateException("игрок не в мире");

        GuiScreen.setClipboardString(json);
        // Подчёркивания вместо пробелов — так команда мода разбирает имя вкладки.
        mc.thePlayer.sendChatMessage("/dwq tab paste " + tabID.replace(' ', '_'));
        Chat.say("вкладка §b" + tabID + "§r отправлена на сервер (" + json.length() + " симв.)");
    }

    /** Только положить в буфер — если заливать будет человек руками. */
    public static void toClipboard(String json) {
        GuiScreen.setClipboardString(json);
    }
}
