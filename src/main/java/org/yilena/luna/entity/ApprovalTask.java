package org.yilena.luna.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 审批任务实体，负责保存待审批工具调用的任务信息以及审批后续聊所需的上下文。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalTask implements Serializable {

    /**
     * 序列化版本号，用于 Redis 或持久化反序列化兼容。
     */
    private static final long serialVersionUID = 1L;

    /**
     * 审批任务唯一标识，用于前后端和缓存检索同一条审批记录。
     */
    private String taskId;
    /**
     * 触发审批的会话标识，用于审批完成后恢复原对话流程。
     */
    private String sessionId;
    /**
     * 被审批能力对应的资源主键。
     */
    private Long resourceId;
    /**
     * 审批任务当前状态编码，例如待审批、执行中、已完成或已拒绝。
     */
    private String status;

    /**
     * 被审批能力的展示名称，便于前端提示用户确认操作内容。
     */
    private String skillName;

    /**
     * MCP 服务编码，用于审批通过后路由到目标服务。
     */
    private String serverCode;
    /**
     * 待执行工具名称，用于审批通过后发起具体调用。
     */
    private String toolName;

    /**
     * 旧版 Bean 路由名称，仅用于兼容历史调用链路。
     */
    private String beanName;
    /**
     * 旧版方法名称，仅用于兼容历史调用链路。
     */
    private String methodName;

    /**
     * 工具调用参数的 JSON 字符串。
     */
    private String argsJson;
    /**
     * 审批处理完成后的结果内容，通常为工具执行结果或续聊返回值。
     */
    private String result;
    /**
     * 失败或拒绝场景下的错误码，便于前端识别处理结果。
     */
    private String errorCode;
    /**
     * 任务创建时间戳，单位为毫秒。
     */
    private Long createTime;
    /**
     * 任务最后更新时间戳，单位为毫秒。
     */
    private Long updateTime;

    /**
     * 原始聊天会话键，用于审批结束后恢复到对应对话上下文。
     */
    private String chatSessionKey;
    /**
     * 触发本次审批的用户原始输入。
     */
    private String userInput;
    /**
     * 审批前收集到的记忆片段，供审批后续聊复用。
     */
    private List<String> memorySnippets;
    /**
     * 审批前命中的知识片段，供审批后回复继续使用。
     */
    private List<String> knowledgeSnippets;
    /**
     * 审批前命中的用户偏好片段，帮助后续回复保持个性化。
     */
    private List<String> preferenceSnippets;
    /**
     * 审批前命中的长期记忆片段，用于恢复更完整的上下文。
     */
    private List<String> longTermMemorySnippets;
}
