package org.yilena.luna.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yilena.luna.properties.EmbeddingProperty;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/*
    程序应用初始化类
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApplicationInitConfig {

    private final EmbeddingProperty embeddingProperty;

    @Value("${inference.http.enabled:true}")
    private boolean inferenceHttpEnabled;

    @Value("${inference.http.embedding-url:http://127.0.0.1:18080/embedding}")
    private String embeddingServiceUrl;

    @Value("${inference.http.rerank-url:http://127.0.0.1:18081/rerank}")
    private String rerankServiceUrl;

    @Value("${rerank.model-path:}")
    private String rerankModelPath;

    @Value("${inference.http.startup-timeout-ms:180000}")
    private long startupTimeoutMs;

    @Value("${inference.http.startup-check-interval-ms:1000}")
    private long startupCheckIntervalMs;

    /**
     * 保存自动拉起的 Python 子进程，方便应用退出时清理
     */
    private final List<Process> managedProcesses = new ArrayList<>();

    @PostConstruct
    public void symbolInit() {
        if (!inferenceHttpEnabled) {
            log.info("inference.http.enabled=false，跳过自动拉起推理 HTTP 服务");
            return;
        }

        try {
            // 从 URL 中提取 host/port，避免配置重复
            HostPort embeddingHostPort = parseHostPort(embeddingServiceUrl, 18080);
            HostPort rerankHostPort = parseHostPort(rerankServiceUrl, 18081);

            String pythonPath = embeddingProperty.getPythonPath();
            if (pythonPath == null || pythonPath.isBlank()) {
                log.error("自动拉起失败：embedding.python-path 未配置");
                return;
            }

            // 启动 embedding HTTP 服务
            String embeddingScriptPath = resolveScriptPath("src/main/resources/python/embedding_service_http.py");
            String embeddingModelPath = embeddingProperty.getModelPath();
            if (embeddingModelPath == null || embeddingModelPath.isBlank()) {
                log.error("自动拉起失败：embedding.model-path 未配置");
                return;
            }

            Process embeddingProcess = startPythonProcess(List.of(
                    pythonPath,
                    embeddingScriptPath,
                    "--host", embeddingHostPort.host(),
                    "--port", String.valueOf(embeddingHostPort.port()),
                    "--embedding-model-path", embeddingModelPath
            ), "embedding-http");

            managedProcesses.add(embeddingProcess);
            waitUntilHttpReady(
                    "embedding-http",
                    embeddingServiceUrl,
                    "{\"text\":\"ping\"}",
                    embeddingProcess
            );
            log.info("已自动拉起 embedding HTTP 服务，host={}, port={}", embeddingHostPort.host(), embeddingHostPort.port());

            // 启动 rerank HTTP 服务
            String rerankScriptPath = resolveScriptPath("src/main/resources/python/rerank_service_http.py");
            if (rerankModelPath == null || rerankModelPath.isBlank()) {
                log.error("自动拉起失败：rerank.model-path 未配置");
                return;
            }

            Process rerankProcess = startPythonProcess(List.of(
                    pythonPath,
                    rerankScriptPath,
                    "--host", rerankHostPort.host(),
                    "--port", String.valueOf(rerankHostPort.port()),
                    "--rerank-model-path", rerankModelPath
            ), "rerank-http");

            managedProcesses.add(rerankProcess);
            waitUntilHttpReady(
                    "rerank-http",
                    rerankServiceUrl,
                    "{\"query\":\"ping\",\"documents\":[\"ping\"]}",
                    rerankProcess
            );
            log.info("已自动拉起 rerank HTTP 服务，host={}, port={}", rerankHostPort.host(), rerankHostPort.port());

        } catch (Exception e) {
            log.error("自动拉起推理 HTTP 服务失败: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public void shutdownManagedProcesses() {
        for (Process process : managedProcesses) {
            if (process != null && process.isAlive()) {
                try {
                    process.destroy();
                    log.info("已发送子进程关闭信号，pid={}", process.pid());
                } catch (Exception e) {
                    log.warn("关闭子进程失败，pid={}, err={}", process.pid(), e.getMessage());
                }
            }
        }
        managedProcesses.clear();
    }

    private Process startPythonProcess(List<String> command, String tag) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        // 让子进程输出沿用当前 Java 进程输出，便于排查问题
        pb.inheritIO();

        log.info("准备启动 {}，command={}", tag, command);
        Process process = pb.start();

        // 防止“瞬间退出”却被误判为启动成功
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (!process.isAlive()) {
            int exitCode = process.exitValue();
            throw new IOException("子进程启动失败: " + tag + ", exitCode=" + exitCode);
        }
        return process;
    }

    private void waitUntilHttpReady(String serviceName, String url, String requestJson, Process process) throws Exception {
        long deadline = System.currentTimeMillis() + startupTimeoutMs;
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(1000))
                .build();

        Exception lastException = null;
        int lastStatus = -1;

        while (System.currentTimeMillis() < deadline) {
            if (process != null && !process.isAlive()) {
                throw new IllegalStateException(serviceName + " 子进程已退出，pid=" + process.pid());
            }

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofMillis(1500))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                lastStatus = response.statusCode();

                // 只要能连接并返回 HTTP 响应（2xx/4xx），说明服务已可用
                if (lastStatus >= 200 && lastStatus < 500) {
                    log.info("{} 健康检查通过，url={}, status={}", serviceName, url, lastStatus);
                    return;
                }
            } catch (Exception e) {
                lastException = e;
            }

            try {
                Thread.sleep(startupCheckIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待 " + serviceName + " 就绪时被中断", e);
            }
        }

        String msg = String.format(
                "%s 就绪超时，url=%s, timeoutMs=%d, lastStatus=%d, lastError=%s",
                serviceName, url, startupTimeoutMs, lastStatus, lastException != null ? lastException.getMessage() : "none"
        );
        throw new IllegalStateException(msg, lastException);
    }

    private String resolveScriptPath(String defaultPath) {
        // 1) 优先磁盘路径
        File f = new File(defaultPath);
        if (f.exists()) {
            return f.getPath();
        }

        // 2) 回退：兼容从项目根目录执行时的相对路径
        File alt = new File("./" + defaultPath);
        if (alt.exists()) {
            return alt.getPath();
        }

        // 3) 回退：从 classpath 提取到临时文件（兼容 jar 运行）
        String classpathPath = defaultPath.replace("\\", "/");
        if (classpathPath.startsWith("src/main/resources/")) {
            classpathPath = classpathPath.substring("src/main/resources/".length());
        }

        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(classpathPath)) {
            if (is != null) {
                String fileName = new File(classpathPath).getName();
                File tempFile = File.createTempFile("luna_py_", "_" + fileName);
                tempFile.deleteOnExit();
                Files.copy(is, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                log.info("脚本文件从 classpath 提取成功: {} -> {}", classpathPath, tempFile.getAbsolutePath());
                return tempFile.getAbsolutePath();
            }
        } catch (Exception e) {
            log.warn("从 classpath 提取脚本失败: {}, err={}", classpathPath, e.getMessage());
        }

        throw new IllegalStateException("找不到脚本文件: " + defaultPath);
    }

    private HostPort parseHostPort(String url, int defaultPort) {
        if (url == null || url.isBlank()) {
            return new HostPort("127.0.0.1", defaultPort);
        }

        try {
            // 示例: http://127.0.0.1:18080/embedding
            String noProtocol = url.replace("http://", "").replace("https://", "");
            String hostPortPart = noProtocol.split("/")[0];
            String[] arr = hostPortPart.split(":");
            String host = arr[0];
            int port = arr.length > 1 ? Integer.parseInt(arr[1]) : defaultPort;
            return new HostPort(host, port);
        } catch (Exception e) {
            log.warn("解析 URL 失败，使用默认 host/port, url={}, err={}", url, e.getMessage());
            return new HostPort("127.0.0.1", defaultPort);
        }
    }

    private record HostPort(String host, int port) {
    }
}
