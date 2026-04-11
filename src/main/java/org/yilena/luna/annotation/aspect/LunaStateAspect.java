package org.yilena.luna.annotation.aspect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.yilena.luna.annotation.LunaState;
import org.yilena.luna.constants.LunaStateConstant;
import org.yilena.luna.sse.LunaStatusPublisher;

/**
 * 状态切面，负责在标记了状态注解的方法执行前后发布 Luna 当前状态。
 */
@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class LunaStateAspect {

    /**
     * 状态发布器，用于向前端状态流推送当前执行状态。
     */
    private final LunaStatusPublisher statusPublisher;

    @Around("@annotation(lunaState)")
    public Object around(ProceedingJoinPoint point, LunaState lunaState) throws Throwable {
        /**
         * 方法执行前先发布注解声明的状态和值，让前端及时感知当前阶段。
         */
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, lunaState.status(), lunaState.value());
        try {
            return point.proceed();
        } finally {
            /**
             * 方法结束后统一回退到思考态，保证状态流不会停留在中间业务阶段。
             */
            statusPublisher.publish(
                    LunaStatusPublisher.DEFAULT_CLIENT_ID,
                    LunaStateConstant.STATUS_THINKING,
                    LunaStateConstant.VALUE_THINKING
            );
        }
    }
}
