package org.yilena.luna.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yilena.luna.annotation.LunaLogRecord;
import org.yilena.luna.annotation.LunaState;
import org.yilena.luna.constants.LogActionConstant;
import org.yilena.luna.constants.LogModuleConstant;
import org.yilena.luna.constants.LunaStateConstant;
import org.yilena.luna.enums.LogType;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class SearchTools extends BaseTool {

    @Value("${serper.api-key:}")
    private String apiKey;

    private final OkHttpClient client;

    public SearchTools(ObjectMapper objectMapper) {
        super(objectMapper);
        this.client = new OkHttpClient().newBuilder().build();
    }

    /**
     * 通用的 Serper API 请求执行器
     */
    private String executeSerperRequest(String endpoint, Map<String, Object> payload) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return error("未配置 Serper API Key，请在 application.yaml 中配置 serper.api-key");
        }

        try {
            String jsonBody = objectMapper.writeValueAsString(payload);
            MediaType mediaType = MediaType.parse("application/json");
            RequestBody body = RequestBody.create(mediaType, jsonBody);
            
            Request request = new Request.Builder()
                    .url("https://google.serper.dev/" + endpoint)
                    .method("POST", body)
                    .addHeader("X-API-KEY", apiKey)
                    .addHeader("Content-Type", "application/json")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    // 将返回的 JSON 字符串解析为 JsonNode，避免 success() 方法二次转义字符串
                    JsonNode jsonNode = objectMapper.readTree(response.body().string());
                    return success(jsonNode);
                } else {
                    String errorBody = response.body() != null ? response.body().string() : "";
                    log.error("Serper API 请求失败: HTTP {}, Body: {}", response.code(), errorBody);
                    return error("搜索请求失败: HTTP " + response.code());
                }
            }
        } catch (Exception e) {
            log.error("执行搜索请求异常", e);
            return error("搜索异常: " + e.getMessage());
        }
    }

    @LunaState(value = LunaStateConstant.VALUE_SEARCH_WEB, status = LunaStateConstant.STATUS_SEARCHING)
    @Tool("当你需要回答的问题超出了你的知识范围，或者需要获取实时信息（如新闻、天气、股价）时，调用此工具进行普通网页搜索。返回格式为 JSON。")
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.SEARCH_WEB, type = LogType.TOOL_CALL, content = "执行网页搜索")
    public String searchWeb(String query) {
        log.info("Luna 正在执行普通网页搜索，关键词: {}", query);
        Map<String, Object> payload = new HashMap<>();
        payload.put("q", query);
        payload.put("gl", "cn");
        payload.put("hl", "zh-cn");
        return executeSerperRequest("search", payload);
    }

    @LunaState(value = LunaStateConstant.VALUE_SEARCH_IMAGES, status = LunaStateConstant.STATUS_SEARCHING)
    @Tool("当你需要搜索图片时，调用此工具进行图片搜索。返回格式为 JSON。")
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.SEARCH_IMAGES, type = LogType.TOOL_CALL, content = "执行图片搜索")
    public String searchImages(String query) {
        log.info("Luna 正在执行图片搜索，关键词: {}", query);
        Map<String, Object> payload = new HashMap<>();
        payload.put("q", query);
        payload.put("gl", "cn");
        payload.put("hl", "zh-cn");
        return executeSerperRequest("images", payload);
    }

    @LunaState(value = LunaStateConstant.VALUE_SEARCH_NEWS, status = LunaStateConstant.STATUS_SEARCHING)
    @Tool("当你需要获取最新新闻时，调用此工具进行新闻搜索。返回格式为 JSON。")
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.SEARCH_NEWS, type = LogType.TOOL_CALL, content = "执行新闻搜索")
    public String searchNews(String query) {
        log.info("Luna 正在执行新闻搜索，关键词: {}", query);
        Map<String, Object> payload = new HashMap<>();
        payload.put("q", query);
        payload.put("gl", "cn");
        payload.put("hl", "zh-cn");
        return executeSerperRequest("news", payload);
    }

    @LunaState(value = LunaStateConstant.VALUE_SEARCH_LENS, status = LunaStateConstant.STATUS_SEARCHING)
    @Tool("当你需要通过图片URL进行以图搜图时，调用此工具。返回格式为 JSON。")
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.SEARCH_LENS, type = LogType.TOOL_CALL, content = "执行以图搜图")
    public String searchLens(String url) {
        log.info("Luna 正在执行以图搜图，URL: {}", url);
        Map<String, Object> payload = new HashMap<>();
        payload.put("url", url);
        payload.put("gl", "cn");
        payload.put("hl", "zh-cn");
        return executeSerperRequest("lens", payload);
    }

    @LunaState(value = LunaStateConstant.VALUE_SCRAPE_WEB, status = LunaStateConstant.STATUS_SCRAPING)
    @Tool("当你需要抓取特定网页的具体内容时，调用此工具。返回格式为 JSON。")
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.SCRAPE_WEB, type = LogType.TOOL_CALL, content = "抓取网页内容")
    public String scrapeWeb(String url) {
        log.info("Luna 正在执行网页内容抓取，URL: {}", url);
        Map<String, Object> payload = new HashMap<>();
        payload.put("url", url);
        return executeSerperRequest("scrape", payload);
    }
}
