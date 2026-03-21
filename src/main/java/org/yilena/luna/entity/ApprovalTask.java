package org.yilena.luna.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 審批任務上下文
 * 存儲在 Redis 中，用於在用戶批准後恢復執行
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalTask implements Serializable {
    
    private static final long serialVersionUID = 1L;

    /**
     * 任務唯一標識 (UUID)
     */
    private String taskId;

    /**
     * 會話 ID（審批關聯）
     */
    private String sessionId;

    /**
     * 技能名稱
     */
    private String skillName;

    /**
     * Spring Bean 名稱
     */
    private String beanName;

    /**
     * 方法名稱
     */
    private String methodName;

    /**
     * 參數 JSON 字符串
     */
    private String argsJson;

    /**
     * 創建時間戳
     */
    private Long createTime;

    // ===== 續跑 chat 所需上下文（新增） =====

    /**
     * chat 會話key（例如 yyyy:MM:dd）
     */
    private String chatSessionKey;

    /**
     * 當輪用戶輸入
     */
    private String userInput;

    /**
     * 當輪裁剪後的近期對話片段
     */
    private List<String> memorySnippets;

    /**
     * 當輪裁剪後的知識庫片段
     */
    private List<String> knowledgeSnippets;
}
