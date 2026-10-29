package ru.dw.qeditor.android.gles;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Вместо {@code org.lwjgl.system.MemoryUtil}: прямые буферы в порядке байт
 * машины. Освобождать их руками на Android нечем — память отдаёт сборщик,
 * поэтому {@link #memFree} ничего не делает.
 */
public final class MemoryUtil {

    private MemoryUtil() { }

    public static ByteBuffer memAlloc(int size) {
        return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
    }

    public static ShortBuffer memAllocShort(int count) {
        return memAlloc(count * 2).asShortBuffer();
    }

    public static void memFree(java.nio.Buffer buffer) { }
}
