package org.yilena.luna.annotation; // 注释

import org.yilena.luna.enums.LogType; // 注释

import java.lang.annotation.*; // 注释

@Target(ElementType.METHOD) // 注释
@Retention(RetentionPolicy.RUNTIME) // 注释
@Documented // 注释
public @interface LunaLogRecord { // 注释
    String module() default ""; // 注释
    String action() default ""; // 注释
    String content() default ""; // 注释
    LogType type() default LogType.SYSTEM_EVENT; // 注释
} // 注释
