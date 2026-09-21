package ru.dw.qbridge.attach;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;

/**
 * Половина инжектора, которая выполняется уже внутри клиента.
 *
 * <p>Вынесена из {@link Attacher} отдельным классом ради версии класс-файла:
 * этот собирается под Java 8, потому что клиенты 1.7.10 бывают и на «восьмёрке»
 * (GribLand, например, запускает игру на jre1.8.0_311), а Java 8 просто не
 * загрузит класс, собранный новее. Attach API в этом классе поэтому не
 * упоминается — он живёт в модуле, которого в восьмёрке нет.
 */
public final class DwqAgent {

    private DwqAgent() {}

    /** @param args путь к jar-у мода */
    public static void agentmain(String args, Instrumentation inst) {
        try {
            File jar = new File(args == null ? "" : args.trim());
            if (!jar.isFile()) throw new IllegalArgumentException("нет jar-а мода: " + jar);

            ClassLoader loader = minecraftLoader(inst);
            if (loader == null) {
                throw new IllegalStateException("не нашёл загрузчик Minecraft — впрыскивать некуда");
            }
            addUrl(loader, jar.toURI().toURL());

            Class<?> entry = Class.forName("ru.dw.qbridge.DwqBridge", true, loader);
            Method attach = entry.getMethod("attach");
            Object result = attach.invoke(null);
            System.out.println("[dwq-attach] " + result);
        } catch (Throwable t) {
            System.out.println("[dwq-attach] впрыск не удался: " + t);
            t.printStackTrace();
        }
    }

    /**
     * Тот самый загрузчик, которым загружена игра, — берём его у самого класса
     * игры. Гадать по имени класса загрузчика не нужно, и это важно: положи jar
     * мода на системный путь классов, и он загрузится без преобразования имён
     * FML, то есть упадёт на первом же обращении к Minecraft.
     */
    private static ClassLoader minecraftLoader(Instrumentation inst) {
        Class<?>[] loaded = inst.getAllLoadedClasses();
        for (int i = 0; i < loaded.length; i++) {
            if ("net.minecraft.client.Minecraft".equals(loaded[i].getName())) {
                ClassLoader cl = loaded[i].getClassLoader();
                if (cl != null) return cl;
            }
        }
        // Запасной путь: класс игры мог ещё не загрузиться (клиент на заставке).
        for (int i = 0; i < loaded.length; i++) {
            ClassLoader cl = loaded[i].getClassLoader();
            if (cl != null && cl.getClass().getName().endsWith("LaunchClassLoader")) return cl;
        }
        return null;
    }

    /** У LaunchClassLoader addURL публичный; ниже по иерархии — защищённый из URLClassLoader. */
    private static void addUrl(ClassLoader loader, URL url) throws Exception {
        for (Class<?> c = loader.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod("addURL", URL.class);
                m.setAccessible(true);
                m.invoke(loader, url);
                return;
            } catch (NoSuchMethodException ignored) {
                // ищем выше по иерархии
            }
        }
        throw new IllegalStateException("у загрузчика " + loader.getClass().getName() + " нет addURL");
    }
}
