package org.yilena.luna.annotation;

import org.yilena.luna.enums.LogType;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LunaLogRecord {
    String module() default "";
    String action() default "";
    LogType type() default LogType.SYSTEM_EVENT;
}
