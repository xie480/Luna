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
public class EditPolicy {
    private Boolean create;
    private Boolean update;
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
