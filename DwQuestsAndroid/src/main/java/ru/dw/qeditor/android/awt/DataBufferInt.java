package ru.dw.qeditor.android.awt;

public final class DataBufferInt extends DataBuffer {

    private final int[] data;

    DataBufferInt(int[] data) { this.data = data; }

    public int[] getData() { return data; }
}
