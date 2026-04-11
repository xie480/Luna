package org.yilena.luna.prompt.governance.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
/**
 * 编辑策略模型，负责描述提示词是否允许创建、更新和删除，
 * 用于在治理流程中约束不同类型提示词的可编辑范围。
 */
public class EditPolicy {
    /**
     * 是否允许创建同类提示词。
     */
    private Boolean create;
    /**
     * 是否允许更新当前提示词。
     */
    private Boolean update;
    /**
     * 是否允许删除当前提示词。
     */
    private Boolean delete;

    public static EditPolicy contentDefault() {
        return EditPolicy.builder().create(true).update(true).delete(true).build();
    }

    public static EditPolicy executionDefault() {
        return EditPolicy.builder().create(false).update(true).delete(false).build();
    }

    public static EditPolicy fromMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return EditPolicy.builder().create(false).update(true).delete(false).build();
        }
        return EditPolicy.builder()
                .create(readBoolean(values.get("create")))
                .update(readBoolean(values.get("update")))
                .delete(readBoolean(values.get("delete")))
                .build();
    }

    public Map<String, Object> toMap() {
        return Map.of(
                "create", bool(create, false),
                "update", bool(update, true),
                "delete", bool(delete, false)
        );
    }

    private static Boolean readBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return null;
        }
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static boolean bool(Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }
}
