package ru.dw.qbridge.editor;

import cpw.mods.fml.client.registry.ClientRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemStack;
import org.lwjgl.input.Keyboard;
import ru.dw.qbridge.DwqBridge;
import ru.dw.qbridge.bridge.Api;
import ru.dw.qbridge.client.Chat;
import ru.dw.qbridge.export.IconRenderer;
import ru.dw.qbridge.export.Stacks;
import ru.dw.qeditor.app.App;
import ru.dw.qeditor.assets.Config;
import ru.dw.qeditor.assets.ItemRegistry;
import ru.dw.qeditor.assets.ModAssets;
import ru.dw.qeditor.gl.Batch;
import ru.dw.qeditor.link.GameLink;
import ru.dw.qeditor.link.IconFetcher;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Редактор квестов внутри игры: подключает его к ресурсам, предметам и мосту
 * клиента и держит одно окно на весь сеанс.
 *
 * <p>Окно одно и живёт, пока жива игра: закрыть экран — не значит потерять
 * открытую вкладку, выделение или историю отмен. Открыли снова — всё на месте,
 * как у свёрнутой программы.
 *
 * <p>Что редактор получает от игры вместо файлов на диске:
 * <ul>
 *   <li>текстуры мода и шрифт — менеджер ресурсов ({@link GamePack});</li>
 *   <li>список предметов с русскими именами — живой реестр ({@code /items} моста);</li>
 *   <li>иконки — рисует игра, в том числе варианты с NBT, прямо перед кадром;</li>
 *   <li>описание предмета — настоящая подсказка {@code ItemStack.getTooltip};</li>
 *   <li>всё меню «Игра» — тот же разбор запросов моста, но без HTTP.</li>
 * </ul>
 */
public final class EditorHost {

    /** Сколько времени кадра отдавать дорисовке иконок. */
    private static final long ICON_BUDGET_NANOS = 8_000_000L;

    private static final EditorHost INSTANCE = new EditorHost();

    public static EditorHost get() { return INSTANCE; }

    private boolean wired;
    private McWindow window;
    private Batch batch;
    private App app;
    private final GlState gl = new GlState();
    private IconRenderer icons;
    /** Файл, который надо открыть при следующем кадре (команда с аргументом). */
    private File pendingFile;

    private EditorHost() { }

    /** Подключить редактор к игре — один раз, до первого кадра. */
    private void wire() {
        if (wired) return;
        wired = true;
        Minecraft mc = Minecraft.getMinecraft();
        Config.initInGame(mc.mcDataDir);
        // вкладки из игры и выгрузка мода лежат здесь — пусть и папка по умолчанию будет эта
        Config c = Config.get();
        if (Config.isBlank(c.lastFolder)) {
            File tabs = new File(DwqBridge.outDir, "tabs");
            if (tabs.isDirectory() || tabs.mkdirs()) c.lastFolder = tabs.getAbsolutePath();
        }
        ModAssets.useGamePack(new GamePack());
        ItemRegistry.setLive(new LiveItems());
        GameLink.useLocal((path, query, body) -> {
            Api.Reply r = Api.shared().handle(path, query, body);
            return new GameLink.Response(r.code, r.data, r.iconPath);
        });
        IconFetcher.get().useDirect(this::renderIcon);
    }

    private KeyBinding key;
    private boolean openRequested;

    /** Клавиша «Редактор квестов» в настройках управления; по умолчанию F7 — она в паке свободна. */
    public void registerKey() {
        if (key != null) return;
        key = new KeyBinding("Редактор квестов", Keyboard.KEY_F7, "DwQuests Bridge");
        ClientRegistry.registerKeyBinding(key);
    }

    /**
     * Открыть следующим тиком. Команда из чата не может открыть экран сразу:
     * чат закрывается уже после неё и закрыл бы редактор вместе с собой.
     */
    public void requestOpen(File file) {
        if (file != null) pendingFile = file;
        openRequested = true;
    }

    /** Конец тика клиента: клавиша и отложенное открытие. */
    public void tick() {
        Minecraft mc = Minecraft.getMinecraft();
        if (key != null && key.isPressed() && mc.currentScreen == null) openRequested = true;
        if (!openRequested) return;
        openRequested = false;
        if (!(mc.currentScreen instanceof EditorScreen)) mc.displayGuiScreen(new EditorScreen());
    }

    McWindow window() {
        wire();
        if (window == null) window = new McWindow();
        return window;
    }

    /** Кадр редактора — из {@link EditorScreen#drawScreen}. */
    void frame() {
        McWindow w = window();
        w.syncMouse();
        // иконки рисуются тем же вызовом, что слот инвентаря, — в игровом
        // состоянии GL и до того, как редактор заберёт его себе
        try {
            IconFetcher.get().pump(ICON_BUDGET_NANOS);
        } catch (Throwable t) {
            DwqBridge.LOG.warn("дорисовка иконок упала", t);
        }
        gl.save();
        try {
            if (batch == null) batch = new Batch();
            if (app == null) app = new App(w, batch);
            if (pendingFile != null) {
                File f = pendingFile;
                pendingFile = null;
                app.openFile(f);
            }
            app.drawFrame();
        } catch (Throwable t) {
            DwqBridge.LOG.error("кадр редактора упал", t);
            Chat.error("Редактор упал: " + t + " — подробности в логе");
            Minecraft.getMinecraft().displayGuiScreen(null);
        } finally {
            gl.restore();
            w.endFrame();
        }
    }

    private BufferedImage renderIcon(String forgeString) {
        ItemStack stack = Stacks.parse(forgeString);
        if (stack == null) return null;
        if (icons == null) icons = new IconRenderer(DwqBridge.config.iconSize);
        return icons.render(stack);
    }

    // ------------------------------------------------------------------ предметы

    /** Реестр и подсказки — прямо из игры. */
    private static final class LiveItems implements ItemRegistry.Live {

        @Override
        public byte[] itemsJson() {
            try {
                Api.Reply r = Api.shared().handle("/items", Collections.<String, String>emptyMap(), null);
                return r.code == 200 ? r.data : null;
            } catch (Exception e) {
                DwqBridge.LOG.warn("список предметов не собрался", e);
                return null;
            }
        }

        @Override
        public List<String> tooltip(String forgeString) {
            ItemStack stack = Stacks.parse(forgeString);
            if (stack == null) return null;
            Minecraft mc = Minecraft.getMinecraft();
            List<String> out = new ArrayList<>();
            try {
                if (mc.thePlayer != null) {
                    for (Object line : stack.getTooltip(mc.thePlayer, mc.gameSettings.advancedItemTooltips))
                        out.add(String.valueOf(line));
                }
            } catch (Throwable t) {
                // подсказку некоторых модов без мира не собрать — хватит имени
                out.clear();
            }
            if (out.isEmpty()) out.add(stack.getDisplayName());
            // как GuiContainer.renderToolTip: имя цветом редкости, остальное серым
            try {
                out.set(0, stack.getRarity().rarityColor + out.get(0));
            } catch (Throwable ignored) {
                // редкость некоторых модов без мира не считается — имя останется белым
            }
            for (int i = 1; i < out.size(); i++) out.set(i, "§7" + out.get(i));
            return out;
        }
    }
}
