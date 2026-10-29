package ru.dw.qeditor.android;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import ru.dw.qeditor.assets.Config;
import ru.dw.qeditor.gl.GlWindow;

/**
 * Окно редактора на телефоне: размер поверхности GL, масштаб интерфейса и
 * системный буфер обмена. Ввод сюда кладёт {@link TouchInput}.
 */
final class AndroidWindow extends GlWindow {

    private final ClipboardManager clipboard;
    private final float density;
    /** Наименьшая сторона экрана в dp — по ней телефон отличается от планшета. */
    private final float smallestDp;
    private final Runnable onClose;
    private volatile int width = 1, height = 1;

    AndroidWindow(Context context, Runnable onClose) {
        this.clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        this.density = context.getResources().getDisplayMetrics().density;
        this.smallestDp = context.getResources().getConfiguration().smallestScreenWidthDp;
        this.onClose = onClose;
    }

    void resize(int w, int h) {
        width = Math.max(1, w);
        height = Math.max(1, h);
    }

    @Override public int width() { return width; }

    @Override public int height() { return height; }

    /**
     * Точек экрана на логическую точку интерфейса. Интерфейс редактора
     * рассчитан на монитор, поэтому на телефоне «авто» чуть мельче системного
     * размера — иначе окна настроек и диалоги не помещаются по высоте.
     */
    @Override
    public float contentScale() {
        int pct = Config.get().uiScale;
        float factor = pct > 0 ? pct / 100f : smallestDp < 600 ? 0.85f : 1f;
        return Math.max(1f, density * factor);
    }

    float density() { return density; }

    @Override
    public String clipboard() {
        try {
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;
            CharSequence text = clip.getItemAt(0).getText();
            return text == null ? null : text.toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public void setClipboard(String s) {
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText("DwQuests", s));
        } catch (RuntimeException ignored) {
            // слишком большой текст для буфера — Android откажет; редактор продолжит работать
        }
    }

    @Override
    public void close() { onClose.run(); }
}
