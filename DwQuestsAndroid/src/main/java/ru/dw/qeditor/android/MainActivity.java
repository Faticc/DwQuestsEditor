package ru.dw.qeditor.android;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import ru.dw.qeditor.android.awt.Clipboard;
import ru.dw.qeditor.assets.Config;
import ru.dw.qeditor.assets.ItemRegistry;
import ru.dw.qeditor.assets.ModAssets;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static ru.dw.qeditor.gl.Keys.*;

/**
 * Единственный экран приложения: поверхность редактора и строка правки текста
 * над клавиатурой. Заодно — всё, что редактору нужно от телефона
 * ({@link Platform}): доступ к памяти, «Поделиться», системный выбор файлов.
 */
public final class MainActivity extends Activity implements Platform {

    private static final int REQ_IMPORT = 1;
    private static final int REQ_EXPORT = 2;
    private static final int REQ_LEGACY_STORAGE = 3;

    private EditorView view;
    private View textBar;
    private boolean immersive;

    private File importTarget;
    private Consumer<List<File>> importDone;
    private File exportSource;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Config.init(getFilesDir());
        refreshStorageAccess();
        Platform.set(this);
        ModAssets.useGamePack(new ApkAssets(getAssets(), "game"));
        ItemRegistry.useEmbedded(new ApkAssets(getAssets(), "icons"));
        Clipboard.install(new Clipboard.Access() {
            @Override public String get() { return readClipboard(); }

            @Override public void set(String text) { writeClipboard(text); }
        });

        Window w = getWindow();
        w.setStatusBarColor(Color.BLACK);
        w.setNavigationBarColor(Color.BLACK);

