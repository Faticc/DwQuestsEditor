package ru.dw.qeditor.gui;

/**
 * Тёмная тема редактора в палитре самого мода: фон окна DwQuests — градиент
 * #2C5364 → #0F2027, от него и пляшут остальные цвета.
 *
 * <p>Цвета здесь — обычные ARGB-числа: рисователь принимает именно их, и
 * никакой {@code java.awt.Color} для этого не нужен.
 */
public final class Theme {

    private Theme() { }

    // ---- палитра -------------------------------------------------------------

    /** Фон приложения. */
    public static final int BG = 0xFF0F1419;
    /** Фон панели/карточки. */
    public static final int SURFACE = 0xFF161C24;
    /** Фон приподнятого элемента (поле ввода, список). */
    public static final int SURFACE_2 = 0xFF1C2430;
    /** Фон при наведении. */
    public static final int HOVER = 0xFF243040;
    /** Фон нажатого элемента. */
    public static final int PRESSED = 0xFF2C3A4C;
    /** Линии и рамки. */
    public static final int BORDER = 0xFF27313D;
    public static final int BORDER_STRONG = 0xFF38475A;

    /** Основной текст. */
    public static final int TEXT = 0xFFE2E8EF;
    /** Второстепенный текст, подписи. */
    public static final int TEXT_MUTED = 0xFF8A99A8;
    public static final int TEXT_DISABLED = 0xFF5A6673;

    /** Акцент — из градиента окна мода. */
    public static final int ACCENT = 0xFF4FA8CC;
    public static final int ACCENT_HOVER = 0xFF5FBCE2;
    public static final int ACCENT_PRESSED = 0xFF3E8CAC;
    public static final int ACCENT_SOFT = 0xFF2C5364;

    public static final int DANGER = 0xFFD9635F;
    public static final int WARNING = 0xFFD9A24F;
    public static final int SUCCESS = 0xFF5FB47A;

    /** Полупрозрачная заслонка под модальным окном. */
    public static final int SCRIM = 0xA00A0D12;
    /** Выделение текста в поле ввода. */
    public static final int SELECTION = 0x804FA8CC;

    // ---- размеры (в логических точках) ---------------------------------------

    public static final float RADIUS = 8;
    public static final float RADIUS_SMALL = 6;
    public static final float GAP = 8;
    public static final float GAP_LARGE = 16;
    public static final float ROW = 26;
    public static final float ROW_LARGE = 32;
    public static final float MENU_BAR = 28;
    public static final float STATUS_BAR = 26;
    public static final float SCROLLBAR = 10;

    // ---- шрифт ---------------------------------------------------------------

    public static final int FONT = 13;
    public static final int FONT_SMALL = 12;
    public static final int FONT_LARGE = 15;

    /** Затемнить или осветлить цвет — для наведения и нажатия. */
    public static int shade(int color, float factor) {
        int a = color >>> 24;
        int r = Math.min(255, Math.round((color >> 16 & 255) * factor));
        int g = Math.min(255, Math.round((color >> 8 & 255) * factor));
        int b = Math.min(255, Math.round((color & 255) * factor));
        return a << 24 | r << 16 | g << 8 | b;
    }

    public static int alpha(int color, float alpha) {
        int a = Math.round(Math.min(1f, Math.max(0f, alpha)) * 255f);
        return a << 24 | (color & 0xFFFFFF);
    }
}
