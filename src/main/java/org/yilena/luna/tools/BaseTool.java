package org.yilena.luna.tools;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

public abstract class BaseTool {

    protected final ObjectMapper objectMapper;

    public BaseTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    protected String success(Object data) {
        try {
            Map<String, Object> map = new HashMap<>();
            map.put("status", "success");
            map.put("data", data);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{\"status\":\"error\", \"message\":\"JSON序列化失败\"}";
        }
    }

    protected String error(String message) {
        try {
            Map<String, Object> map = new HashMap<>();
            map.put("status", "error");
            map.put("message**Generating New Tools Files**

I'm now generating all the files, including `BaseTool.java`, `SearchTools.java`, `PreferenceTools.java`, `ScheduleTools.java`, `MemoryTools.java`, `KnowledgeBaseTools.java`, and `LogTools.java`, alongside an empty `LunaTools.java`. The `BaseTool` class will be abstract to handle the `ObjectMapper` and helper methods, supporting dependency injection for all other tool classes. All dependencies and annotations are added.


", message);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{\"status\":\"error\", \"message\":\"" + message + "\"}";
        }
    }
}
