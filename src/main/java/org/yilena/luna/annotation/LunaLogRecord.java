package org.yilena.luna.annotation;

import org.yilena.luna.enums.LogType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 日志记录注解，用于标记需要进入 Luna 日志切面的业务方法。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LunaLogRecord {

    /**
     * 日志所属模块。
     */
    String module() default "";

    /**
     * 当前业务动作名称。
     */
    String action() default "";

    /**
     * 日志默认内容描述。
     */
    String content() default "";

    /**
     * 日志类型，默认记为系统事件。
     */
    LogType type() default LogType.SYSTEM_EVENT;
}
