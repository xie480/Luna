package org.yilena.luna.memory.guard;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class LegacyMemoryReadonlyAspect {

    @Value("${memory.legacy-readonly.enabled:true}")
    private boolean legacyReadonlyEnabled;

    @Around("execution(* org.yilena.luna.mapper.McpToolMapper.insert*(..)) || " +
            "execution(* org.yilena.luna.mapper.McpToolMapper.update*(..)) || " +
            "execution(* org.yilena.luna.mapper.McpToolMapper.delete*(..)) || " +
            "execution(* org.yilena.luna.mapper.McpSkillMapper.insert*(..)) || " +
            "execution(* org.yilena.luna.mapper.McpSkillMapper.update*(..)) || " +
            "execution(* org.yilena.luna.mapper.McpSkillMapper.delete*(..))")
    public Object blockLegacyWrite(ProceedingJoinPoint pjp) throws Throwable {
        if (!legacyReadonlyEnabled) {
            return pjp.proceed();
        }
        throw new IllegalStateException("legacy readonly mode enabled: writes to mcp_tools/mcp_skills are blocked");
    }
}