        view = new EditorView(this);
        textBar = buildTextBar();
        textBar.setVisibility(View.GONE);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        column.addView(textBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0F1419);
        root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        if (Build.VERSION.SDK_INT >= 30) {
            // Android 15+ рисует приложение под системными панелями всегда; отступы
            // (панели, вырез экрана, клавиатура) ставим сами — на всех версиях от 11
            w.setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                int types = WindowInsets.Type.ime() | WindowInsets.Type.displayCutout()
                        | (immersive ? 0 : WindowInsets.Type.systemBars());
                Insets in = insets.getInsets(types);
                v.setPadding(in.left, in.top, in.right, in.bottom);
                return WindowInsets.CONSUMED;
            });
        }

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
        }
        handleIntent(getIntent());
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() { back(); }

    /** «Назад» — это Esc редактора: закрыть меню, диалог, полный экран, раздел, выйти. */
    private void back() {
        view.sendKey(GLFW_KEY_ESCAPE, 0);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStorageAccess();
        view.onResume();
        view.post(app -> app.onResume());
        if (immersive) applyImmersive();
    }

    @Override
    protected void onPause() {
        // черновик пишется в потоке отрисовки — ждём его, пока система не
        // заморозила приложение
        CountDownLatch done = new CountDownLatch(1);
        view.postNow(app -> app.onPause(), done::countDown);
        try {
            done.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        view.onPause();
        super.onPause();
    }

    /** Редактор попросил закрыться (после вопроса про несохранённое). */
    void quitEditor() {
        finishAndRemoveTask();
    }

    // ------------------------------------------------------------------ строка правки текста

    /**
     * Ctrl+A/C/X/V/Z/Y и стрелки над клавиатурой. Это обычные кнопки Android, а
     * не часть интерфейса редактора: касание по ним не уводит фокус из поля.
     */
    private View buildTextBar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundColor(0xFF161C24);
        int pad = dp(4);
        row.setPadding(pad, pad, pad, pad);
        addBarButton(row, "Всё", GLFW_KEY_A, GLFW_MOD_CONTROL);
        addBarButton(row, "Копировать", GLFW_KEY_C, GLFW_MOD_CONTROL);
        addBarButton(row, "Вырезать", GLFW_KEY_X, GLFW_MOD_CONTROL);
        addBarButton(row, "Вставить", GLFW_KEY_V, GLFW_MOD_CONTROL);
        addBarButton(row, "↶", GLFW_KEY_Z, GLFW_MOD_CONTROL);
        addBarButton(row, "↷", GLFW_KEY_Y, GLFW_MOD_CONTROL);
        addBarButton(row, "◀", GLFW_KEY_LEFT, 0);
        addBarButton(row, "▶", GLFW_KEY_RIGHT, 0);
        addBarButton(row, "Начало", GLFW_KEY_HOME, 0);
        addBarButton(row, "Конец", GLFW_KEY_END, 0);
        addBarButton(row, "Готово", GLFW_KEY_ESCAPE, 0);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setBackgroundColor(0xFF161C24);
        scroll.addView(row);
        return scroll;
    }

    private void addBarButton(LinearLayout row, String label, int key, int mods) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(0xFFE2E8EF);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundColor(0xFF1C2430);
        b.setMinWidth(dp(48));
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setOnClickListener(v -> view.sendKey(key, mods));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        lp.setMargins(dp(2), 0, dp(2), 0);
        row.addView(b, lp);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ Platform: клавиатура, экран

    @Override
    public void showKeyboard(boolean show) {
        runOnUiThread(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (show) {
                view.requestFocus();
                view.resetComposing();
                imm.restartInput(view);
                imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
                textBar.setVisibility(View.VISIBLE);
            } else {
                imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
                textBar.setVisibility(View.GONE);
            }
        });
    }

    @Override
    public void setImmersive(boolean on) {
        runOnUiThread(() -> {
            immersive = on;
            applyImmersive();
        });
    }

    @SuppressWarnings("deprecation")
    private void applyImmersive() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                if (immersive) {
                    c.hide(WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                } else {
                    c.show(WindowInsets.Type.systemBars());
                }
            }
            w.getDecorView().requestApplyInsets();
        } else {
            int flags = immersive
                    ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                      | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    : 0;
            w.getDecorView().setSystemUiVisibility(flags);
        }
    }

    @Override
    public void quit() { runOnUiThread(this::quitEditor); }

    @Override
    public String describe() {
        android.util.DisplayMetrics m = getResources().getDisplayMetrics();
        return "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), "
                + Build.MANUFACTURER + " " + Build.MODEL + ", экран " + m.widthPixels + "×" + m.heightPixels
                + ", плотность " + String.format(Locale.ROOT, "%.2f", m.density)
                + "\nПапка по умолчанию: " + defaultFolder().getAbsolutePath()
                + "\nДоступ к памяти: " + (hasStorageAccess() ? "есть" : "только свой каталог");
    }

    @Override
    public InputStream asset(String path) {
        try {
            return getAssets().open(path);
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ Platform: память

    /**
     * Окно выбора файла спрашивает это каждый кадр, а на Android 11+ это вызов
     * в систему — поэтому ответ помнится и обновляется при возврате в приложение
     * (разрешение выдают на отдельном экране настроек).
     */
    private volatile boolean storageAccess;

    private void refreshStorageAccess() {
        storageAccess = Build.VERSION.SDK_INT >= 30
                ? Environment.isExternalStorageManager()
                : checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public boolean hasStorageAccess() { return storageAccess; }

    @Override
    public void requestStorageAccess() {
        runOnUiThread(() -> {
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                } catch (ActivityNotFoundException e) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            } else {
                requestPermissions(new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_LEGACY_STORAGE);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_LEGACY_STORAGE) {
            refreshStorageAccess();
            view.post(app -> app.onResume());
        }
    }

    @Override
    public File defaultFolder() {
        if (hasStorageAccess()) {
            File docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            return new File(docs, "DwQuests");
        }
        return ownFolder();
    }

    /** Свой каталог приложения во внешней памяти: доступен без разрешений. */
    private File ownFolder() {
        File ext = getExternalFilesDir(null);
        return new File(ext != null ? ext : getFilesDir(), "DwQuests");
    }

    @Override
    public List<Place> places() {
        List<Place> out = new ArrayList<>();
        if (hasStorageAccess()) {
            out.add(new Place("DwQuests", new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOCUMENTS), "DwQuests")));
            out.add(new Place("Загрузки",
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)));
            out.add(new Place("Память", Environment.getExternalStorageDirectory()));
        }
        out.add(new Place("Приложение", ownFolder()));
        return out;
    }

    // ------------------------------------------------------------------ Platform: обмен файлами

    @Override
    public void share(File file, String mime, String title) {
        runOnUiThread(() -> {
            try {
                Uri uri = ShareProvider.publish(this, file);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType(mime);
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.putExtra(Intent.EXTRA_SUBJECT, title);
                send.setClipData(ClipData.newRawUri(file.getName(), uri));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(send, title));
            } catch (IOException | ActivityNotFoundException e) {
                Toast.makeText(this, "Не удалось поделиться: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void importFiles(File targetDir, Consumer<List<File>> done) {
        runOnUiThread(() -> {
            importTarget = targetDir;
            importDone = done;
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            // у .json на телефонах тип бывает какой угодно — фильтруем по имени после выбора
            pick.setType("*/*");
            pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try {
                startActivityForResult(pick, REQ_IMPORT);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "Нет системного выбора файлов", Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void exportFile(File file, String mime) {
        runOnUiThread(() -> {
            exportSource = file;
            Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            create.addCategory(Intent.CATEGORY_OPENABLE);
            create.setType(mime);
            create.putExtra(Intent.EXTRA_TITLE, file.getName());
            try {
                startActivityForResult(create, REQ_EXPORT);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "Нет системного «Сохранить в…»", Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMPORT) {
            List<File> files = new ArrayList<>();
            if (resultCode == RESULT_OK && data != null) {
                List<Uri> uris = new ArrayList<>();
                if (data.getClipData() != null) {
                    for (int i = 0; i < data.getClipData().getItemCount(); i++)
                        uris.add(data.getClipData().getItemAt(i).getUri());
                } else if (data.getData() != null) {
                    uris.add(data.getData());
                }
                for (Uri u : uris) {
                    File f = copyIn(u, importTarget);
                    if (f != null) files.add(f);
                }
            }
            Consumer<List<File>> done = importDone;
            importDone = null;
            if (done != null) view.post(app -> done.accept(files));
        } else if (requestCode == REQ_EXPORT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try (InputStream in = new FileInputStream(exportSource);
                 OutputStream out = getContentResolver().openOutputStream(data.getData(), "wt")) {
                copy(in, out);
                Toast.makeText(this, "Сохранено: " + exportSource.getName(), Toast.LENGTH_SHORT).show();
            } catch (IOException | RuntimeException e) {
                Toast.makeText(this, "Не удалось сохранить: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    /** Документ по адресу Android — файлом в папку (имя — как его показывает система). */
    private File copyIn(Uri uri, File dir) {
        String name = displayName(uri);
        if (name == null || name.isEmpty()) name = "вкладка.json";
        if (!name.toLowerCase(Locale.ROOT).endsWith(".json")) name = name + ".json";
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        dir.mkdirs();
        File out = new File(dir, name);
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(out)) {
            if (in == null) return null;
            copy(in, os);
            return out;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private String displayName(Uri uri) {
        if ("file".equals(uri.getScheme())) return new File(uri.getPath()).getName();
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) {
            // у некоторых источников имени нет — придумаем
        }
        return uri.getLastPathSegment();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    /** «Открыть с помощью» и «Поделиться» из других приложений. */
    @SuppressWarnings("deprecation")
    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(action)) uri = intent.getData();
        else if (Intent.ACTION_SEND.equals(action)) uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri != null) {
            File f = "file".equals(uri.getScheme()) && uri.getPath() != null && new File(uri.getPath()).canRead()
                    ? new File(uri.getPath())
                    : copyIn(uri, new File(defaultFolder(), "Входящие"));
            if (f != null) view.post(app -> app.openExternal(f));
            else Toast.makeText(this, "Не удалось прочитать файл", Toast.LENGTH_LONG).show();
        } else if (Intent.ACTION_SEND.equals(action)) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null && !text.isEmpty()) view.post(app -> app.openExternalText(text));
        }
        // одно и то же намерение не должно открываться снова после поворота или возврата
        setIntent(new Intent());
    }

    // ------------------------------------------------------------------ буфер обмена

    private String readClipboard() {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return null;
        CharSequence t = clip.getItemAt(0).coerceToText(this);
        return t == null ? null : t.toString();
    }

    private void writeClipboard(String text) {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("DwQuests", text));
    }
}
