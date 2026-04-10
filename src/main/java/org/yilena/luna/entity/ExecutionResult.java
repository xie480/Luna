package org.yilena.luna.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
/**
 * 执行结果实体，负责统一承接工具或流程执行后的状态、说明和结果数据。
 */
public class ExecutionResult implements Serializable {

    /**
     * 序列化版本号，用于结果对象跨进程传输时保持兼容。
     */
    private static final long serialVersionUID = 1L;

    /**
     * 执行状态，例如成功、失败或处理中。
     */
    private String status;
    /**
     * 对执行结果的补充说明信息。
     */
    private String message;
    /**
     * 结构化结果数据，适用于工具输出为键值对的场景。
     */
    private Map<String, Object> data;
    /**
     * 原始结果字符串，适用于保留未结构化输出内容。
     */
    private String rawResult;
}
