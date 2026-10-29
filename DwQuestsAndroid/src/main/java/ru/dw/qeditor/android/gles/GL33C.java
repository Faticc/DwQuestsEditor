package ru.dw.qeditor.android.gles;

import android.opengl.GLES30;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

/**
 * Те вызовы {@code org.lwjgl.opengl.GL33C}, которыми пользуется редактор, —
 * поверх GLES 3.0.
 *
 * <p>Общие исходники при сборке получают этот класс вместо LWJGL-овского
 * (см. build.gradle), поэтому подписи здесь те же, что у LWJGL: «gen» без
 * массивов, смещения в буфере числом {@code long}, буфер без явного размера.
 * Набор вызовов маленький — пакет вершин, текстуры, кадровый буфер, — и всё
 * это есть в GLES 3.0 один к одному.
 */
public final class GL33C {

    private GL33C() { }

    public static final int GL_FALSE = 0;
    public static final int GL_TRUE = 1;
    public static final int GL_ARRAY_BUFFER = GLES30.GL_ARRAY_BUFFER;
    public static final int GL_ELEMENT_ARRAY_BUFFER = GLES30.GL_ELEMENT_ARRAY_BUFFER;
    public static final int GL_STATIC_DRAW = GLES30.GL_STATIC_DRAW;
    public static final int GL_STREAM_DRAW = GLES30.GL_STREAM_DRAW;
    public static final int GL_BLEND = GLES30.GL_BLEND;
    public static final int GL_CULL_FACE = GLES30.GL_CULL_FACE;
    public static final int GL_DEPTH_TEST = GLES30.GL_DEPTH_TEST;
    public static final int GL_SCISSOR_TEST = GLES30.GL_SCISSOR_TEST;
    public static final int GL_CLAMP_TO_EDGE = GLES30.GL_CLAMP_TO_EDGE;
    public static final int GL_REPEAT = GLES30.GL_REPEAT;
    public static final int GL_COLOR_ATTACHMENT0 = GLES30.GL_COLOR_ATTACHMENT0;
    public static final int GL_COLOR_BUFFER_BIT = GLES30.GL_COLOR_BUFFER_BIT;
    public static final int GL_COMPILE_STATUS = GLES30.GL_COMPILE_STATUS;
    public static final int GL_LINK_STATUS = GLES30.GL_LINK_STATUS;
    public static final int GL_FLOAT = GLES30.GL_FLOAT;
    public static final int GL_UNSIGNED_BYTE = GLES30.GL_UNSIGNED_BYTE;
    public static final int GL_UNSIGNED_SHORT = GLES30.GL_UNSIGNED_SHORT;
    public static final int GL_VERTEX_SHADER = GLES30.GL_VERTEX_SHADER;
    public static final int GL_FRAGMENT_SHADER = GLES30.GL_FRAGMENT_SHADER;
    public static final int GL_FRAMEBUFFER = GLES30.GL_FRAMEBUFFER;
    public static final int GL_FRAMEBUFFER_BINDING = GLES30.GL_FRAMEBUFFER_BINDING;
    public static final int GL_FRAMEBUFFER_COMPLETE = GLES30.GL_FRAMEBUFFER_COMPLETE;
    public static final int GL_LINEAR = GLES30.GL_LINEAR;
    public static final int GL_NEAREST = GLES30.GL_NEAREST;
    public static final int GL_LINEAR_MIPMAP_LINEAR = GLES30.GL_LINEAR_MIPMAP_LINEAR;
    public static final int GL_LINE_STRIP = GLES30.GL_LINE_STRIP;
    public static final int GL_ONE = GLES30.GL_ONE;
    public static final int GL_SRC_ALPHA = GLES30.GL_SRC_ALPHA;
    public static final int GL_ONE_MINUS_SRC_ALPHA = GLES30.GL_ONE_MINUS_SRC_ALPHA;
    public static final int GL_PACK_ALIGNMENT = GLES30.GL_PACK_ALIGNMENT;
    public static final int GL_UNPACK_ALIGNMENT = GLES30.GL_UNPACK_ALIGNMENT;
    public static final int GL_RGBA = GLES30.GL_RGBA;
    public static final int GL_RGBA8 = GLES30.GL_RGBA8;
    public static final int GL_TEXTURE0 = GLES30.GL_TEXTURE0;
    public static final int GL_TEXTURE_2D = GLES30.GL_TEXTURE_2D;
    public static final int GL_TEXTURE_MAG_FILTER = GLES30.GL_TEXTURE_MAG_FILTER;
    public static final int GL_TEXTURE_MIN_FILTER = GLES30.GL_TEXTURE_MIN_FILTER;
    public static final int GL_TEXTURE_MAX_LEVEL = GLES30.GL_TEXTURE_MAX_LEVEL;
    public static final int GL_TEXTURE_WRAP_S = GLES30.GL_TEXTURE_WRAP_S;
    public static final int GL_TEXTURE_WRAP_T = GLES30.GL_TEXTURE_WRAP_T;
    public static final int GL_TRIANGLES = GLES30.GL_TRIANGLES;
    public static final int GL_MAX_TEXTURE_SIZE = GLES30.GL_MAX_TEXTURE_SIZE;

