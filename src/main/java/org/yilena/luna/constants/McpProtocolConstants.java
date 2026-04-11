package org.yilena.luna.constants;

/**
 * MCP 协议常量类，负责统一维护 MCP JSON-RPC 协议版本、路由路径和方法名，
 * 供服务端注册、客户端调用和协议适配层复用。
 */
public final class McpProtocolConstants {

    private McpProtocolConstants() {
    }

    /**
     * JSON-RPC 协议版本号。
     */
    public static final String JSON_RPC_VERSION = "2.0";

    /**
     * 通用 RPC 路径。
     */
    public static final String PATH_RPC = "/rpc";
    /**
     * 工具列表路径。
     */
    public static final String PATH_TOOLS_LIST = "/tools/list";
    /**
     * 工具调用路径。
     */
    public static final String PATH_TOOLS_CALL = "/tools/call";
    /**
     * 提示词列表路径。
     */
    public static final String PATH_PROMPTS_LIST = "/prompts/list";
    /**
     * 提示词获取路径。
     */
    public static final String PATH_PROMPTS_GET = "/prompts/get";
    /**
     * 资源列表路径。
     */
    public static final String PATH_RESOURCES_LIST = "/resources/list";
    /**
     * 资源读取路径。
     */
    public static final String PATH_RESOURCES_READ = "/resources/read";

    /**
     * 工具列表方法名。
     */
    public static final String METHOD_TOOLS_LIST = "tools/list";
    /**
     * 工具调用方法名。
     */
    public static final String METHOD_TOOLS_CALL = "tools/call";
    /**
     * 提示词列表方法名。
     */
    public static final String METHOD_PROMPTS_LIST = "prompts/list";
    /**
     * 提示词获取方法名。
     */
    public static final String METHOD_PROMPTS_GET = "prompts/get";
    /**
     * 资源列表方法名。
     */
    public static final String METHOD_RESOURCES_LIST = "resources/list";
    /**
     * 资源读取方法名。
     */
    public static final String METHOD_RESOURCES_READ = "resources/read";

    /**
     * MCP 前缀工具列表方法名。
     */
    public static final String METHOD_MCP_TOOLS_LIST = "mcp.tools.list";
    /**
     * MCP 前缀工具调用方法名。
     */
    public static final String METHOD_MCP_TOOLS_CALL = "mcp.tools.call";
    /**
     * MCP 前缀提示词列表方法名。
     */
    public static final String METHOD_MCP_PROMPTS_LIST = "mcp.prompts.list";
    public static final String METHOD_MCP_PROMPTS_GET = "mcp.prompts.get";
    public static final String METHOD_MCP_RESOURCES_LIST = "mcp.resources.list";
    public static final String METHOD_MCP_RESOURCES_READ = "mcp.resources.read";

    public static final String DEFAULT_ARGUMENTS_JSON = "{}";
}
