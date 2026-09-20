package ru.dw.qeditor.ui.theme;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Тёмная тема редактора в палитре самого мода: фон окна DwQuests — градиент
 * #2C5364 → #0F2027, от него и пляшут остальные цвета.
 *
 * <p>Ставится поверх Nimbus: у него единственного из штатных L&amp;F можно
 * перекрасить всё разом, а остальное добивают свои компоненты в этом пакете.
 */
public final class Theme {

    private Theme() { }

    // ---- палитра -------------------------------------------------------------

    /** Фон приложения. */
    public static final Color BG = new Color(0x0F1419);
    /** Фон панели/карточки. */
    public static final Color SURFACE = new Color(0x161C24);
    /** Фон приподнятого элемента (поле ввода, список). */
    public static final Color SURFACE_2 = new Color(0x1C2430);
    /** Фон при наведении. */
    public static final Color HOVER = new Color(0x243040);
    /** Линии и рамки. */
    public static final Color BORDER = new Color(0x27313D);
    public static final Color BORDER_STRONG = new Color(0x38475A);

    /** Основной текст. */
    public static final Color TEXT = new Color(0xE2E8EF);
    /** Второстепенный текст, подписи. */
    public static final Color TEXT_MUTED = new Color(0x8A99A8);
    public static final Color TEXT_DISABLED = new Color(0x5A6673);

    /** Акцент — из градиента окна мода. */
    public static final Color ACCENT = new Color(0x4FA8CC);
    public static final Color ACCENT_HOVER = new Color(0x5FBCE2);
    public static final Color ACCENT_PRESSED = new Color(0x3E8CAC);
    public static final Color ACCENT_SOFT = new Color(0x2C5364);

    public static final Color DANGER = new Color(0xD9635F);
    public static final Color WARNING = new Color(0xD9A24F);
    public static final Color SUCCESS = new Color(0x5FB47A);

    // ---- размеры -------------------------------------------------------------

    public static final int RADIUS = 8;
    public static final int RADIUS_SMALL = 6;
    public static final int GAP = 8;
    public static final int GAP_LARGE = 16;

    // ---- шрифты --------------------------------------------------------------

    private static String uiFamily;

