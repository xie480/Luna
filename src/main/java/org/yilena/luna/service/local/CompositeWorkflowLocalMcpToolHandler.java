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
/**
 * 组合工作流本地处理器，负责把本地 MCP 工具名映射为工作流模板并交给工作流执行器执行。
 */
public class CompositeWorkflowLocalMcpToolHandler implements LocalMcpToolHandler {

    /**
     * 工作流模板 Mapper，用于按名称加载本地工作流模板。
     */
    private final WorkflowTemplateMapper workflowTemplateMapper;
    /**
     * 工作流执行器提供器，用于延迟获取执行器实例。
     */
    private final ObjectProvider<WorkflowExecutor> workflowExecutorProvider;
    /**
     * JSON 处理器，用于序列化模板中的输入输出结构。
     */
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
        /**
         * 仅当实现类型为本地处理器且能加载到同名工作流模板时才认为支持。
         */
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
        /**
         * 先按工具名加载并校验工作流模板，模板不存在或未启用时直接返回错误。
         */
        String toolName = context == null ? "" : text(context.toolName());
        if (toolName.isBlank()) {
            return error("WORKFLOW_TOOL_NAME_REQUIRED", "toolName is required");
        }
        WorkflowTemplate template = loadWorkflowTemplate(toolName);
        if (template == null || !Boolean.TRUE.equals(template.getEnabled())) {
            return error("WORKFLOW_TEMPLATE_NOT_FOUND", "workflow template not found: " + toolName);
        }
        /**
         * 将模板记录转换为可执行的工作流资源对象，供统一工作流执行链路复用。
         */
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
            /**
             * 工作流执行器可用后，构造参数 JSON 并进入统一执行入口。
             */
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

    /**
     * 按工作流名称加载模板记录，加载失败时返回 null。
     */
    private WorkflowTemplate loadWorkflowTemplate(String workflowName) {
        try {
            return workflowTemplateMapper.selectOne(new LambdaQueryWrapper<WorkflowTemplate>()
                    .eq(WorkflowTemplate::getWorkflowName, workflowName)
                    .last("LIMIT 1"));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 将模板中的工具槽位配置转换为工作流资源可识别的槽位定义。
     */
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

    /**
     * 安全序列化模板中的结构化字段，失败时返回 null。
     */
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

    /**
     * 构建本地工作流处理器错误响应。
     */
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

    /**
     * 将任意值转换为布尔值，无法识别时回退到默认值。
     */
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

    /**
     * 将任意对象转换为去空格文本。
     */
    private String text(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }
}
