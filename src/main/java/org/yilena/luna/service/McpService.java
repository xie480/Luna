package org.yilena.luna.service;

import org.yilena.luna.entity.McpSkill;
import org.yilena.luna.entity.McpTool;
import org.yilena.luna.entity.Resource;

import java.util.List;

/**
 * MCP 服務接口
 * 負責聚合管理 Tool 和 Skill
 */
public interface McpService {
    
    /**
     * 註冊工具
     */
    McpTool registerTool(McpTool tool);

    /**
     * 註冊技能
     */
    McpSkill registerSkill(McpSkill skill);

    /**
     * 獲取所有資源 (Tool + Skill)
     */
    List<Resource> listAll();

    /**
     * 根據 ID 獲取資源
     */
    Resource getResourceById(String id);

    /**
     * 搜索資源 (同時搜索 Tool 和 Skill)
     */
    List<Resource> searchResources(String query);
}
