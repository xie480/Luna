package org.yilena.luna.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestParam;
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
/**
 * 搜索工具类，负责封装网页、图片、新闻、以图搜图和网页抓取能力，统一接入 Serper 搜索服务。
 */
public class SearchTools extends BaseTool {

    /**
     * Serper API 密钥，用于调用外部搜索与抓取服务。
     */
    @Value("${serper.api-key:}")
    private String apiKey;

    /**
     * HTTP 客户端，用于向 Serper 服务发起请求。
     */
    private final OkHttpClient client;

    public SearchTools(ObjectMapper objectMapper) {
        super(objectMapper);
        this.client = new OkHttpClient().newBuilder().build();
    }

    /**
     * 通用的 Serper API 请求执行器
     */
    /**
     * 通用 Serper 请求执行器，负责完成鉴权、请求发送和响应包装。
     */
    private String executeSerperRequest(String endpoint, Map<String, Object> payload) {
        /**
         * 请求前先检查 API Key，避免在未配置鉴权信息时发起无效外部调用。
         */
        if (apiKey == null || apiKey.trim().isEmpty()) {
            log.error("【Tool Debug】未配置 Serper API Key");
            return error("未配置 Serper API Key，请在 application.yaml 中配置 serper.api-key");
        }

        try {
            // 将请求体序列化并构造 Serper HTTP 请求。
            String jsonBody = objectMapper.writeValueAsString(payload);
            MediaType mediaType = MediaType.parse("application/json");
            RequestBody body = RequestBody.create(mediaType, jsonBody);
            
            /**
             * 请求体准备完成后组装目标端点和鉴权头，确保外部接口可以正确识别请求。
             */
            Request request = new Request.Builder()
                    .url("https://google.serper.dev/" + endpoint)
                    .method("POST", body)
                    .addHeader("X-API-KEY", apiKey)
                    .addHeader("Content-Type", "application/json")
                    .build();

            log.info("【Tool Debug】正在向 Serper 发送 HTTP 请求, Endpoint: {}", endpoint);

            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    /**
                     * 成功响应统一解析为 JSON 节点，并包装成工具标准返回结构。
                     */
                    // 成功时解析为 JSON 并按统一 success 结构返回。
                    String responseString = response.body().string();
                    log.info("【Tool Debug】Serper API 成功返回数据，长度: {} 字符", responseString.length());
                    log.debug("【Tool Debug】Serper API 原始返回内容: {}", responseString);
                    
                    JsonNode jsonNode = objectMapper.readTree(responseString);
                    return success(jsonNode);
                } else {
                    /**
                     * 失败响应保留状态码和返回体，便于定位外部搜索服务调用问题。
                     */
                    // 失败时记录 HTTP 状态码与响应体，便于排障。
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
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.SEARCH_WEB, type = LogType.TOOL_CALL, content = "执行网页搜索")
    /**
     * 执行网页搜索，返回与查询词最相关的网页结果集合。
     */
    public String web_search(@RequestParam("query") String query) {
        log.info("【Tool Debug】大模型触发了 web_search 工具，关键词: {}", query);
        // 组装网页搜索参数并调用 search 端点。
        /**
         * 组装网页搜索参数，并指定中文场景的地区与语言偏好。
         */
        Map<String, Object> payload = new HashMap<>();
        payload.put("q", query);
        payload.put("gl", "cn");
        payload.put("hl", "zh-cn");
        return executeSerperRequest("search", payload);
    }

    @LunaState(value = LunaStateConstant.VALUE_SEARCH_IMAGES, status = LunaStateConstant.STATUS_SEARCHING)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.SEARCH_IMAGES, type = LogType.TOOL_CALL, content = "执行图片搜索")
    /**
     * 执行图片搜索，返回与查询词相关的图片检索结果。
     */
    public String image_search(@RequestParam("query") String query) {
        log.info("【Tool Debug】大模型触发了 image_search 工具，关键词: {}", query);
        // 组装图片搜索参数并调用 images 端点。
        /**
         * 组装图片搜索参数，复用统一的地区和语言配置。
         */
        Map<String, Object> payload = new HashMap<>();
        payload.put("q", query);
        payload.put("gl", "cn");
        payload.put("hl", "zh-cn");
        return executeSerperRequest("images", payload);
    }

    @LunaState(value = LunaStateConstant.VALUE_SEARCH_NEWS, status = LunaStateConstant.STATUS_SEARCHING)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.SEARCH_NEWS, type = LogType.TOOL_CALL, content = "执行新闻搜索")
    /**
     * 执行新闻搜索，返回与查询主题相关的新闻内容。
     */
    public String news_search(@RequestParam("query") String query) {
        log.info("【Tool Debug】大模型触发了 news_search 工具，关键词: {}", query);
        // 组装新闻搜索参数并调用 news 端点。
        /**
         * 组装新闻搜索参数，使请求命中新闻检索端点。
         */
        Map<String, Object> payload = new HashMap<>();
        payload.put("q", query);
        payload.put("gl", "cn");
        payload.put("hl", "zh-cn");
        return executeSerperRequest("news", payload);
    }

    @LunaState(value = LunaStateConstant.VALUE_SEARCH_LENS, status = LunaStateConstant.STATUS_SEARCHING)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.SEARCH_LENS, type = LogType.TOOL_CALL, content = "执行以图搜图")
    /**
     * 执行以图搜图，根据图片地址检索视觉相似内容。
     */
    public String lens_search(@RequestParam("url") String url) {
        log.info("【Tool Debug】大模型触发了 lens_search 工具，URL: {}", url);
        // 组装以图搜图参数并调用 lens 端点。
        /**
         * 组装以图搜图参数，交由 Lens 端点完成图片反查。
         */
        Map<String, Object> payload = new HashMap<>();
        payload.put("url", url);
        payload.put("gl", "cn");
        payload.put("hl", "zh-cn");
        return executeSerperRequest("lens", payload);
    }

    @LunaState(value = LunaStateConstant.VALUE_SCRAPE_WEB, status = LunaStateConstant.STATUS_SCRAPING)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.SCRAPE_WEB, type = LogType.TOOL_CALL, content = "抓取网页内容")
    /**
     * 抓取指定网页内容，用于在搜索结果之外补充页面正文信息。
     */
    public String web_scrape(@RequestParam("url") String url) {
        log.info("【Tool Debug】大模型触发了 web_scrape 工具，URL: {}", url);
        // 组装网页抓取参数并调用 scrape 端点。
        /**
         * 组装网页抓取参数，直接请求页面正文抓取能力。
         */
        Map<String, Object> payload = new HashMap<>();
        payload.put("url", url);
        return executeSerperRequest("scrape", payload);
    }
}
