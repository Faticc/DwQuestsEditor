package ru.dw.qeditor.android.gles;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Метка lwjgl3ify из сборки мода; на Android ни на что не влияет. */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface Lwjgl3Aware { }