    private static final int[] ONE = new int[1];

    // ------------------------------------------------------------------ шейдеры

    public static int glCreateShader(int type) { return GLES30.glCreateShader(type); }

    /**
     * Шейдеры редактора написаны под {@code #version 330 core}; язык GLSL ES 3.00
     * от него отличается в двух местах — строкой версии и обязательной
     * точностью по умолчанию во фрагментном шейдере.
     */
    public static void glShaderSource(int shader, CharSequence source) {
        String s = source.toString().replace("#version 330 core",
                "#version 300 es\nprecision highp float;\nprecision highp int;\nprecision mediump sampler2D;");
        GLES30.glShaderSource(shader, s);
    }

    public static void glCompileShader(int shader) { GLES30.glCompileShader(shader); }

    public static int glGetShaderi(int shader, int pname) {
        GLES30.glGetShaderiv(shader, pname, ONE, 0);
        return ONE[0];
    }

    public static String glGetShaderInfoLog(int shader) { return GLES30.glGetShaderInfoLog(shader); }

    public static void glDeleteShader(int shader) { GLES30.glDeleteShader(shader); }

    public static int glCreateProgram() { return GLES30.glCreateProgram(); }

    public static void glAttachShader(int program, int shader) { GLES30.glAttachShader(program, shader); }

    public static void glDetachShader(int program, int shader) { GLES30.glDetachShader(program, shader); }

    public static void glLinkProgram(int program) { GLES30.glLinkProgram(program); }

    public static int glGetProgrami(int program, int pname) {
        GLES30.glGetProgramiv(program, pname, ONE, 0);
        return ONE[0];
    }

    public static String glGetProgramInfoLog(int program) { return GLES30.glGetProgramInfoLog(program); }

    public static void glUseProgram(int program) { GLES30.glUseProgram(program); }

    public static void glDeleteProgram(int program) { GLES30.glDeleteProgram(program); }

    public static int glGetUniformLocation(int program, CharSequence name) {
        return GLES30.glGetUniformLocation(program, name.toString());
    }

    public static void glUniform1i(int location, int v) { GLES30.glUniform1i(location, v); }

    public static void glUniform2f(int location, float a, float b) { GLES30.glUniform2f(location, a, b); }

    // ------------------------------------------------------------------ буферы

    public static int glGenBuffers() {
        GLES30.glGenBuffers(1, ONE, 0);
        return ONE[0];
    }

    public static void glDeleteBuffers(int buffer) {
        ONE[0] = buffer;
        GLES30.glDeleteBuffers(1, ONE, 0);
    }

    public static void glBindBuffer(int target, int buffer) { GLES30.glBindBuffer(target, buffer); }

    public static void glBufferData(int target, long size, int usage) {
        GLES30.glBufferData(target, (int) size, null, usage);
    }

    public static void glBufferData(int target, ByteBuffer data, int usage) {
        GLES30.glBufferData(target, data.remaining(), data, usage);
    }

    public static void glBufferData(int target, ShortBuffer data, int usage) {
        GLES30.glBufferData(target, data.remaining() * 2, data, usage);
    }

