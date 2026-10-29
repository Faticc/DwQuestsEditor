package ru.dw.qeditor.android.awt;

/** Только чтобы {@code img.getRaster().getDataBuffer()} отдавал массив точек. */
public final class WritableRaster {

    private final DataBuffer buffer;

    WritableRaster(DataBuffer buffer) { this.buffer = buffer; }

    public DataBuffer getDataBuffer() { return buffer; }
}
