package ru.dw.qeditor.gl;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Накопитель четырёхугольников: всё, что рисует редактор, складывается в один
 * поток вершин и уходит на видеокарту пачкой.
 *
 * <p>Шейдер один на все виды примитивов, вид выбирается режимом в вершине.
 * Это ровно то, чего не хватало версии на Java2D: круги и градиенты из
 * {@code circle.frag} / {@code gradient.frag} мода снова считаются во
 * фрагментном шейдере, а не растеризуются в {@code BufferedImage} и не живут
 * в кэшах; тонировка нодов — это просто цвет вершины.
 *
 * <p>Пакет сбрасывается, когда меняется текстура, режим смешивания или область
 * отсечения: только это и заставляет драйвер начинать новый вызов отрисовки.
 */
public final class Batch {

    /** Текстура × цвет вершины. */
    public static final int MODE_TEXTURE = 0;
    /** Сплошная заливка, текстура не нужна — можно рисовать в любом пакете. */
    public static final int MODE_SOLID = 1;
    /** circle.frag: круг с мягким краем, внутренней тенью или подсветкой. */
    public static final int MODE_CIRCLE = 2;
    /** gradient.frag: трёхточечный линейный градиент под углом. */
    public static final int MODE_GRADIENT = 3;
    /** Цвет берётся из вершины, из текстуры — только альфа (силуэт, обводка). */
    public static final int MODE_MASK = 4;
    /** Отрезок линии: поперёк квада мягкий край шириной в пиксель. */
    public static final int MODE_LINE = 5;
    /** Прямоугольник со скруглёнными углами и необязательной рамкой. */
    public static final int MODE_ROUND = 6;

    public static final int BLEND_NORMAL = 0;
    /** glBlendFunc(GL_SRC_ALPHA, GL_ONE) — свечение нодов. */
    public static final int BLEND_ADDITIVE = 1;

    private static final int MAX_QUADS = 16384;
    private static final int VERTEX_BYTES = 48;

    private static final String VERTEX_SRC = """
            #version 330 core
            layout(location = 0) in vec2 aPos;
            layout(location = 1) in vec2 aUv;
            layout(location = 2) in vec4 aCol0;
            layout(location = 3) in vec4 aCol1;
            layout(location = 4) in vec4 aCol2;
            layout(location = 5) in vec4 aP0;
            layout(location = 6) in float aMode;

            uniform vec2 uViewport;

            out vec2 vUv;
            out vec4 vCol0;
            out vec4 vCol1;
            out vec4 vCol2;
            out vec4 vP0;
            flat out int vMode;

            void main() {
                vec2 ndc = vec2(aPos.x / uViewport.x * 2.0 - 1.0,
                                1.0 - aPos.y / uViewport.y * 2.0);
                gl_Position = vec4(ndc, 0.0, 1.0);
                vUv = aUv;
                vCol0 = aCol0;
                vCol1 = aCol1;
                vCol2 = aCol2;
                vP0 = aP0;
                vMode = int(aMode + 0.5);
            }
            """;

