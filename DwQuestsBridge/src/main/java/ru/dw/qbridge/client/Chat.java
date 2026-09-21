package ru.dw.qbridge.client;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import ru.dw.qbridge.DwqBridge;

/** Сообщения игроку; они же уходят в лог — чат при выгрузке легко пролистать. */
public final class Chat {

    private Chat() {}

    public static void say(String message) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText("§7[DwQB] §r" + message));
        }
        DwqBridge.LOG.info(strip(message));
    }

    public static void ok(String message) {
        say(EnumChatFormatting.GREEN + message);
    }

    public static void warn(String message) {
        say(EnumChatFormatting.YELLOW + message);
    }

    public static void error(String message) {
        say(EnumChatFormatting.RED + message);
    }

    private static String strip(String s) {
        return s.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
    }
}
