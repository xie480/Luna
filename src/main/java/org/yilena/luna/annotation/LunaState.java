package org.yilena.luna.annotation;

import org.yilena.luna.constants.LunaStateConstant;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 用于标记方法执行时 Luna 的内部状态，通过 AOP 自动推送到前端
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LunaState {
    /**
     * 展示给用户的文本，例如："Luna 正在全网搜索最新资讯..."
     */
    String value();

    /**
     * 状态标识码
     */
    String status() default LunaStateConstant.STATUS_WORKING;
}
