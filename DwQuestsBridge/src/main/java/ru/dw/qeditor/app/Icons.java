package ru.dw.qeditor.app;

import ru.dw.qeditor.assets.ItemRegistry;
import ru.dw.qeditor.gui.Ui;
import ru.dw.qeditor.render.GuiPainter;

/**
 * Иконка предмета в интерфейсе редактора.
 *
 * <p>{@link GuiPainter#item} рисует предмет в игровых 16×16, поэтому вывод
 * нужного размера — это смена масштаба. Раньше для этого держали кэш
 * {@code BufferedImage} на каждый размер; теперь картинка лежит в атласе и
 * растягивается видеокартой бесплатно.
 *
 * <p>Наведёшь на иконку — покажется описание предмета, какое даёт сама игра:
 * имя цветом редкости и строки модов (энергия, зачарования, лор).
 */
public final class Icons {

    private Icons() { }

    /** Иконка размером size×size логических точек с левым верхним углом в (x, y). */
    public static void item(Ui ui, String forgeName, float x, float y, float size) {
        if (forgeName == null || forgeName.isEmpty()) return;
        GuiPainter p = ui.painter();
        p.push();
        p.translate(x, y);
        p.scale(size / 16f);
        p.item(forgeName, 0, 0);
        p.pop();
        if (ItemRegistry.isLive() && ui.over(x, y, size, size))
            ui.itemTooltip(ItemRegistry.get().tooltip(forgeName));
    }
}
