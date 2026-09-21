package ru.dw.qbridge.client;

/** Длинная работа, которую крутят по кусочкам в тике клиента. */
public interface Job {

    /** Человеческое имя работы — попадает в чат. */
    String name();

    /**
     * Сделать порцию работы, уложившись примерно в бюджет.
     * @return true, если работа закончена
     */
    boolean step(long budgetMs) throws Exception;

    /** Доля выполненного, 0..1 — для процентов в чате. */
    float fraction();

    /** Итог для чата, когда работа закончилась. */
    String summary();

    /** Прервали снаружи: закрыть файлы. */
    void abort();
}
