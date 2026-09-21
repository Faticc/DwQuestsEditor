package ru.dw.qbridge.export;

import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.StatCollector;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Имена предметов: строка мода, имя файла иконки и «приведённый» вид NBT.
 *
 * <p>Строка предмета — та же, что понимает мод: {@code mod:name[:meta][{nbt}]}
 * (см. {@code ForgeNameUtils.getNameFromStackWithNBT} в DwCore). Своя реализация,
 * а не вызов DwCore, чтобы выгрузка иконок работала в любом клиенте 1.7.10 —
 * даже там, где квестов нет вовсе.
 *
 * <p>Экранирование имени файла — как у старого icon_exporter (символ → {@code $индекс$}),
 * иначе редактор не найдёт уже выгруженные иконки.
 */
public final class Names {

    private static final char[] ILLEGAL = {'\\', '/', ':', '*', '?', '"', '<', '>', '|'};

    private Names() {}

    /** {@code mod:name[:meta]} — мета опускается, если 0. */
    public static String id(ItemStack stack) {
        Object name = Item.itemRegistry.getNameForObject(stack.getItem());
        if (name == null) {
            GameRegistry.UniqueIdentifier uid = GameRegistry.findUniqueIdentifierFor(stack.getItem());
            name = uid == null ? null : uid.modId + ":" + uid.name;
        }
        if (name == null) return null;
        int meta = stack.getItemDamage();
        return meta == 0 ? name.toString() : name + ":" + meta;
    }

    /** Полная строка предмета вместе с NBT — то, что пишут в поле iconItem вкладки. */
    public static String full(ItemStack stack) {
        String id = id(stack);
        if (id == null) return null;
        if (!stack.hasTagCompound()) return id;
        return id + nbtText(stack.getTagCompound());
    }

    /** NBT в том виде, в каком его принимает JsonToNBT (как это делает DwCore). */
    public static String nbtText(NBTTagCompound tag) {
        // NBTTagCompound.toString() в 1.7.10 оставляет висящие запятые: {a:1,}
        return tag.toString().replaceAll(",]", "]").replaceAll(",}", "}");
    }

    /**
     * Приведённый вид NBT: ключи отсортированы, типовые суффиксы чисел убраны,
     * пробелов нет. Нужен, чтобы иконку нашли по NBT, написанному во вкладке
     * руками — там порядок ключей и запись чисел другие, а предмет тот же.
     * Ровно то же самое считает редактор (NbtLite.canonical).
     */
    public static String canonical(NBTBase tag) {
        StringBuilder sb = new StringBuilder();
        canonical(tag, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void canonical(NBTBase tag, StringBuilder sb) {
        if (tag == null) {
            sb.append("null");
            return;
        }
        if (tag instanceof NBTTagCompound) {
            NBTTagCompound c = (NBTTagCompound) tag;
            List<String> keys = new ArrayList<String>((Collection<String>) c.func_150296_c());
            Collections.sort(keys);
            sb.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(keys.get(i)).append(':');
                canonical(c.getTag(keys.get(i)), sb);
            }
            sb.append('}');
            return;
        }
        if (tag instanceof NBTTagList) {
            NBTTagList l = (NBTTagList) tag;
            sb.append('[');
            for (int i = 0; i < l.tagCount(); i++) {
                if (i > 0) sb.append(',');
                // getCompoundTagAt отдаёт пустой тег для не-составных списков,
                // поэтому берём элемент как есть через копию списка.
                canonical(elementAt(l, i), sb);
            }
            sb.append(']');
            return;
        }
        if (tag instanceof NBTTagIntArray) {
            int[] a = ((NBTTagIntArray) tag).func_150302_c();
            sb.append('[');
            for (int i = 0; i < a.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(a[i]);
            }
            sb.append(']');
            return;
        }
        if (tag instanceof NBTTagByteArray) {
            byte[] a = ((NBTTagByteArray) tag).func_150292_c();
            sb.append('[');
            for (int i = 0; i < a.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(a[i]);
            }
            sb.append(']');
            return;
        }
        String text = tag.toString();
        if (text.startsWith("\"")) {
            sb.append(text);
            return;
        }
        sb.append(number(text));
    }

    /** Элемент списка любого типа: в 1.7.10 у NBTTagList нет публичного get. */
    private static NBTBase elementAt(NBTTagList list, int index) {
        NBTTagList copy = (NBTTagList) list.copy();
        // removeTag отдаёт сам элемент; работаем на копии, чтобы не портить стек.
        for (int i = 0; i < index; i++) copy.removeTag(0);
        return copy.removeTag(0);
    }

    /** "12b" -> "12", "1.0f" -> "1", "0.50d" -> "0.5". */
    static String number(String raw) {
        String s = raw.trim();
        if (s.isEmpty()) return s;
        char last = s.charAt(s.length() - 1);
        if (last == 'b' || last == 's' || last == 'L' || last == 'l'
                || last == 'f' || last == 'F' || last == 'd' || last == 'D') {
            s = s.substring(0, s.length() - 1);
        }
        try {
            double d = Double.parseDouble(s);
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                return String.valueOf((long) d);
            }
            return String.valueOf(d);
        } catch (NumberFormatException e) {
            return s;
        }
    }