    public static String family() {
        if (uiFamily != null) return uiFamily;
        Set<String> available = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String candidate : new String[]{
                "Segoe UI Variable Text", "Segoe UI", "Inter", "Roboto", "Noto Sans", "SF Pro Text", "Dialog"}) {
            if (available.contains(candidate)) {
                uiFamily = candidate;
                return uiFamily;
            }
        }
        uiFamily = "Dialog";
        return uiFamily;
    }

    public static Font font(int size) { return new Font(family(), Font.PLAIN, size); }

    public static Font fontBold(int size) { return new Font(family(), Font.BOLD, size); }

    public static Font mono(int size) {
        Set<String> available = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String candidate : new String[]{"Cascadia Mono", "Consolas", "JetBrains Mono", "Menlo"}) {
            if (available.contains(candidate)) return new Font(candidate, Font.PLAIN, size);
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, size);
    }

    public static final int FONT_SIZE = 13;
    public static final int FONT_SIZE_SMALL = 11;

    // ---- установка -----------------------------------------------------------

    public static void install() {
        try {
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (Exception ignored) { }

        put("control", SURFACE);
        put("info", SURFACE_2);
        put("nimbusBase", new Color(0x1B2836));
        put("nimbusBlueGrey", new Color(0x222C38));
        put("nimbusDisabledText", TEXT_DISABLED);
        put("nimbusFocus", ACCENT);
        put("nimbusLightBackground", SURFACE_2);
        put("nimbusSelectedText", Color.WHITE);
        put("nimbusSelectionBackground", ACCENT_SOFT);
        put("nimbusSelection", ACCENT_SOFT);
        put("nimbusBorder", BORDER);
        put("nimbusGreen", SUCCESS);
        put("nimbusOrange", WARNING);
        put("nimbusRed", DANGER);
        put("text", TEXT);
        put("textForeground", TEXT);
        put("textBackground", SURFACE_2);
        put("background", BG);
        put("menu", SURFACE);
        put("menuText", TEXT);
        put("controlText", TEXT);
        put("infoText", TEXT);

        // явные ключи — Nimbus не всегда наследует
        put("Panel.background", BG);
        put("Viewport.background", BG);
        put("ScrollPane.background", BG);
        put("SplitPane.background", BG);
        put("OptionPane.background", SURFACE);
        put("OptionPane.messageForeground", TEXT);

        put("Label.foreground", TEXT);
        put("Label.disabledForeground", TEXT_DISABLED);

        put("TextField.background", SURFACE_2);
        put("TextField.foreground", TEXT);
        put("TextField.caretForeground", ACCENT);
        put("TextArea.background", SURFACE_2);
        put("TextArea.foreground", TEXT);
        put("TextArea.caretForeground", ACCENT);
        put("FormattedTextField.background", SURFACE_2);
        put("FormattedTextField.foreground", TEXT);

        put("List.background", SURFACE_2);
        put("List.foreground", TEXT);
        put("List.selectionBackground", ACCENT_SOFT);
        put("List.selectionForeground", Color.WHITE);

        put("Table.background", SURFACE_2);
        put("Table.foreground", TEXT);
        put("Table.alternateRowColor", new Color(0x19212B));
        put("Table.gridColor", BORDER);
        put("Table.selectionBackground", ACCENT_SOFT);
        put("Table.selectionForeground", Color.WHITE);
        put("TableHeader.background", SURFACE);
        put("TableHeader.foreground", TEXT_MUTED);

        put("ComboBox.background", SURFACE_2);
        put("ComboBox.foreground", TEXT);
        put("ComboBox:\"ComboBox.renderer\"[Selected].background", ACCENT_SOFT);

        put("MenuBar.background", SURFACE);
        put("MenuBar.foreground", TEXT);
        put("Menu.background", SURFACE);
        put("Menu.foreground", TEXT);
        put("MenuItem.background", SURFACE);
        put("MenuItem.foreground", TEXT);
        put("PopupMenu.background", SURFACE);
        put("PopupMenu.foreground", TEXT);
        put("Separator.foreground", BORDER);

        put("CheckBox.background", BG);
        put("CheckBox.foreground", TEXT);
        put("Spinner.background", SURFACE_2);
        put("Spinner.foreground", TEXT);

        put("ToolTip.background", new Color(0x212B36));
        put("ToolTip.foreground", TEXT);

        FontUIResource f = new FontUIResource(family(), Font.PLAIN, FONT_SIZE);
        UIManager.put("defaultFont", f);
        for (Object key : UIManager.getLookAndFeelDefaults().keySet()) {
            if (key instanceof String && ((String) key).endsWith(".font")) UIManager.put(key, f);
        }

        UIManager.put("SplitPane.dividerSize", 6);
        UIManager.put("ScrollBar.thumbHeight", 10);
        UIManager.put("Table.showGrid", Boolean.FALSE);
        UIManager.put("ToolTipManager.enableToolTipMode", "allWindows");
    }

    private static void put(String key, Color c) {
        UIManager.put(key, new ColorUIResource(c));
    }

    // ---- рамки ---------------------------------------------------------------

    public static Border padding(int top, int left, int bottom, int right) {
        return BorderFactory.createEmptyBorder(top, left, bottom, right);
    }

    public static Border padding(int all) { return padding(all, all, all, all); }

    /** Тонкая линия снизу — разделитель без «объёма». */
    public static Border bottomLine() {
        return BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER);
    }

    public static Border topLine() {
        return BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER);
    }

    public static Border leftLine() {
        return BorderFactory.createMatteBorder(0, 1, 0, 0, BORDER);
    }

    /** Применить «плоский» вид к прокрутке: без рамки, тонкий ползунок. */
    public static void styleScroll(javax.swing.JScrollPane scroll) {
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setBackground(BG);
        scroll.getViewport().setBackground(BG);
        scroll.getVerticalScrollBar().setUI(new FlatScrollBarUI());
        scroll.getHorizontalScrollBar().setUI(new FlatScrollBarUI());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getVerticalScrollBar().setOpaque(false);
        scroll.getHorizontalScrollBar().setOpaque(false);
        scroll.setViewportBorder(null);
    }

    public static void styleInput(JComponent c) {
        c.setBackground(SURFACE_2);
        c.setForeground(TEXT);
        c.setBorder(new RoundedBorder(RADIUS_SMALL, BORDER, 6, 9));
        c.setFont(font(FONT_SIZE));
    }

    /** Подпись-заголовок раздела: мелкая, приглушённая, в верхнем регистре. */
    public static javax.swing.JLabel sectionTitle(String text) {
        javax.swing.JLabel l = new javax.swing.JLabel(text.toUpperCase());
        l.setFont(fontBold(FONT_SIZE_SMALL));
        l.setForeground(TEXT_MUTED);
        return l;
    }

    public static javax.swing.JLabel caption(String text) {
        javax.swing.JLabel l = new javax.swing.JLabel(text);
        l.setFont(font(FONT_SIZE_SMALL));
        l.setForeground(TEXT_MUTED);
        return l;
    }
}
