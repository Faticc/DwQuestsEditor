package ru.dw.qbridge.editor;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Состояние OpenGL вокруг кадра редактора.
 *
 * <p>Редактор рисует шейдерами GL 3.3 со своим массивом вершин, а Minecraft —
 * фиксированным конвейером с массивами из памяти клиента (Tessellator), и
 * последнему нужен массив вершин 0 и буфер 0. Поэтому перед кадром всё, что
 * трогает редактор, запоминается, а после — возвращается как было: иначе
 * мир, чат и прочие экраны нарисуются с чужими смешиванием, отсечением и
 * текстурами.
 *
 * <p>Кроме того, контекст игры — совместимого профиля, и в нём продолжают
 * работать фиксированные проверки поверх шейдера: прежде всего отсечение по
 * альфе (Minecraft держит {@code GL_GREATER 0.1}), которое выбросило бы
 * полупрозрачные края свечения и сглаженного текста. Их на время кадра
 * выключаем.
 */
@Lwjgl3Aware
final class GlState {

    private int program;
    private int vao;
    private int arrayBuffer;
    private int drawFbo;
    private int readFbo;
    private int activeTexture;

    void save() {
        program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        readFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);

        // включения, смешивание, отсечение, вьюпорт, привязки текстур по блокам
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        // выравнивание и длина строк при загрузке текстур
        GL11.glPushClientAttrib(GL11.GL_CLIENT_ALL_ATTRIB_BITS);

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_FOG);
        GL11.glColorMask(true, true, true, true);
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
    }

    void restore() {
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
        GL20.glUseProgram(program);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
        GL11.glPopClientAttrib();
        GL11.glPopAttrib();
        GL13.glActiveTexture(activeTexture);
    }
}
