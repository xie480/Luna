package org.yilena.luna.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * JSON-RPC standard error codes used by MCP gateway.
 */
@Getter
@AllArgsConstructor
public enum JsonRpcErrorCodeEnum {
    INVALID_REQUEST(-32600, "Invalid Request"),
    METHOD_NOT_FOUND(-32601, "Method not found"),
    INTERNAL_ERROR(-32603, "Internal error");

    private final int code;
    private final String desc;

    public static JsonRpcErrorCodeEnum ofCode(int code) {
        return Arrays.stream(values())
                .filter(item -> item.code == code)
                .findFirst()
                .orElse(null);
    }
}
