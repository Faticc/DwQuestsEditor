package ru.dw.qbridge;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.ClientCommandHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ru.dw.qbridge.bridge.BridgeServer;
import ru.dw.qbridge.client.Chat;
import ru.dw.qbridge.client.ClientTicker;
import ru.dw.qbridge.command.CommandDwqb;

import java.io.File;

/**
 * Клиентский мод для редактора квестов DwQuests.
 *
 * <p>Делает две вещи:
 * <ol>
 *   <li><b>Выгружает ассеты</b> — иконки всех предметов пака (включая варианты,
 *       которые отличаются только NBT: цветы Botany, пчёлы Forestry и прочая
 *       генетика), ресурсы модов DwQuests/DwCore и шрифт. На выходе — папка
 *       ровно той формы, что читает редактор ({@code icons/} + {@code game/}).</li>
 *   <li><b>Связывает игру с редактором</b> — локальный HTTP-мост: редактор берёт
 *       вкладки прямо из памяти клиента, просит дорисовать недостающую иконку
 *       и отдаёт изменённую вкладку обратно в игру.</li>
 * </ol>
 *
 * Всё работает на стороне клиента: команды {@code /dwqb} — клиентские, сервер
 * о них не знает. Единственное, что требует прав на сервере, — заливка вкладки
 * обратно, и она идёт штатным путём: буфер обмена + {@code /dwq tab paste}.
 */
@Mod(modid = DwqBridge.MODID, name = "DwQuests Bridge", version = "1.0", acceptableRemoteVersions = "*")
public class DwqBridge {

    public static final String MODID = "dwqbridge";

    public static final Logger LOG = LogManager.getLogger("DwQuestsBridge");

    /** Куда складывается выгрузка: {@code <клиент>/dwq_export}. */
    public static File outDir;

    public static BridgeConfig config;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent e) {
        config = new BridgeConfig(e.getSuggestedConfigurationFile());
        // configDir = <клиент>/config, значит родитель -- корень клиента.
        outDir = new File(e.getModConfigurationDirectory().getParentFile(), config.outDirName);
    }

    @Mod.EventHandler
    @SideOnly(Side.CLIENT)
    public void init(FMLInitializationEvent e) {
        startClient(config.autoStart);
    }

    /** Команда, тикер и (по желанию) мост. Общее для обычного запуска и впрыска. */
    private static synchronized void startClient(boolean withBridge) {
        if (started) return;
        started = true;
        ClientTicker.register();
        ClientCommandHandler.instance.registerCommand(new CommandDwqb());
        ru.dw.qbridge.editor.EditorHost.get().registerKey();
        if (withBridge) BridgeServer.get().start();
    }

    private static boolean started;

    /**
     * Точка входа для впрыска в уже запущенный клиент — её зовёт агент из
     * {@code attach/} (см. README, раздел «Впрыск в запущенный клиент»).
     *
     * <p>Так можно, потому что мод ничего не регистрирует в игре: ни предметов,
     * ни рецептов, ни сетевых каналов — только клиентскую команду, обработчик
     * тика и свой HTTP-мост. Всё это принимается в любой момент, в отличие от
     * настоящих модов, которым нужны этапы загрузки FML.
     *
     * <p>Регистрация уходит в очередь главного потока: обработчики событий и
     * список команд трогать из чужого потока нельзя.
     */
    public static synchronized String attach() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return "клиент ещё не создан — впрыск рано";
        if (started) {
            return "уже подключён" + (BridgeServer.get().running()
                    ? ", мост на 127.0.0.1:" + BridgeServer.get().port() : ", мост выключен");
        }
        if (config == null) {
            config = new BridgeConfig(new File(new File(mc.mcDataDir, "config"), "dwqbridge.cfg"));
            outDir = new File(mc.mcDataDir, config.outDirName);
        }
        mc.func_152344_a(new Runnable() {
            public void run() {
                try {
                    startClient(true);
                    Chat.ok("DwQuestsBridge подключён на лету — /dwqb help, мост на 127.0.0.1:"
                            + BridgeServer.get().port());
                } catch (Throwable t) {
                    LOG.error("впрыск не удался", t);
                    Chat.error("DwQuestsBridge не подключился: " + t);
                }
            }
        });
        return "задание отправлено в главный поток; порт " + config.port;
    }

    /** Подпапка выгрузки; создаётся при первом обращении. Пустое имя — сама папка. */
    public static File dir(String name) {
        File f = name == null || name.isEmpty() ? outDir : new File(outDir, name);
        if (!f.isDirectory() && !f.mkdirs()) {
            throw new RuntimeException("не удалось создать папку " + f);
        }
        return f;
    }
}
