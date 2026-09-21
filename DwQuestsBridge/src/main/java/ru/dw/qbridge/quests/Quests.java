package ru.dw.qbridge.quests;

import cpw.mods.fml.common.Loader;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import ru.dw.qbridge.export.Stacks;
import ru.sky.dw.quests.client.ClientQuestManager;
import ru.sky.dw.quests.common.quest.Quest;
import ru.sky.dw.quests.common.quest.QuestTab;
import ru.sky.dw.quests.common.quest.task.IQuestTask;
import ru.sky.dw.quests.common.quest.task.ItemTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Доступ к вкладкам квестов, которые сервер прислал этому клиенту.
 *
 * <p>Сервер шлёт всю карту квестов всем игрокам ({@code CSPQuestSync}), так что
 * читать её можно без каких-либо прав — это те же данные, по которым рисуется
 * окно квестов.
 *
 * <p>Класс трогает классы DwQuests, поэтому загружать его можно только после
 * {@link #modPresent()} — иначе в клиенте без квестов будет NoClassDefFoundError.
 */
public final class Quests {

    private Quests() {}

    /** Проверка без обращения к классам мода — её можно звать всегда. */
    public static boolean modPresent() {
        return Loader.isModLoaded("DwQuests");
    }

    public static Collection<QuestTab> tabs() {
        List<QuestTab> list = new ArrayList<QuestTab>(ClientQuestManager.INSTANCE.getQuestData().values());
        Collections.sort(list, new java.util.Comparator<QuestTab>() {
            public int compare(QuestTab a, QuestTab b) {
                if (a.getSortIndex() != b.getSortIndex()) return a.getSortIndex() - b.getSortIndex();
                return a.getTabID().compareTo(b.getTabID());
            }
        });
        return list;
    }

    public static QuestTab tab(String tabID) {
        return ClientQuestManager.INSTANCE.getQuestData().get(tabID);
    }

    public static int questCount() {
        int n = 0;
        for (QuestTab tab : ClientQuestManager.INSTANCE.getQuestData().values()) n += tab.getQuestsCount();
        return n;
    }

    /**
     * Все предметы, которые встречаются во вкладках: иконки вкладок и квестов,
     * предметы задач, награды и содержимое гифтов. Ровно то, что редактору надо
     * уметь нарисовать, — по этому списку удобно выгружать иконки точечно.
     */
    public static List<ItemStack> referencedStacks() {
        List<ItemStack> out = new ArrayList<ItemStack>();
        for (QuestTab tab : tabs()) {
            add(out, tab.getIconStack());
            for (Quest quest : tab.getQuests()) {
                add(out, quest.getIconStack());
                for (IQuestTask task : quest.getTasks()) {
                    if (task instanceof ItemTask) {
                        ItemTask item = (ItemTask) task;
                        if (item.getItemInfo() != null) add(out, item.getItemInfo().getItemStack());
                    }
                }
                for (ItemStack reward : quest.getRewardStacks()) {
                    add(out, reward);
                    addGiftContents(out, reward);
                }
            }
        }
        return out;
    }

    /**
     * Предметы внутри гифта лежат не так, как везде: слот — это {@code {Slot, ID, Tag}},
     * где NBT предмета вынесен в отдельный тег (см. {@code ItemGift.saveInventoryToNBT}).
     */
    private static void addGiftContents(List<ItemStack> out, ItemStack gift) {
        if (gift == null || !gift.hasTagCompound()) return;
        NBTTagCompound tag = gift.getTagCompound();
        if (!tag.hasKey("Items", 9)) return;
        NBTTagList items = tag.getTagList("Items", 10);
        for (int i = 0; i < items.tagCount(); i++) {
            NBTTagCompound slot = items.getCompoundTagAt(i);
            ItemStack stack = Stacks.parse(slot.getString("ID"));
            if (stack == null) continue;
            if (slot.hasKey("Tag", 10)) stack.setTagCompound(slot.getCompoundTag("Tag"));
            add(out, stack);
        }
    }

    private static void add(List<ItemStack> out, ItemStack stack) {
        if (stack != null && stack.getItem() != null) out.add(stack);
    }

    /**
     * Картинки из описаний: {@code <IMG>mod:textures/…:u:v:w:h[:scale]</IMG>}.
     * Их тоже надо выгрузить, иначе в просмотре редактора будут дыры.
     */
    public static Set<ResourceLocation> referencedImages() {
        Set<ResourceLocation> out = new LinkedHashSet<ResourceLocation>();
        for (QuestTab tab : tabs()) {
            for (Quest quest : tab.getQuests()) {
                collectImages(quest.getDescription(), out);
            }
        }
        return out;
    }

    private static void collectImages(String description, Set<ResourceLocation> out) {
        if (description == null) return;
        int from = 0;
        while (true) {
            int start = description.indexOf("<IMG>", from);
            if (start < 0) return;
            int end = description.indexOf("</IMG>", start);
            if (end < 0) return;
            String body = description.substring(start + 5, end);
            from = end + 6;
            String[] parts = body.split(":");
            if (parts.length < 2) continue;
            String domain = parts[0].toLowerCase(Locale.ROOT);
            if (domain.isEmpty() || parts[1].isEmpty()) continue;
            try {
                out.add(new ResourceLocation(domain, parts[1]));
            } catch (Throwable ignored) {
                // мусор в описании — не повод ронять выгрузку
            }
        }
    }

    /** Фон вкладки лежит в ресурсах мода под её именем. */
    public static Set<ResourceLocation> backgrounds() {
        Set<ResourceLocation> out = new LinkedHashSet<ResourceLocation>();
        for (QuestTab tab : tabs()) {
            out.add(new ResourceLocation("dwquests", "textures/gui/backgrounds/" + tab.getTabID() + ".png"));
        }
        return out;
    }
}
