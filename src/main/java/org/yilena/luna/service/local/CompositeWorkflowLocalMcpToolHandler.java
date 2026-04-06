package org.yilena.luna.service.local;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.yilena.luna.constants.BooleanTextConstants;
import org.yilena.luna.constants.JsonFieldConstants;
import org.yilena.luna.constants.LocalToolConstants;
import org.yilena.luna.constants.McpConstant;
import org.yilena.luna.constants.McpProtocolConstants;
import org.yilena.luna.constants.ResultStatusConstants;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.entity.WorkflowTemplate;
import org.yilena.luna.enums.ResourceType;
import org.yilena.luna.enums.RunMode;
import org.yilena.luna.enums.Sensitivity;
import org.yilena.luna.executor.WorkflowExecutor;
import org.yilena.luna.mapper.WorkflowTemplateMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class CompositeWorkflowLocalMcpToolHandler implements LocalMcpToolHandler {

    private final WorkflowTemplateMapper workflowTemplateMapper;
    private final ObjectProvider<WorkflowExecutor> workflowExecutorProvider;
    private final ObjectMapper objectMapper;

    @Override
    public String toolName() {
        return "__composite_workflow_tool__";
    }

    @Override
    public List<String> aliases() {
        return List.of();
    }

    @Override
    public boolean supports(InvocationContext context) {
        if (context == null) {
            return false;
        }
        if (!LocalToolConstants.IMPL_TYPE_LOCAL_HANDLER.equalsIgnoreCase(text(context.implType()))) {
            return false;
        }
        String name = text(context.toolName());
        if (name.isBlank()) {
            return false;
        }
        return loadWorkflowTemplate(name) != null;
    }

    @Override
    public String handle(InvocationContext context) {
        String toolName = context == null ? "" : text(context.toolName());
        if (toolName.isBlank()) {
            return error("WORKFLOW_TOOL_NAME_REQUIRED", "toolName is required");
        }
        WorkflowTemplate template = loadWorkflowTemplate(toolName);
        if (template == null || !Boolean.TRUE.equals(template.getEnabled())) {
            return error("WORKFLOW_TEMPLATE_NOT_FOUND", "workflow template not found: " + toolName);
        }
        Resource workflow = Resource.builder()
                .id(String.valueOf(template.getId()))
                .type(ResourceType.WORKFLOW)
                .serverCode(McpConstant.LOCAL_SERVER_CODE)
                .name(template.getWorkflowName())
                .description(template.getDescription())
                .version(template.getVersion())
                .inputSchema(toJson(template.getInputSchema()))
                .outputSchema(toJson(template.getOutputSchema()))
                .requiredCapabilities(template.getRequiredCapabilities())
                .toolSlots(toSlots(template.getToolSlots()))
                .thoughtChain(template.getThoughtChain())
                .requiresApproval(false)
                .sensitivity(Sensitivity.LOW)
                .runMode(RunMode.SYNC)
                .build();
        try {
            WorkflowExecutor workflowExecutor = workflowExecutorProvider.getIfAvailable();
            if (workflowExecutor == null) {
                return error("WORKFLOW_EXECUTOR_UNAVAILABLE", "workflow executor unavailable");
            }
            String argsJson = context.argumentsJson() == null || context.argumentsJson().isBlank()
                    ? McpProtocolConstants.DEFAULT_ARGUMENTS_JSON
                    : context.argumentsJson();
            return workflowExecutor.execute(workflow, argsJson);
        } catch (Exception e) {
            log.warn("composite workflow local handler failed, workflowName={}, err={}", toolName, e.getMessage());
            return error("WORKFLOW_EXECUTION_FAILED", e.getMessage());
        }
    }

    @Override
    public String handle(String argumentsJson) {
        return error("TOOL_CONTEXT_REQUIRED", "InvocationContext is required");
    }

    private WorkflowTemplate loadWorkflowTemplate(String workflowName) {
        try {
            return workflowTemplateMapper.selectOne(new LambdaQueryWrapper<WorkflowTemplate>()
                    .eq(WorkflowTemplate::getWorkflowName, workflowName)
                    .last("LIMIT 1"));
        } catch (Exception e) {
            return null;
        }
    }

    private List<Resource.ToolSlotDto> toSlots(List<Map<String, Object>> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return source.stream().map(item -> Resource.ToolSlotDto.builder()
                .slot(text(item == null ? null : item.get("slot")))
                .capability(text(item == null ? null : item.get("capability")))
                .required(bool(item == null ? null : item.get("required"), true))
                .build()).toList();
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private String error(String code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(JsonFieldConstants.STATUS, ResultStatusConstants.ERROR);
        payload.put(JsonFieldConstants.ERROR_CODE, code);
        payload.put(JsonFieldConstants.MESSAGE, message == null ? "" : message);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return LocalToolConstants.STATUS_ERROR_JSON;
        }
    }

    private boolean bool(Object raw, boolean fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Boolean b) {
            return b;
        }
        String text = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (BooleanTextConstants.TRUE.equals(text)
                || BooleanTextConstants.ONE.equals(text)
                || BooleanTextConstants.YES.equals(text)) {
            return true;
        }
        if (BooleanTextConstants.FALSE.equals(text)
                || BooleanTextConstants.ZERO.equals(text)
                || BooleanTextConstants.NO.equals(text)) {
            return false;
        }
        return fallback;
    }

    private String text(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }
}
