package ru.dw.qeditor.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Отдаёт файлы другим приложениям для «Поделиться».
 *
 * <p>Путь к файлу чужому приложению на Android 7+ передавать нельзя — только
 * адрес {@code content://}. Библиотеки поддержки (FileProvider) ради этого
 * тянуть не стали: файл копируется в свой каталог {@code cache/share}, и
 * отсюда отдаётся только он, на чтение.
 */
public final class ShareProvider extends ContentProvider {

    static final String AUTHORITY = "ru.dw.qeditor.share";

    /** Положить копию файла в каталог раздачи и вернуть её адрес. */
    static Uri publish(Context context, File file) throws IOException {
        File dir = new File(context.getCacheDir(), "share");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("нет каталога " + dir);
        File out = new File(dir, file.getName());
        if (!file.getCanonicalPath().equals(out.getCanonicalPath())) {
            try (InputStream in = new FileInputStream(file); OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
        }
        return new Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath("share").appendPath(out.getName()).build();
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.equals("..")) throw new FileNotFoundException(String.valueOf(uri));
        File f = new File(new File(getContext().getCacheDir(), "share"), name);
        if (!f.isFile()) throw new FileNotFoundException(f.getPath());
        return f;
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f;
        try {
            f = fileFor(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        String n = String.valueOf(uri.getLastPathSegment()).toLowerCase(Locale.ROOT);
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        return "application/octet-stream";
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
