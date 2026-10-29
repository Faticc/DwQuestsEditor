package ru.dw.qeditor.android.awt;

/**
 * Системный буфер обмена. Сам буфер живёт у Activity
 * ({@code android.content.ClipboardManager}), сюда подставляется доступ к нему.
 */
public final class Clipboard {

    /** Как читать и писать буфер — задаёт окно приложения при запуске. */
    public interface Access {
        String get();

        void set(String text);
    }

    static final Clipboard SYSTEM = new Clipboard();

    private static volatile Access access;

    public static void install(Access a) { access = a; }

    public void setContents(StringSelection contents, Object owner) {
        Access a = access;
        if (a != null) a.set(contents.text());
    }

    public Object getData(DataFlavor flavor) {
        Access a = access;
        return a == null ? null : a.get();
    }
}
