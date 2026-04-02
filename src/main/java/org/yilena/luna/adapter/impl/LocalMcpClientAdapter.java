package org.yilena.luna.adapter.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yilena.luna.adapter.McpClientAdapter;
import org.yilena.luna.constants.McpConstant;
import org.yilena.luna.entity.McpPromptDescriptor;
import org.yilena.luna.entity.McpPromptResult;
import org.yilena.luna.entity.McpResourceDescriptor;
import org.yilena.luna.entity.McpResourceResult;
import org.yilena.luna.entity.McpServerRegistry;
import org.yilena.luna.entity.McpToolCallResult;
import org.yilena.luna.entity.McpToolDescriptor;
import org.yilena.luna.mapper.McpServerRegistryMapper;
import org.yilena.luna.service.LocalMcpServerService;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
@RequiredArgsConstructor
public class LocalMcpClientAdapter implements McpClientAdapter {

    private final McpServerRegistryMapper serverRegistryMapper;
    private final LocalMcpServerService localMcpServerService;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public List<McpToolDescriptor> listTools(String serverCode) {
        String targetServer = normalizeServerCode(serverCode);
        McpServerRegistry registry = loadServerRegistry(targetServer);
        if (isLocalServer(registry)) {
            return localMcpServerService.listTools(targetServer);
        }
        return remoteListTools(registry, targetServer);
    }

    @Override
    public McpToolCallResult callTool(String serverCode, String toolName, String argumentsJson) {
        String targetServer = normalizeServerCode(serverCode);
        McpServerRegistry registry = loadServerRegistry(targetServer);
        if (isLocalServer(registry)) {
            return localMcpServerService.callTool(targetServer, toolName, argumentsJson);
        }
        return remoteCallTool(registry, targetServer, toolName, argumentsJson);
    }

    @Override
    public List<McpPromptDescriptor> listPrompts(String serverCode) {
        String targetServer = normalizeServerCode(serverCode);
        McpServerRegistry registry = loadServerRegistry(targetServer);
        if (isLocalServer(registry)) {
            return localMcpServerService.listPrompts(targetServer);
        }
        return remoteListPrompts(registry, targetServer);
    }

    @Override
    public McpPromptResult getPrompt(String serverCode, String promptName, String argumentsJson) {
        String targetServer = normalizeServerCode(serverCode);
        McpServerRegistry registry = loadServerRegistry(targetServer);
        if (isLocalServer(registry)) {
            return localMcpServerService.getPrompt(targetServer, promptName, argumentsJson);
        }
        return remoteGetPrompt(registry, targetServer, promptName, argumentsJson);
    }

    @Override
    public List<McpResourceDescriptor> listResources(String serverCode) {
        String targetServer = normalizeServerCode(serverCode);
        McpServerRegistry registry = loadServerRegistry(targetServer);
        if (isLocalServer(registry)) {
            return localMcpServerService.listResources(targetServer);
        }
        return remoteListResources(registry, targetServer);
    }

    @Override
    public McpResourceResult readResource(String serverCode, String resourceUri) {
        String targetServer = normalizeServerCode(serverCode);
        McpServerRegistry registry = loadServerRegistry(targetServer);
        if (isLocalServer(registry)) {
            return localMcpServerService.readResource(targetServer, resourceUri);
        }
        return remoteReadResource(registry, targetServer, resourceUri);
    }

    private String normalizeServerCode(String serverCode) {
        if (serverCode == null || serverCode.isBlank()) {
            return McpConstant.LOCAL_SERVER_CODE;
        }
        return serverCode.trim();
    }

    private McpServerRegistry loadServerRegistry(String serverCode) {
        try {
            return serverRegistryMapper.selectOne(
                    new LambdaQueryWrapper<McpServerRegistry>()
                            .eq(McpServerRegistry::getServerCode, serverCode)
                            .eq(McpServerRegistry::getEnabled, true)
                            .last("LIMIT 1")
            );
        } catch (Exception e) {
            log.warn("load mcp server registry failed, serverCode={}", serverCode, e);
            return null;
        }
    }

