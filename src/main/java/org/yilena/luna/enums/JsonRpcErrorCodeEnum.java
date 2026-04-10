package org.yilena.luna.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * JSON-RPC 错误码枚举，定义 MCP 网关返回的标准协议错误。
 */
@Getter
@AllArgsConstructor
public enum JsonRpcErrorCodeEnum {
    /**
     * 请求结构不合法。
     */
    INVALID_REQUEST(-32600, "非法请求"),
    /**
     * 方法不存在。
     */
    METHOD_NOT_FOUND(-32601, "方法不存在"),
    /**
     * 服务内部异常。
     */
    INTERNAL_ERROR(-32603, "内部错误");

    /**
     * JSON-RPC 标准错误码。
     */
    private final int code;
    /**
     * 错误中文描述。
     */
    private final String desc;

    /**
     * 根据错误码解析枚举。
     */
    public static JsonRpcErrorCodeEnum ofCode(int code) {
        return Arrays.stream(values())
                .filter(item -> item.code == code)
                .findFirst()
                .orElse(null);
    }
}
