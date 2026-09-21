package ru.dw.qeditor.ai;

import ru.dw.qeditor.gl.Batch;
import ru.dw.qeditor.gl.Offscreen;
import ru.dw.qeditor.gl.Textures;
import ru.dw.qeditor.model.QuestTab;
import ru.dw.qeditor.render.GamePreview;
import ru.dw.qeditor.render.GuiPainter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Снимок вкладки в PNG — чтобы помощник видел карту, а не только JSON: где
 * узлы стоят, какие у них иконки и как идут связи. Словами это описывать
 * дороже, чем показать.
 *
 * <p>Рисуется тем же {@link GamePreview}, что и вкладка «Просмотр», но в
 * кадровый буфер, а не в окно. Вызывать только с главного потока и вне
 * {@code batch.begin()/end()} текущего кадра — пакет вершин один на всё окно.
 */
public final class TabShot {

    private TabShot() { }

    /** Масштаб окна мода на снимке: как guiScale = 2 в игре. */
    private static final int GUI_SCALE = 2;

    /**
     * @param openQuestID показать открытой панель этого квеста либо null
     */
    public static File render(QuestTab tab, String openQuestID, Batch batch, File dir,
                              int width, int height) {
        GamePreview preview = new GamePreview();
        preview.setTab(tab);
        preview.setGuiScale(GUI_SCALE);
        preview.setViewport(width, height);
        // зум как в моде: 2 — самое дальнее, что игрок вообще может увидеть
        preview.setZoom(2f);
        preview.centerMap();
        if (openQuestID != null && tab.quests.containsKey(openQuestID)) preview.openQuest(openQuestID);

        Offscreen target = Offscreen.create(width, height);
        try {
            BufferedImage img = null;
            // два прохода: иконки предметов попадают в атлас прямо во время
            // первого, и уровни уменьшения у них считаются только после него
            for (int pass = 0; pass < 2; pass++) {
                Textures.refresh();
                target.bind(0xFF000000);
                batch.begin(width, height);
                GuiPainter p = GuiPainter.of(batch, GUI_SCALE, width, height);
                preview.render(p, width, height);
                p.dispose();
                batch.end();
                if (pass == 1) img = target.read();
            }
            target.unbind();

            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            File file = new File(dir, "tab.png");
            ImageIO.write(img, "png", file);
            return file;
        } catch (Exception e) {
            return null;
        } finally {
            target.dispose();
        }
    }
}
