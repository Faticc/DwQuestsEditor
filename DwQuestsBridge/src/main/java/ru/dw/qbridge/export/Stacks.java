package ru.dw.qbridge.export;

import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTTagCompound;
import ru.dw.qbridge.DwqBridge;

/**
 * Строка предмета → стек. Разбор тот же, что у {@code ForgeNameUtils}, но с
 * важной разницей: неизвестный id даёт {@code null}, а не бедрок.
 *
 * <p>Бедрок вместо ошибки — как раз то, из-за чего во вкладках заводятся серые
 * кубы: мод молча подставляет его на любой незнакомый id. Редактору и проверке
 * нужно знать правду, поэтому здесь честный null.
 */
public final class Stacks {

    private Stacks() {}

    public static ItemStack parse(String input) {
        if (input == null) return null;
        String text = input.trim();
        if (text.isEmpty() || text.equals("null") || text.startsWith("command:")) return null;

        String nbtText = null;
        int brace = text.indexOf('{');
        if (brace >= 0) {
            nbtText = text.substring(brace);
            text = text.substring(0, brace).trim();
        }

        int count = 1;
        int eq = text.lastIndexOf('=');
        if (eq >= 0) {
            try {
                count = Integer.parseInt(text.substring(eq + 1).trim());
            } catch (NumberFormatException ignored) {
                // "=" бывает и внутри имени — тогда это не количество
                eq = -1;
            }
            if (eq >= 0) text = text.substring(0, eq).trim();
        }

        String[] parts = text.split(":");
        if (parts.length < 2) return null;
        int meta = 0;
        if (parts.length > 2) {
            if (parts[2].equals("*")) meta = 32767;
            else {
                try {
                    meta = Integer.parseInt(parts[2]);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }

        Item item = GameRegistry.findItem(parts[0], parts[1]);
        if (item == null) return null;

        ItemStack stack = new ItemStack(item, Math.max(1, Math.min(count, 64)), meta == 32767 ? 0 : meta);
        if (nbtText != null) {
            try {
                stack.setTagCompound((NBTTagCompound) JsonToNBT.func_150315_a(nbtText));
            } catch (Throwable e) {
                DwqBridge.LOG.warn("NBT не разобралось у " + input + ": " + e);
                return null;
            }
        }
        return stack;
    }
}
