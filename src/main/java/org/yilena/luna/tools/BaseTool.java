package org.yilena.luna.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.yilena.luna.constants.JsonFieldConstants;
import org.yilena.luna.constants.ResultStatusConstants;

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
            map.put(JsonFieldConstants.STATUS, ResultStatusConstants.SUCCESS);
            map.put(JsonFieldConstants.DATA, data);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{\"" + JsonFieldConstants.STATUS + "\":\"" + ResultStatusConstants.ERROR
                    + "\", \"" + JsonFieldConstants.MESSAGE + "\":\"JSON serialization failed\"}";
        }
    }

    protected String error(String message) {
        try {
            Map<String, Object> map = new HashMap<>();
            map.put(JsonFieldConstants.STATUS, ResultStatusConstants.ERROR);
            map.put(JsonFieldConstants.MESSAGE, message);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{\"" + JsonFieldConstants.STATUS + "\":\"" + ResultStatusConstants.ERROR
                    + "\", \"" + JsonFieldConstants.MESSAGE + "\":\"" + message + "\"}";
        }
    }
}
