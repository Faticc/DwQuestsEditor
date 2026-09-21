package ru.dw.qbridge.export;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.item.ItemStack;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import ru.dw.qbridge.DwqBridge;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

/**
 * Рисует иконку предмета в кадровый буфер и снимает её картинкой.
 *
 * <p>Рисуется тем же вызовом, что и слот в инвентаре
 * ({@code RenderItem.renderItemAndEffectIntoGUI}), поэтому иконка получается
 * такой же, как в игре: и предметы с чужим рисователем (IItemRenderer), и
 * блоки кубом, и цвет из NBT — {@code getColorFromItemStack} видит наш стек
 * целиком. Из-за этого иконки цветов Botany и прочей генетики выходят разными,
 * хотя id у них один.
 *
 * <p>Вызывать только из главного потока: контекст OpenGL принадлежит ему.
 */
public final class IconRenderer {

    private final int size;
    private Framebuffer fbo;
    private ByteBuffer pixels;
    private int[] argb;

    public IconRenderer(int size) {
        this.size = size;
    }

    public int size() {
        return size;
    }

    /** Картинка иконки или null, если предмет не смог нарисоваться. */
    public BufferedImage render(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return null;
        Minecraft mc = Minecraft.getMinecraft();
        TextureManager tex = mc.getTextureManager();

        if (fbo == null) {
            fbo = new Framebuffer(size, size, true);
            fbo.setFramebufferFilter(GL11.GL_NEAREST);
            pixels = BufferUtils.createByteBuffer(size * size * 4);
            argb = new int[size * size];
        }

        RenderItem renderItem = RenderItem.getInstance();
        // Часть предметов рисует свой TESR прямо в слоте (сундуки, баннеры модов);
        // без этих полей диспетчер падает на null.
        TileEntityRendererDispatcher ter = TileEntityRendererDispatcher.instance;
        ter.field_147553_e = tex;
        if (ter.field_147550_f == null) ter.field_147550_f = mc.theWorld;
        if (ter.field_147551_g == null) ter.field_147551_g = mc.thePlayer;

        boolean ok = false;
        try {
            fbo.bindFramebuffer(true);
            GL11.glViewport(0, 0, size, size);
            GL11.glClearColor(0f, 0f, 0f, 0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();
            GL11.glOrtho(0.0D, 16.0D, 16.0D, 0.0D, 100.0D, 300.0D);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();
            GL11.glTranslatef(0f, 0f, -200f);

            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glColor4f(1f, 1f, 1f, 1f);
            // Тот же свет, что у слота инвентаря: без него кубы блоков выходят плоскими.
            RenderHelper.enableGUIStandardItemLighting();

            renderItem.zLevel = 0f;
            renderItem.renderItemAndEffectIntoGUI(mc.fontRenderer, tex, stack, 0, 0);
            ok = true;
        } catch (Throwable t) {
            // renderItemAndEffectIntoGUI заворачивает любую ошибку в ReportedException,
            // то есть один кривой предмет мода уронил бы всю выгрузку.
            DwqBridge.LOG.warn("иконка не нарисовалась: " + safeName(stack) + " — " + t);
        } finally {
            RenderHelper.disableStandardItemLighting();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
        }

        BufferedImage image = null;
        if (ok) {
            pixels.clear();
            GL11.glReadPixels(0, 0, size, size, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            image = toImage();
        }

        fbo.unbindFramebuffer();
        // Экран клиента: свой кадровый буфер игры (если включён) плюс родной вьюпорт.
        Framebuffer main = mc.getFramebuffer();
        if (main != null) main.bindFramebuffer(true);
        GL11.glViewport(0, 0, mc.displayWidth, mc.displayHeight);
        return image;
    }

    /** Кадровый буфер читается снизу вверх — переворачиваем. */
    private BufferedImage toImage() {
        boolean empty = true;
        for (int y = 0; y < size; y++) {
            int src = y * size * 4;
            int dst = (size - 1 - y) * size;
            for (int x = 0; x < size; x++, src += 4) {
                int r = pixels.get(src) & 0xFF;
                int g = pixels.get(src + 1) & 0xFF;
                int b = pixels.get(src + 2) & 0xFF;
                int a = pixels.get(src + 3) & 0xFF;
                if (a != 0) empty = false;
                argb[dst + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        if (empty) return null;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, size, size, argb, 0, size);
        return img;
    }

    public void dispose() {
        if (fbo != null) {
            fbo.deleteFramebuffer();
            fbo = null;
        }
    }

    private static String safeName(ItemStack stack) {
        try {
            return String.valueOf(stack.getItem()) + ":" + stack.getItemDamage();
        } catch (Throwable t) {
            return "?";
        }
    }
}