    /** Первые 8 знаков SHA-1 приведённого NBT — хвост имени файла иконки. */
    public static String nbtHash(NBTTagCompound tag) {
        return hash(canonical(tag));
    }

    public static String hash(String canonical) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(canonical.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(Character.forDigit((d[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(d[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(canonical.hashCode());
        }
    }

    /**
     * Путь картинки внутри img/: {@code <мод>/<имя>[_мета][~хеш].png}.
     * Хвост {@code ~хеш} появляется только у предметов с NBT.
     */
    public static String iconPath(ItemStack stack) {
        Object registry = Item.itemRegistry.getNameForObject(stack.getItem());
        if (registry == null) return null;
        String s = registry.toString();
        int colon = s.indexOf(':');
        String mod = colon > 0 ? s.substring(0, colon) : "minecraft";
        String name = colon > 0 ? s.substring(colon + 1) : s;
        StringBuilder sb = new StringBuilder();
        sb.append(esc(mod)).append('/').append(esc(name));
        if (stack.getItemDamage() != 0) sb.append('_').append(stack.getItemDamage());
        if (stack.hasTagCompound()) sb.append('~').append(nbtHash(stack.getTagCompound()));
        return sb.append(".png").toString();
    }

    /** Экранирование под файловую систему — один-в-один со старым экспортёром. */
    public static String esc(String name) {
        String out = name;
        for (int i = 0; i < ILLEGAL.length; i++) {
            out = out.replace(String.valueOf(ILLEGAL[i]), "$" + i + "$");
        }
        return out;
    }

    /**
     * Имя файла вкладки: пробелы — подчёркиванием, как в {@code /dwq tab paste Имя_вкладки}
     * и как называет файлы редактор.
     */
    public static String tabFile(String tabID) {
        return esc(tabID).replace(' ', '_') + ".json";
    }

    /** Название предмета в игре; у битых предметов бывает исключение. */
    public static String displayName(ItemStack stack) {
        try {
            String n = stack.getDisplayName();
            if (n != null && !n.isEmpty()) return n;
        } catch (Throwable ignored) {
            // локализаторы некоторых модов падают на чужих стеках
        }
        try {
            return StatCollector.translateToLocal(stack.getUnlocalizedName() + ".name");
        } catch (Throwable ignored) {
            return String.valueOf(id(stack));
        }
    }

    /** Числовой id как у старого экспортёра: {@code <id>[:мета]}. */
    public static String numericId(ItemStack stack) {
        int id = Item.getIdFromItem(stack.getItem());
        int meta = stack.getItemDamage();
        return meta == 0 ? String.valueOf(id) : id + ":" + meta;
    }
}