    private static final String FRAGMENT_SRC = """
            #version 330 core
            in vec2 vUv;
            in vec4 vCol0;
            in vec4 vCol1;
            in vec4 vCol2;
            in vec4 vP0;
            flat in int vMode;

            uniform sampler2D uTex;

            out vec4 fragColor;

            // GLSL-овский smoothstep не определён при edge0 > edge1, а в circle.frag
            // мода края идут именно в таком порядке — повторяем формулу вручную.
            float sstep(float e0, float e1, float x) {
                float d = e1 - e0;
                if (d == 0.0) return x < e0 ? 0.0 : 1.0;
                float t = clamp((x - e0) / d, 0.0, 1.0);
                return t * t * (3.0 - 2.0 * t);
            }

            void main() {
                if (vMode == 1) {                       // сплошной цвет
                    fragColor = vCol0;
                } else if (vMode == 0) {                // текстура
                    fragColor = texture(uTex, vUv) * vCol0;
                } else if (vMode == 4) {                // силуэт текстуры
                    fragColor = vec4(vCol0.rgb, vCol0.a * texture(uTex, vUv).a);
                } else if (vMode == 2) {                // circle.frag
                    float aspect = vP0.w;
                    float smoothEdge = vP0.x;
                    float shadowSize = vP0.y;
                    int shadowType = int(vP0.z + 0.5);
                    vec2 uv = vec2((vUv.x - 0.5) * aspect, vUv.y - 0.5);
                    float d = length(uv);
                    float radius = 0.5;
                    if (shadowType > 0) {
                        float edgeAlpha = sstep(radius, radius - smoothEdge, d);
                        float size = shadowType == 1 ? 1.0 - shadowSize : shadowSize;
                        float shadowAlpha = sstep(radius - size, radius, d);
                        if (shadowType == 1) shadowAlpha = 1.0 - shadowAlpha;
                        float m = vCol1.a * shadowAlpha;
                        fragColor = vec4(mix(vCol0.rgb, vCol1.rgb, m), edgeAlpha);
                    } else {
                        fragColor = vec4(vCol0.rgb, sstep(radius, radius - smoothEdge, d) * vCol0.a);
                    }
                } else if (vMode == 3) {                // gradient.frag
                    float angle = vP0.x;
                    float w = vP0.y;
                    float h = vP0.z;
                    vec2 dir = vec2(cos(angle), sin(angle));
                    float len = sqrt(pow(w * dir.x, 2.0) + pow(h * dir.y, 2.0));
                    if (len == 0.0) len = 1.0;
                    vec2 rel = vec2(vUv.x * w - w * 0.5, vUv.y * h - h * 0.5);
                    float t = clamp((dot(rel, dir) + 0.5 * len) / len, 0.0, 1.0);
                    vec4 c = t < 0.5 ? mix(vCol0, vCol1, t * 2.0)
                                     : mix(vCol1, vCol2, (t - 0.5) * 2.0);
                    // color.a *= mix(start.a, end.a, gradient) — как в шейдере мода
                    fragColor = vec4(c.rgb, c.a * mix(vCol0.a, vCol2.a, t));
                } else if (vMode == 5) {                // отрезок линии
                    float feather = clamp((1.0 - abs(vUv.y)) * vP0.x, 0.0, 1.0);
                    fragColor = vec4(vCol0.rgb, vCol0.a * feather);
                } else {                                // скруглённый прямоугольник
                    vec2 hs = vec2(vP0.y, vP0.z) * 0.5;   // half — зарезервированное слово GLSL
                    float r = min(vP0.x, min(hs.x, hs.y));
                    vec2 pos = vUv * vec2(vP0.y, vP0.z) - hs;
                    vec2 q = abs(pos) - hs + r;
                    float d = min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
                    float inside = clamp(0.5 - d, 0.0, 1.0);
                    float border = vP0.w;
                    if (border > 0.0) {
                        float outer = inside;
                        float inner = clamp(0.5 - (d + border), 0.0, 1.0);
                        vec4 c = mix(vCol1, vCol0, inner);
                        fragColor = vec4(c.rgb, c.a * outer);
                    } else {
                        fragColor = vec4(vCol0.rgb, vCol0.a * inside);
                    }
                }
                if (fragColor.a <= 0.0) discard;
            }
            """;

    private final GlProgram program;
    private final int viewportUniform;
    private final int vao;
    private final int vbo;
    private final int ibo;
    private final ByteBuffer vertices;
    private final GlTexture white;

    private int quads;
    private int viewportW = 1;
    private int viewportH = 1;

    private int currentTexture;
    private int currentBlend = BLEND_NORMAL;
    private boolean scissorOn;
    private int scX, scY, scW, scH;

    private long drawCalls;
    private long quadCount;

