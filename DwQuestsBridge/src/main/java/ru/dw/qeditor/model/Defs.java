package ru.dw.qeditor.model;

/**
 * Перечисления мода DwQuests — значения и цвета взяты 1-в-1 из исходников
 * ru.sky.dw.quests / ru.sky.dw.core, чтобы редактор и просмотр совпадали с игрой.
 */
public final class Defs {

    private Defs() { }

    /** ru.sky.dw.core.common.api.Rarity */
    public enum Rarity {
        COMMON(-5592406, "Обычный"),
        UNCOMMON(-16711936, "Необычный"),
        RARE(-12255233, "Редкий"),
        EPIC(-47873, "Эпический"),
        LEGENDARY(-23296, "Легендарный"),
        MYTHIC(-48060, "Мифический");

        public final int color;
        public final String ru;

        Rarity(int color, String ru) { this.color = color; this.ru = ru; }

        public static Rarity of(String name) {
            if (name != null) for (Rarity r : values()) if (r.name().equals(name)) return r;
            return UNCOMMON;
        }
    }

    /** QuestButton.Star — цвета ядра и двух теней ореола. */
    public enum Star {
        GRAY(-5592406, -11184811, -14540254),
        GREEN(-16711936, -16735488, -16768512),
        BLUE(-12255233, -16733526, -16777182),
        PURPLE(-47873, -5635926, -14548958),
        ORANGE(-23296, -5593856, -14540288),
        RED(-48060, -5636096, -14548992);

        public final int mainColor;
        public final int shadowColor1;
        public final int shadowColor2;

        Star(int mainColor, int shadowColor1, int shadowColor2) {
            this.mainColor = mainColor;
            this.shadowColor1 = shadowColor1;
            this.shadowColor2 = shadowColor2;
        }

        public static Star of(Rarity rarity) {
            switch (rarity) {
                case UNCOMMON: return GREEN;
                case RARE: return BLUE;
                case EPIC: return PURPLE;
                case LEGENDARY: return ORANGE;
                case MYTHIC: return RED;
                default: return GRAY;
            }
        }
    }

    /** Quest.IconSize */
    public enum IconSize {
        DEFAULT(1.0F, "Обычный"),
        MEDIUM(1.5F, "Средний"),
        LARGE(2.0F, "Большой"),
        HUGE(2.5F, "Огромный");

        public final float scale;
        public final String ru;

        IconSize(float scale, String ru) { this.scale = scale; this.ru = ru; }

        public static IconSize of(String name) {
            if (name != null) for (IconSize s : values()) if (s.name().equals(name)) return s;
            return DEFAULT;
        }
    }

    /** ru.sky.dw.core.common.NodeType */
    public enum NodeType {
        NORMAL("Обычный"),
        UNSTABLE("Нестабильный"),
        DARK("Тёмный"),
        TAINTED("Порча"),
        HUNGRY("Голодный"),
        PURE("Чистый"),
        SMOKE("Дым"),
        SMOKE2("Дым 2");

        public final String ru;

        NodeType(String ru) { this.ru = ru; }

        public static NodeType of(String name) {
            if (name != null) for (NodeType n : values()) if (n.name().equals(name)) return n;
            return NORMAL;
        }
    }

    /** Quest.TasksType */
    public enum TasksType {
        DEFAULT("Задачи"),
        ONE_OF("Задача на выбор"),
        ORDER("Задачи по порядку");

        public final String ru;

        TasksType(String ru) { this.ru = ru; }

        /** Ровно как Quest.TasksType.getDescription() — заголовок блока задач в игре. */
        public String guiTitle() { return this == ONE_OF ? "Задача на выбор" : "Задачи"; }

        public static TasksType of(String name) {
            if (name != null) for (TasksType t : values()) if (t.name().equals(name)) return t;
            return DEFAULT;
        }
    }

    /** Parent.ParentType */
    public enum ParentType {
        DEFAULT("Обычная"),
        HIDDEN("Спрятана"),
        FAKE("Фейк");

        public final String ru;

        ParentType(String ru) { this.ru = ru; }

        public static ParentType of(String name) {
            if (name != null) for (ParentType p : values()) if (p.name().equals(name)) return p;
            return DEFAULT;
        }
    }

    /** Parent.LineType */
    public enum LineType {
        // символы дуги — из тех, что есть в системных шрифтах: ︵ и ︶
        // (вертикальные формы) не рисовались вовсе
        DEFAULT("Дуга ◠"),
        INVERTED("Дуга ◡"),
        STRAIGHT("Прямая ━");

        public final String ru;

        LineType(String ru) { this.ru = ru; }

        public static LineType of(String name) {
            if (name != null) for (LineType l : values()) if (l.name().equals(name)) return l;
            return DEFAULT;
        }
    }

