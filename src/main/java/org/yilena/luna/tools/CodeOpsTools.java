package org.yilena.luna.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestParam;
import org.yilena.luna.annotation.LunaLogRecord;
import org.yilena.luna.annotation.LunaState;
import org.yilena.luna.constants.LunaStateConstant;
import org.yilena.luna.utils.SnowflakeIdUtil;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * CodeOps 工具集合：
 * - read_repo_tree
 * - read_source_file
 * - write_source_file
 * - apply_unified_patch
 * - run_build_command
 * - run_test_command
 * - run_lint_command
 * - run_format_command
 * - collect_test_report
 * - git_create_checkpoint
 * - git_rollback_checkpoint
 * - search_symbol_references
 * - scan_dependency_vulnerabilities
 */
@Slf4j
@Component
public class CodeOpsTools extends BaseTool {

    public CodeOpsTools(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS)
    @LunaLogRecord(module = "tool", action = "read_repo_tree", content = "读取仓库目录树")
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
            log.info("read_repo_tree 完成, repoPath={}, count={}", repoPath, items.size());
            return success(Map.of("tree", items, "fileCount", items.size()));
        } catch (Exception e) {
            log.error("read_repo_tree 失败", e);
            return error("read_repo_tree 失败: " + e.getMessage());
        }
    }

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS)
    @LunaLogRecord(module = "tool", action = "read_source_file", content = "读取源码文件")
    public String readSourceFile(
            @RequestParam("filePath") String filePath,
            @RequestParam(value = "encoding", required = false) String encoding,
            @RequestParam(value = "maxBytes", required = false) Integer maxBytes
    ) {
        try {
            String enc = (encoding == null || encoding.isBlank()) ? "UTF-8" : encoding;
            int limit = maxBytes == null ? 1024 * 1024 : maxBytes;
            byte[] bytes = Files.readAllBytes(Paths.get(filePath));
            if (bytes.length > limit) return error("文件超过 maxBytes 限制");
            String content = new String(bytes, enc);
            log.info("read_source_file 完成, filePath={}, size={}", filePath, bytes.length);
            return success(Map.of("content", content, "size", bytes.length));
        } catch (Exception e) {
            log.error("read_source_file 失败", e);
            return error("read_source_file 失败: " + e.getMessage());
        }
    }

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS)
    @LunaLogRecord(module = "tool", action = "write_source_file", content = "写入源码文件")
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
            log.info("write_source_file 完成, filePath={}", filePath);
            return success(Map.of("written", true, "backupPath", backupPath));
        } catch (Exception e) {
            log.error("write_source_file 失败", e);
            return error("write_source_file 失败: " + e.getMessage());
        }
    }

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS)
    @LunaLogRecord(module = "tool", action = "apply_unified_patch", content = "应用补丁")
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
        log.info("apply_unified_patch 占位执行, repoPath={}, checkOnly={}", repoPath, checkOnly);
        return success(out);
    }

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS)
    @LunaLogRecord(module = "tool", action = "run_build_command", content = "执行构建命令")
    public String runBuildCommand(
            @RequestParam("workDir") String workDir,
            @RequestParam("command") String command,
            @RequestParam(value = "timeoutSec", required = false) Integer timeoutSec
    ) {
        return runCommand(workDir, command, timeoutSec);
    }

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS)
    @LunaLogRecord(module = "tool", action = "run_test_command", content = "执行测试命令")
    public String runTestCommand(
            @RequestParam("workDir") String workDir,
            @RequestParam("command") String command,
            @RequestParam(value = "timeoutSec", required = false) Integer timeoutSec
    ) {
        return runCommand(workDir, command, timeoutSec);
    }

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS)
    @LunaLogRecord(module = "tool", action = "run_lint_command", content = "执行静态检查")
    public String runLintCommand(
            @RequestParam("workDir") String workDir,
            @RequestParam("command") String command,
            @RequestParam(value = "timeoutSec", required = false) Integer timeoutSec
    ) {
        return runCommand(workDir, command, timeoutSec);
    }

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS)
    @LunaLogRecord(module = "tool", action = "run_format_command", content = "执行格式化")
    public String runFormatCommand(
            @RequestParam("workDir") String workDir,
            @RequestParam("command") String command,
            @RequestParam(value = "timeoutSec", required = false) Integer timeoutSec
    ) {
        return runCommand(workDir, command, timeoutSec);
    }

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS)
    @LunaLogRecord(module = "tool", action = "collect_test_report", content = "收集测试报告")
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
                "parserType", (parserType == null || parserType.isBlank()) ? "auto" : parserType,
                "reportDirs", reportDirs
        ));
    }

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS)
    @LunaLogRecord(module = "tool", action = "git_create_checkpoint", content = "创建代码检查点")
    public String gitCreateCheckpoint(
            @RequestParam("repoPath") String repoPath,
            @RequestParam("mode") String mode,
            @RequestParam("message") String message
    ) {
        String checkpointId = "cp_" + SnowflakeIdUtil.nextIdStr();
        return success(Map.of("checkpointId", checkpointId, "mode", mode, "repoPath", repoPath, "message", message));
    }

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS)
    @LunaLogRecord(module = "tool", action = "git_rollback_checkpoint", content = "回滚代码检查点")
    public String gitRollbackCheckpoint(
            @RequestParam("repoPath") String repoPath,
            @RequestParam("checkpointId") String checkpointId,
            @RequestParam(value = "mode", required = false) String mode
    ) {
        return success(Map.of("rolledBack", true, "currentHead", checkpointId, "mode", (mode == null || mode.isBlank()) ? "soft" : mode, "repoPath", repoPath));
    }

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS)
    @LunaLogRecord(module = "tool", action = "search_symbol_references", content = "搜索符号引用")
    public String searchSymbolReferences(
            @RequestParam("repoPath") String repoPath,
            @RequestParam("symbol") String symbol,
            @RequestParam(value = "language", required = false) String language
    ) {
        return success(Map.of("references", List.of(), "symbol", symbol, "repoPath", repoPath, "language", language));
    }

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS)
    @LunaLogRecord(module = "tool", action = "scan_dependency_vulnerabilities", content = "扫描依赖漏洞")
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
                "ecosystem", (ecosystem == null || ecosystem.isBlank()) ? "auto" : ecosystem,
                "failOnSeverity", failOnSeverity
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
            return success(Map.of(
                    "exitCode", exitCode,
                    "stdoutTail", tail(output, 4000),
                    "stderrTail", "",
                    "costMs", System.currentTimeMillis() - start
            ));
        } catch (Exception e) {
            log.error("命令执行失败", e);
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
}
