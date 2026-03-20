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

        if (!process.isAlive()) {
            throw new IOException("子进程启动失败: " + tag);
        }
        return process;
    }

    private String resolveScriptPath(String defaultPath) {
        File f = new File(defaultPath);
        if (f.exists()) {
            return f.getPath();
        }
        // 回退：兼容从项目根目录执行时的相对路径
        File alt = new File("./" + defaultPath);
        if (alt.exists()) {
            return alt.getPath();
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
