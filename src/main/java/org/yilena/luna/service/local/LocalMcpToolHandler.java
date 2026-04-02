package org.yilena.luna.service.local;

import java.util.List;

/**
 * Local MCP tool handler abstraction.
 * Each handler owns one MCP tool execution path without host-side reflection.
 */
public interface LocalMcpToolHandler {

    String toolName();

    default List<String> aliases() {
        return List.of();
    }

    String handle(String argumentsJson);
}