    private boolean isLocalServer(McpServerRegistry registry) {
        if (registry != null && McpConstant.LOCAL_SERVER_CODE.equals(registry.getServerCode())) {
            return true;
        }
        if (registry == null) {
            return true;
        }
        String transport = normalizeTransport(registry.getTransportType());
        if ("LOCAL".equals(transport)) {
            return true;
        }
        return registry.getBaseUrl() == null || registry.getBaseUrl().isBlank();
    }

    private String normalizeTransport(String transportType) {
        if (transportType == null || transportType.isBlank()) {
            return "HTTP";
        }
        return transportType.trim().toUpperCase(Locale.ROOT);
    }

    private List<McpToolDescriptor> remoteListTools(McpServerRegistry registry, String serverCode) {
        String body = remoteGet(registry, "/tools/list?serverCode=" + encode(serverCode), 10000);
        try {
            return objectMapper.readValue(body, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("remote tools/list parse failed, serverCode={}, body={}", serverCode, body, e);
            return Collections.emptyList();
        }
    }

    private McpToolCallResult remoteCallTool(McpServerRegistry registry, String serverCode, String toolName, String argumentsJson) {
        String payload = toJson(Map.of(
                "serverCode", serverCode,
                "toolName", toolName == null ? "" : toolName,
                "argumentsJson", argumentsJson == null ? "{}" : argumentsJson
        ));
        String body = remotePost(registry, "/tools/call", payload, 15000);
        try {
            return objectMapper.readValue(body, McpToolCallResult.class);
        } catch (Exception e) {
            return McpToolCallResult.builder()
                    .status("error")
                    .serverCode(serverCode)
                    .toolName(toolName)
                    .rawResult(errorResult("REMOTE_CALL_PARSE_FAILED", e.getMessage()))
                    .data(Map.of("errorCode", "REMOTE_CALL_PARSE_FAILED", "message", e.getMessage()))
                    .build();
        }
    }

    private List<McpPromptDescriptor> remoteListPrompts(McpServerRegistry registry, String serverCode) {
        String body = remoteGet(registry, "/prompts/list?serverCode=" + encode(serverCode), 10000);
        try {
            return objectMapper.readValue(body, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("remote prompts/list parse failed, serverCode={}", serverCode, e);
            return Collections.emptyList();
        }
    }

    private McpPromptResult remoteGetPrompt(McpServerRegistry registry, String serverCode, String promptName, String argumentsJson) {
        String payload = toJson(Map.of(
                "serverCode", serverCode,
                "promptName", promptName == null ? "" : promptName,
                "argumentsJson", argumentsJson == null ? "{}" : argumentsJson
        ));
        String body = remotePost(registry, "/prompts/get", payload, 10000);
        try {
            return objectMapper.readValue(body, McpPromptResult.class);
        } catch (Exception e) {
            return McpPromptResult.builder()
                    .status("error")
                    .serverCode(serverCode)
                    .promptName(promptName)
                    .promptContent(Map.of("errorCode", "REMOTE_PROMPT_PARSE_FAILED", "message", e.getMessage()))
                    .build();
        }
    }

    private List<McpResourceDescriptor> remoteListResources(McpServerRegistry registry, String serverCode) {
        String body = remoteGet(registry, "/resources/list?serverCode=" + encode(serverCode), 10000);
        try {
            return objectMapper.readValue(body, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("remote resources/list parse failed, serverCode={}", serverCode, e);
            return Collections.emptyList();
        }
    }

    private McpResourceResult remoteReadResource(McpServerRegistry registry, String serverCode, String resourceUri) {
        String payload = toJson(Map.of(
                "serverCode", serverCode,
                "resourceUri", resourceUri == null ? "" : resourceUri
        ));
        String body = remotePost(registry, "/resources/read", payload, 10000);
        try {
            return objectMapper.readValue(body, McpResourceResult.class);
        } catch (Exception e) {
            return McpResourceResult.builder()
                    .status("error")
                    .serverCode(serverCode)
                    .resourceUri(resourceUri)
                    .data(Map.of("errorCode", "REMOTE_RESOURCE_PARSE_FAILED", "message", e.getMessage()))
                    .build();
        }
    }

    private String remoteGet(McpServerRegistry registry, String path, int timeoutMs) {
        String transport = normalizeTransport(registry.getTransportType());
        return switch (transport) {
            case "HTTP", "SSE" -> remoteHttpGet(registry, path, timeoutMs);
            case "RPC" -> remoteRpcCall(registry, path, null, timeoutMs);
            case "WS" -> remoteWsCall(registry, path, null, timeoutMs);
            case "STDIO" -> remoteStdioCall(registry, "GET", path, null, timeoutMs);
            default -> errorResult("UNSUPPORTED_TRANSPORT", "transport_type " + transport + " is not supported");
        };
    }

    private String remotePost(McpServerRegistry registry, String path, String payload, int timeoutMs) {
        String transport = normalizeTransport(registry.getTransportType());
        return switch (transport) {
            case "HTTP", "SSE" -> remoteHttpPost(registry, path, payload, timeoutMs);
            case "RPC" -> remoteRpcCall(registry, path, payload, timeoutMs);
            case "WS" -> remoteWsCall(registry, path, payload, timeoutMs);
            case "STDIO" -> remoteStdioCall(registry, "POST", path, payload, timeoutMs);
            default -> errorResult("UNSUPPORTED_TRANSPORT", "transport_type " + transport + " is not supported");
        };
    }

    private String remoteRpcCall(McpServerRegistry registry, String path, String payload, int timeoutMs) {
        try {
            String rpcMethod = mapPathToRpcMethod(path);
            Map<String, Object> params = extractRpcParams(path, payload);
            Map<String, Object> requestBody = new java.util.LinkedHashMap<>();
            requestBody.put("jsonrpc", "2.0");
            requestBody.put("id", String.valueOf(System.currentTimeMillis()));
            requestBody.put("method", rpcMethod);
            requestBody.put("params", params);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(joinUrl(registry.getBaseUrl(), "/rpc")))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(toJson(requestBody), StandardCharsets.UTF_8));
            applyAuth(builder, registry);
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                return errorResult("REMOTE_RPC_HTTP_ERROR", "status=" + response.statusCode() + ", body=" + response.body());
            }
            Map<String, Object> rpcResponse = objectMapper.readValue(response.body(), new TypeReference<>() {});
            Object error = rpcResponse.get("error");
            if (error != null) {
                return errorResult("REMOTE_RPC_ERROR", String.valueOf(error));
            }
            Object result = rpcResponse.get("result");
            return result == null ? "{}" : objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return errorResult("REMOTE_RPC_ERROR", e.getMessage());
        }
    }

