package ru.dw.qbridge.command;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.item.ItemStack;
import ru.dw.qbridge.DwqBridge;
import ru.dw.qbridge.bridge.BridgeServer;
import ru.dw.qbridge.client.Chat;
import ru.dw.qbridge.client.ClientTicker;
import ru.dw.qbridge.export.IconRenderer;
import ru.dw.qbridge.export.Names;
import ru.dw.qbridge.export.Stacks;
import ru.dw.qbridge.quests.Quests;
import ru.dw.qbridge.quests.TabUpload;
import ru.dw.qbridge.task.ExportTasks;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

/**
 * Клиентская команда {@code /dwqb} — то же, что умеет мост, только руками.
 * Проверок прав у неё нет и быть не может: сервер об этой команде не знает.
 */
public class CommandDwqb extends CommandBase {

    private static final String[] SUB = {"help", "icons", "assets", "tabs", "all", "icon", "paste", "bridge", "stop", "status"};

    @Override
    public String getCommandName() {
        return "dwqb";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/dwqb help";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            DwqBridge.LOG.error("команда /dwqb упала", t);
            Chat.error(String.valueOf(t.getMessage() == null ? t : t.getMessage()));
        }
    }

    private void run(String[] args) throws Exception {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase();
        String[] rest = args.length == 0 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);

        if (sub.equals("help")) {
            help();
        } else if (sub.equals("icons")) {
            String scope = rest.length > 0 ? rest[0] : "all";
            boolean force = has(rest, "--force");
            ExportTasks.startIcons(scope, !force);
        } else if (sub.equals("assets")) {
            Chat.say("выгружаю текстуры и шрифт…");
            Chat.ok("ресурсы: " + ExportTasks.exportAssets(ExportTasks.defaultMods()));
        } else if (sub.equals("tabs")) {
            ExportTasks.startTabs(ExportTasks.mode(rest.length > 0 ? rest[0] : null));
        } else if (sub.equals("all")) {
            Chat.say("выгружаю текстуры и шрифт…");
            Chat.ok("ресурсы: " + ExportTasks.exportAssets(ExportTasks.defaultMods()));
            ExportTasks.startIcons("all", !has(rest, "--force"));
            Chat.say("вкладки пойдут следом — как только закончатся иконки (/dwqb tabs)");
        } else if (sub.equals("icon")) {
            icon(rest);
        } else if (sub.equals("paste")) {
            paste(rest);
        } else if (sub.equals("bridge")) {
            bridge(rest);
        } else if (sub.equals("stop")) {
            ClientTicker.get().stop();
        } else if (sub.equals("status")) {
            status();
        } else {
            help();
        }
    }

    private void help() {
        Chat.say("§b/dwqb icons [all|quests|registry] [--force] §7— иконки предметов (с NBT)");
        Chat.say("§b/dwqb assets §7— текстуры модов и шрифт");
        Chat.say("§b/dwqb tabs [auto|raw|local] §7— вкладки квестов в файлы");
        Chat.say("§b/dwqb all §7— ресурсы и иконки разом");
        Chat.say("§b/dwqb icon <mod:name[:meta][{nbt}]> §7— дорисовать одну иконку");
        Chat.say("§b/dwqb paste <вкладка> [файл] §7— залить вкладку в игру (нужны права на /dwq)");
        Chat.say("§b/dwqb bridge [start|stop] §7— мост с редактором");
        Chat.say("§b/dwqb stop §7— прервать текущую выгрузку");
        Chat.say("§7всё складывается в " + DwqBridge.outDir.getAbsolutePath());
    }

    private void status() {
        String job = ClientTicker.get().busyName();
        Chat.say(job == null ? "ничего не идёт" : job + ": " + (int) (ClientTicker.get().fraction() * 100) + "%");
        Chat.say(BridgeServer.get().running()
                ? "мост слушает 127.0.0.1:" + BridgeServer.get().port()
                : "мост выключен");
        if (Quests.modPresent()) {
            Chat.say("вкладок " + Quests.tabs().size() + ", квестов " + Quests.questCount());
        }
    }

    private void icon(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("нужна строка предмета");
        String text = join(args);
        ItemStack stack = Stacks.parse(text);
        if (stack == null) throw new IllegalArgumentException("не нашёлся предмет: " + text);

        IconRenderer renderer = new IconRenderer(DwqBridge.config.iconSize);
        BufferedImage img = renderer.render(stack);
        renderer.dispose();
        if (img == null) throw new IllegalStateException("предмет не нарисовался: " + text);

        String path = Names.iconPath(stack);
        File out = new File(ExportTasks.iconsDir(), "img/" + path);
        if (!out.getParentFile().isDirectory() && !out.getParentFile().mkdirs()) {
            throw new IllegalStateException("нет папки " + out.getParentFile());
        }
        ImageIO.write(img, "png", out);
        Chat.ok(Names.displayName(stack) + " → icons/img/" + path);
    }

    private void paste(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("нужно имя вкладки");
        String tabID = args[0].replace('_', ' ');
        File file = args.length > 1
                ? new File(args[1])
                : new File(ExportTasks.tabsDir(), Names.tabFile(tabID));
        // выгрузки прежних версий лежат с пробелами в имени
        if (!file.isFile() && args.length == 1) file = new File(ExportTasks.tabsDir(), Names.esc(tabID) + ".json");
        if (!file.isFile()) throw new IllegalArgumentException("нет файла " + file);
        TabUpload.paste(tabID, read(file));
    }

    private void bridge(String[] args) {
        String action = args.length == 0 ? "toggle" : args[0].toLowerCase();
        if (action.equals("stop")) {
            BridgeServer.get().stop();
            Chat.ok("мост остановлен");
            return;
        }
        if (action.equals("start") || action.equals("toggle")) {
            if (BridgeServer.get().running()) {
                Chat.say("мост уже слушает 127.0.0.1:" + BridgeServer.get().port());
                return;
            }
            BridgeServer.get().start();
            if (BridgeServer.get().running()) {
                Chat.ok("мост слушает 127.0.0.1:" + BridgeServer.get().port()
                        + " (ключ — в config/dwqbridge.cfg и в dwq_export/bridge.json)");
            }
            return;
        }
        status();
    }

    private static String read(File file) throws Exception {
        InputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    private static boolean has(String[] args, String flag) {
        for (int i = 0; i < args.length; i++) if (args[i].equalsIgnoreCase(flag)) return true;
        return false;
    }

    private static String join(String[] args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--")) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }

    @Override
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) return getListOfStringsMatchingLastWord(args, SUB);
        if (args.length == 2 && args[0].equalsIgnoreCase("icons")) {
            return getListOfStringsMatchingLastWord(args, "all", "quests", "registry");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("tabs")) {
            return getListOfStringsMatchingLastWord(args, "auto", "raw", "local");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("bridge")) {
            return getListOfStringsMatchingLastWord(args, "start", "stop");
        }
        return null;
    }
}
