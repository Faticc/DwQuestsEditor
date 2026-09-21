package ru.dw.qbridge.quests;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.common.MinecraftForge;
import ru.sky.dw.quests.client.ClientQuestManager;
import ru.sky.dw.quests.common.network.C2SGiveAllRewards;
import ru.sky.dw.quests.common.network.NetworkHandler;
import ru.sky.dw.quests.common.network.SPQuestButtons;
import ru.sky.dw.quests.common.quest.Quest;
import ru.sky.dw.quests.common.quest.QuestTab;
import ru.sky.dw.quests.common.quest.playerdata.PlayerData;
import ru.sky.dw.quests.common.quest.playerdata.QuestData;
import ru.sky.dw.quests.common.quest.playerdata.TabData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Прогресс квестов <em>своего</em> игрока: выполнить, сбросить, забрать награду.
 *
 * <p>Два пути, и оба штатные:
 * <ul>
 * <li>выполнение и сброс — команды {@code /dwq complete…} / {@code /dwq clear…}
 *   из чата от имени игрока, права проверяет сервер;</li>
 * <li>награды — те же пакеты, что шлют кнопки книги квестов «Забрать награду»
 *   ({@code QuestPanel}) и «Забрать все награды» ({@code TabButtons}).</li>
 * </ul>
 *
 * <p>Команды пропускаются только из короткого списка и только на себя:
 * последний аргумент обязан быть ником этого клиента. Мост слушает любой
 * локальный процесс с ключом — чужой прогресс через него трогать нельзя.
 *
 * <p>Все методы, кроме {@link #takeReplies}, — из главного потока.
 */
public final class QuestProgress {

    private static final Set<String> COMMANDS = new HashSet<String>(Arrays.asList(
            "complete", "complete_quest", "complete_with_reward", "clear", "clear_quest"));

    /** Чат 1.7.10 режет сообщение до 100 символов — длинная команда ушла бы обрезанной. */
    private static final int CHAT_LIMIT = 100;

    private static boolean listening;
    private static final List<String> replies = new ArrayList<String>();
    private static volatile boolean capturing;

    private QuestProgress() {}

    public static String player() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) throw new IllegalStateException("игрок не в мире");
        return mc.thePlayer.getCommandSenderName();
    }

    // ------------------------------------------------------------------ команды

    /** Проверить и отправить команду в чат. Возвращает отправленный текст. */
    public static String command(String text) {
        String me = player();
        String cmd = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        String[] parts = cmd.split(" ");
        if (parts.length < 3 || !parts[0].equals("/dwq") || !COMMANDS.contains(parts[1])) {
            throw new IllegalArgumentException("мост пропускает только /dwq " + COMMANDS + ", а пришло: " + cmd);
        }
        if (!parts[parts.length - 1].equalsIgnoreCase(me)) {
            throw new IllegalArgumentException("команда должна быть на своего игрока: последним аргументом "
                    + me + ", а не " + parts[parts.length - 1]);
        }
        if (cmd.length() > CHAT_LIMIT) {
            throw new IllegalArgumentException("команда длиннее " + CHAT_LIMIT + " символов — чат её обрежет");
        }
        startCapture();
        Minecraft.getMinecraft().thePlayer.sendChatMessage(cmd);
        return cmd;
    }

    // ------------------------------------------------------------------ награды

    /** Кнопка «Забрать награду» у одного квеста — с теми же проверками, что в книге. */
    public static String claim(String tabID, String questID) {
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player == null) throw new IllegalStateException("игрок не в мире");
        QuestTab tab = Quests.tab(tabID);
        Quest quest = tab == null ? null : tab.getQuestByID(questID);
        if (quest == null) throw new IllegalArgumentException("в игре нет квеста " + tabID + " / " + questID);
        QuestData data = questData(tabID, questID);
        if (data == null || !data.isCompleted()) throw new IllegalStateException("квест ещё не выполнен");
        if (data.isRewarded()) throw new IllegalStateException("награда уже забрана");
        int need = quest.getRewardStacks().size();
        if (freeSlots(player) < need) {
            throw new IllegalStateException("в инвентаре нет места: нужно " + need + " свободных слотов");
        }
        startCapture();
        NetworkHandler.sendToServer(new SPQuestButtons(SPQuestButtons.Type.GET_REWARD, tabID, questID));
        // книга делает так же: не ждёт синхронизации, чтобы кнопку не нажали дважды
        data.setRewarded(true);
        return "награда за " + questID + " запрошена";
    }

    /**
     * «Забрать награду» у нескольких квестов вкладки разом; пустой список —
     * все выполненные квесты вкладки с незабранной наградой. Место в инвентаре
     * считается на всех: как только его не хватает, остальные не просим.
     */
    public static String claimMany(String tabID, List<String> questIDs) {
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player == null) throw new IllegalStateException("игрок не в мире");
        QuestTab tab = Quests.tab(tabID);
        if (tab == null) throw new IllegalArgumentException("в игре нет вкладки '" + tabID + "'");
        List<Quest> quests = new ArrayList<Quest>();
        if (questIDs.isEmpty()) {
            quests.addAll(tab.getQuests());
        } else {
            for (String id : questIDs) {
                Quest q = tab.getQuestByID(id);
                if (q != null) quests.add(q);
            }
        }
        int free = freeSlots(player);
        int sent = 0, notDone = 0, noRoom = 0;
        for (Quest quest : quests) {
            QuestData data = questData(tabID, quest.getQuestID());
            if (data == null || !data.isCompleted()) {
                notDone++;
                continue;
            }
            if (data.isRewarded()) continue;
            int need = quest.getRewardStacks().size();
            if (need > free) {
                noRoom++;
                continue;
            }
            if (sent == 0) startCapture();
            NetworkHandler.sendToServer(new SPQuestButtons(SPQuestButtons.Type.GET_REWARD, tabID, quest.getQuestID()));
            data.setRewarded(true);
            free -= need;
            sent++;
        }
        if (sent == 0 && noRoom == 0) {
            throw new IllegalStateException(questIDs.size() == 1 && notDone == 1
                    ? "квест ещё не выполнен" : "незабранных наград нет");
        }
        return "запрошено наград: " + sent
                + (noRoom > 0 ? ", не хватило места ещё на " + noRoom : "")
                + (notDone > 0 && !questIDs.isEmpty() ? ", не выполнено: " + notDone : "");
    }

    /** Кнопка «Забрать все награды». */
    public static String claimAll() {
        player();
        int waiting = 0;
        PlayerData pd = playerData();
        if (pd != null) {
            for (TabData tab : pd.getTabData().values()) {
                for (QuestData q : tab.getQuestData().values()) {
                    if (q.isCompleted() && !q.isRewarded()) waiting++;
                }
            }
            if (waiting == 0) throw new IllegalStateException("незабранных наград нет");
        }
        startCapture();
        NetworkHandler.sendToServer(new C2SGiveAllRewards());
        return pd == null ? "запрошены все награды" : "запрошены награды за квестов: " + waiting;
    }

    // ------------------------------------------------------------------ состояние

    /** Прогресс по вкладке: для каждого квеста — выполнен, награда, активен. */
    public static String progress(String tabID) {
        QuestTab tab = Quests.tab(tabID);
        if (tab == null) throw new IllegalArgumentException("нет вкладки '" + tabID + "'");
        PlayerData pd = playerData();
        StringBuilder sb = new StringBuilder("{\"player\":").append(quote(player()));
        sb.append(",\"known\":").append(pd != null).append(",\"quests\":{");
        boolean first = true;
        for (Quest quest : tab.getQuests()) {
            QuestData q = questData(tabID, quest.getQuestID());
            if (!first) sb.append(',');
            first = false;
            sb.append(quote(quest.getQuestID())).append(":{\"completed\":").append(q != null && q.isCompleted())
                    .append(",\"rewarded\":").append(q != null && q.isRewarded())
                    .append(",\"active\":").append(pd != null && pd.isQuestActive(tabID, quest.getQuestID()))
                    .append('}');
        }
        return sb.append("}}").toString();
    }

    private static PlayerData playerData() {
        return ClientQuestManager.INSTANCE.getPlayerData(player());
    }

    private static QuestData questData(String tabID, String questID) {
        PlayerData pd = playerData();
        TabData tab = pd == null ? null : pd.getTabData(tabID);
        return tab == null ? null : tab.getQuestData(questID);
    }

    private static int freeSlots(EntityPlayer player) {
        int n = 0;
        for (int i = 0; i < player.inventory.mainInventory.length; i++) {
            if (player.inventory.mainInventory[i] == null) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ ответ сервера

    private static void startCapture() {
        if (!listening) {
            MinecraftForge.EVENT_BUS.register(new ChatListener());
            listening = true;
        }
        synchronized (replies) {
            replies.clear();
        }
        capturing = true;
    }

    /** Что пришло в чат после действия. Зовётся из потока моста, когда отведённое время вышло. */
    public static List<String> takeReplies() {
        capturing = false;
        synchronized (replies) {
            List<String> out = new ArrayList<String>(replies);
            replies.clear();
            return out;
        }
    }

    public static final class ChatListener {
        @SubscribeEvent
        public void onChat(ClientChatReceivedEvent event) {
            if (!capturing || event.message == null) return;
            String text = EnumChatFormatting.getTextWithoutFormattingCodes(event.message.getUnformattedText());
            if (text == null || text.trim().isEmpty()) return;
            synchronized (replies) {
                if (replies.size() < 50) replies.add(text);
            }
        }
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }
}
