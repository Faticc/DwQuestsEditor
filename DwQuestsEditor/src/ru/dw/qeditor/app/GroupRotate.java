package ru.dw.qeditor.app;

import ru.dw.qeditor.model.Quest;
import ru.dw.qeditor.model.QuestTab;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Поворот группы квестов вокруг общего центра.
 *
 * <p>Связи держатся на questID, а не на месте узла, поэтому поворот их не
 * рвёт — меняются только {@code displayX/displayY}. Считается всегда от
 * исходных координат, а не от прошлого кадра: иначе ошибки округления
 * накапливаются и фигура за несколько оборотов «расплывается».
 */
public final class GroupRotate {

    /** Координаты после поворота округляются до сотой клетки. */
    public static final float PRECISION = 100f;

    private final Map<String, float[]> origin = new LinkedHashMap<>();
    /** Центр поворота в клетках — середина рамки вокруг узлов. */
    public final float pivotX, pivotY;

    public GroupRotate(QuestTab tab, Collection<String> ids) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (String id : ids) {
            Quest q = tab.quests.get(id);
            if (q == null) continue;
            origin.put(id, new float[]{q.displayX, q.displayY});
            minX = Math.min(minX, q.displayX);
            minY = Math.min(minY, q.displayY);
            maxX = Math.max(maxX, q.displayX);
            maxY = Math.max(maxY, q.displayY);
        }
        pivotX = origin.isEmpty() ? 0 : (minX + maxX) / 2f;
        pivotY = origin.isEmpty() ? 0 : (minY + maxY) / 2f;
    }

    public boolean isEmpty() { return origin.isEmpty(); }

    /**
     * Поставить узлы на место, повёрнутое на {@code degrees} от исходного.
     * Ось Y карты смотрит вниз, так что положительный угол — по часовой.
     *
     * @return сдвинулся ли хоть один узел
     */
    public boolean apply(QuestTab tab, double degrees) {
        double a = Math.toRadians(degrees);
        double cos = Math.cos(a), sin = Math.sin(a);
        boolean moved = false;
        for (Map.Entry<String, float[]> en : origin.entrySet()) {
            Quest q = tab.quests.get(en.getKey());
            if (q == null) continue;
            double dx = en.getValue()[0] - pivotX;
            double dy = en.getValue()[1] - pivotY;
            float nx = round(pivotX + dx * cos - dy * sin);
            float ny = round(pivotY + dx * sin + dy * cos);
            if (nx != q.displayX || ny != q.displayY) moved = true;
            q.displayX = nx;
            q.displayY = ny;
        }
        return moved;
    }

    private static float round(double v) {
        return (float) (Math.round(v * PRECISION) / (double) PRECISION);
    }
}
