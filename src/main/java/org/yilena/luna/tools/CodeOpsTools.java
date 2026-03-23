package org.yilena.luna.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestParam;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.yilena.luna.annotation.LunaLogRecord;
import org.yilena.luna.annotation.LunaState;
import org.yilena.luna.constants.LogActionConstant;
import org.yilena.luna.constants.LogModuleConstant;
import org.yilena.luna.constants.LunaStateConstant;
import org.yilena.luna.enums.LogType;
import org.yilena.luna.utils.SnowflakeIdUtil;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * CodeOps 工具集合
 */
@Slf4j
@Component
public class CodeOpsTools extends BaseTool {

    @Value("${codeops.workspace-root:}")
    private String workspaceRoot;

    private static final Set<String> CMD_HEAD_WHITELIST = Set.of(
            "mvn", "gradle", "npm", "pnpm", "yarn", "pytest", "python", "bash", "sh"
    );

    private static final List<String> DANGEROUS_TOKENS = List.of("&&", ";", "|", ">", "<", "`", "$(");

    public CodeOpsTools(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "读取仓库目录树")
    public String readRepoTree(
            @RequestParam("repoPath") String repoPath,
            @RequestParam(value = "maxDepth", required = false) Integer maxDepth,
            @RequestParam(value = "includeHidden", required = false) Boolean includeHidden
    ) {
        try {
            Path root = resolveSafePath(repoPath, true);
            int depth = maxDepth == null ? 4 : maxDepth;
            boolean hidden = Boolean.TRUE.equals(includeHidden);

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
            log.info("read_repo_tree 完成, repoPath={}, count={}", root, items.size());
            return success(Map.of("tree", items, "fileCount", items.size()));
        } catch (Exception e) {
            log.error("read_repo_tree 失败", e);
            return error("read_repo_tree 失败: " + e.getMessage());
        }
    }

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "读取源码文件")
    public String readSourceFile(
            @RequestParam("filePath") String filePath,
            @RequestParam(value = "encoding", required = false) String encoding,
            @RequestParam(value = "maxBytes", required = false) Integer maxBytes
    ) {
        try {
            Path p = resolveSafePath(filePath, false);
            String enc = (encoding == null || encoding.isBlank()) ? "UTF-8" : encoding;
            int limit = maxBytes == null ? 1024 * 1024 : maxBytes;
            byte[] bytes = Files.readAllBytes(p);
            if (bytes.length > limit) return error("文件超过 maxBytes 限制");
            String content = new String(bytes, enc);
            log.info("read_source_file 完成, filePath={}, size={}", p, bytes.length);
            return success(Map.of("content", content, "size", bytes.length));
        } catch (Exception e) {
            log.error("read_source_file 失败", e);
            return error("read_source_file 失败: " + e.getMessage());
        }
    }

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "写入源码文件")
    public String writeSourceFile(
            @RequestParam("filePath") String filePath,
            @RequestParam("content") String content,
            @RequestParam(value = "backup", required = false) Boolean backup
    ) {
        try {
            Path p = resolveSafePath(filePath, false);
            String backupPath = null;
            if (Boolean.TRUE.equals(backup) && Files.exists(p)) {
                backupPath = p.toString() + ".bak." + System.currentTimeMillis();
                Files.copy(p, Paths.get(backupPath), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.createDirectories(p.getParent());
            Files.writeString(p, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("write_source_file 完成, filePath={}", p);
            return success(Map.of("written", true, "backupPath", backupPath));
        } catch (Exception e) {
            log.error("write_source_file 失败", e);
            return error("write_source_file 失败: " + e.getMessage());
        }
    }

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "应用补丁")
    public String applyUnifiedPatch(
            @RequestParam("repoPath") String repoPath,
            @RequestParam("patchText") String patchText,
            @RequestParam(value = "checkOnly", required = false) Boolean checkOnly
    ) {
        try {
            boolean dryRun = Boolean.TRUE.equals(checkOnly);
            Path repo = resolveSafePath(repoPath, true);

            Path patchFile = Files.createTempFile("luna_patch_", ".diff");
            Files.writeString(patchFile, patchText == null ? "" : patchText, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);

            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            cmd.add("apply");
            if (dryRun) {
                cmd.add("--check");
            } else {
                cmd.add("--whitespace=nowarn");
            }
            cmd.add(patchFile.toString());

            ProcessResult pr = runCommandInternal(repo.toFile(), cmd, 120);
            Files.deleteIfExists(patchFile);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("checkOnly", dryRun);
            out.put("checkOnlyResult", pr.exitCode == 0 ? "OK" : "FAILED");
            out.put("appliedFiles", List.of());
            out.put("failedFiles", pr.exitCode == 0 ? List.of() : List.of(pr.stderrTail));
            out.put("exitCode", pr.exitCode);
            out.put("stdoutTail", pr.stdoutTail);
            out.put("stderrTail", pr.stderrTail);

            if (dryRun || pr.exitCode == 0) {
                return success(out);
            }
            return error("apply_unified_patch 失败: " + pr.stderrTail);
        } catch (Exception e) {
            log.error("apply_unified_patch 失败", e);
            return error("apply_unified_patch 失败: " + e.getMessage());
        }
    }

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "执行构建命令")
    public String runBuildCommand(@RequestParam("workDir") String workDir, @RequestParam("command") String command, @RequestParam(value = "timeoutSec", required = false) Integer timeoutSec) {
        return runCommand(workDir, command, timeoutSec);
    }

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "执行测试命令")
    public String runTestCommand(@RequestParam("workDir") String workDir, @RequestParam("command") String command, @RequestParam(value = "timeoutSec", required = false) Integer timeoutSec) {
        return runCommand(workDir, command, timeoutSec);
    }

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "执行静态检查")
    public String runLintCommand(@RequestParam("workDir") String workDir, @RequestParam("command") String command, @RequestParam(value = "timeoutSec", required = false) Integer timeoutSec) {
        return runCommand(workDir, command, timeoutSec);
    }

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "执行格式化")
    public String runFormatCommand(@RequestParam("workDir") String workDir, @RequestParam("command") String command, @RequestParam(value = "timeoutSec", required = false) Integer timeoutSec) {
        return runCommand(workDir, command, timeoutSec);
    }

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "收集测试报告")
    public String collectTestReport(
            @RequestParam("reportDirs") String reportDirs,
            @RequestParam(value = "parserType", required = false) String parserType
    ) {
        try {
            List<String> dirs = parseReportDirs(reportDirs);
            int passed = 0, failed = 0, skipped = 0;
            List<Map<String, Object>> failedCases = new ArrayList<>();

            for (String dir : dirs) {
                Path base = resolveSafePath(dir, true);
                if (!Files.exists(base)) {
                    continue;
                }
                try (Stream<Path> stream = Files.walk(base)) {
                    List<Path> xmlFiles = stream
                            .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".xml"))
                            .toList();
                    for (Path xml : xmlFiles) {
                        String content = Files.readString(xml, StandardCharsets.UTF_8);
                        if (!content.contains("<testsuite")) {
                            continue;
                        }
                        Document doc = DocumentBuilderFactory.newInstance()
                                .newDocumentBuilder()
                                .parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
                        NodeList suites = doc.getElementsByTagName("testsuite");
                        for (int i = 0; i < suites.getLength(); i++) {
                            org.w3c.dom.Element suite = (org.w3c.dom.Element) suites.item(i);
                            int tests = parseIntAttr(suite, "tests");
                            int failures = parseIntAttr(suite, "failures");
                            int errors = parseIntAttr(suite, "errors");
                            int skip = parseIntAttr(suite, "skipped");
                            failed += failures + errors;
                            skipped += skip;
                            passed += Math.max(0, tests - failures - errors - skip);
                        }

                        NodeList testcases = doc.getElementsByTagName("testcase");
                        for (int i = 0; i < testcases.getLength(); i++) {
                            org.w3c.dom.Element tc = (org.w3c.dom.Element) testcases.item(i);
                            NodeList failNodes = tc.getElementsByTagName("failure");
                            NodeList errNodes = tc.getElementsByTagName("error");
                            if (failNodes.getLength() > 0 || errNodes.getLength() > 0) {
                                Map<String, Object> one = new LinkedHashMap<>();
                                one.put("className", tc.getAttribute("classname"));
                                one.put("name", tc.getAttribute("name"));
                                one.put("file", xml.toString());
                                failedCases.add(one);
                            }
                        }
                    }
                }
            }

            int total = passed + failed + skipped;
            return success(Map.of(
                    "passed", passed,
                    "failed", failed,
                    "skipped", skipped,
                    "total", total,
                    "failedCases", failedCases,
                    "parserType", (parserType == null || parserType.isBlank()) ? "auto" : parserType,
                    "reportDirs", dirs
            ));
        } catch (Exception e) {
            log.error("collect_test_report 失败", e);
            return error("collect_test_report 失败: " + e.getMessage());
        }
    }

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "创建代码检查点")
    public String gitCreateCheckpoint(
            @RequestParam("repoPath") String repoPath,
            @RequestParam("mode") String mode,
            @RequestParam("message") String message
    ) {
        try {
            String m = mode == null ? "commit" : mode.trim().toLowerCase(Locale.ROOT);
            File repo = resolveSafePath(repoPath, true).toFile();

            String checkpointId;
            switch (m) {
                case "stash" -> {
                    ProcessResult pr = runCommandInternal(repo, List.of("git", "stash", "push", "-u", "-m", message), 60);
                    if (pr.exitCode != 0) return error("git stash 失败: " + pr.stderrTail);
                    checkpointId = "stash@" + System.currentTimeMillis();
                }
                case "tag" -> {
                    String tag = "luna-checkpoint-" + SnowflakeIdUtil.nextIdStr();
                    ProcessResult pr = runCommandInternal(repo, List.of("git", "tag", tag), 30);
                    if (pr.exitCode != 0) return error("git tag 失败: " + pr.stderrTail);
                    checkpointId = tag;
                }
                case "commit" -> {
                    runCommandInternal(repo, List.of("git", "add", "-A"), 30);
                    ProcessResult commit = runCommandInternal(repo, List.of("git", "commit", "-m", message), 60);
                    if (commit.exitCode != 0 && !commit.stderrTail.contains("nothing to commit")) {
                        return error("git commit 失败: " + commit.stderrTail);
                    }
                    ProcessResult rev = runCommandInternal(repo, List.of("git", "rev-parse", "HEAD"), 30);
                    if (rev.exitCode != 0) return error("读取 HEAD 失败: " + rev.stderrTail);
                    checkpointId = rev.stdoutTail.trim();
                }
                default -> {
                    return error("mode 非法，仅支持 commit/stash/tag");
                }
            }

            return success(Map.of("checkpointId", checkpointId, "mode", m, "repoPath", repoPath, "message", message));
        } catch (Exception e) {
            log.error("git_create_checkpoint 失败", e);
            return error("git_create_checkpoint 失败: " + e.getMessage());
        }
    }

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "回滚代码检查点")
    public String gitRollbackCheckpoint(
            @RequestParam("repoPath") String repoPath,
            @RequestParam("checkpointId") String checkpointId,
            @RequestParam(value = "mode", required = false) String mode
    ) {
        try {
            String m = (mode == null || mode.isBlank()) ? "soft" : mode.trim().toLowerCase(Locale.ROOT);
            if (!List.of("hard", "soft", "mixed").contains(m)) {
                return error("mode 非法，仅支持 hard/soft/mixed");
            }

            File repo = resolveSafePath(repoPath, true).toFile();
            ProcessResult pr = runCommandInternal(repo, List.of("git", "reset", "--" + m, checkpointId), 60);
            if (pr.exitCode != 0) {
                return error("git rollback 失败: " + pr.stderrTail);
            }

            ProcessResult head = runCommandInternal(repo, List.of("git", "rev-parse", "HEAD"), 30);
            String currentHead = head.exitCode == 0 ? head.stdoutTail.trim() : checkpointId;

            return success(Map.of("rolledBack", true, "currentHead", currentHead, "mode", m, "repoPath", repoPath));
        } catch (Exception e) {
            log.error("git_rollback_checkpoint 失败", e);
            return error("git_rollback_checkpoint 失败: " + e.getMessage());
        }
    }

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "搜索符号引用")
    public String searchSymbolReferences(
            @RequestParam("repoPath") String repoPath,
            @RequestParam("symbol") String symbol,
            @RequestParam(value = "language", required = false) String language
    ) {
        try {
            Path root = resolveSafePath(repoPath, true);
            if (!Files.exists(root)) {
                return error("repoPath 不存在");
            }

            List<Map<String, Object>> refs = new ArrayList<>();
            try (Stream<Path> stream = Files.walk(root)) {
                List<Path> files = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> !p.toString().contains(File.separator + ".git" + File.separator))
                        .toList();

                for (Path f : files) {
                    if (refs.size() >= 500) break;
                    List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        if (line.contains(symbol)) {
                            Map<String, Object> one = new LinkedHashMap<>();
                            one.put("file", root.relativize(f).toString().replace("\\", "/"));
                            one.put("line", i + 1);
                            one.put("snippet", trimLine(line, 200));
                            refs.add(one);
                            if (refs.size() >= 500) break;
                        }
                    }
                }
            }

            return success(Map.of("references", refs, "symbol", symbol, "repoPath", repoPath, "language", language));
        } catch (Exception e) {
            log.error("search_symbol_references 失败", e);
            return error("search_symbol_references 失败: " + e.getMessage());
        }
    }

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "扫描依赖漏洞")
    public String scanDependencyVulnerabilities(
            @RequestParam("repoPath") String repoPath,
            @RequestParam(value = "ecosystem", required = false) String ecosystem,
            @RequestParam(value = "failOnSeverity", required = false) String failOnSeverity
    ) {
        return error("当前环境未启用SCA依赖漏洞扫描器，请参阅 docs/openclaw_tool_dependency_guide.md 完成依赖安装后再启用。");
    }

    private String runCommand(String workDir, String command, Integer timeoutSec) {
        try {
            Path wd = resolveSafePath(workDir, true);
            validateCommand(command);

            ProcessResult pr = runCommandInternal(wd.toFile(), parseCommand(command), timeoutSec == null ? 600 : timeoutSec);
            return success(Map.of(
                    "exitCode", pr.exitCode,
                    "stdoutTail", pr.stdoutTail,
                    "stderrTail", pr.stderrTail,
                    "costMs", pr.costMs
            ));
        } catch (Exception e) {
            log.error("命令执行失败", e);
            return error("命令执行失败: " + e.getMessage());
        }
    }

    private ProcessResult runCommandInternal(File workDir, List<String> cmd, int timeoutSec) throws Exception {
        long start = System.currentTimeMillis();
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir);
        Process p = pb.start();

        boolean done = p.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            throw new RuntimeException("命令超时");
        }

        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        return new ProcessResult(
                p.exitValue(),
                tail(stdout, 4000),
                tail(stderr, 4000),
                System.currentTimeMillis() - start
        );
    }

    private static List<String> parseCommand(String cmd) {
        return Arrays.stream(cmd.trim().split("\\s+")).toList();
    }

    private static String tail(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(s.length() - max);
    }

    private static String trimLine(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private List<String> parseReportDirs(String reportDirs) {
        if (reportDirs == null || reportDirs.isBlank()) {
            return List.of();
        }
        String txt = reportDirs.trim();
        try {
            if (txt.startsWith("[")) {
                JsonNode node = objectMapper.readTree(txt);
                if (node.isArray()) {
                    List<String> out = new ArrayList<>();
                    for (JsonNode n : node) {
                        out.add(n.asText());
                    }
                    return out;
                }
            }
        } catch (Exception ignore) {
        }
        return Arrays.stream(txt.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    private static int parseIntAttr(org.w3c.dom.Element el, String attr) {
        try {
            String v = el.getAttribute(attr);
            if (v == null || v.isBlank()) return 0;
            return Integer.parseInt(v);
        } catch (Exception e) {
            return 0;
        }
    }

    private void validateCommand(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command 不能为空");
        }
        String trimmed = command.trim();
        for (String token : DANGEROUS_TOKENS) {
            if (trimmed.contains(token)) {
                throw new IllegalArgumentException("命令包含危险符号: " + token);
            }
        }
        List<String> parts = parseCommand(trimmed);
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("command 不能为空");
        }
        String head = parts.get(0).toLowerCase(Locale.ROOT);
        if (!CMD_HEAD_WHITELIST.contains(head)) {
            throw new IllegalArgumentException("命令不在白名单: " + head);
        }
    }

    private Path resolveSafePath(String inputPath, boolean mustDirectory) throws Exception {
        if (inputPath == null || inputPath.isBlank()) {
            throw new IllegalArgumentException("路径不能为空");
        }

        Path target = Paths.get(inputPath).toAbsolutePath().normalize();

        if (workspaceRoot != null && !workspaceRoot.isBlank()) {
            Path root = Paths.get(workspaceRoot).toAbsolutePath().normalize();
            if (!target.startsWith(root)) {
                throw new SecurityException("路径越界，不在受控工作区内");
            }
        }

        if (mustDirectory && (!Files.exists(target) || !Files.isDirectory(target))) {
            throw new IllegalArgumentException("目录不存在: " + target);
        }
        return target;
    }

    private record ProcessResult(int exitCode, String stdoutTail, String stderrTail, long costMs) {
    }
}
