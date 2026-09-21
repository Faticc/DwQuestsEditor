package ru.dw.qbridge.quests;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.common.MinecraftForge;
import ru.dw.qbridge.client.Chat;
import ru.dw.qbridge.client.Job;

import java.util.ArrayList;
import java.util.List;

/**
 * Заливка нескольких вкладок подряд — по одной, тем же {@code /dwq tab paste}.
 *
 * <p>Подряд их слать нельзя: команда только просит сервер, а сервер потом
 * присылает запрос, и клиент читает буфер обмена <em>в этот момент</em>
 * ({@code CSPEditTab$Handler}), а имя вкладки берёт из запроса. Положи мы в
 * буфер следующую вкладку раньше — она сохранится под именем предыдущей.
 * Поэтому следующая уходит только после ответа сервера «… успешно сохранено»;
 * нет ответа — останавливаемся, а не гадаем.
 */
public final class TabBatchUpload implements Job {

    /** Сколько ждать ответа сервера на одну вкладку. */
    private static final long WAIT_MS = 20000L;

    private static boolean listening;
    private static volatile TabBatchUpload active;

    private final List<String[]> tabs;
    private final List<String> saved = new ArrayList<String>();
    private int index;
    private boolean waiting;
    private long sentAt;
    private volatile boolean confirmed;
    private String failure;

    /** @param tabs пары {tabID, json} */
    public TabBatchUpload(List<String[]> tabs) {
        this.tabs = tabs;
        if (!listening) {
            MinecraftForge.EVENT_BUS.register(new ChatListener());
            listening = true;
        }
        active = this;
    }

    @Override
    public String name() {
        return "Заливка вкладок (" + tabs.size() + ")";
    }

    @Override
    public float fraction() {
        return tabs.isEmpty() ? 1f : (float) saved.size() / (float) tabs.size();
    }

    @Override
    public boolean step(long budgetMs) {
        if (waiting) {
            if (confirmed) {
                saved.add(tabs.get(index)[0]);
                index++;
                waiting = false;
            } else if (System.currentTimeMillis() - sentAt > WAIT_MS) {
                failure = "сервер не подтвердил вкладку '" + tabs.get(index)[0]
                        + "' (нет прав на /dwq или сервер занят) — остальные не отправлены";
                Chat.error(failure);
                return finish();
            }
            return false;
        }
        if (index >= tabs.size()) return finish();

        String[] tab = tabs.get(index);
        confirmed = false;
        TabUpload.paste(tab[0], tab[1]);
        waiting = true;
        sentAt = System.currentTimeMillis();
        return false;
    }

    private boolean finish() {
        active = null;
        // в буфере осталась последняя вкладка — чужой текст там человеку ни к чему
        if (failure == null) GuiScreen.setClipboardString("");
        return true;
    }

    @Override
    public String summary() {
        return "залито " + saved.size() + " из " + tabs.size() + (failure == null ? "" : ". " + failure);
    }

    @Override
    public void abort() {
        active = null;
    }

    public List<String> saved() {
        return saved;
    }

    public String failure() {
        return failure;
    }

    /** Ответ сервера: «<вкладка> успешно сохранено» (подчёркивания там уже пробелы). */
    public static final class ChatListener {
        @SubscribeEvent
        public void onChat(ClientChatReceivedEvent event) {
            TabBatchUpload job = active;
            if (job == null || !job.waiting || event.message == null) return;
            String text = EnumChatFormatting.getTextWithoutFormattingCodes(event.message.getUnformattedText());
            if (text == null || !text.contains("успешно сохранено")) return;
            String tabID = job.tabs.get(job.index)[0];
            if (text.contains(tabID) || text.contains(tabID.replace('_', ' '))) job.confirmed = true;
        }
    }
}
