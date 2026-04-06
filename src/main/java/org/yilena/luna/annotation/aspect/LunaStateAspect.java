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

@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class LunaStateAspect {

    private final LunaStatusPublisher statusPublisher;

    @Around("@annotation(lunaState)")
    public Object around(ProceedingJoinPoint point, LunaState lunaState) throws Throwable {
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, lunaState.status(), lunaState.value());
        try {
            return point.proceed();
        } finally {
            statusPublisher.publish(
                    LunaStatusPublisher.DEFAULT_CLIENT_ID,
                    LunaStateConstant.STATUS_THINKING,
                    LunaStateConstant.VALUE_THINKING
            );
        }
    }
}
