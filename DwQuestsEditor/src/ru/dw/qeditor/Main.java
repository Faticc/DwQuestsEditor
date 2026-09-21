package ru.dw.qeditor;

import ru.dw.qeditor.app.App;

import java.io.File;

/**
 * Точка входа редактора.
 *
 * <p>Окно, интерфейс и карта — на GLFW и OpenGL, поэтому отдельного потока
 * событий нет: всё происходит в главном, кадр за кадром. На macOS GLFW вообще
 * требует главного потока, так что иначе и нельзя.
 *
 * <p>Консольный режим живёт отдельно — {@code ru.dw.qeditor.cli.QuestCli}.
 */
public final class Main {

    private Main() { }

    public static void main(String[] args) {
        App app = new App();
        if (args.length > 0) {
            File f = new File(args[0]);
            if (f.isFile()) app.openFile(f);
        }
        app.run();
    }
}
