package ru.dw.qeditor.android.awt;

public final class Toolkit {

    private static final Toolkit INSTANCE = new Toolkit();

    private Toolkit() { }

    public static Toolkit getDefaultToolkit() { return INSTANCE; }

    public Clipboard getSystemClipboard() { return Clipboard.SYSTEM; }
}
