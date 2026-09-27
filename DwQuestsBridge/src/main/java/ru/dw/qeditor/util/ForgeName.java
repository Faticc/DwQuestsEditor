package ru.dw.qeditor.util;

/**
 * Разбор строки предмета в формате мода: {@code mod:name[:meta][=count][{nbt}]}.
 * Повторяет ForgeNameUtils.getForgeItemWithNbt / getForgeItemWithSize / getForgeItem.
 */
public final class ForgeName {

    public final String raw;
    /** "mod:name[:meta]" — без количества и NBT. */
    public final String id;
    public final String mod;
    public final String name;
    /** Мета; -1 если не указана, 32767 для "*". */
    public final int meta;
    public final String metaRaw;
    public final int count;
    public final boolean hasCount;
    /** NBT-строка вместе с фигурными скобками либо null. */
    public final String nbt;
    /** Награда вида "command:..." — тогда id/mod пустые, а command содержит команду. */
    public final String command;

    private ForgeName(String raw, String id, String mod, String name, int meta, String metaRaw,
                      int count, boolean hasCount, String nbt, String command) {
        this.raw = raw;
        this.id = id;
        this.mod = mod;
        this.name = name;
        this.meta = meta;
        this.metaRaw = metaRaw;
        this.count = count;
        this.hasCount = hasCount;
        this.nbt = nbt;
        this.command = command;
    }

    public static ForgeName parse(String input) {
        String raw = input == null ? "" : input;
        if (raw.startsWith("command:"))
            return new ForgeName(raw, "", "", "", -1, null, 1, false, null, raw.substring("command:".length()));

        String head = raw;
        String nbt = null;
        int brace = raw.indexOf('{');
        if (brace >= 0) {
            head = raw.substring(0, brace).trim();
            int close = raw.lastIndexOf('}');
            nbt = close > brace ? raw.substring(brace, close + 1) : raw.substring(brace);
        }

        int count = 1;
        boolean hasCount = false;
        int eq = head.lastIndexOf('=');
        if (eq >= 0) {
            String tail = head.substring(eq + 1).trim();
            head = head.substring(0, eq);
            try {
                count = Integer.parseInt(tail);
                hasCount = true;
            } catch (NumberFormatException ignored) { }
        }

        String[] parts = head.split(":");
        String mod = parts.length > 0 ? parts[0] : "";
        String name = parts.length > 1 ? parts[1] : "";
        String metaRaw = parts.length > 2 ? parts[2] : null;
        int meta = -1;
        if (metaRaw != null) {
            if (metaRaw.equals("*")) meta = 32767;
            else {
                try { meta = Integer.parseInt(metaRaw); } catch (NumberFormatException ignored) { meta = -1; }
            }
        }
        String id = metaRaw != null ? mod + ":" + name + ":" + metaRaw : mod + ":" + name;
        return new ForgeName(raw, id, mod, name, meta, metaRaw, count, hasCount, nbt, null);
    }

    public boolean isCommand() { return command != null; }

    public boolean isGift() { return id.startsWith("DwQuests:Gift"); }

    public boolean isScript() { return id.startsWith("customnpcs:scripted_item"); }

    /** Собрать строку обратно: id[=count][nbt]. */
    public static String build(String id, int count, String nbt) {
        StringBuilder sb = new StringBuilder(id == null ? "" : id);
        if (count > 1) sb.append('=').append(count);
        if (nbt != null && !nbt.isEmpty()) sb.append(nbt);
        return sb.toString();
    }

    /** Заменить только предметный id, сохранив количество и NBT. */
    public String withId(String newId) { return build(newId, hasCount ? count : 1, nbt); }

    /** Заменить только количество. */
    public String withCount(int newCount) { return build(id, newCount, nbt); }

    /** Заменить только NBT. */
    public String withNbt(String newNbt) { return build(id, hasCount ? count : 1, newNbt); }
}