    public Batch() {
        program = new GlProgram(VERTEX_SRC, FRAGMENT_SRC);
        viewportUniform = program.uniform("uViewport");
        program.use();
        glUniform1i(program.uniform("uTex"), 0);

        vertices = MemoryUtil.memAlloc(MAX_QUADS * 4 * VERTEX_BYTES);

        vao = glGenVertexArrays();
        glBindVertexArray(vao);

        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) MAX_QUADS * 4 * VERTEX_BYTES, GL_STREAM_DRAW);

        ibo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo);
        ShortBuffer idx = MemoryUtil.memAllocShort(MAX_QUADS * 6);
        for (int i = 0; i < MAX_QUADS; i++) {
            short v = (short) (i * 4);
            idx.put(v).put((short) (v + 1)).put((short) (v + 2));
            idx.put(v).put((short) (v + 2)).put((short) (v + 3));
        }
        idx.flip();
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, idx, GL_STATIC_DRAW);
        MemoryUtil.memFree(idx);

        glVertexAttribPointer(0, 2, GL_FLOAT, false, VERTEX_BYTES, 0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, VERTEX_BYTES, 8);
        glVertexAttribPointer(2, 4, GL_UNSIGNED_BYTE, true, VERTEX_BYTES, 16);
        glVertexAttribPointer(3, 4, GL_UNSIGNED_BYTE, true, VERTEX_BYTES, 20);
        glVertexAttribPointer(4, 4, GL_UNSIGNED_BYTE, true, VERTEX_BYTES, 24);
        glVertexAttribPointer(5, 4, GL_FLOAT, false, VERTEX_BYTES, 28);
        glVertexAttribPointer(6, 1, GL_FLOAT, false, VERTEX_BYTES, 44);
        for (int i = 0; i <= 6; i++) glEnableVertexAttribArray(i);
        glBindVertexArray(0);

        white = GlTexture.white();
    }

    // ------------------------------------------------------------------ кадр

    public void begin(int viewportWidth, int viewportHeight) {
        this.viewportW = Math.max(1, viewportWidth);
        this.viewportH = Math.max(1, viewportHeight);
        quads = 0;
        vertices.clear();
        currentTexture = 0;
        currentBlend = BLEND_NORMAL;
        scissorOn = false;
        drawCalls = 0;
        quadCount = 0;

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glDisable(GL_SCISSOR_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    }

    public void end() { flush(); }

    public long drawCalls() { return drawCalls; }

    public long quadsDrawn() { return quadCount; }

    // ------------------------------------------------------------------ состояние

    public void texture(GlTexture tex) {
        int id = tex == null ? white.id() : tex.id();
        if (id != currentTexture) {
            flush();
            currentTexture = id;
        }
    }

    /** Сплошные примитивы не смотрят в текстуру — пакет можно не рвать. */
    public void noTexture() {
        if (currentTexture == 0) currentTexture = white.id();
    }

    public void blend(int mode) {
        if (mode != currentBlend) {
            flush();
            currentBlend = mode;
        }
    }

    /** Отсечение в пикселях кадрового буфера, начало координат — левый верх. */
    public void scissor(int x, int y, int w, int h) {
        if (scissorOn && scX == x && scY == y && scW == w && scH == h) return;
        flush();
        scissorOn = true;
        scX = x;
        scY = y;
        scW = Math.max(0, w);
        scH = Math.max(0, h);
    }

    public void scissorOff() {
        if (!scissorOn) return;
        flush();
        scissorOn = false;
    }

    // ------------------------------------------------------------------ примитивы

    /**
     * Прямоугольник целиком: четыре угла считаются из x/y/w/h, UV — из u/v.
     * Все остальные методы рисования сводятся к нему.
     */
    public void quad(float x, float y, float w, float h,
                     float u1, float v1, float u2, float v2,
                     int col0, int col1, int col2,
                     float p0, float p1, float p2, float p3, int mode) {
        vertex(x, y, u1, v1, col0, col1, col2, p0, p1, p2, p3, mode);
        vertex(x + w, y, u2, v1, col0, col1, col2, p0, p1, p2, p3, mode);
        vertex(x + w, y + h, u2, v2, col0, col1, col2, p0, p1, p2, p3, mode);
        vertex(x, y + h, u1, v2, col0, col1, col2, p0, p1, p2, p3, mode);
        quads++;
        if (quads >= MAX_QUADS) flush();
    }

    /** Произвольный четырёхугольник со своим цветом в каждой вершине. */
    public void quadFree(float x1, float y1, int c1, float x2, float y2, int c2,
                         float x3, float y3, int c3, float x4, float y4, int c4,
                         float v12, float v34, float p0, int mode) {
        vertex(x1, y1, 0, v12, c1, 0, 0, p0, 0, 0, 0, mode);
        vertex(x2, y2, 0, v12, c2, 0, 0, p0, 0, 0, 0, mode);
        vertex(x3, y3, 0, v34, c3, 0, 0, p0, 0, 0, 0, mode);
        vertex(x4, y4, 0, v34, c4, 0, 0, p0, 0, 0, 0, mode);
        quads++;
        if (quads >= MAX_QUADS) flush();
    }

    /**
     * Четырёхугольник с произвольными углами и своими UV в каждом.
     * Нужен курсиву шрифта: в игре он делается сдвигом вершин, а не наклоном
     * всей картинки.
     */
    public void quadUv(float x1, float y1, float u1, float v1,
                       float x2, float y2, float u2, float v2,
                       float x3, float y3, float u3, float v3,
                       float x4, float y4, float u4, float v4,
                       int color, int mode) {
        vertex(x1, y1, u1, v1, color, 0, 0, 0, 0, 0, 0, mode);
        vertex(x2, y2, u2, v2, color, 0, 0, 0, 0, 0, 0, mode);
        vertex(x3, y3, u3, v3, color, 0, 0, 0, 0, 0, 0, mode);
        vertex(x4, y4, u4, v4, color, 0, 0, 0, 0, 0, 0, mode);
        quads++;
        if (quads >= MAX_QUADS) flush();
    }

    public void solid(float x, float y, float w, float h, int color) {
        noTexture();
        quad(x, y, w, h, 0, 0, 1, 1, color, 0, 0, 0, 0, 0, 0, MODE_SOLID);
    }

    public void sprite(float x, float y, float w, float h,
                       float u1, float v1, float u2, float v2, int tint) {
        quad(x, y, w, h, u1, v1, u2, v2, tint, 0, 0, 0, 0, 0, 0, MODE_TEXTURE);
    }

    public void mask(float x, float y, float w, float h,
                     float u1, float v1, float u2, float v2, int color) {
        quad(x, y, w, h, u1, v1, u2, v2, color, 0, 0, 0, 0, 0, 0, MODE_MASK);
    }

    /**
     * Скруглённый прямоугольник. Край считается по расстоянию до формы, поэтому
     * сглаживание получается само и не зависит от настроек драйвера.
     *
     * @param border толщина рамки в точках; 0 — только заливка
     */
    public void roundRect(float x, float y, float w, float h, float radius,
                          int fill, int borderColor, float border) {
        noTexture();
        quad(x, y, w, h, 0, 0, 1, 1, fill, borderColor, 0, radius, w, h, border, MODE_ROUND);
    }

    public void circle(float x, float y, float w, float h, int color, int shadowColor,
                       float smoothEdge, float shadowSize, int shadowType) {
        noTexture();
        quad(x, y, w, h, 0, 0, 1, 1, color, shadowColor, 0,
                smoothEdge, shadowSize, shadowType, h == 0 ? 1f : w / h, MODE_CIRCLE);
    }

    public void gradient(float x, float y, float w, float h,
                         int start, int middle, int end, int angleDeg) {
        noTexture();
        float angle = (float) Math.toRadians(angleDeg + 90.0);
        quad(x, y, w, h, 0, 0, 1, 1, start, middle, end, angle, w, h, 0, MODE_GRADIENT);
    }

    /**
     * Отрезок толщиной width (в пикселях экрана) с разными цветами концов.
     * Мягкий край считается в шейдере, поэтому сглаживание не зависит от
     * настроек драйвера и стоит столько же, сколько обычная заливка.
     */
    public void segment(float x1, float y1, float x2, float y2, float width, int colorA, int colorB) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-4f) return;
        float half = Math.max(0.35f, width / 2f);
        float nx = -dy / len * half;
        float ny = dx / len * half;
        noTexture();
        // цвет меняется вдоль отрезка, мягкий край — поперёк
        quadFree(x1 + nx, y1 + ny, colorA, x2 + nx, y2 + ny, colorB,
                x2 - nx, y2 - ny, colorB, x1 - nx, y1 - ny, colorA,
                -1f, 1f, half, MODE_LINE);
    }

    private void vertex(float x, float y, float u, float v,
                        int col0, int col1, int col2,
                        float p0, float p1, float p2, float p3, int mode) {
        ByteBuffer b = vertices;
        b.putFloat(x).putFloat(y).putFloat(u).putFloat(v);
        putColor(b, col0);
        putColor(b, col1);
        putColor(b, col2);
        b.putFloat(p0).putFloat(p1).putFloat(p2).putFloat(p3);
        b.putFloat(mode);
    }

    private static void putColor(ByteBuffer b, int argb) {
        b.put((byte) (argb >> 16 & 255));
        b.put((byte) (argb >> 8 & 255));
        b.put((byte) (argb & 255));
        b.put((byte) (argb >>> 24));
    }

    // ------------------------------------------------------------------ вывод

    public void flush() {
        if (quads == 0) {
            applyState();
            return;
        }
        int count = quads;
        vertices.flip();

        applyState();
        program.use();
        glUniform2f(viewportUniform, viewportW, viewportH);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, currentTexture == 0 ? white.id() : currentTexture);

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, vertices);
        glDrawElements(GL_TRIANGLES, count * 6, GL_UNSIGNED_SHORT, 0);
        glBindVertexArray(0);

        vertices.clear();
        quads = 0;
        drawCalls++;
        quadCount += count;
    }

    private void applyState() {
        if (currentBlend == BLEND_ADDITIVE) glBlendFunc(GL_SRC_ALPHA, GL_ONE);
        else glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        if (scissorOn) {
            glEnable(GL_SCISSOR_TEST);
            // GL считает Y снизу, весь остальной редактор — сверху
            glScissor(scX, viewportH - scY - scH, scW, scH);
        } else {
            glDisable(GL_SCISSOR_TEST);
        }
    }

    public void dispose() {
        flush();
        MemoryUtil.memFree(vertices);
        glDeleteBuffers(vbo);
        glDeleteBuffers(ibo);
        glDeleteVertexArrays(vao);
        white.dispose();
        program.dispose();
    }
}