    public static void glBufferSubData(int target, long offset, ByteBuffer data) {
        GLES30.glBufferSubData(target, (int) offset, data.remaining(), data);
    }

    public static int glGenVertexArrays() {
        GLES30.glGenVertexArrays(1, ONE, 0);
        return ONE[0];
    }

    public static void glDeleteVertexArrays(int array) {
        ONE[0] = array;
        GLES30.glDeleteVertexArrays(1, ONE, 0);
    }

    public static void glBindVertexArray(int array) { GLES30.glBindVertexArray(array); }

    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized,
                                             int stride, long pointer) {
        GLES30.glVertexAttribPointer(index, size, type, normalized, stride, (int) pointer);
    }

    public static void glEnableVertexAttribArray(int index) { GLES30.glEnableVertexAttribArray(index); }

    public static void glDrawElements(int mode, int count, int type, long indices) {
        GLES30.glDrawElements(mode, count, type, (int) indices);
    }

    // ------------------------------------------------------------------ состояние

    public static void glEnable(int cap) { GLES30.glEnable(cap); }

    public static void glDisable(int cap) { GLES30.glDisable(cap); }

    public static void glBlendFunc(int s, int d) { GLES30.glBlendFunc(s, d); }

    public static void glScissor(int x, int y, int w, int h) { GLES30.glScissor(x, y, w, h); }

    public static void glViewport(int x, int y, int w, int h) { GLES30.glViewport(x, y, w, h); }

    public static void glClearColor(float r, float g, float b, float a) { GLES30.glClearColor(r, g, b, a); }

    public static void glClear(int mask) { GLES30.glClear(mask); }

    public static int glGetInteger(int pname) {
        GLES30.glGetIntegerv(pname, ONE, 0);
        return ONE[0];
    }

    public static void glPixelStorei(int pname, int param) { GLES30.glPixelStorei(pname, param); }

    // ------------------------------------------------------------------ текстуры

    public static int glGenTextures() {
        GLES30.glGenTextures(1, ONE, 0);
        return ONE[0];
    }

    public static void glDeleteTextures(int texture) {
        ONE[0] = texture;
        GLES30.glDeleteTextures(1, ONE, 0);
    }

    public static void glActiveTexture(int unit) { GLES30.glActiveTexture(unit); }

    public static void glBindTexture(int target, int texture) { GLES30.glBindTexture(target, texture); }

    public static void glTexParameteri(int target, int pname, int param) {
        GLES30.glTexParameteri(target, pname, param);
    }

    public static void glTexImage2D(int target, int level, int internalFormat, int w, int h, int border,
                                    int format, int type, ByteBuffer pixels) {
        GLES30.glTexImage2D(target, level, internalFormat, w, h, border, format, type, pixels);
    }

    public static void glTexSubImage2D(int target, int level, int x, int y, int w, int h,
                                       int format, int type, ByteBuffer pixels) {
        GLES30.glTexSubImage2D(target, level, x, y, w, h, format, type, pixels);
    }

    public static void glGenerateMipmap(int target) { GLES30.glGenerateMipmap(target); }

    public static void glReadPixels(int x, int y, int w, int h, int format, int type, ByteBuffer pixels) {
        GLES30.glReadPixels(x, y, w, h, format, type, pixels);
    }

    // ------------------------------------------------------------------ кадровый буфер

    public static int glGenFramebuffers() {
        GLES30.glGenFramebuffers(1, ONE, 0);
        return ONE[0];
    }

    public static void glDeleteFramebuffers(int fbo) {
        ONE[0] = fbo;
        GLES30.glDeleteFramebuffers(1, ONE, 0);
    }

    public static void glBindFramebuffer(int target, int fbo) { GLES30.glBindFramebuffer(target, fbo); }

    public static void glFramebufferTexture2D(int target, int attachment, int texTarget, int texture, int level) {
        GLES30.glFramebufferTexture2D(target, attachment, texTarget, texture, level);
    }

    public static int glCheckFramebufferStatus(int target) { return GLES30.glCheckFramebufferStatus(target); }
}
