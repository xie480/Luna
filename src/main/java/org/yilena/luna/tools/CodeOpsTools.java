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
/**
 * 代码运维工具类，负责在受控工作区内提供仓库读写、命令执行、Git 操作和依赖扫描等工程能力。
 */
public class CodeOpsTools extends BaseTool {

    /**
     * 受控工作区根目录，用于限制工具只在指定目录内操作文件。
     */
    @Value("${codeops.workspace-root:}")
    private String workspaceRoot;

    /**
     * 允许执行的命令白名单，用于约束构建、测试和扫描命令的入口。
     */
    private static final Set<String> CMD_HEAD_WHITELIST = Set.of(
            "mvn", "gradle", "npm", "pnpm", "yarn", "pytest", "python", "python3", "bash", "sh", "pip-audit"
    );

    /**
     * 命令中禁止出现的危险符号，用于阻断串联命令和重定向等高风险操作。
     */
    private static final List<String> DANGEROUS_TOKENS = List.of("&&", ";", "|", ">", "<", "`", "$(");

    public CodeOpsTools(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "读取仓库目录树")
    /**
     * 读取仓库目录树，并按深度和隐藏文件参数返回受控目录下的结构清单。
     */
    public String readRepoTree(
            @RequestParam("repoPath") String repoPath,
            @RequestParam(value = "maxDepth", required = false) Integer maxDepth,
            @RequestParam(value = "includeHidden", required = false) Boolean includeHidden
    ) {
        try {
            // 所有路径先做工作区安全校验，避免越界读取。
            /**
             * 所有路径先做安全校验，再限定遍历深度，避免越界读取和过深扫描。
             */
            Path root = resolveSafePath(repoPath, true);
            int depth = maxDepth == null ? 4 : maxDepth;
            boolean hidden = Boolean.TRUE.equals(includeHidden);

            List<String> items = new ArrayList<>();
            try (var stream = Files.walk(root, depth)) {
                // 默认过滤高噪目录，减少目录树体积。
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
    /**
     * 读取源码文件内容，并按编码和最大字节数限制返回文本。
     */
    public String readSourceFile(
            @RequestParam("filePath") String filePath,
            @RequestParam(value = "encoding", required = false) String encoding,
            @RequestParam(value = "maxBytes", required = false) Integer maxBytes
    ) {
        try {
            /**
             * 先校验文件路径，再控制读取大小，避免将超大文件一次性拉入对话链路。
             */
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
    /**
     * 写入源码文件内容，必要时先创建备份，确保文件修改过程可回退。
     */
    public String writeSourceFile(
            @RequestParam("filePath") String filePath,
            @RequestParam("content") String content,
            @RequestParam(value = "backup", required = false) Boolean backup
    ) {
        try {
            /**
             * 先校验目标路径，再按需创建备份，避免写入越界和误覆盖关键文件。
             */
            Path p = resolveSafePath(filePath, false);
            String backupPath = null;
            if (Boolean.TRUE.equals(backup) && Files.exists(p)) {
                backupPath = p.toString() + ".bak." + System.currentTimeMillis();
                Files.copy(p, Paths.get(backupPath), StandardCopyOption.REPLACE_EXISTING);
            }
            /**
             * 目标目录不存在时先创建目录，再以 UTF-8 覆盖写入最新内容。
             */
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
    /**
     * 在指定仓库中执行统一补丁，可选择仅校验补丁可用性而不真正应用。
     */
    public String applyUnifiedPatch(
            @RequestParam("repoPath") String repoPath,
            @RequestParam("patchText") String patchText,
            @RequestParam(value = "checkOnly", required = false) Boolean checkOnly
    ) {
        try {
            boolean dryRun = Boolean.TRUE.equals(checkOnly);
            /**
             * 先确定受控仓库路径，再根据 checkOnly 决定走校验还是实际应用流程。
             */
            Path repo = resolveSafePath(repoPath, true);

            // 先落盘临时 patch 文件，再调用 git apply。
            /**
             * 先将补丁内容写入临时文件，再交给 git apply 处理，复用标准补丁能力。
             */
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

            /**
             * 无论补丁是否成功，都统一回传命令结果和失败摘要，便于上层判断下一步动作。
             */
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
    /**
     * 执行构建命令，复用统一命令执行入口并受白名单约束。
     */
    public String runBuildCommand(@RequestParam("workDir") String workDir, @RequestParam("command") String command, @RequestParam(value = "timeoutSec", required = false) Integer timeoutSec) {
        return runCommand(workDir, command, timeoutSec);
    }

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "执行测试命令")
    /**
     * 执行测试命令，返回命令退出码和标准输出摘要。
     */
    public String runTestCommand(@RequestParam("workDir") String workDir, @RequestParam("command") String command, @RequestParam(value = "timeoutSec", required = false) Integer timeoutSec) {
        return runCommand(workDir, command, timeoutSec);
    }

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "执行静态检查")
    /**
     * 执行静态检查命令，供代码质量扫描链路复用。
     */
    public String runLintCommand(@RequestParam("workDir") String workDir, @RequestParam("command") String command, @RequestParam(value = "timeoutSec", required = false) Integer timeoutSec) {
        return runCommand(workDir, command, timeoutSec);
    }

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "执行格式化")
    /**
     * 执行格式化命令，统一走受控命令执行链路。
     */
    public String runFormatCommand(@RequestParam("workDir") String workDir, @RequestParam("command") String command, @RequestParam(value = "timeoutSec", required = false) Integer timeoutSec) {
        return runCommand(workDir, command, timeoutSec);
    }

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "收集测试报告")
    /**
     * 收集测试报告目录中的 XML 报告，并汇总通过、失败和跳过统计。
     */
    public String collectTestReport(
            @RequestParam("reportDirs") String reportDirs,
            @RequestParam(value = "parserType", required = false) String parserType
    ) {
        try {
            /**
             * 先解析报告目录列表，再逐目录扫描 testsuite XML 文件汇总测试结果。
             */
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
                        /**
                         * 对每个测试报告文件同时提取总量统计和失败用例明细，便于排查异常。
                         */
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
    /**
     * 创建 Git 检查点，支持 stash、tag 和 commit 三种策略。
     */
    public String gitCreateCheckpoint(
            @RequestParam("repoPath") String repoPath,
            @RequestParam("mode") String mode,
            @RequestParam("message") String message
    ) {
        try {
            /**
             * 先规范化检查点模式，再按不同 Git 策略创建可回退的状态锚点。
             */
            String m = mode == null ? "commit" : mode.trim().toLowerCase(Locale.ROOT);
            File repo = resolveSafePath(repoPath, true).toFile();

            String checkpointId;
            // 支持三种检查点策略：stash/tag/commit。
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
    /**
     * 回滚到指定 Git 检查点，并返回回滚后的当前 HEAD。
     */
    public String gitRollbackCheckpoint(
            @RequestParam("repoPath") String repoPath,
            @RequestParam("checkpointId") String checkpointId,
            @RequestParam(value = "mode", required = false) String mode
    ) {
        try {
            /**
             * 先校验回滚模式，再执行对应级别的 git reset，避免出现非法重置操作。
             */
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
    /**
     * 在仓库中搜索符号引用，返回命中文件、行号和代码片段。
     */
    public String searchSymbolReferences(
            @RequestParam("repoPath") String repoPath,
            @RequestParam("symbol") String symbol,
            @RequestParam(value = "language", required = false) String language
    ) {
        try {
            /**
             * 先校验仓库根路径，再遍历文件内容，限制返回结果规模避免响应过大。
             */
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
    /**
     * 扫描项目依赖漏洞，自动识别生态并基于阈值判断是否需要阻断。
     */
    public String scanDependencyVulnerabilities(
            @RequestParam("repoPath") String repoPath,
            @RequestParam(value = "ecosystem", required = false) String ecosystem,
            @RequestParam(value = "failOnSeverity", required = false) String failOnSeverity
    ) {
        try {
            /**
             * 先确定仓库和生态类型，再组装对应的依赖扫描命令。
             */
            Path repo = resolveSafePath(repoPath, true);
            String eco = ecosystem == null ? "" : ecosystem.trim().toLowerCase(Locale.ROOT);

            // 自动识别生态并构建对应的 SCA 命令。
            List<String> cmd = buildScaCommand(repo, eco);
            if (cmd == null || cmd.isEmpty()) {
                return error("无法识别项目生态，请传 ecosystem=maven|gradle|npm|pnpm|yarn|python");
            }

            /**
             * 执行漏洞扫描后收集报告文件，并生成统一漏洞摘要和阈值判断结果。
             */
            ProcessResult pr = runCommandInternal(repo.toFile(), cmd, 1800);

            List<Path> reportCandidates = findSCAReports(repo, eco);
            Map<String, Object> summary = summarizeScaReports(reportCandidates);

            String normalizedSeverity = normalizeSeverity(failOnSeverity);
            boolean thresholdReached = isThresholdReached(summary, normalizedSeverity);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("repoPath", repo.toString());
            out.put("ecosystem", detectEcoLabel(repo, eco));
            out.put("command", String.join(" ", cmd));
            out.put("exitCode", pr.exitCode);
            out.put("stdoutTail", pr.stdoutTail);
            out.put("stderrTail", pr.stderrTail);
            out.put("costMs", pr.costMs);
            out.put("reportFiles", reportCandidates.stream().map(Path::toString).toList());
            out.put("summary", summary);
            out.put("failOnSeverity", normalizedSeverity);
            out.put("thresholdReached", thresholdReached);

            if (pr.exitCode != 0) {
                return error("依赖漏洞扫描执行失败，exitCode=" + pr.exitCode + "，stderr=" + pr.stderrTail);
            }
            if (thresholdReached) {
                return error("依赖漏洞扫描发现达到阈值的风险（" + normalizedSeverity + "及以上）");
            }
            return success(out);
        } catch (Exception e) {
            log.error("scan_dependency_vulnerabilities 失败", e);
            return error("scan_dependency_vulnerabilities 失败: " + e.getMessage());
        }
    }

    /**
     * 执行受控命令，统一完成路径校验、命令校验和结果包装。
     */
    private String runCommand(String workDir, String command, Integer timeoutSec) {
        try {
            /**
             * 先校验工作目录是否在受控范围内，避免命令在未知目录执行。
             */
            Path wd = resolveSafePath(workDir, true);
            // 执行前做命令白名单与危险符号校验。
            validateCommand(command);

            /**
             * 路径和命令均通过校验后执行进程，并统一封装退出码和输出摘要。
             */
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

    /**
     * 使用原生进程执行命令，并截断保存标准输出、错误输出和耗时信息。
     */
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

    /**
     * 将命令字符串按空白拆分为进程参数列表。
     */
    private static List<String> parseCommand(String cmd) {
        return Arrays.stream(cmd.trim().split("\\s+")).toList();
    }

    /**
     * 截取字符串尾部内容，避免长输出占用过大响应体。
     */
    private static String tail(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(s.length() - max);
    }

    /**
     * 截断单行片段长度，便于搜索结果展示。
     */
    private static String trimLine(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    /**
     * 解析测试报告目录参数，兼容 JSON 数组和逗号分隔两种输入格式。
     */
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

    /**
     * 读取 XML 节点中的整数字段，解析失败时返回 0。
     */
    private static int parseIntAttr(org.w3c.dom.Element el, String attr) {
        try {
            String v = el.getAttribute(attr);
            if (v == null || v.isBlank()) return 0;
            return Integer.parseInt(v);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 校验命令是否为空、是否包含危险符号以及是否命中白名单头部命令。
     */
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

    /**
     * 解析并校验受控路径，必要时强制要求目标必须是目录。
     */
    private Path resolveSafePath(String inputPath, boolean mustDirectory) throws Exception {
        if (inputPath == null || inputPath.isBlank()) {
            throw new IllegalArgumentException("路径不能为空");
        }

        Path target = Paths.get(inputPath).toAbsolutePath().normalize();

        // workspaceRoot 配置存在时，强制限制在受控目录内。
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

    /**
     * 根据项目生态生成对应的依赖漏洞扫描命令。
     */
    private List<String> buildScaCommand(Path repo, String ecosystem) {
        String eco = detectEcoLabel(repo, ecosystem);
        if ("maven".equals(eco)) {
            return List.of(
                    "mvn",
                    "-B",
                    "-DskipTests",
                    "org.owasp:dependency-check-maven:check"
            );
        }
        if ("gradle".equals(eco)) {
            return List.of(
                    "gradle",
                    "dependencyCheckAnalyze",
                    "--no-daemon"
            );
        }
        if ("npm".equals(eco) || "pnpm".equals(eco) || "yarn".equals(eco)) {
            return List.of(eco, "audit", "--json");
        }
        if ("python".equals(eco)) {
            return List.of("pip-audit", "-f", "json");
        }
        return null;
    }

    /**
     * 自动识别项目生态，优先使用外部显式指定值。
     */
    private String detectEcoLabel(Path repo, String requestedEco) {
        if (requestedEco != null && !requestedEco.isBlank()) {
            return requestedEco.trim().toLowerCase(Locale.ROOT);
        }
        if (Files.exists(repo.resolve("pom.xml"))) return "maven";
        if (Files.exists(repo.resolve("build.gradle")) || Files.exists(repo.resolve("build.gradle.kts"))) return "gradle";
        if (Files.exists(repo.resolve("package-lock.json")) || Files.exists(repo.resolve("package.json"))) return "npm";
        if (Files.exists(repo.resolve("pnpm-lock.yaml"))) return "pnpm";
        if (Files.exists(repo.resolve("yarn.lock"))) return "yarn";
        if (Files.exists(repo.resolve("requirements.txt")) || Files.exists(repo.resolve("pyproject.toml"))) return "python";
        return "";
    }

    /**
     * 按生态约定位置收集依赖扫描报告文件。
     */
    private List<Path> findSCAReports(Path repo, String eco) {
        List<Path> reports = new ArrayList<>();
        try {
            String actualEco = detectEcoLabel(repo, eco);
            if ("maven".equals(actualEco) || "gradle".equals(actualEco)) {
                Path targetReport = repo.resolve("target").resolve("dependency-check-report.json");
                if (Files.exists(targetReport)) reports.add(targetReport);
                Path buildReport = repo.resolve("build").resolve("reports").resolve("dependency-check-report.json");
                if (Files.exists(buildReport)) reports.add(buildReport);
            } else if ("npm".equals(actualEco) || "pnpm".equals(actualEco) || "yarn".equals(actualEco)) {
                Path npmAudit = repo.resolve("npm-audit.json");
                if (Files.exists(npmAudit)) reports.add(npmAudit);
            } else if ("python".equals(actualEco)) {
                Path pipAudit = repo.resolve("pip-audit-report.json");
                if (Files.exists(pipAudit)) reports.add(pipAudit);
            }
        } catch (Exception ignored) {
        }
        return reports;
    }

    /**
     * 汇总依赖扫描报告中的漏洞等级和依赖数，形成统一摘要。
     */
    private Map<String, Object> summarizeScaReports(List<Path> reportFiles) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("critical", 0);
        summary.put("high", 0);
        summary.put("medium", 0);
        summary.put("low", 0);
        summary.put("unknown", 0);
        summary.put("dependencies", 0);
        summary.put("vulnerabilities", 0);

        int critical = 0, high = 0, medium = 0, low = 0, unknown = 0, deps = 0;

        for (Path report : reportFiles) {
            try {
                JsonNode root = objectMapper.readTree(Files.readString(report, StandardCharsets.UTF_8));
                if (root.has("dependencies") && root.get("dependencies").isArray()) {
                    for (JsonNode dep : root.get("dependencies")) {
                        deps++;
                        JsonNode vulns = dep.get("vulnerabilities");
                        if (vulns != null && vulns.isArray()) {
                            for (JsonNode v : vulns) {
                                String severity = v.path("severity").asText("").toUpperCase(Locale.ROOT);
                                switch (severity) {
                                    case "CRITICAL" -> critical++;
                                    case "HIGH" -> high++;
                                    case "MEDIUM" -> medium++;
                                    case "LOW" -> low++;
                                    default -> unknown++;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("解析漏洞报告失败: {}, err={}", report, e.getMessage());
            }
        }

        summary.put("critical", critical);
        summary.put("high", high);
        summary.put("medium", medium);
        summary.put("low", low);
        summary.put("unknown", unknown);
        summary.put("dependencies", deps);
        summary.put("vulnerabilities", critical + high + medium + low + unknown);
        return summary;
    }

    /**
     * 规范化漏洞严重级别阈值，非法值回退到 HIGH。
     */
    private String normalizeSeverity(String severity) {
        if (severity == null || severity.isBlank()) return "HIGH";
        String s = severity.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW").contains(s)) return "HIGH";
        return s;
    }

    /**
     * 判断当前漏洞摘要是否达到阻断阈值。
     */
    private boolean isThresholdReached(Map<String, Object> summary, String severity) {
        int critical = intOf(summary.get("critical"));
        int high = intOf(summary.get("high"));
        int medium = intOf(summary.get("medium"));
        int low = intOf(summary.get("low"));

        // 以传入阈值做分级判断，命中即视为扫描失败。
        return switch (severity) {
            case "CRITICAL" -> critical > 0;
            case "HIGH" -> critical + high > 0;
            case "MEDIUM" -> critical + high + medium > 0;
            case "LOW" -> critical + high + medium + low > 0;
            default -> critical + high > 0;
        };
    }

    /**
     * 将任意对象安全转换为整数，转换失败时返回 0。
     */
    private int intOf(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 进程执行结果，记录命令退出码、输出尾部内容和耗时信息。
     */
    private record ProcessResult(int exitCode, String stdoutTail, String stderrTail, long costMs) {
    }
}
