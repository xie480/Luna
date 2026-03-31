package org.yilena.luna.memory.guard;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yilena.luna.mapper.LegacyMcpReadMetricMapper;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class LegacyMcpReadMetricAspect {

    private final LegacyMcpReadMetricMapper metricMapper;

    @Value("${app.release-version:${spring.application.name:unknown}}")
    private String appReleaseVersion;

    @Around("execution(* org.yilena.luna.mapper.McpToolMapper.select*(..)) || " +
            "execution(* org.yilena.luna.mapper.McpToolMapper.searchByVector(..))")
    public Object metricToolRead(ProceedingJoinPoint pjp) throws Throwable {
        Object result = pjp.proceed();
        bump("mcp_tools");
        return result;
    }

    @Around("execution(* org.yilena.luna.mapper.McpSkillMapper.select*(..)) || " +
            "execution(* org.yilena.luna.mapper.McpSkillMapper.searchByVector(..))")
    public Object metricSkillRead(ProceedingJoinPoint pjp) throws Throwable {
        Object result = pjp.proceed();
        bump("mcp_skills");
        return result;
    }

    private void bump(String legacyTable) {
        try {
            metricMapper.bumpRead(normalize(appReleaseVersion), legacyTable);
        } catch (Exception ex) {
            log.warn("legacy read metric bump failed, table={}, version={}", legacyTable, appReleaseVersion, ex);
        }
    }

    private String normalize(String version) {
        if (version == null || version.isBlank()) {
            return "unknown";
        }
        return version.trim();
    }
}

