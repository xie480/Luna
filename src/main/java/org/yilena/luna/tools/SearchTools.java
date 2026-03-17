package org.yilena.luna.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
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
            log.error("【Tool Debug】未配置 Serper API Key");
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

            log.info("【Tool Debug】正在向 Serper 发送 HTTP 请求, Endpoint: {}", endpoint);

            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String responseString = response.body().string();
                    // 【关键排查点】打印出 Serper 实际返回的原始数据，证明工具确实被执行且拿到了数据
                    log.info("【Tool Debug】Serper API 成功返回数据，长度: {} 字符", responseString.length());
                    log.debug("【Tool Debug】Serper API 原始返回内容: {}", responseString);
                    
                    JsonNode jsonNode = objectMapper.readTree(responseString);
                    return success(jsonNode);
                } else {
                    String errorBody = response.body() != null ? response.body().string() : "";
                    log.error("【Tool Debug】Serper API 请求失败: HTTP {}, Body: {}", response.code(), errorBody);
                    return error("搜索请求失败: HTTP " + response.code());
                }
            }
        } catch (Exception e) {
            log.error("【Tool Debug】执行搜索请求异常", e);
            return error("搜索异常: " + e.getMessage());
        }
    }

    @LunaState(value = LunaStateConstant.VALUE_SEARCH_WEB, status = LunaStateConstant.STATUS_SEARCHING)
    @Tool("通用网页搜索工具。当用户明确要求'搜索'、'查询'，或者问题超出知识范围时，必须调用此工具。")
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.SEARCH_WEB, type = LogType.TOOL_CALL, content = "执行网页搜索")
    public String searchWeb(@P("需要搜索的关键词或短语") String query) {
        log.info("【Tool Debug】大模型触发了 searchWeb 工具，关键词: {}", query);
        Map<String, Object> payload = new HashMap<>();
        payload.put("q", query);
        payload.put("gl", "cn");
        payload.put("hl", "zh-cn");
        return executeSerperRequest("search", payload);
    }

    @LunaState(value = LunaStateConstant.VALUE_SEARCH_IMAGES, status = LunaStateConstant.STATUS_SEARCHING)
    @Tool("图片搜索工具。当用户明确要求'找图'、'看图'、'搜索图片'时调用。")
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.SEARCH_IMAGES, type = LogType.TOOL_CALL, content = "执行图片搜索")
    public String searchImages(@P("需要搜索的图片关键词") String query) {
        log.info("【Tool Debug】大模型触发了 searchImages 工具，关键词: {}", query);
        Map<String, Object> payload = new HashMap<>();
        payload.put("q", query);
        payload.put("gl", "cn");
        payload.put("hl", "zh-cn");
        return executeSerperRequest("images", payload);
    }

    @LunaState(value = LunaStateConstant.VALUE_SEARCH_NEWS, status = LunaStateConstant.STATUS_SEARCHING)
    @Tool("新闻搜索工具。当用户询问'新闻'、'热点'、'发生什么事'或明确要求搜索新闻时，必须优先调用此工具。")
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.SEARCH_NEWS, type = LogType.TOOL_CALL, content = "执行新闻搜索")
    public String searchNews(@P("需要搜索的新闻关键词或主题") String query) {
        log.info("【Tool Debug】大模型触发了 searchNews 工具，关键词: {}", query);
        Map<String, Object> payload = new HashMap<>();
        payload.put("q", query);
        payload.put("gl", "cn");
        payload.put("hl", "zh-cn");
        return executeSerperRequest("news", payload);
    }

    @LunaState(value = LunaStateConstant.VALUE_SEARCH_LENS, status = LunaStateConstant.STATUS_SEARCHING)
    @Tool("以图搜图工具。仅当用户提供图片URL并要求搜索相关信息时调用。")
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.SEARCH_LENS, type = LogType.TOOL_CALL, content = "执行以图搜图")
    public String searchLens(@P("需要进行以图搜图的图片完整URL地址") String url) {
        log.info("【Tool Debug】大模型触发了 searchLens 工具，URL: {}", url);
        Map<String, Object> payload = new HashMap<>();
        payload.put("url", url);
        payload.put("gl", "cn");
        payload.put("hl", "zh-cn");
        return executeSerperRequest("lens", payload);
    }

    @LunaState(value = LunaStateConstant.VALUE_SCRAPE_WEB, status = LunaStateConstant.STATUS_SCRAPING)
    @Tool("网页抓取工具。仅当用户提供具体URL并要求读取/总结该网页内容时调用。")
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.SCRAPE_WEB, type = LogType.TOOL_CALL, content = "抓取网页内容")
    public String scrapeWeb(@P("需要抓取内容的网页完整URL地址") String url) {
        log.info("【Tool Debug】大模型触发了 scrapeWeb 工具，URL: {}", url);
        Map<String, Object> payload = new HashMap<>();
        payload.put("url", url);
        return executeSerperRequest("scrape", payload);
    }
}
