package ru.dw.qbridge.task;

import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import ru.dw.qbridge.DwqBridge;
import ru.dw.qbridge.client.ClientTicker;
import ru.dw.qbridge.export.AssetExport;
import ru.dw.qbridge.export.IconExportJob;
import ru.dw.qbridge.export.StackScan;
import ru.dw.qbridge.quests.Quests;
import ru.dw.qbridge.quests.TabDumpJob;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Что именно выгружать — одно место и для команды {@code /dwqb}, и для моста.
 *
 * <p>Складывается всё в {@code dwq_export/}: {@code icons/} и {@code game/} —
 * в точности те две папки, которые редактор ждёт увидеть в своём {@code runtime/},
 * плюс {@code tabs/} с вкладками.
 */
public final class ExportTasks {

    private ExportTasks() {}

    public static File iconsDir() {
        return DwqBridge.dir("icons");
    }

    public static File gameDir() {
        return DwqBridge.dir("game");
    }

    public static File tabsDir() {
        return DwqBridge.dir("tabs");
    }

    // ------------------------------------------------------------------- иконки

    /**
     * @param scope all — весь реестр, NEI и предметы вкладок; quests — только то,
     *              что встречается во вкладках; registry — только реестр
     */
    public static void startIcons(final String scope, final boolean skipExisting) throws Exception {
        final int size = DwqBridge.config.iconSize;
        Map<String, ItemStack> stacks = ClientTicker.get().call(new Callable<Map<String, ItemStack>>() {
            public Map<String, ItemStack> call() {
                StackScan scan = new StackScan();
                if ("quests".equals(scope)) {
                    requireQuests();
                    scan.addAll(Quests.referencedStacks());
                } else if ("registry".equals(scope)) {
                    scan.addRegistry();
                } else {
                    scan.addRegistry().addNei();
                    if (Quests.modPresent()) scan.addAll(Quests.referencedStacks());
                }
                return scan.result();
            }
        }, 120000L);

        ClientTicker.get().start(new IconExportJob(iconsDir(), stacks, size, skipExisting));
    }

    // ------------------------------------------------------------------ ресурсы

    /** Текстуры и шрифт. Работа файловая и быстрая — делаем разом в главном потоке. */
    public static String exportAssets(final String[] modIds) throws Exception {
        return ClientTicker.get().call(new Callable<String>() {
            public String call() {
                File game = gameDir();
                AssetExport.Result result = new AssetExport.Result();
                AssetExport.fromMods(game, modIds, result);
                AssetExport.font(game, result);
                if (Quests.modPresent()) {
                    Set<ResourceLocation> extra = new LinkedHashSet<ResourceLocation>();
                    extra.addAll(Quests.backgrounds());
                    extra.addAll(Quests.referencedImages());
                    AssetExport.resources(game, extra, result);
                }
                return result.toString();
            }
        }, 120000L);
    }

    public static String[] defaultMods() {
        return new String[]{"DwQuests", "DwCore"};
    }

    // ------------------------------------------------------------------ вкладки

    public static void startTabs(final TabDumpJob.Mode mode) throws Exception {
        requireQuests();
        TabDumpJob job = ClientTicker.get().call(new Callable<TabDumpJob>() {
            public TabDumpJob call() {
                return new TabDumpJob(tabsDir(), mode);
            }
        }, 30000L);
        ClientTicker.get().start(job);
    }

    // --------------------------------------------------------------------- мост

    /** {@code POST /export?what=icons|assets|tabs&scope=…&mode=…} */
    public static String startFromBridge(Map<String, String> query) throws Exception {
        String what = query.get("what");
        if (what == null) what = "icons";

        if (what.equals("assets")) {
            return "{\"ok\":true,\"assets\":\"" + exportAssets(defaultMods()).replace('"', '\'') + "\"}";
        }
        if (what.equals("tabs")) {
            startTabs(mode(query.get("mode")));
            return "{\"ok\":true,\"started\":\"tabs\"}";
        }
        if (what.equals("icons")) {
            String scope = query.get("scope");
            boolean skip = !"0".equals(query.get("skipExisting"));
            startIcons(scope == null ? "all" : scope, skip);
            return "{\"ok\":true,\"started\":\"icons\"}";
        }
        throw new IllegalArgumentException("неизвестный what=" + what);
    }

    public static TabDumpJob.Mode mode(String text) {
        if (text == null) return TabDumpJob.Mode.AUTO;
        if (text.equalsIgnoreCase("raw")) return TabDumpJob.Mode.RAW;
        if (text.equalsIgnoreCase("local")) return TabDumpJob.Mode.LOCAL;
        return TabDumpJob.Mode.AUTO;
    }

    private static void requireQuests() {
        if (!Quests.modPresent()) throw new IllegalStateException("в клиенте нет мода DwQuests");
    }
}
