package org.yilena.luna.constants;

/**
 * JSON 字段常量类，负责统一维护接口载荷、工具结果和映射结构中常用的字段名，
 * 避免各处重复硬编码公共字段键。
 */
public final class JsonFieldConstants {

    private JsonFieldConstants() {
    }

    /**
     * 状态字段名。
     */
    public static final String STATUS = "status";
    /**
     * 消息字段名。
     */
    public static final String MESSAGE = "message";
    /**
     * 数据主体字段名。
     */
    public static final String DATA = "data";
    /**
     * 原始内容字段名。
     */
    public static final String RAW = "raw";
    /**
     * 错误信息字段名。
     */
    public static final String ERROR = "error";
    /**
     * 结果字段名。
     */
    public static final String RESULT = "result";
    /**
     * 编码字段名。
     */
    public static final String CODE = "code";
    /**
     * 标识字段名。
     */
    public static final String ID = "id";
    /**
     * 令牌字段名。
     */
    public static final String TOKEN = "token";
    /**
     * JSON-RPC 版本字段名。
     */
    public static final String JSON_RPC = "jsonrpc";
    /**
     * 方法名字段名。
     */
    public static final String METHOD = "method";
    /**
     * 参数字段名。
     */
    public static final String PARAMS = "params";
    /**
     * 工具字段名。
     */
    public static final String TOOL = "tool";
    /**
     * 任务标识字段名。
     */
    public static final String TASK_ID = "taskId";
    /**
     * 审批结果字段名。
     */
    public static final String APPROVED = "approved";
    /**
     * 错误码字段名。
     */
    public static final String ERROR_CODE = "errorCode";
}