    /**
     * IQuestTask.Type. Порядок значений сохранён как в моде.
     * kind: ITEM — задача с предметом, KILL, CONFIRM, TIME, OTHER.
     * prefix — как мод строит описание задачи (ItemTask.getDescription).
     */
    public enum TaskType {
        UNKNOWN(Kind.OTHER, "Неизвестно", "UNKNOWN: "),
        ITEM_DETECT(Kind.ITEM, "Сбор", "Сбор: "),
        ITEM_CRAFT(Kind.ITEM, "Создание", "Создание: "),
        ITEM_FORGE(Kind.ITEM, "Ковка", "Ковка: "),
        ITEM_ENCHANT(Kind.ITEM, "Зачарование", "Зачарование: "),
        ITEM_SMELT(Kind.ITEM, "Выплавка", "Выплавка: "),
        ITEM_GRIND(Kind.ITEM, "Измельчение", "Измельчение: "),
        ITEM_EXTRACT(Kind.ITEM, "Экстрагирование", "Экстрагирование: "),
        ITEM_COMPRESS(Kind.ITEM, "Сжатие", "Сжатие: "),
        ITEM_CANNING(Kind.ITEM, "Консервирование", "Консервирование: "),
        ITEM_CENTRIFUGE(Kind.ITEM, "Центрифугирование", "Центрифугирование: "),
        ITEM_WASH(Kind.ITEM, "Промывка", "Промывка: "),
        ITEM_CUT(Kind.ITEM, "Резка", "Резка: "),
        ITEM_EXTRUDE(Kind.ITEM, "Выдавливание", "Выдавливание: "),
        ITEM_ROLL(Kind.ITEM, "Прокатка", "Прокатка: "),
        ITEM_TRANSFORM(Kind.ITEM, "Преобразование", "Преобразование: "),
        ITEM_PHOTOSYNTHESIS(Kind.ITEM, "Фотосинтез", "Фотосинтез: "),
        ITEM_TRANSPOSE(Kind.ITEM, "Наполнение", "Наполнение: "),
        ITEM_SAW(Kind.ITEM, "Распил", "Распил: "),
        ITEM_RF_INFUSE(Kind.ITEM, "Зарядка", "Зарядка: "),
        ITEM_ASSEMBLE(Kind.ITEM, "Сборка", "Сборка: "),
        ITEM_INSCRIBE(Kind.ITEM, "Высечение", "Высечение: "),
        ITEM_PASS(Kind.ITEM, "Внести", ""),
        ENTITY_KILL(Kind.KILL, "Ликвидация", "Ликвидация: "),
        // В моде COMMAND читается как ItemTask и попадает в ветку default
        // getDescription(), поэтому в игре у неё префикс «UNKNOWN: ».
        COMMAND(Kind.ITEM, "Команда", "UNKNOWN: "),
        TASK_CONFIRM(Kind.CONFIRM, "Подтвердить", ""),
        PLAY_TIME(Kind.TIME, "Отыграть часов", "");

        public enum Kind { ITEM, KILL, CONFIRM, TIME, OTHER }

        public final Kind kind;
        public final String ru;
        public final String prefix;

        TaskType(Kind kind, String ru, String prefix) {
            this.kind = kind;
            this.ru = ru;
            this.prefix = prefix;
        }

        /** IQuestTask.Type.hasAction() — есть ли у задачи кнопка в GUI. */
        public boolean hasAction() { return this == TASK_CONFIRM || this == ITEM_PASS; }

        /** IQuestTask.Type.getButtonText() */
        public String buttonText() {
            switch (this) {
                case TASK_CONFIRM: return "Подтвердить";
                case ITEM_PASS: return "Внести";
                default: return "Unknown";
            }
        }

        /** Реализовано ли автоматическое отслеживание на сервере (TASK_TYPES_TODO.md). */
        public boolean serverSupported() {
            switch (this) {
                case TASK_CONFIRM:
                case ITEM_PASS:
                case ITEM_DETECT:
                case ITEM_CRAFT:
                case ITEM_SMELT:
                case ENTITY_KILL:
                case PLAY_TIME:
                    return true;
                default:
                    return false;
            }
        }

        public static TaskType of(String name) {
            if (name != null) for (TaskType t : values()) if (t.name().equals(name)) return t;
            return UNKNOWN;
        }
    }

    /**
     * ItemGift.GiftTexture. Числа — количество вариантов в каждом слое (pass);
     * файл текстуры: assets/dwquests/textures/items/gifts/&lt;name&gt;_&lt;id&gt;_&lt;pass&gt;.png.
     * Мета предмета = ordinal текстуры, NBT-поле Layers = id для каждого слоя.
     */
    public enum GiftTexture {
        Present("Подарок", 6, 6),
        Loot_box("Лутбокс", 6),
        Chest("Сундук", 9),
        Bag("Мешок", 25);

        public final String ru;
        public final int[] passes;

        GiftTexture(String ru, int... passes) {
            this.ru = ru;
            this.passes = passes;
        }

        public int passCount() { return passes.length; }

        public static GiftTexture of(int meta) {
            return meta >= 0 && meta < values().length ? values()[meta] : Present;
        }

        public static GiftTexture of(String name) {
            if (name != null) for (GiftTexture t : values()) if (t.name().equals(name)) return t;
            return Present;
        }
    }

    /** ContainerGift.DropType */
    public enum DropType {
        DEFAULT("Все предметы"),
        RANDOM("Случайно");

        public final String ru;

        DropType(String ru) { this.ru = ru; }

        public static DropType of(String name) {
            if (name != null) for (DropType d : values()) if (d.name().equals(name)) return d;
            return DEFAULT;
        }
    }

    /** PlayTimeTask.dependName — склонение «час/часа/часов». */
    public static String dependName(int n, String form1, String form2, String form3) {
        n = Math.abs(n) % 100;
        int n1 = n % 10;
        if (n > 10 && n < 20) return form3;
        if (n1 > 1 && n1 < 5) return form2;
        return n1 == 1 ? form1 : form3;
    }
}
