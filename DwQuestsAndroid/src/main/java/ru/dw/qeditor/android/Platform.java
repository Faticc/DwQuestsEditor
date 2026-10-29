package ru.dw.qeditor.android;

import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.function.Consumer;

/**
 * Что окну редактора нужно от телефона: доступ к памяти, «поделиться»,
 * системный выбор файла, клавиатура, полный экран.
 *
 * <p>Окно редактора ({@code app/App}) о классах Android не знает — только об
 * этом интерфейсе. Его реализует Activity, а проверка на компьютере
 * подставляет свою заглушку и так рисует то же окно без телефона.
 *
 * <p>Все вызовы приходят из потока отрисовки; ответы (выбранные файлы) тоже
 * возвращаются в него.
 */
public interface Platform {

    /** Быстрый переход в окне выбора файла: «Документы», «Загрузки»… */
    record Place(String label, File dir) { }

    /** Есть ли доступ к общей памяти телефона (а не только к своему каталогу). */
    boolean hasStorageAccess();

    /** Попросить доступ: на Android 11+ это экран «Доступ ко всем файлам». */
    void requestStorageAccess();

    /** Куда класть вкладки по умолчанию. */
    File defaultFolder();

    List<Place> places();

    /** Отправить файл через «Поделиться» (мессенджер, почта, облако). */
    void share(File file, String mime, String title);

    /** Системный выбор файлов: выбранные копируются в {@code targetDir}. */
    void importFiles(File targetDir, Consumer<List<File>> done);

    /** Системное «Сохранить в…»: копия файла туда, куда укажет человек. */
    void exportFile(File file, String mime);

    /** Встроенный в приложение файл (примеры вкладок) или null. */
    InputStream asset(String path);

    void showKeyboard(boolean show);

    /** Спрятать строку состояния и кнопки навигации. */
    void setImmersive(boolean on);

    /** Закрыть приложение. */
    void quit();

    /** Версия Android и устройство — для «О программе» и диагностики. */
    String describe();

    // ------------------------------------------------------------------ текущая

    final class Holder {
        private static Platform current;

        private Holder() { }
    }

    static Platform get() { return Holder.current; }

    static void set(Platform p) { Holder.current = p; }
}
