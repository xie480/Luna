package org.yilena.luna.constants;

/**
 * MCP JSON-RPC protocol literals.
 */
public final class McpProtocolConstants {

    private McpProtocolConstants() {
    }

    public static final String JSON_RPC_VERSION = "2.0";

    public static final String PATH_RPC = "/rpc";
    public static final String PATH_TOOLS_LIST = "/tools/list";
    public static final String PATH_TOOLS_CALL = "/tools/call";
    public static final String PATH_PROMPTS_LIST = "/prompts/list";
    public static final String PATH_PROMPTS_GET = "/prompts/get";
    public static final String PATH_RESOURCES_LIST = "/resources/list";
    public static final String PATH_RESOURCES_READ = "/resources/read";

    public static final String METHOD_TOOLS_LIST = "tools/list";
    public static final String METHOD_TOOLS_CALL = "tools/call";
    public static final String METHOD_PROMPTS_LIST = "prompts/list";
    public static final String METHOD_PROMPTS_GET = "prompts/get";
    public static final String METHOD_RESOURCES_LIST = "resources/list";
    public static final String METHOD_RESOURCES_READ = "resources/read";

    public static final String METHOD_MCP_TOOLS_LIST = "mcp.tools.list";
    public static final String METHOD_MCP_TOOLS_CALL = "mcp.tools.call";
    public static final String METHOD_MCP_PROMPTS_LIST = "mcp.prompts.list";
    public static final String METHOD_MCP_PROMPTS_GET = "mcp.prompts.get";
    public static final String METHOD_MCP_RESOURCES_LIST = "mcp.resources.list";
    public static final String METHOD_MCP_RESOURCES_READ = "mcp.resources.read";

    public static final String DEFAULT_ARGUMENTS_JSON = "{}";
}
