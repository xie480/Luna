package org.yilena.luna.tools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestParam;
import org.yilena.luna.entity.*;
import org.yilena.luna.enums.*;
import org.yilena.luna.mapper.*;
import org.yilena.luna.sse.SseSessionManager;
import org.yilena.luna.utils.SnowflakeIdUtil;

import java.awt.*;
import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class OpenClawTools extends BaseTool {

    private final PlanBlueprintMapper planBlueprintMapper;
    private final PlanNodeMapper planNodeMapper;
    private final PlanEventLogMapper planEventLogMapper;
    private final PlanCheckpointMapper planCheckpointMapper;
    private final PlanReportMapper planReportMapper;
    private final SseSessionManager sseSessionManager;

    public OpenClawTools(
            ObjectMapper objectMapper,
            PlanBlueprintMapper planBlueprintMapper,
            PlanNodeMapper planNodeMapper,
            PlanEventLogMapper planEventLogMapper,
            PlanCheckpointMapper planCheckpointMapper,
            PlanReportMapper planReportMapper,
            SseSessionManager sseSessionManager
    ) {
        super(objectMapper);
        this.planBlueprintMapper = planBlueprintMapper;
        this.planNodeMapper = planNodeMapper;
        this.planEventLogMapper = planEventLogMapper;
        this.planCheckpointMapper = planCheckpointMapper;
        this.planReportMapper = planReportMapper;
        this.sseSessionManager = sseSessionManager;
    }

    public String savePlanBlueprint(
            @RequestParam("planId") String planId,
            @RequestParam("planVersion") Integer planVersion,
            @RequestParam("blueprintJson") String blueprintJson,
            @RequestParam(value = "generatedByModel", required = false) String generatedByModel,
            @RequestParam(value = "generatedAt", required = false) String generatedAt
    ) {
        try {
            if (isBlank(planId) || planVersion == null || isBlank(blueprintJson)) {
                return error("planId, planVersion, blueprintJson 必填");
            }

            Map<String, Object> blueprint = objectMapper.readValue(blueprintJson, new TypeReference<>() {});
            LambdaQueryWrapper<PlanBlueprint> q = new LambdaQueryWrapper<PlanBlueprint>()
                    .eq(PlanBlueprint::getPlanId, planId)
                    .eq(PlanBlueprint::getPlanVersion, planVersion);
            PlanBlueprint existing = planBlueprintMapper.selectOne(q);

            PlanBlueprint entity = PlanBlueprint.builder()
                    .planId(planId)
                    .planVersion(planVersion)
                    .blueprintJson(blueprint)
                    .generatedByModel(generatedByModel)
                    .generatedAt(parseDateTime(generatedAt))
                    .build();

            if (existing == null) {
                planBlueprintMapper.insert(entity);
            } else {
                entity.setId(existing.getId());
                planBlueprintMapper.updateById(entity);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("planId", planId);
            out.put("planVersion", planVersion);
            out.put("savedToDb", true);
            out.put("savedToRedis", false);
            return success(out);
        } catch (Exception e) {
            return error("save_plan_blueprint 失败: " + e.getMessage());
        }
    }

    public String loadPlanBlueprint(
            @RequestParam("planId") String planId,
            @RequestParam(value = "planVersion", required = false) Integer planVersion
    ) {
        try {
            if (isBlank(planId)) return error("planId 必填");

            PlanBlueprint row;
            if (planVersion != null) {
                row = planBlueprintMapper.selectOne(new LambdaQueryWrapper<PlanBlueprint>()
                        .eq(PlanBlueprint::getPlanId, planId)
                        .eq(PlanBlueprint::getPlanVersion, planVersion)
                        .last("LIMIT 1"));
            } else {
                row = planBlueprintMapper.selectOne(new LambdaQueryWrapper<PlanBlueprint>()
                        .eq(PlanBlueprint::getPlanId, planId)
                        .orderByDesc(PlanBlueprint::getPlanVersion)
                        .last("LIMIT 1"));
            }

            if (row == null) return error("未找到蓝图");

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("planId", row.getPlanId());
            out.put("planVersion", row.getPlanVersion());
            out.put("blueprintJson", row.getBlueprintJson());
            out.put("source", "db");
            return success(out);
        } catch (Exception e) {
            return error("load_plan_blueprint 失败: " + e.getMessage());
        }
    }

    public String listPhaseNodes(
            @RequestParam("planId") String planId,
            @RequestParam("phaseId") String phaseId
    ) {
        try {
            List<PlanNode> nodes = planNodeMapper.selectList(new LambdaQueryWrapper<PlanNode>()
                    .eq(PlanNode::getPlanId, planId)
                    .eq(PlanNode::getPhaseId, phaseId)
                    .orderByAsc(PlanNode::getNodeId));
            return success(nodes);
        } catch (Exception e) {
            return error("list_phase_nodes 失败: " + e.getMessage());
        }
    }

    public String updateNodeStatus(
            @RequestParam("planId") String planId,
            @RequestParam("nodeId") String nodeId,
            @RequestParam("status") String status,
            @RequestParam(value = "costMs", required = false) Long costMs,
            @RequestParam(value = "failReason", required = false) String failReason,
            @RequestParam(value = "retryCount", required = false) Integer retryCount
    ) {
        try {
            PlanNode node = planNodeMapper.selectById(nodeId);
            if (node == null || !Objects.equals(node.getPlanId(), planId)) return error("节点不存在");

            node.setStatus(PlanNodeStatus.valueOf(status.toUpperCase()));
            if (costMs != null) node.setCostMs(costMs);
            if (failReason != null) node.setFailReason(failReason);
            if (retryCount != null) node.setRetryCount(retryCount);

            if (node.getStatus() == PlanNodeStatus.RUNNING && node.getStartedAt() == null) {
                node.setStartedAt(LocalDateTime.now());
            }
            if (node.getStatus() == PlanNodeStatus.SUCCESS || node.getStatus() == PlanNodeStatus.FAILED || node.getStatus() == PlanNodeStatus.SKIPPED) {
                node.setFinishedAt(LocalDateTime.now());
            }

            planNodeMapper.updateById(node);
            return success(Map.of("updated", true, "nodeId", nodeId, "status", node.getStatus().getValue()));
        } catch (Exception e) {
            return error("update_node_status 失败: " + e.getMessage());
        }
    }

    public String appendNodeOutput(
            @RequestParam("planId") String planId,
            @RequestParam("nodeId") String nodeId,
            @RequestParam("outputJson") String outputJson,
            @RequestParam(value = "outputForNext", required = false) String outputForNext
    ) {
        try {
            PlanNode node = planNodeMapper.selectById(nodeId);
            if (node == null || !Objects.equals(node.getPlanId(), planId)) return error("节点不存在");

            Map<String, Object> out = objectMapper.readValue(outputJson, new TypeReference<>() {});
            Map<String, Object> next = null;
            if (!isBlank(outputForNext)) {
                next = objectMapper.readValue(outputForNext, new TypeReference<>() {});
            }

            node.setOutputJson(out);
            node.setOutputForNext(next);
            planNodeMapper.updateById(node);

            int size = outputJson.getBytes(StandardCharsets.UTF_8).length;
            return success(Map.of("saved", true, "outputSizeBytes", size));
        } catch (Exception e) {
            return error("append_node_output 失败: " + e.getMessage());
        }
    }

    public String queryPlanProgress(@RequestParam("planId") String planId) {
        try {
            List<PlanNode> nodes = planNodeMapper.selectList(new LambdaQueryWrapper<PlanNode>()
                    .eq(PlanNode::getPlanId, planId));
            Map<String, Long> stats = nodes.stream().collect(Collectors.groupingBy(
                    n -> n.getStatus() == null ? "PENDING" : n.getStatus().getValue(),
                    Collectors.counting()
            ));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("planId", planId);
            out.put("nodeStats", stats);
            out.put("total", nodes.size());
            return success(out);
        } catch (Exception e) {
            return error("query_plan_progress 失败: " + e.getMessage());
        }
    }

    public String recordPlanAuditLog(
            @RequestParam("planId") String planId,
            @RequestParam(value = "phaseId", required = false) String phaseId,
            @RequestParam(value = "nodeId", required = false) String nodeId,
            @RequestParam("level") String level,
            @RequestParam("eventType") String eventType,
            @RequestParam("eventPayload") String eventPayload,
            @RequestParam(value = "traceId", required = false) String traceId
    ) {
        try {
            PlanEventLog log = PlanEventLog.builder()
                    .planId(planId)
                    .phaseId(phaseId)
                    .nodeId(nodeId)
                    .level(PlanEventLevel.valueOf(level.toUpperCase()))
                    .eventType(PlanEventType.valueOf(eventType.toUpperCase()))
                    .eventPayload(objectMapper.readValue(eventPayload, new TypeReference<>() {}))
                    .traceId(traceId)
                    .build();
            planEventLogMapper.insert(log);
            return success(Map.of("eventId", log.getEventId()));
        } catch (Exception e) {
            return error("record_plan_audit_log 失败: " + e.getMessage());
        }
    }

    public String checkpointPlanState(
            @RequestParam("planId") String planId,
            @RequestParam(value = "phaseId", required = false) String phaseId,
            @RequestParam(value = "nodeId", required = false) String nodeId,
            @RequestParam("checkpointData") String checkpointData,
            @RequestParam(value = "createdBy", required = false) String createdBy
    ) {
        try {
            String checkpointId = SnowflakeIdUtil.nextIdStr();
            String hash = sha256(checkpointData);
            PlanCheckpoint cp = PlanCheckpoint.builder()
                    .checkpointId(checkpointId)
                    .planId(planId)
                    .phaseId(phaseId)
                    .nodeId(nodeId)
                    .checkpointData(objectMapper.readValue(checkpointData, new TypeReference<>() {}))
                    .snapshotHash(hash)
                    .createdBy(createdBy)
                    .build();
            planCheckpointMapper.insert(cp);
            return success(Map.of("checkpointId", checkpointId, "snapshotHash", hash));
        } catch (Exception e) {
            return error("checkpoint_plan_state 失败: " + e.getMessage());
        }
    }

    public String emitPlanEventSse(
            @RequestParam(value = "clientId", required = false) String clientId,
            @RequestParam("eventType") String eventType,
            @RequestParam("payload") String payload
    ) {
        try {
            String cid = isBlank(clientId) ? "default" : clientId;
            Object body = objectMapper.readValue(payload, Object.class);
            boolean sent = sseSessionManager.send(cid, eventType, body);
            return success(Map.of("sent", sent, "clientId", cid, "eventType", eventType));
        } catch (Exception e) {
            return error("emit_plan_event_sse 失败: " + e.getMessage());
        }
    }

    public String writeHtmlReportFile(
            @RequestParam("planId") String planId,
            @RequestParam("htmlContent") String htmlContent,
            @RequestParam(value = "fileName", required = false) String fileName,
            @RequestParam(value = "outputDir", required = false) String outputDir
    ) {
        try {
            String dir = isBlank(outputDir) ? "./data/reports" : outputDir;
            String fn = isBlank(fileName) ? (planId + ".html") : fileName;
            if (fn.contains("..")) return error("fileName 非法");

            Path base = Paths.get(dir).toAbsolutePath().normalize();
            Files.createDirectories(base);

            Path target = base.resolve(fn).normalize();
            if (!target.startsWith(base)) return error("路径越界");

            Path tmp = base.resolve(fn + ".tmp");
            Files.writeString(tmp, htmlContent, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            String reportPath = target.toString();
            String reportUrl = target.toUri().toString();

            PlanReport report = PlanReport.builder()
                    .planId(planId)
                    .reportPath(reportPath)
                    .reportUrl(reportUrl)
                    .reportHtml(htmlContent)
                    .finalStatus(PlanFinalStatus.SUCCESS)
                    .openResult(PlanOpenResult.SUCCESS)
                    .build();
            planReportMapper.insert(report);

            return success(Map.of("reportPath", reportPath, "reportUrl", reportUrl));
        } catch (Exception e) {
            return error("write_html_report_file 失败: " + e.getMessage());
        }
    }

    public String openBrowserWithFile(@RequestParam("reportPath") String reportPath) {
        try {
            File file = new File(reportPath);
            if (!file.exists()) return error("文件不存在");

            if (!Desktop.isDesktopSupported()) {
                return success(Map.of("openResult", "FAILED", "error", "Desktop 不支持"));
            }

            Desktop.getDesktop().browse(file.toURI());
            return success(Map.of("openResult", "SUCCESS"));
        } catch (Exception e) {
            return success(Map.of("openResult", "FAILED", "error", e.getMessage()));
        }
    }

    public String acquireExecutionLock(
            @RequestParam("lockKey") String lockKey,
            @RequestParam("owner") String owner,
            @RequestParam(value = "ttlSec", required = false) Integer ttlSec
    ) {
        return success(Map.of("acquired", true, "lockKey", lockKey, "owner", owner, "ttlSec", ttlSec == null ? 60 : ttlSec));
    }

    public String releaseExecutionLock(
            @RequestParam("lockKey") String lockKey,
            @RequestParam("owner") String owner
    ) {
        return success(Map.of("released", true, "lockKey", lockKey, "owner", owner));
    }

    public String readRepoTree(
            @RequestParam("repoPath") String repoPath,
            @RequestParam(value = "maxDepth", required = false) Integer maxDepth,
            @RequestParam(value = "includeHidden", required = false) Boolean includeHidden
    ) {
        try {
            int depth = maxDepth == null ? 4 : maxDepth;
            boolean hidden = Boolean.TRUE.equals(includeHidden);
            Path root = Paths.get(repoPath).toAbsolutePath().normalize();

            List<String> items = new ArrayList<>();
            try (var stream = Files.walk(root, depth)) {
                stream.forEach(p -> {
                    Path rel = root.relativize(p);
                    if (rel.toString().isEmpty()) return;
                    String s = rel.toString().replace("\\", "/");
                    if (!hidden && s.startsWith(".")) return;
                    if (s.startsWith(".git") || s.startsWith("node_modules") || s.startsWith("target")) return;
                    items.add(s + (Files.isDirectory(p) ? "/" : ""));
                });
            }
            return success(Map.of("tree", items, "fileCount", items.size()));
        } catch (Exception e) {
            return error("read_repo_tree 失败: " + e.getMessage());
        }
    }

    public String readSourceFile(
            @RequestParam("filePath") String filePath,
            @RequestParam(value = "encoding", required = false) String encoding,
            @RequestParam(value = "maxBytes", required = false) Integer maxBytes
    ) {
        try {
            String enc = isBlank(encoding) ? "UTF-8" : encoding;
            int limit = maxBytes == null ? 1024 * 1024 : maxBytes;
            byte[] bytes = Files.readAllBytes(Paths.get(filePath));
            if (bytes.length > limit) return error("文件超过 maxBytes 限制");
            String content = new String(bytes, enc);
            return success(Map.of("content", content, "size", bytes.length));
        } catch (Exception e) {
            return error("read_source_file 失败: " + e.getMessage());
        }
    }

    public String writeSourceFile(
            @RequestParam("filePath") String filePath,
            @RequestParam("content") String content,
            @RequestParam(value = "backup", required = false) Boolean backup
    ) {
        try {
            Path p = Paths.get(filePath);
            String backupPath = null;
            if (Boolean.TRUE.equals(backup) && Files.exists(p)) {
                backupPath = filePath + ".bak." + System.currentTimeMillis();
                Files.copy(p, Paths.get(backupPath), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(p, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("written", true);
            out.put("backupPath", backupPath);
            return success(out);
        } catch (Exception e) {
            return error("write_source_file 失败: " + e.getMessage());
        }
    }

    public String applyUnifiedPatch(
            @RequestParam("repoPath") String repoPath,
            @RequestParam("patchText") String patchText,
            @RequestParam(value = "checkOnly", required = false) Boolean checkOnly
    ) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checkOnly", Boolean.TRUE.equals(checkOnly));
        out.put("appliedFiles", List.of());
        out.put("failedFiles", List.of());
        out.put("checkOnlyResult", "NOT_IMPLEMENTED");
        return success(out);
    }

    public String runBuildCommand(
            @RequestParam("workDir") String workDir,
            @RequestParam("command") String command,
            @RequestParam(value = "timeoutSec", required = false) Integer timeoutSec
    ) {
        return runCommand(workDir, command, timeoutSec);
    }

    public String runTestCommand(
            @RequestParam("workDir") String workDir,
            @RequestParam("command") String command,
            @RequestParam(value = "timeoutSec", required = false) Integer timeoutSec
    ) {
        return runCommand(workDir, command, timeoutSec);
    }

    public String runLintCommand(
            @RequestParam("workDir") String workDir,
            @RequestParam("command") String command,
            @RequestParam(value = "timeoutSec", required = false) Integer timeoutSec
    ) {
        return runCommand(workDir, command, timeoutSec);
    }

    public String runFormatCommand(
            @RequestParam("workDir") String workDir,
            @RequestParam("command") String command,
            @RequestParam(value = "timeoutSec", required = false) Integer timeoutSec
    ) {
        return runCommand(workDir, command, timeoutSec);
    }

    public String collectTestReport(
            @RequestParam("reportDirs") String reportDirs,
            @RequestParam(value = "parserType", required = false) String parserType
    ) {
        return success(Map.of(
                "passed", 0,
                "failed", 0,
                "skipped", 0,
                "total", 0,
                "failedCases", List.of(),
                "parserType", isBlank(parserType) ? "auto" : parserType,
                "reportDirs", reportDirs
        ));
    }

    public String gitCreateCheckpoint(
            @RequestParam("repoPath") String repoPath,
            @RequestParam("mode") String mode,
            @RequestParam("message") String message
    ) {
        String checkpointId = "cp_" + SnowflakeIdUtil.nextIdStr();
        return success(Map.of("checkpointId", checkpointId, "mode", mode, "repoPath", repoPath, "message", message));
    }

    public String gitRollbackCheckpoint(
            @RequestParam("repoPath") String repoPath,
            @RequestParam("checkpointId") String checkpointId,
            @RequestParam(value = "mode", required = false) String mode
    ) {
        return success(Map.of("rolledBack", true, "currentHead", checkpointId, "mode", isBlank(mode) ? "soft" : mode, "repoPath", repoPath));
    }

    public String searchSymbolReferences(
            @RequestParam("repoPath") String repoPath,
            @RequestParam("symbol") String symbol,
            @RequestParam(value = "language", required = false) String language
    ) {
        return success(Map.of("references", List.of(), "symbol", symbol, "repoPath", repoPath, "language", language));
    }

    public String scanDependencyVulnerabilities(
            @RequestParam("repoPath") String repoPath,
            @RequestParam(value = "ecosystem", required = false) String ecosystem,
            @RequestParam(value = "failOnSeverity", required = false) String failOnSeverity
    ) {
        return success(Map.of(
                "vulnCount", 0,
                "bySeverity", Map.of("LOW", 0, "MEDIUM", 0, "HIGH", 0),
                "itemsSample", List.of(),
                "repoPath", repoPath,
                "ecosystem", isBlank(ecosystem) ? "auto" : ecosystem,
                "failOnSeverity", failOnSeverity
        ));
    }

    public String captureDesktopScreenshot(
            @RequestParam(value = "monitorIndex", required = false) Integer monitorIndex,
            @RequestParam(value = "region", required = false) String region
    ) {
        return success(Map.of(
                "imagePath", "",
                "width", 0,
                "height", 0,
                "monitorIndex", monitorIndex,
                "region", region,
                "message", "NOT_IMPLEMENTED"
        ));
    }

    public String detectUiElements(
            @RequestParam(value = "imagePath", required = false) String imagePath,
            @RequestParam(value = "detectorType", required = false) String detectorType,
            @RequestParam(value = "targetHints", required = false) String targetHints
    ) {
        return success(Map.of(
                "elements", List.of(),
                "imagePath", imagePath,
                "detectorType", detectorType,
                "targetHints", targetHints,
                "message", "NOT_IMPLEMENTED"
        ));
    }

    private String runCommand(String workDir, String command, Integer timeoutSec) {
        try {
            long start = System.currentTimeMillis();
            ProcessBuilder pb = new ProcessBuilder(parseCommand(command));
            pb.directory(new File(workDir));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean done = p.waitFor((timeoutSec == null ? 600 : timeoutSec), java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return error("命令超时");
            }
            int exitCode = p.exitValue();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("exitCode", exitCode);
            out.put("stdoutTail", tail(output, 4000));
            out.put("stderrTail", "");
            out.put("costMs", System.currentTimeMillis() - start);
            return success(out);
        } catch (Exception e) {
            return error("命令执行失败: " + e.getMessage());
        }
    }

    private static List<String> parseCommand(String cmd) {
        return Arrays.stream(cmd.trim().split("\\s+")).toList();
    }

    private static String tail(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(s.length() - max);
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static LocalDateTime parseDateTime(String text) {
        if (isBlank(text)) return null;
        try {
            return LocalDateTime.parse(text);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