    private String mapPathToRpcMethod(String path) {
        String clean = path == null ? "" : path.trim();
        int queryIdx = clean.indexOf('?');
        if (queryIdx >= 0) {
            clean = clean.substring(0, queryIdx);
        }
        return switch (clean) {
            case "/tools/list" -> "tools/list";
            case "/tools/call" -> "tools/call";
            case "/prompts/list" -> "prompts/list";
            case "/prompts/get" -> "prompts/get";
            case "/resources/list" -> "resources/list";
            case "/resources/read" -> "resources/read";
            default -> clean.startsWith("/") ? clean.substring(1) : clean;
        };
    }

    private Map<String, Object> extractRpcParams(String path, String payload) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        String cleanPath = path == null ? "" : path.trim();
        int queryIdx = cleanPath.indexOf('?');
        if (queryIdx >= 0 && queryIdx + 1 < cleanPath.length()) {
            String query = cleanPath.substring(queryIdx + 1);
            for (String pair : query.split("&")) {
                if (pair == null || pair.isBlank()) {
                    continue;
                }
                int i = pair.indexOf('=');
                if (i < 0) {
                    out.put(urlDecode(pair), "");
                } else {
                    out.put(urlDecode(pair.substring(0, i)), urlDecode(pair.substring(i + 1)));
                }
            }
        }
        if (payload != null && !payload.isBlank()) {
            try {
                Map<String, Object> payloadMap = objectMapper.readValue(payload, new TypeReference<>() {});
                out.putAll(payloadMap);
            } catch (Exception ignore) {
                // ignore invalid payload json and keep query-only params
            }
        }
        return out;
    }

    private String urlDecode(String text) {
        return java.net.URLDecoder.decode(text == null ? "" : text, StandardCharsets.UTF_8);
    }

    private String remoteHttpGet(McpServerRegistry registry, String path, int timeoutMs) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(joinUrl(registry.getBaseUrl(), path)))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Accept", "application/json")
                    .GET();
            applyAuth(builder, registry);
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                return errorResult("REMOTE_HTTP_ERROR", "status=" + response.statusCode() + ", body=" + response.body());
            }
            return response.body() == null ? "{}" : response.body();
        } catch (Exception e) {
            return errorResult("REMOTE_HTTP_ERROR", e.getMessage());
        }
    }

    private String remoteHttpPost(McpServerRegistry registry, String path, String payload, int timeoutMs) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(joinUrl(registry.getBaseUrl(), path)))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload == null ? "{}" : payload, StandardCharsets.UTF_8));
            applyAuth(builder, registry);
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                return errorResult("REMOTE_HTTP_ERROR", "status=" + response.statusCode() + ", body=" + response.body());
            }
            return response.body() == null ? "{}" : response.body();
        } catch (Exception e) {
            return errorResult("REMOTE_HTTP_ERROR", e.getMessage());
        }
    }

    private String remoteWsCall(McpServerRegistry registry, String path, String payload, int timeoutMs) {
        try {
            URI target = URI.create(joinUrl(toWebSocketBaseUrl(registry.getBaseUrl()), path));
            CompletableFuture<String> responseFuture = new CompletableFuture<>();
            WsCollector collector = new WsCollector(responseFuture);
            WebSocket socket = httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofMillis(timeoutMs))
                    .buildAsync(target, collector)
                    .get(timeoutMs, TimeUnit.MILLISECONDS);

            String frame = payload == null ? "{}" : payload;
            socket.sendText(frame, true).get(timeoutMs, TimeUnit.MILLISECONDS);
            String body = responseFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            return body == null || body.isBlank() ? "{}" : body;
        } catch (TimeoutException e) {
            return errorResult("REMOTE_WS_TIMEOUT", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorResult("REMOTE_WS_ERROR", e.getMessage());
        } catch (ExecutionException e) {
            return errorResult("REMOTE_WS_ERROR", e.getMessage());
        } catch (Exception e) {
            return errorResult("REMOTE_WS_ERROR", e.getMessage());
        }
    }

    private String remoteStdioCall(McpServerRegistry registry, String method, String path, String payload, int timeoutMs) {
        Map<String, Object> config = registry.getAuthConfig() == null ? Map.of() : registry.getAuthConfig();
        List<String> command = parseStdioCommand(config);
        if (command.isEmpty()) {
            return errorResult("STDIO_COMMAND_REQUIRED", "auth_config.command is required for STDIO transport");
        }
        Process process = null;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            String cwd = String.valueOf(config.getOrDefault("cwd", "")).trim();
            if (!cwd.isBlank()) {
                pb.directory(new java.io.File(cwd));
            }
            process = pb.start();
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
                 BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                 BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String request = toJson(Map.of(
                        "method", method == null ? "POST" : method,
                        "path", path == null ? "" : path,
                        "payload", payload == null ? "{}" : payload
                ));
                writer.write(request);
                writer.newLine();
                writer.flush();
                CompletableFuture<String> responseFuture = CompletableFuture.supplyAsync(() -> {
                    try {
                        return reader.readLine();
                    } catch (Exception e) {
                        return null;
                    }
                }, executor);
                String line = responseFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
                if (line == null || line.isBlank()) {
                    String err = errReader.readLine();
                    if (err != null && !err.isBlank()) {
                        return errorResult("REMOTE_STDIO_ERROR", err);
                    }
                    return "{}";
                }
                return line;
            }
        } catch (TimeoutException e) {
            return errorResult("REMOTE_STDIO_TIMEOUT", e.getMessage());
        } catch (Exception e) {
            return errorResult("REMOTE_STDIO_ERROR", e.getMessage());
        } finally {
            executor.shutdownNow();
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private List<String> parseStdioCommand(Map<String, Object> config) {
        Object raw = config.get("command");
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            List<String> cmd = new ArrayList<>();
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    cmd.add(String.valueOf(item).trim());
                }
            }
            return cmd;
        }
        String text = String.valueOf(raw).trim();
        if (text.isBlank()) {
            return List.of();
        }
        if (text.startsWith("[") && text.endsWith("]")) {
            try {
                List<String> list = objectMapper.readValue(text, new TypeReference<>() {});
                return list == null ? List.of() : list.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).toList();
            } catch (Exception ignore) {
                // fallback to whitespace splitting below
            }
        }
        return Arrays.stream(text.split("\\s+")).filter(s -> !s.isBlank()).toList();
    }

    private String toWebSocketBaseUrl(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (base.startsWith("https://")) {
            return "wss://" + base.substring("https://".length());
        }
        if (base.startsWith("http://")) {
            return "ws://" + base.substring("http://".length());
        }
        if (base.startsWith("ws://") || base.startsWith("wss://")) {
            return base;
        }
        return "ws://" + base;
    }

    private void applyAuth(HttpRequest.Builder builder, McpServerRegistry registry) {
        if (registry == null || registry.getAuthType() == null || registry.getAuthType().isBlank()) {
            return;
        }
        String authType = registry.getAuthType().trim().toUpperCase(Locale.ROOT);
        Map<String, Object> config = registry.getAuthConfig() == null ? Map.of() : registry.getAuthConfig();
        if ("BEARER".equals(authType)) {
            String token = String.valueOf(config.getOrDefault("token", ""));
            if (!token.isBlank()) {
                builder.header("Authorization", "Bearer " + token.trim());
            }
            return;
        }
        if ("BASIC".equals(authType)) {
            String user = String.valueOf(config.getOrDefault("username", ""));
            String pass = String.valueOf(config.getOrDefault("password", ""));
            if (!user.isBlank()) {
                String raw = user + ":" + pass;
                String encoded = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
                builder.header("Authorization", "Basic " + encoded);
            }
        }
    }

    private String joinUrl(String baseUrl, String path) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String p = path == null ? "" : path.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        return base + p;
    }

    private String encode(String text) {
        return URLEncoder.encode(text == null ? "" : text, StandardCharsets.UTF_8);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String errorResult(String code, String message) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "status", "error",
                    "errorCode", code,
                    "message", message
            ));
        } catch (Exception e) {
            log.warn("build error result failed", e);
            return "{\"status\":\"error\"}";
        }
    }

    private static final class WsCollector implements WebSocket.Listener {

        private final CompletableFuture<String> resultFuture;
        private final StringBuilder buffer = new StringBuilder();

        private WsCollector(CompletableFuture<String> resultFuture) {
            this.resultFuture = resultFuture;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last && !resultFuture.isDone()) {
                resultFuture.complete(buffer.toString());
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (!resultFuture.isDone()) {
                resultFuture.completeExceptionally(error);
            }
        }
    }
}
