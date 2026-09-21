package ru.dw.qbridge;

import net.minecraftforge.common.config.Configuration;

import java.io.File;
import java.security.SecureRandom;
import java.math.BigInteger;

/** Настройки мода: порт моста, ключ доступа, размер иконки. */
public class BridgeConfig {

    public final int port;
    public final String token;
    public final boolean autoStart;
    public final int iconSize;
    public final String outDirName;
    /** Сколько миллисекунд тика тратить на выгрузку. Остальное нужно самому клиенту. */
    public final int tickBudgetMs;

    public BridgeConfig(File file) {
        Configuration cfg = new Configuration(file);
        cfg.load();

        port = cfg.get(Configuration.CATEGORY_GENERAL, "port", 25599,
                "Порт локального моста (слушается только на 127.0.0.1)").getInt();
        autoStart = cfg.get(Configuration.CATEGORY_GENERAL, "autoStart", true,
                "Поднимать мост сразу при запуске клиента").getBoolean();
        iconSize = cfg.get(Configuration.CATEGORY_GENERAL, "iconSize", 64,
                "Сторона картинки иконки в пикселях").getInt();
        outDirName = cfg.get(Configuration.CATEGORY_GENERAL, "outDir", "dwq_export",
                "Папка выгрузки внутри клиента").getString();
        tickBudgetMs = cfg.get(Configuration.CATEGORY_GENERAL, "tickBudgetMs", 25,
                "Сколько миллисекунд тика тратить на выгрузку (тик — 50 мс)").getInt();

        // Ключ нужен, чтобы на мост не ходил кто попало из браузера: страница на
        // любом сайте умеет слать запросы на localhost, а ключа у неё нет.
        String saved = cfg.get(Configuration.CATEGORY_GENERAL, "token", "",
                "Ключ доступа к мосту; пустой — будет создан при первом запуске").getString();
        if (saved == null || saved.trim().isEmpty()) {
            saved = new BigInteger(130, new SecureRandom()).toString(32);
            cfg.get(Configuration.CATEGORY_GENERAL, "token", "").set(saved);
        }
        token = saved;

        if (cfg.hasChanged()) cfg.save();
    }
}
