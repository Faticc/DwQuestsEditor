package ru.dw.qeditor.gl;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Шейдерная программа: компиляция, линковка, поиск uniform-ов.
 *
 * <p>Ошибки компиляции валят программу сразу и с текстом лога — молча
 * работающий «чёрный экран» отлаживать намного дороже.
 */
public final class GlProgram {

    private final int id;

    public GlProgram(String vertexSource, String fragmentSource) {
        int vs = compile(GL_VERTEX_SHADER, vertexSource, "вершинный");
        int fs = compile(GL_FRAGMENT_SHADER, fragmentSource, "фрагментный");
        id = glCreateProgram();
        glAttachShader(id, vs);
        glAttachShader(id, fs);
        glLinkProgram(id);
        if (glGetProgrami(id, GL_LINK_STATUS) == GL_FALSE)
            throw new IllegalStateException("Не слинковалась программа: " + glGetProgramInfoLog(id));
        glDetachShader(id, vs);
        glDetachShader(id, fs);
        glDeleteShader(vs);
        glDeleteShader(fs);
    }

    private static int compile(int type, String source, String what) {
        int sh = glCreateShader(type);
        glShaderSource(sh, source);
        glCompileShader(sh);
        if (glGetShaderi(sh, GL_COMPILE_STATUS) == GL_FALSE)
            throw new IllegalStateException("Не скомпилировался " + what + " шейдер: " + glGetShaderInfoLog(sh));
        return sh;
    }

    public void use() { glUseProgram(id); }

    public int uniform(String name) { return glGetUniformLocation(id, name); }

    public void dispose() { glDeleteProgram(id); }
}
