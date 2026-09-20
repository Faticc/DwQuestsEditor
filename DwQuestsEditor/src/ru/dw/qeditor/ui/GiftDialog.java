package ru.dw.qeditor.ui;

import ru.dw.qeditor.assets.Gifts;
import ru.dw.qeditor.assets.NbtLite;
import ru.dw.qeditor.model.Defs;
import ru.dw.qeditor.util.ForgeName;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Window;

/**
 * Окно редактора гифта — точная копия игрового GuiContainerGift, только слоты
 * заполняются через выбор предмета, а не перетаскиванием из инвентаря.
 *
 * <p>Результат — строка награды {@code DwQuests:Gift:<мета>{<NBT>}} с тем же
 * набором тегов, что пишет мод (Layers, DropType, display.Name, display.Lore,
 * MinRandom/MaxRandom, Items со Slot).
 */
public class GiftDialog extends JDialog {

    private final GiftEditorCanvas canvas = new GiftEditorCanvas();
    private final JLabel preview = new JLabel();
    private final JCheckBox resetPrefix = new JCheckBox("Ставить §r перед именем (как в игре)", true);
    private String result;

    private GiftDialog(Window owner, String reward) {
        super(owner, "Гифт (DwQuests:Gift)", ModalityType.APPLICATION_MODAL);
        load(reward);

        canvas.setOnChange(this::updatePreview);
        updatePreview();

        JPanel south = new JPanel(new BorderLayout());
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        left.add(new JLabel("Предпросмотр:"));
        left.add(preview);
        left.add(resetPrefix);
        left.add(Ui.smallBtn("Масштаб −", "Уменьшить окно", () -> canvas.setScale(1)));
        left.add(Ui.smallBtn("Масштаб +", "Увеличить окно", () -> canvas.setScale(3)));
        south.add(left, BorderLayout.WEST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        JButton ok = Ui.btn("Готово", this::accept);
        buttons.add(ok);
        buttons.add(Ui.btn("Отмена", () -> { result = null; dispose(); }));
        south.add(buttons, BorderLayout.EAST);

        JScrollPane scroll = new JScrollPane(canvas);
        scroll.setBorder(BorderFactory.createEmptyBorder());

        setLayout(new BorderLayout());
        add(scroll, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(ok);
        pack();
        setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(canvas::requestFocusInWindow);
    }

    /** @return строка награды-гифта либо null, если отменено. */
    public static String edit(Component parent, String reward) {
        Window w = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        GiftDialog d = new GiftDialog(w, reward);
        d.setVisible(true);
        d.canvas.dispose();
        return d.result;
    }

    // ------------------------------------------------------------------ NBT

    private void load(String reward) {
        ForgeName fn = ForgeName.parse(reward == null || reward.isEmpty() ? "DwQuests:Gift:0" : reward);
        Defs.GiftTexture tex = Defs.GiftTexture.of(fn.meta < 0 ? 0 : fn.meta);
        canvas.setTexture(tex);
        canvas.setLayers(Gifts.layers(fn.nbt, tex));

        NbtLite.Compound nbt = fn.nbt == null ? null : NbtLite.parse(fn.nbt);
        if (nbt == null) {
            canvas.setName("Базовый Гифт");
            canvas.setLore("");
            canvas.setDropType(Defs.DropType.DEFAULT);
            return;
        }

        NbtLite.Compound display = nbt.comp("display");
        canvas.setName(display == null ? "Базовый Гифт" : nz(display.string("Name")));
        canvas.setLore(display == null ? "" : nz(display.string("Lore")));
        canvas.setDropType(Defs.DropType.of(nbt.string("DropType")));
        canvas.setRandom(nbt.i("MinRandom", 1), nbt.i("MaxRandom", 1));

        String[] bySlot = new String[27];
        NbtLite.ListTag list = nbt.list("Items");
        if (list != null) {
            int fallback = 0;
            for (Object o : list) {
                if (!(o instanceof NbtLite.Compound)) continue;
                NbtLite.Compound item = (NbtLite.Compound) o;
                String id = item.string("ID");
                if (id == null) continue;
                int slot = item.i("Slot", -1);
                if (slot < 0 || slot >= bySlot.length) slot = fallback;
                while (slot < bySlot.length && bySlot[slot] != null) slot++;
                if (slot < bySlot.length) bySlot[slot] = id;
                fallback = Math.min(bySlot.length - 1, fallback + 1);
            }
        }
        canvas.setSlots(bySlot);
    }

    private String buildReward() {
        NbtLite.Compound nbt = new NbtLite.Compound();

        NbtLite.ListTag layers = new NbtLite.ListTag();
        for (int v : canvas.getLayers()) layers.add(NbtLite.Num.of(v));
        nbt.put("Layers", layers);

        nbt.put("DropType", canvas.getDropType().name());

        NbtLite.Compound display = new NbtLite.Compound();
        String name = canvas.getName();
        if (name == null || name.trim().isEmpty()) name = "Базовый Гифт";
        name = name.replace('&', '§');
        if (resetPrefix.isSelected() && !name.startsWith("§r")) name = "§r" + name;
        display.put("Name", name);
        display.put("Lore", canvas.getLore() == null ? "" : canvas.getLore().replace('&', '§'));
        nbt.put("display", display);

        if (canvas.getDropType() == Defs.DropType.RANDOM) {
            nbt.put("MinRandom", NbtLite.Num.of(canvas.getMinRandom()));
            nbt.put("MaxRandom", NbtLite.Num.of(canvas.getMaxRandom()));
        }

        NbtLite.ListTag items = new NbtLite.ListTag();
        items.indexed = true;
        String[] slots = canvas.getSlots();
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null) continue;
            NbtLite.Compound item = new NbtLite.Compound();
            item.put("Slot", NbtLite.Num.of(i, 'b'));
            item.put("ID", slots[i]);
            items.add(item);
        }
        nbt.put("Items", items);

        return "DwQuests:Gift:" + canvas.getTexture().ordinal() + NbtLite.write(nbt);
    }

    private void updatePreview() {
        preview.setIcon(Ui.itemIcon(buildReward(), 32));
        preview.setToolTipText(canvas.getName());
    }

    private void accept() {
        result = buildReward();
        dispose();
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
