package org.yilena.luna.annotation.aspect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.yilena.luna.annotation.LunaState;
import org.yilena.luna.sse.LunaStatusPublisher;

@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
/**
 * LunaStateAspect ??
 */
public class LunaStateAspect {

    private final LunaStatusPublisher statusPublisher;

    @Around("@annotation(lunaState)")
    public Object around(ProceedingJoinPoint point, LunaState lunaState) throws Throwable {
        // 1. 方法执行前，推送“开始”状态
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, lunaState.status(), lunaState.value());

        try {
            // 2. 执行目标方法 (如联网搜索、RAG检索等)
            return point.proceed();
        } finally {
            // 3. 方法执行后，恢复为“思考中”状态（因为工具调用完毕后，大模型还需要继续思考总结）
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, "THINKING", "Luna 正在思考...");
        }
    }
}
