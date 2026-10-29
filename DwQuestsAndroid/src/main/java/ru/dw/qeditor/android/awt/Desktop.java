package ru.dw.qeditor.android.awt;

import java.io.File;
import java.io.IOException;

/**
 * «Открыть папку в проводнике» — на телефоне некуда: системного проводника,
 * которому можно отдать путь, нет. Вызов просто ничего не делает.
 */
public final class Desktop {

    private static final Desktop INSTANCE = new Desktop();

    private Desktop() { }

    public static Desktop getDesktop() { return INSTANCE; }

    public static boolean isDesktopSupported() { return false; }

    public void open(File file) throws IOException { }
}
