package ru.dw.qeditor.util;

import ru.dw.qeditor.assets.Config;
import ru.dw.qeditor.assets.McFont;
import ru.dw.qeditor.assets.ModAssets;
import ru.dw.qeditor.assets.ItemRegistry;
import ru.dw.qeditor.model.QuestTab;

import java.util.List;

/** Проверка окружения: пути, иконки, шрифт, ресурсы мода. Печатает отчёт. */
public final class SelfTest {

    public static String run() {
        StringBuilder sb = new StringBuilder();
        Config c = Config.get();
        sb.append("Программа:       ").append(Config.programDir().getAbsolutePath()).append('\n');
        sb.append("Файл настроек:   ").append(c.propertiesFile().getAbsolutePath()).append('\n');
        sb.append("Запуск:          ").append(Config.inGame() ? "в игре" : "без игры (консоль)").append('\n');
        sb.append("Клиент:          ").append(path(c.clientDir)).append('\n');
        sb.append("Папка иконок:    ").append(path(c.iconsDir)).append('\n');
        sb.append("Предметы:        ").append(ItemRegistry.isLive() ? "живой реестр игры" : "items.json с диска").append('\n');
        sb.append('\n');

        ItemRegistry reg = ItemRegistry.get();
        if (reg.loadError() != null) sb.append("ОШИБКА списка предметов: ").append(reg.loadError()).append('\n');
        else sb.append("Предметов: ").append(reg.size())
                .append(", модов: ").append(reg.mods().size()).append('\n');

        String[] probes = {
                "minecraft:dirt", "minecraft:dirt:2", "minecraft:stone",
                "BuildCraft|Transport:pipeLens:18", "IC2:itemArmorQuantumHelmet",
                "DwQuests:Gift", "ThermalExpansion:Machine:8", "minecraft:bedrock"
        };
        int ok = 0;
        for (String p : probes) {
            String icon = reg.iconPath(p);
            if (icon != null) ok++;
            sb.append("  ").append(p).append(" -> ")
              .append(icon == null ? "ИКОНКА НЕ НАЙДЕНА" : icon)
              .append("  [").append(reg.displayName(p)).append("]\n");
        }
        sb.append("Проверка иконок: ").append(ok).append('/').append(probes.length).append('\n');
        sb.append('\n');

        sb.append("Иконки читаются из: ").append(ItemRegistry.get().describe()).append('\n');

        ModAssets assets = ModAssets.get();
        sb.append("Ресурсы мода из:    ").append(assets.describe()).append('\n');
        for (String p : assets.problems()) sb.append("ВНИМАНИЕ: ").append(p).append('\n');
        sb.append("Фон Default: ").append(assets.defaultBackground() != null ? "есть" : "нет").append('\n');
        sb.append("Текстура звёзд: ").append(assets.stars() != null ? "есть" : "нет").append('\n');
        sb.append("Ноды: ").append(assets.nodes() != null ? "есть" : "нет").append('\n');
        List<String> tabs = assets.knownTabBackgrounds();
        sb.append("Фоны вкладок в моде (").append(tabs.size()).append("): ").append(String.join(", ", tabs)).append('\n');
        sb.append('\n');

        McFont font = McFont.get();
        if (!font.isReady()) sb.append("ОШИБКА шрифта: ").append(font.error()).append('\n');
        else {
            sb.append("Шрифт Minecraft загружен. Ширины: ")
              .append("'A'=").append(font.charWidth('A'))
              .append(", ' '=").append(font.charWidth(' '))
              .append(", 'Я'=").append(font.charWidth('Я'))
              .append(", 'i'=").append(font.charWidth('i')).append('\n');
            sb.append("Ширина строки \"Привет, мир!\" = ").append(font.width("Привет, мир!")).append(" px\n");
        }

        sb.append('\n').append(iconNamingCheck());
        sb.append(ru.dw.qeditor.link.GameLink.get().connected()
                ? "Связь с игрой: " + ru.dw.qeditor.link.GameLink.get().status() + "\n"
                : "Связь с игрой: нет (мост ищется в <клиент>/dwq_export/bridge.json)\n");
        return sb.toString();
    }

    /**
     * Имя файла иконки для предмета с NBT редактор и мод-выгрузчик считают
     * независимо друг от друга — и обязаны сойтись, иначе выгруженные варианты
     * (цветы Botany и прочая генетика) просто не найдутся.
     *
     * <p>Ожидаемые хеши сняты с самого мода: {@code Names.nbtHash} прогонялся
     * на этих же строках через настоящий {@code JsonToNBT} 1.7.10.
     */
    private static String iconNamingCheck() {
        String[][] cases = {
                {"{display:{Name:\"Rosa\"}}", "cf2430c8"},
                {"{b:1,a:2}", "86ec606c"},
                {"{a:2,b:1}", "86ec606c"},
                {"{Age:3b,Health:1.0f,Genome:\"rosa|white\"}", "1b87a44f"},
                {"{Layers:[0,1],MinRandom:1}", "fc803fc6"},
                {"{Items:[0:{Slot:0b,ID:\"minecraft:dirt=1\"}],DropType:\"RANDOM\"}", "bf9130b8"},
                {"{Count:1b,Damage:0s,Weird:1.50d}", "53f1f1df"}
        };
        StringBuilder sb = new StringBuilder();
        int bad = 0;
        for (String[] c : cases) {
            String got = ru.dw.qeditor.assets.ItemRegistry.hash8(
                    ru.dw.qeditor.assets.NbtLite.canonical(c[0]));
            if (!got.equals(c[1])) {
                bad++;
                sb.append("  РАСХОЖДЕНИЕ: ").append(c[0]).append(" → ").append(got)
                  .append(", а мод считает ").append(c[1]).append('\n');
            }
        }
        sb.insert(0, "Имена иконок с NBT сходятся с модом: "
                + (bad == 0 ? "да (" + cases.length + " проверок)" : "НЕТ, расхождений " + bad) + "\n");
        return sb.toString();
    }

    private static String path(String p) {
        if (Config.isBlank(p)) return "(не задан — берётся из самой программы)";
        java.io.File f = new java.io.File(p);
        return p + (f.exists() ? "  [ок]" : "  [НЕ НАЙДЕН]");
    }

    public static void main(String[] args) {
        System.out.println(run());
        if (args.length > 0) {
            try {
                String json = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[0])),
                        java.nio.charset.StandardCharsets.UTF_8);
                QuestTab tab = QuestTab.parse(json);
                System.out.println("Вкладка '" + tab.tabID + "': квестов " + tab.quests.size());
            } catch (Exception e) {
                System.out.println("Не удалось прочитать вкладку: " + e);
            }
        }
    }
}
