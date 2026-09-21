package ru.dw.qbridge.attach;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Впрыск мода в уже запущенный клиент Minecraft.
 *
 * <p>Обычный мод так подключить нельзя: FML ищет моды, строит их и рассылает
 * этапы загрузки ровно один раз при старте, и реестры к моменту входа в мир
 * уже закрыты. Но DwQuestsBridge в игре ничего не регистрирует — ему нужны
 * только клиентская команда, обработчик тика и свой поток с HTTP. Всё это
 * принимается в любой момент, поэтому для него впрыск работает.
 *
 * <p>Как это устроено: мы подключаемся к чужой JVM механизмом Attach API,
 * грузим туда себя же агентом ({@code agentmain}), находим загрузчик классов,
 * которым загружен сам Minecraft, добавляем в него jar мода и зовём
 * {@code DwqBridge.attach()}.
 *
 * <p><b>Почему именно этот загрузчик.</b> В бою классы Minecraft лежат
 * обфусцированными, а мод собран под SRG-имена; совмещает их преобразователь
 * FML внутри LaunchClassLoader. Положи jar мода на системный путь классов — и
 * он загрузится без преобразования, то есть свалится на первом же обращении к
 * игре. Поэтому jar добавляется ровно в тот загрузчик, которым загружен класс
 * {@code net.minecraft.client.Minecraft}.
 *
 * <pre>
 * java -jar dwq-attach.jar [--pid N] [--mod путь\dwqbridge-1.0.jar] [--client путь\клиент]
 * </pre>
 */
public final class Attacher {

    /** По этим кускам строки запуска узнаём клиент среди прочих java-процессов. */
    private static final String[] MARKERS = {
            "launchwrapper", "retrofuturabootstrap", "net.minecraft", "Minecraft", "lwjgl3ify"
    };

    private Attacher() {}

    // --------------------------------------------------------------- запуск

    public static void main(String[] args) {
        String pid = null;
        String modJar = null;
        String clientDir = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--pid") && i + 1 < args.length) pid = args[++i];
            else if (args[i].equals("--mod") && i + 1 < args.length) modJar = args[++i];
            else if (args[i].equals("--client") && i + 1 < args.length) clientDir = args[++i];
            else if (args[i].equals("--list")) { list(); return; }
            else {
                System.out.println("Ключи: --pid N | --mod <jar> | --client <папка> | --list");
                return;
            }
        }

        File jar = modJar != null ? new File(modJar) : defaultModJar();
        if (jar == null || !jar.isFile()) {
            System.out.println("Не найден jar мода" + (jar == null ? "" : ": " + jar.getAbsolutePath()));
            System.out.println("Сначала build.bat, либо укажите --mod <путь к dwqbridge-1.0.jar>");
            System.exit(2);
        }

        if (pid == null) {
            List<VirtualMachineDescriptor> found = candidates();
            if (found.isEmpty()) {
                System.out.println("Запущенный клиент Minecraft не найден.");
                System.out.println("Список всех java-процессов: --list, затем --pid <номер>");
                System.exit(1);
            }
            if (found.size() > 1) {
                System.out.println("Подходит несколько процессов — уточните --pid:");
                for (VirtualMachineDescriptor d : found) System.out.println("  " + d.id() + "  " + d.displayName());
                System.exit(1);
            }
            pid = found.get(0).id();
            System.out.println("Клиент: pid " + pid);
        }

        try {
            VirtualMachine vm = VirtualMachine.attach(pid);
            try {
                vm.loadAgent(selfJar().getAbsolutePath(), jar.getAbsolutePath());
            } finally {
                vm.detach();
            }
        } catch (Throwable t) {
            System.out.println("Не вышло подключиться к процессу " + pid + ": " + t);
            String message = String.valueOf(t.getMessage()).toLowerCase();
            if (message.contains("not supported") || message.contains("no such process")
                    || message.contains("attach")) {
                System.out.println();
                System.out.println("Похоже, в этой JVM механизм подключения выключен.");
                System.out.println("Так делают лаунчеры: ключ -XX:+DisableAttachMechanism в строке");
                System.out.println("запуска игры (посмотреть можно через диспетчер задач или");
                System.out.println("Get-CimInstance Win32_Process). Снять его может только тот, кто");
                System.out.println("формирует запуск, — сам лаунчер. Тогда остаётся обычный путь:");
                System.out.println("install.bat в папку mods и перезапуск клиента.");
            }
            if (message.contains("agent") && message.contains("load")) {
                System.out.println("Если JVM не даёт грузить агент на ходу, ей нужен ключ");
                System.out.println("-XX:+EnableDynamicAgentLoading (это разовый перезапуск).");
            }
            System.exit(1);
        }

        System.out.println("Агент загружен. Что делает мод — видно в чате игры и в её логе.");
        check(clientDir);
    }

    /** Проверка, что мост поднялся: порт берём из bridge.json клиента. */
    private static void check(String clientDir) {
        File handshake = null;
        if (clientDir != null) handshake = new File(new File(clientDir), "dwq_export/bridge.json");
        int port = handshake != null && handshake.isFile() ? portFrom(handshake) : 25599;
        for (int i = 0; i < 20; i++) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress("127.0.0.1", port), 300);
                System.out.println("Мост отвечает: 127.0.0.1:" + port);
                return;
            } catch (Exception ignored) {
                // ещё не поднялся — клиент подхватит задание в ближайшем тике
            } finally {
                try { s.close(); } catch (Exception ignored) { }
            }
            try { Thread.sleep(250); } catch (InterruptedException e) { return; }
        }
        System.out.println("Мост на порту " + port + " пока молчит — посмотрите чат игры.");
    }

    private static int portFrom(File file) {
        try {
            String text = new String(java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8");
            int i = text.indexOf("\"port\"");
            if (i < 0) return 25599;
            StringBuilder digits = new StringBuilder();
            for (int k = i + 6; k < text.length(); k++) {
                char c = text.charAt(k);
                if (Character.isDigit(c)) digits.append(c);
                else if (digits.length() > 0) break;
            }
            return digits.length() == 0 ? 25599 : Integer.parseInt(digits.toString());
        } catch (Exception e) {
            return 25599;
        }
    }

    private static void list() {
        for (VirtualMachineDescriptor d : VirtualMachine.list()) {
            System.out.println(d.id() + "  " + d.displayName());
        }
    }

    private static List<VirtualMachineDescriptor> candidates() {
        List<VirtualMachineDescriptor> out = new ArrayList<VirtualMachineDescriptor>();
        String self = String.valueOf(ProcessHandle.current().pid());
        for (VirtualMachineDescriptor d : VirtualMachine.list()) {
            if (d.id().equals(self)) continue;
            String name = d.displayName();
            if (name == null) continue;
            for (String marker : MARKERS) {
                if (name.toLowerCase().contains(marker.toLowerCase())) {
                    out.add(d);
                    break;
                }
            }
        }
        return out;
    }

    private static File selfJar() throws Exception {
        return new File(Attacher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    /** По умолчанию берём jar рядом: attach/dwq-attach.jar -> ../build/libs/. */
    private static File defaultModJar() {
        try {
            File here = selfJar().getParentFile();
            File guess = new File(here.getParentFile(), "build/libs/dwqbridge-1.0.jar");
            if (guess.isFile()) return guess;
            return new File(here, "dwqbridge-1.0.jar");
        } catch (Exception e) {
            return null;
        }
    }

}
