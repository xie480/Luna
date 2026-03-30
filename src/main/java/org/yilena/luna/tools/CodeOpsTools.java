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

    @Value("${codeops.workspace-root:}") // 声明注解
    private String workspaceRoot; // 声明成员字段

    private static final Set<String> CMD_HEAD_WHITELIST = Set.of( // 定义方法签名
            "mvn", "gradle", "npm", "pnpm", "yarn", "pytest", "python", "python3", "bash", "sh", "pip-audit" // 执行当前逻辑
    ); // 执行语句逻辑

    private static final List<String> DANGEROUS_TOKENS = List.of("&&", ";", "|", ">", "<", "`", "$("); // 定义方法签名

    public CodeOpsTools(ObjectMapper objectMapper) { // 定义方法签名
        super(objectMapper); // 执行语句逻辑
    } // 结束当前代码块

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS) // 声明注解
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "读取仓库目录树") // 声明注解
    public String readRepoTree( // 定义方法签名
            @RequestParam("repoPath") String repoPath, // 声明注解
            @RequestParam(value = "maxDepth", required = false) Integer maxDepth, // 声明注解
            @RequestParam(value = "includeHidden", required = false) Boolean includeHidden // 声明注解
    ) { // 开始新的代码块
        try { // 尝试执行核心逻辑
            Path root = resolveSafePath(repoPath, true); // 执行赋值操作
            int depth = maxDepth == null ? 4 : maxDepth; // 执行赋值操作
            boolean hidden = Boolean.TRUE.equals(includeHidden); // 执行赋值操作

            List<String> items = new ArrayList<>(); // 执行赋值操作
            try (var stream = Files.walk(root, depth)) { // 尝试执行核心逻辑
                stream.forEach(p -> { // 开始新的代码块
                    Path rel = root.relativize(p); // 执行赋值操作
                    if (rel.toString().isEmpty()) return; // 进行条件判断
                    String s = rel.toString().replace("\\", "/"); // 执行赋值操作
                    if (!hidden && s.startsWith(".")) return; // 进行条件判断
                    if (s.startsWith(".git") || s.startsWith("node_modules") || s.startsWith("target")) return; // 进行条件判断
                    items.add(s + (Files.isDirectory(p) ? "/" : "")); // 执行语句逻辑
                }); // 执行语句逻辑
            } // 结束当前代码块
            log.info("read_repo_tree 完成, repoPath={}, count={}", root, items.size()); // 执行赋值操作
            return success(Map.of("tree", items, "fileCount", items.size())); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            log.error("read_repo_tree 失败", e); // 执行语句逻辑
            return error("read_repo_tree 失败: " + e.getMessage()); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS) // 声明注解
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "读取源码文件") // 声明注解
    public String readSourceFile( // 定义方法签名
            @RequestParam("filePath") String filePath, // 声明注解
            @RequestParam(value = "encoding", required = false) String encoding, // 声明注解
            @RequestParam(value = "maxBytes", required = false) Integer maxBytes // 声明注解
    ) { // 开始新的代码块
        try { // 尝试执行核心逻辑
            Path p = resolveSafePath(filePath, false); // 执行赋值操作
            String enc = (encoding == null || encoding.isBlank()) ? "UTF-8" : encoding; // 执行赋值操作
            int limit = maxBytes == null ? 1024 * 1024 : maxBytes; // 执行赋值操作
            byte[] bytes = Files.readAllBytes(p); // 执行赋值操作
            if (bytes.length > limit) return error("文件超过 maxBytes 限制"); // 进行条件判断
            String content = new String(bytes, enc); // 执行赋值操作
            log.info("read_source_file 完成, filePath={}, size={}", p, bytes.length); // 执行赋值操作
            return success(Map.of("content", content, "size", bytes.length)); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            log.error("read_source_file 失败", e); // 执行语句逻辑
            return error("read_source_file 失败: " + e.getMessage()); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS) // 声明注解
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "写入源码文件") // 声明注解
    public String writeSourceFile( // 定义方法签名
            @RequestParam("filePath") String filePath, // 声明注解
            @RequestParam("content") String content, // 声明注解
            @RequestParam(value = "backup", required = false) Boolean backup // 声明注解
    ) { // 开始新的代码块
        try { // 尝试执行核心逻辑
            Path p = resolveSafePath(filePath, false); // 执行赋值操作
            String backupPath = null; // 执行赋值操作
            if (Boolean.TRUE.equals(backup) && Files.exists(p)) { // 进行条件判断
                backupPath = p.toString() + ".bak." + System.currentTimeMillis(); // 执行赋值操作
                Files.copy(p, Paths.get(backupPath), StandardCopyOption.REPLACE_EXISTING); // 执行语句逻辑
            } // 结束当前代码块
            Files.createDirectories(p.getParent()); // 执行语句逻辑
            Files.writeString(p, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING); // 执行语句逻辑
            log.info("write_source_file 完成, filePath={}", p); // 执行赋值操作
            return success(Map.of("written", true, "backupPath", backupPath)); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            log.error("write_source_file 失败", e); // 执行语句逻辑
            return error("write_source_file 失败: " + e.getMessage()); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS) // 声明注解
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "应用补丁") // 声明注解
    public String applyUnifiedPatch( // 定义方法签名
            @RequestParam("repoPath") String repoPath, // 声明注解
            @RequestParam("patchText") String patchText, // 声明注解
            @RequestParam(value = "checkOnly", required = false) Boolean checkOnly // 声明注解
    ) { // 开始新的代码块
        try { // 尝试执行核心逻辑
            boolean dryRun = Boolean.TRUE.equals(checkOnly); // 执行赋值操作
            Path repo = resolveSafePath(repoPath, true); // 执行赋值操作

            Path patchFile = Files.createTempFile("luna_patch_", ".diff"); // 执行赋值操作
            Files.writeString(patchFile, patchText == null ? "" : patchText, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING); // 执行赋值操作

            List<String> cmd = new ArrayList<>(); // 执行赋值操作
            cmd.add("git"); // 执行语句逻辑
            cmd.add("apply"); // 执行语句逻辑
            if (dryRun) { // 进行条件判断
                cmd.add("--check"); // 执行语句逻辑
            } else { // 切换到分支逻辑
                cmd.add("--whitespace=nowarn"); // 执行赋值操作
            } // 结束当前代码块
            cmd.add(patchFile.toString()); // 执行语句逻辑

            ProcessResult pr = runCommandInternal(repo.toFile(), cmd, 120); // 执行赋值操作
            Files.deleteIfExists(patchFile); // 执行语句逻辑

            Map<String, Object> out = new LinkedHashMap<>(); // 执行赋值操作
            out.put("checkOnly", dryRun); // 执行语句逻辑
            out.put("checkOnlyResult", pr.exitCode == 0 ? "OK" : "FAILED"); // 执行赋值操作
            out.put("appliedFiles", List.of()); // 执行语句逻辑
            out.put("failedFiles", pr.exitCode == 0 ? List.of() : List.of(pr.stderrTail)); // 执行赋值操作
            out.put("exitCode", pr.exitCode); // 执行语句逻辑
            out.put("stdoutTail", pr.stdoutTail); // 执行语句逻辑
            out.put("stderrTail", pr.stderrTail); // 执行语句逻辑

            if (dryRun || pr.exitCode == 0) { // 进行条件判断
                return success(out); // 返回处理结果
            } // 结束当前代码块
            return error("apply_unified_patch 失败: " + pr.stderrTail); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            log.error("apply_unified_patch 失败", e); // 执行语句逻辑
            return error("apply_unified_patch 失败: " + e.getMessage()); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS) // 声明注解
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "执行构建命令") // 声明注解
    public String runBuildCommand(@RequestParam("workDir") String workDir, @RequestParam("command") String command, @RequestParam(value = "timeoutSec", required = false) Integer timeoutSec) { // 定义方法签名
        return runCommand(workDir, command, timeoutSec); // 返回处理结果
    } // 结束当前代码块

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS) // 声明注解
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "执行测试命令") // 声明注解
    public String runTestCommand(@RequestParam("workDir") String workDir, @RequestParam("command") String command, @RequestParam(value = "timeoutSec", required = false) Integer timeoutSec) { // 定义方法签名
        return runCommand(workDir, command, timeoutSec); // 返回处理结果
    } // 结束当前代码块

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS) // 声明注解
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "执行静态检查") // 声明注解
    public String runLintCommand(@RequestParam("workDir") String workDir, @RequestParam("command") String command, @RequestParam(value = "timeoutSec", required = false) Integer timeoutSec) { // 定义方法签名
        return runCommand(workDir, command, timeoutSec); // 返回处理结果
    } // 结束当前代码块

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS) // 声明注解
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "执行格式化") // 声明注解
    public String runFormatCommand(@RequestParam("workDir") String workDir, @RequestParam("command") String command, @RequestParam(value = "timeoutSec", required = false) Integer timeoutSec) { // 定义方法签名
        return runCommand(workDir, command, timeoutSec); // 返回处理结果
    } // 结束当前代码块

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS) // 声明注解
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "收集测试报告") // 声明注解
    public String collectTestReport( // 定义方法签名
            @RequestParam("reportDirs") String reportDirs, // 声明注解
            @RequestParam(value = "parserType", required = false) String parserType // 声明注解
    ) { // 开始新的代码块
        try { // 尝试执行核心逻辑
            List<String> dirs = parseReportDirs(reportDirs); // 执行赋值操作
            int passed = 0, failed = 0, skipped = 0; // 执行赋值操作
            List<Map<String, Object>> failedCases = new ArrayList<>(); // 执行赋值操作

            for (String dir : dirs) { // 执行循环处理
                Path base = resolveSafePath(dir, true); // 执行赋值操作
                if (!Files.exists(base)) { // 进行条件判断
                    continue; // 执行语句逻辑
                } // 结束当前代码块
                try (Stream<Path> stream = Files.walk(base)) { // 尝试执行核心逻辑
                    List<Path> xmlFiles = stream // 执行赋值操作
                            .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".xml")) // 执行当前逻辑
                            .toList(); // 执行语句逻辑
                    for (Path xml : xmlFiles) { // 执行循环处理
                        String content = Files.readString(xml, StandardCharsets.UTF_8); // 执行赋值操作
                        if (!content.contains("<testsuite")) { // 进行条件判断
                            continue; // 执行语句逻辑
                        } // 结束当前代码块
                        Document doc = DocumentBuilderFactory.newInstance() // 执行赋值操作
                                .newDocumentBuilder() // 执行当前逻辑
                                .parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))); // 执行语句逻辑
                        NodeList suites = doc.getElementsByTagName("testsuite"); // 执行赋值操作
                        for (int i = 0; i < suites.getLength(); i++) { // 执行循环处理
                            org.w3c.dom.Element suite = (org.w3c.dom.Element) suites.item(i); // 执行赋值操作
                            int tests = parseIntAttr(suite, "tests"); // 执行赋值操作
                            int failures = parseIntAttr(suite, "failures"); // 执行赋值操作
                            int errors = parseIntAttr(suite, "errors"); // 执行赋值操作
                            int skip = parseIntAttr(suite, "skipped"); // 执行赋值操作
                            failed += failures + errors; // 执行赋值操作
                            skipped += skip; // 执行赋值操作
                            passed += Math.max(0, tests - failures - errors - skip); // 执行赋值操作
                        } // 结束当前代码块

                        NodeList testcases = doc.getElementsByTagName("testcase"); // 执行赋值操作
                        for (int i = 0; i < testcases.getLength(); i++) { // 执行循环处理
                            org.w3c.dom.Element tc = (org.w3c.dom.Element) testcases.item(i); // 执行赋值操作
                            NodeList failNodes = tc.getElementsByTagName("failure"); // 执行赋值操作
                            NodeList errNodes = tc.getElementsByTagName("error"); // 执行赋值操作
                            if (failNodes.getLength() > 0 || errNodes.getLength() > 0) { // 进行条件判断
                                Map<String, Object> one = new LinkedHashMap<>(); // 执行赋值操作
                                one.put("className", tc.getAttribute("classname")); // 执行语句逻辑
                                one.put("name", tc.getAttribute("name")); // 执行语句逻辑
                                one.put("file", xml.toString()); // 执行语句逻辑
                                failedCases.add(one); // 执行语句逻辑
                            } // 结束当前代码块
                        } // 结束当前代码块
                    } // 结束当前代码块
                } // 结束当前代码块
            } // 结束当前代码块

            int total = passed + failed + skipped; // 执行赋值操作
            return success(Map.of( // 返回处理结果
                    "passed", passed, // 执行当前逻辑
                    "failed", failed, // 执行当前逻辑
                    "skipped", skipped, // 执行当前逻辑
                    "total", total, // 执行当前逻辑
                    "failedCases", failedCases, // 执行当前逻辑
                    "parserType", (parserType == null || parserType.isBlank()) ? "auto" : parserType, // 执行赋值操作
                    "reportDirs", dirs // 执行当前逻辑
            )); // 执行语句逻辑
        } catch (Exception e) { // 开始新的代码块
            log.error("collect_test_report 失败", e); // 执行语句逻辑
            return error("collect_test_report 失败: " + e.getMessage()); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS) // 声明注解
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "创建代码检查点") // 声明注解
    public String gitCreateCheckpoint( // 定义方法签名
            @RequestParam("repoPath") String repoPath, // 声明注解
            @RequestParam("mode") String mode, // 声明注解
            @RequestParam("message") String message // 声明注解
    ) { // 开始新的代码块
        try { // 尝试执行核心逻辑
            String m = mode == null ? "commit" : mode.trim().toLowerCase(Locale.ROOT); // 执行赋值操作
            File repo = resolveSafePath(repoPath, true).toFile(); // 执行赋值操作

            String checkpointId; // 执行语句逻辑
            switch (m) { // 进行分支选择
                case "stash" -> { // 命中分支条件
                    ProcessResult pr = runCommandInternal(repo, List.of("git", "stash", "push", "-u", "-m", message), 60); // 执行赋值操作
                    if (pr.exitCode != 0) return error("git stash 失败: " + pr.stderrTail); // 进行条件判断
                    checkpointId = "stash@" + System.currentTimeMillis(); // 执行赋值操作
                } // 结束当前代码块
                case "tag" -> { // 命中分支条件
                    String tag = "luna-checkpoint-" + SnowflakeIdUtil.nextIdStr(); // 执行赋值操作
                    ProcessResult pr = runCommandInternal(repo, List.of("git", "tag", tag), 30); // 执行赋值操作
                    if (pr.exitCode != 0) return error("git tag 失败: " + pr.stderrTail); // 进行条件判断
                    checkpointId = tag; // 执行赋值操作
                } // 结束当前代码块
                case "commit" -> { // 命中分支条件
                    runCommandInternal(repo, List.of("git", "add", "-A"), 30); // 执行语句逻辑
                    ProcessResult commit = runCommandInternal(repo, List.of("git", "commit", "-m", message), 60); // 执行赋值操作
                    if (commit.exitCode != 0 && !commit.stderrTail.contains("nothing to commit")) { // 进行条件判断
                        return error("git commit 失败: " + commit.stderrTail); // 返回处理结果
                    } // 结束当前代码块
                    ProcessResult rev = runCommandInternal(repo, List.of("git", "rev-parse", "HEAD"), 30); // 执行赋值操作
                    if (rev.exitCode != 0) return error("读取 HEAD 失败: " + rev.stderrTail); // 进行条件判断
                    checkpointId = rev.stdoutTail.trim(); // 执行赋值操作
                } // 结束当前代码块
                default -> { // 开始新的代码块
                    return error("mode 非法，仅支持 commit/stash/tag"); // 返回处理结果
                } // 结束当前代码块
            } // 结束当前代码块

            return success(Map.of("checkpointId", checkpointId, "mode", m, "repoPath", repoPath, "message", message)); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            log.error("git_create_checkpoint 失败", e); // 执行语句逻辑
            return error("git_create_checkpoint 失败: " + e.getMessage()); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS) // 声明注解
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "回滚代码检查点") // 声明注解
    public String gitRollbackCheckpoint( // 定义方法签名
            @RequestParam("repoPath") String repoPath, // 声明注解
            @RequestParam("checkpointId") String checkpointId, // 声明注解
            @RequestParam(value = "mode", required = false) String mode // 声明注解
    ) { // 开始新的代码块
        try { // 尝试执行核心逻辑
            String m = (mode == null || mode.isBlank()) ? "soft" : mode.trim().toLowerCase(Locale.ROOT); // 执行赋值操作
            if (!List.of("hard", "soft", "mixed").contains(m)) { // 进行条件判断
                return error("mode 非法，仅支持 hard/soft/mixed"); // 返回处理结果
            } // 结束当前代码块

            File repo = resolveSafePath(repoPath, true).toFile(); // 执行赋值操作
            ProcessResult pr = runCommandInternal(repo, List.of("git", "reset", "--" + m, checkpointId), 60); // 执行赋值操作
            if (pr.exitCode != 0) { // 进行条件判断
                return error("git rollback 失败: " + pr.stderrTail); // 返回处理结果
            } // 结束当前代码块

            ProcessResult head = runCommandInternal(repo, List.of("git", "rev-parse", "HEAD"), 30); // 执行赋值操作
            String currentHead = head.exitCode == 0 ? head.stdoutTail.trim() : checkpointId; // 执行赋值操作

            return success(Map.of("rolledBack", true, "currentHead", currentHead, "mode", m, "repoPath", repoPath)); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            log.error("git_rollback_checkpoint 失败", e); // 执行语句逻辑
            return error("git_rollback_checkpoint 失败: " + e.getMessage()); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS) // 声明注解
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "搜索符号引用") // 声明注解
    public String searchSymbolReferences( // 定义方法签名
            @RequestParam("repoPath") String repoPath, // 声明注解
            @RequestParam("symbol") String symbol, // 声明注解
            @RequestParam(value = "language", required = false) String language // 声明注解
    ) { // 开始新的代码块
        try { // 尝试执行核心逻辑
            Path root = resolveSafePath(repoPath, true); // 执行赋值操作
            if (!Files.exists(root)) { // 进行条件判断
                return error("repoPath 不存在"); // 返回处理结果
            } // 结束当前代码块

            List<Map<String, Object>> refs = new ArrayList<>(); // 执行赋值操作
            try (Stream<Path> stream = Files.walk(root)) { // 尝试执行核心逻辑
                List<Path> files = stream // 执行赋值操作
                        .filter(Files::isRegularFile) // 执行当前逻辑
                        .filter(p -> !p.toString().contains(File.separator + ".git" + File.separator)) // 执行当前逻辑
                        .toList(); // 执行语句逻辑

                for (Path f : files) { // 执行循环处理
                    if (refs.size() >= 500) break; // 进行条件判断
                    List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8); // 执行赋值操作
                    for (int i = 0; i < lines.size(); i++) { // 执行循环处理
                        String line = lines.get(i); // 执行赋值操作
                        if (line.contains(symbol)) { // 进行条件判断
                            Map<String, Object> one = new LinkedHashMap<>(); // 执行赋值操作
                            one.put("file", root.relativize(f).toString().replace("\\", "/")); // 执行语句逻辑
                            one.put("line", i + 1); // 执行语句逻辑
                            one.put("snippet", trimLine(line, 200)); // 执行语句逻辑
                            refs.add(one); // 执行语句逻辑
                            if (refs.size() >= 500) break; // 进行条件判断
                        } // 结束当前代码块
                    } // 结束当前代码块
                } // 结束当前代码块
            } // 结束当前代码块

            return success(Map.of("references", refs, "symbol", symbol, "repoPath", repoPath, "language", language)); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            log.error("search_symbol_references 失败", e); // 执行语句逻辑
            return error("search_symbol_references 失败: " + e.getMessage()); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    @LunaState(value = LunaStateConstant.VALUE_CODEOPS, status = LunaStateConstant.STATUS_CODEOPS) // 声明注解
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "扫描依赖漏洞") // 声明注解
    public String scanDependencyVulnerabilities( // 定义方法签名
            @RequestParam("repoPath") String repoPath, // 声明注解
            @RequestParam(value = "ecosystem", required = false) String ecosystem, // 声明注解
            @RequestParam(value = "failOnSeverity", required = false) String failOnSeverity // 声明注解
    ) { // 开始新的代码块
        try { // 尝试执行核心逻辑
            Path repo = resolveSafePath(repoPath, true); // 执行赋值操作
            String eco = ecosystem == null ? "" : ecosystem.trim().toLowerCase(Locale.ROOT); // 执行赋值操作

            List<String> cmd = buildScaCommand(repo, eco); // 执行赋值操作
            if (cmd == null || cmd.isEmpty()) { // 进行条件判断
                return error("无法识别项目生态，请传 ecosystem=maven|gradle|npm|pnpm|yarn|python"); // 返回处理结果
            } // 结束当前代码块

            ProcessResult pr = runCommandInternal(repo.toFile(), cmd, 1800); // 执行赋值操作

            List<Path> reportCandidates = findSCAReports(repo, eco); // 执行赋值操作
            Map<String, Object> summary = summarizeScaReports(reportCandidates); // 执行赋值操作

            String normalizedSeverity = normalizeSeverity(failOnSeverity); // 执行赋值操作
            boolean thresholdReached = isThresholdReached(summary, normalizedSeverity); // 执行赋值操作

            Map<String, Object> out = new LinkedHashMap<>(); // 执行赋值操作
            out.put("repoPath", repo.toString()); // 执行语句逻辑
            out.put("ecosystem", detectEcoLabel(repo, eco)); // 执行语句逻辑
            out.put("command", String.join(" ", cmd)); // 执行语句逻辑
            out.put("exitCode", pr.exitCode); // 执行语句逻辑
            out.put("stdoutTail", pr.stdoutTail); // 执行语句逻辑
            out.put("stderrTail", pr.stderrTail); // 执行语句逻辑
            out.put("costMs", pr.costMs); // 执行语句逻辑
            out.put("reportFiles", reportCandidates.stream().map(Path::toString).toList()); // 执行语句逻辑
            out.put("summary", summary); // 执行语句逻辑
            out.put("failOnSeverity", normalizedSeverity); // 执行语句逻辑
            out.put("thresholdReached", thresholdReached); // 执行语句逻辑

            if (pr.exitCode != 0) { // 进行条件判断
                return error("依赖漏洞扫描执行失败，exitCode=" + pr.exitCode + "，stderr=" + pr.stderrTail); // 返回处理结果
            } // 结束当前代码块
            if (thresholdReached) { // 进行条件判断
                return error("依赖漏洞扫描发现达到阈值的风险（" + normalizedSeverity + "及以上）"); // 返回处理结果
            } // 结束当前代码块
            return success(out); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            log.error("scan_dependency_vulnerabilities 失败", e); // 执行语句逻辑
            return error("scan_dependency_vulnerabilities 失败: " + e.getMessage()); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    private String runCommand(String workDir, String command, Integer timeoutSec) { // 定义方法签名
        try { // 尝试执行核心逻辑
            Path wd = resolveSafePath(workDir, true); // 执行赋值操作
            validateCommand(command); // 执行语句逻辑

            ProcessResult pr = runCommandInternal(wd.toFile(), parseCommand(command), timeoutSec == null ? 600 : timeoutSec); // 执行赋值操作
            return success(Map.of( // 返回处理结果
                    "exitCode", pr.exitCode, // 执行当前逻辑
                    "stdoutTail", pr.stdoutTail, // 执行当前逻辑
                    "stderrTail", pr.stderrTail, // 执行当前逻辑
                    "costMs", pr.costMs // 执行当前逻辑
            )); // 执行语句逻辑
        } catch (Exception e) { // 开始新的代码块
            log.error("命令执行失败", e); // 执行语句逻辑
            return error("命令执行失败: " + e.getMessage()); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    private ProcessResult runCommandInternal(File workDir, List<String> cmd, int timeoutSec) throws Exception { // 定义方法签名
        long start = System.currentTimeMillis(); // 执行赋值操作
        ProcessBuilder pb = new ProcessBuilder(cmd); // 执行赋值操作
        pb.directory(workDir); // 执行语句逻辑
        Process p = pb.start(); // 执行赋值操作

        boolean done = p.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS); // 执行赋值操作
        if (!done) { // 进行条件判断
            p.destroyForcibly(); // 执行语句逻辑
            throw new RuntimeException("命令超时"); // 抛出异常信息
        } // 结束当前代码块

        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8); // 执行赋值操作
        String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8); // 执行赋值操作

        return new ProcessResult( // 返回处理结果
                p.exitValue(), // 执行当前逻辑
                tail(stdout, 4000), // 执行当前逻辑
                tail(stderr, 4000), // 执行当前逻辑
                System.currentTimeMillis() - start // 执行当前逻辑
        ); // 执行语句逻辑
    } // 结束当前代码块

    private static List<String> parseCommand(String cmd) { // 定义方法签名
        return Arrays.stream(cmd.trim().split("\\s+")).toList(); // 返回处理结果
    } // 结束当前代码块

    private static String tail(String s, int max) { // 定义方法签名
        if (s == null) return ""; // 进行条件判断
        return s.length() <= max ? s : s.substring(s.length() - max); // 返回处理结果
    } // 结束当前代码块

    private static String trimLine(String s, int max) { // 定义方法签名
        if (s == null) return ""; // 进行条件判断
        String t = s.trim(); // 执行赋值操作
        return t.length() <= max ? t : t.substring(0, max); // 返回处理结果
    } // 结束当前代码块

    private List<String> parseReportDirs(String reportDirs) { // 定义方法签名
        if (reportDirs == null || reportDirs.isBlank()) { // 进行条件判断
            return List.of(); // 返回处理结果
        } // 结束当前代码块
        String txt = reportDirs.trim(); // 执行赋值操作
        try { // 尝试执行核心逻辑
            if (txt.startsWith("[")) { // 进行条件判断
                JsonNode node = objectMapper.readTree(txt); // 执行赋值操作
                if (node.isArray()) { // 进行条件判断
                    List<String> out = new ArrayList<>(); // 执行赋值操作
                    for (JsonNode n : node) { // 执行循环处理
                        out.add(n.asText()); // 执行语句逻辑
                    } // 结束当前代码块
                    return out; // 返回处理结果
                } // 结束当前代码块
            } // 结束当前代码块
        } catch (Exception ignore) { // 开始新的代码块
        } // 结束当前代码块
        return Arrays.stream(txt.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList(); // 返回处理结果
    } // 结束当前代码块

    private static int parseIntAttr(org.w3c.dom.Element el, String attr) { // 定义方法签名
        try { // 尝试执行核心逻辑
            String v = el.getAttribute(attr); // 执行赋值操作
            if (v == null || v.isBlank()) return 0; // 进行条件判断
            return Integer.parseInt(v); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            return 0; // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    private void validateCommand(String command) { // 定义方法签名
        if (command == null || command.isBlank()) { // 进行条件判断
            throw new IllegalArgumentException("command 不能为空"); // 抛出异常信息
        } // 结束当前代码块
        String trimmed = command.trim(); // 执行赋值操作
        for (String token : DANGEROUS_TOKENS) { // 执行循环处理
            if (trimmed.contains(token)) { // 进行条件判断
                throw new IllegalArgumentException("命令包含危险符号: " + token); // 抛出异常信息
            } // 结束当前代码块
        } // 结束当前代码块
        List<String> parts = parseCommand(trimmed); // 执行赋值操作
        if (parts.isEmpty()) { // 进行条件判断
            throw new IllegalArgumentException("command 不能为空"); // 抛出异常信息
        } // 结束当前代码块
        String head = parts.get(0).toLowerCase(Locale.ROOT); // 执行赋值操作
        if (!CMD_HEAD_WHITELIST.contains(head)) { // 进行条件判断
            throw new IllegalArgumentException("命令不在白名单: " + head); // 抛出异常信息
        } // 结束当前代码块
    } // 结束当前代码块

    private Path resolveSafePath(String inputPath, boolean mustDirectory) throws Exception { // 定义方法签名
        if (inputPath == null || inputPath.isBlank()) { // 进行条件判断
            throw new IllegalArgumentException("路径不能为空"); // 抛出异常信息
        } // 结束当前代码块

        Path target = Paths.get(inputPath).toAbsolutePath().normalize(); // 执行赋值操作

        if (workspaceRoot != null && !workspaceRoot.isBlank()) { // 进行条件判断
            Path root = Paths.get(workspaceRoot).toAbsolutePath().normalize(); // 执行赋值操作
            if (!target.startsWith(root)) { // 进行条件判断
                throw new SecurityException("路径越界，不在受控工作区内"); // 抛出异常信息
            } // 结束当前代码块
        } // 结束当前代码块

        if (mustDirectory && (!Files.exists(target) || !Files.isDirectory(target))) { // 进行条件判断
            throw new IllegalArgumentException("目录不存在: " + target); // 抛出异常信息
        } // 结束当前代码块
        return target; // 返回处理结果
    } // 结束当前代码块

    private List<String> buildScaCommand(Path repo, String ecosystem) { // 定义方法签名
        String eco = detectEcoLabel(repo, ecosystem); // 执行赋值操作
        if ("maven".equals(eco)) { // 进行条件判断
            return List.of( // 返回处理结果
                    "mvn", // 执行当前逻辑
                    "-B", // 执行当前逻辑
                    "-DskipTests", // 执行当前逻辑
                    "org.owasp:dependency-check-maven:check" // 执行当前逻辑
            ); // 执行语句逻辑
        } // 结束当前代码块
        if ("gradle".equals(eco)) { // 进行条件判断
            return List.of( // 返回处理结果
                    "gradle", // 执行当前逻辑
                    "dependencyCheckAnalyze", // 执行当前逻辑
                    "--no-daemon" // 执行当前逻辑
            ); // 执行语句逻辑
        } // 结束当前代码块
        if ("npm".equals(eco) || "pnpm".equals(eco) || "yarn".equals(eco)) { // 进行条件判断
            return List.of(eco, "audit", "--json"); // 返回处理结果
        } // 结束当前代码块
        if ("python".equals(eco)) { // 进行条件判断
            return List.of("pip-audit", "-f", "json"); // 返回处理结果
        } // 结束当前代码块
        return null; // 返回处理结果
    } // 结束当前代码块

    private String detectEcoLabel(Path repo, String requestedEco) { // 定义方法签名
        if (requestedEco != null && !requestedEco.isBlank()) { // 进行条件判断
            return requestedEco.trim().toLowerCase(Locale.ROOT); // 返回处理结果
        } // 结束当前代码块
        if (Files.exists(repo.resolve("pom.xml"))) return "maven"; // 进行条件判断
        if (Files.exists(repo.resolve("build.gradle")) || Files.exists(repo.resolve("build.gradle.kts"))) return "gradle"; // 进行条件判断
        if (Files.exists(repo.resolve("package-lock.json")) || Files.exists(repo.resolve("package.json"))) return "npm"; // 进行条件判断
        if (Files.exists(repo.resolve("pnpm-lock.yaml"))) return "pnpm"; // 进行条件判断
        if (Files.exists(repo.resolve("yarn.lock"))) return "yarn"; // 进行条件判断
        if (Files.exists(repo.resolve("requirements.txt")) || Files.exists(repo.resolve("pyproject.toml"))) return "python"; // 进行条件判断
        return ""; // 返回处理结果
    } // 结束当前代码块

    private List<Path> findSCAReports(Path repo, String eco) { // 定义方法签名
        List<Path> reports = new ArrayList<>(); // 执行赋值操作
        try { // 尝试执行核心逻辑
            String actualEco = detectEcoLabel(repo, eco); // 执行赋值操作
            if ("maven".equals(actualEco) || "gradle".equals(actualEco)) { // 进行条件判断
                Path targetReport = repo.resolve("target").resolve("dependency-check-report.json"); // 执行赋值操作
                if (Files.exists(targetReport)) reports.add(targetReport); // 进行条件判断
                Path buildReport = repo.resolve("build").resolve("reports").resolve("dependency-check-report.json"); // 执行赋值操作
                if (Files.exists(buildReport)) reports.add(buildReport); // 进行条件判断
            } else if ("npm".equals(actualEco) || "pnpm".equals(actualEco) || "yarn".equals(actualEco)) { // 切换到分支逻辑
                Path npmAudit = repo.resolve("npm-audit.json"); // 执行赋值操作
                if (Files.exists(npmAudit)) reports.add(npmAudit); // 进行条件判断
            } else if ("python".equals(actualEco)) { // 切换到分支逻辑
                Path pipAudit = repo.resolve("pip-audit-report.json"); // 执行赋值操作
                if (Files.exists(pipAudit)) reports.add(pipAudit); // 进行条件判断
            } // 结束当前代码块
        } catch (Exception ignored) { // 开始新的代码块
        } // 结束当前代码块
        return reports; // 返回处理结果
    } // 结束当前代码块

    private Map<String, Object> summarizeScaReports(List<Path> reportFiles) { // 定义方法签名
        Map<String, Object> summary = new LinkedHashMap<>(); // 执行赋值操作
        summary.put("critical", 0); // 执行语句逻辑
        summary.put("high", 0); // 执行语句逻辑
        summary.put("medium", 0); // 执行语句逻辑
        summary.put("low", 0); // 执行语句逻辑
        summary.put("unknown", 0); // 执行语句逻辑
        summary.put("dependencies", 0); // 执行语句逻辑
        summary.put("vulnerabilities", 0); // 执行语句逻辑

        int critical = 0, high = 0, medium = 0, low = 0, unknown = 0, deps = 0; // 执行赋值操作

        for (Path report : reportFiles) { // 执行循环处理
            try { // 尝试执行核心逻辑
                JsonNode root = objectMapper.readTree(Files.readString(report, StandardCharsets.UTF_8)); // 执行赋值操作
                if (root.has("dependencies") && root.get("dependencies").isArray()) { // 进行条件判断
                    for (JsonNode dep : root.get("dependencies")) { // 执行循环处理
                        deps++; // 执行语句逻辑
                        JsonNode vulns = dep.get("vulnerabilities"); // 执行赋值操作
                        if (vulns != null && vulns.isArray()) { // 进行条件判断
                            for (JsonNode v : vulns) { // 执行循环处理
                                String severity = v.path("severity").asText("").toUpperCase(Locale.ROOT); // 执行赋值操作
                                switch (severity) { // 进行分支选择
                                    case "CRITICAL" -> critical++; // 命中分支条件
                                    case "HIGH" -> high++; // 命中分支条件
                                    case "MEDIUM" -> medium++; // 命中分支条件
                                    case "LOW" -> low++; // 命中分支条件
                                    default -> unknown++; // 执行语句逻辑
                                } // 结束当前代码块
                            } // 结束当前代码块
                        } // 结束当前代码块
                    } // 结束当前代码块
                } // 结束当前代码块
            } catch (Exception e) { // 开始新的代码块
                log.warn("解析漏洞报告失败: {}, err={}", report, e.getMessage()); // 执行赋值操作
            } // 结束当前代码块
        } // 结束当前代码块

        summary.put("critical", critical); // 执行语句逻辑
        summary.put("high", high); // 执行语句逻辑
        summary.put("medium", medium); // 执行语句逻辑
        summary.put("low", low); // 执行语句逻辑
        summary.put("unknown", unknown); // 执行语句逻辑
        summary.put("dependencies", deps); // 执行语句逻辑
        summary.put("vulnerabilities", critical + high + medium + low + unknown); // 执行语句逻辑
        return summary; // 返回处理结果
    } // 结束当前代码块

    private String normalizeSeverity(String severity) { // 定义方法签名
        if (severity == null || severity.isBlank()) return "HIGH"; // 进行条件判断
        String s = severity.trim().toUpperCase(Locale.ROOT); // 执行赋值操作
        if (!Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW").contains(s)) return "HIGH"; // 进行条件判断
        return s; // 返回处理结果
    } // 结束当前代码块

    private boolean isThresholdReached(Map<String, Object> summary, String severity) { // 定义方法签名
        int critical = intOf(summary.get("critical")); // 执行赋值操作
        int high = intOf(summary.get("high")); // 执行赋值操作
        int medium = intOf(summary.get("medium")); // 执行赋值操作
        int low = intOf(summary.get("low")); // 执行赋值操作

        return switch (severity) { // 返回处理结果
            case "CRITICAL" -> critical > 0; // 命中分支条件
            case "HIGH" -> critical + high > 0; // 命中分支条件
            case "MEDIUM" -> critical + high + medium > 0; // 命中分支条件
            case "LOW" -> critical + high + medium + low > 0; // 命中分支条件
            default -> critical + high > 0; // 执行语句逻辑
        }; // 执行语句逻辑
    } // 结束当前代码块

    private int intOf(Object v) { // 定义方法签名
        if (v == null) return 0; // 进行条件判断
        if (v instanceof Number n) return n.intValue(); // 进行条件判断
        try { // 尝试执行核心逻辑
            return Integer.parseInt(String.valueOf(v)); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            return 0; // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    private record ProcessResult(int exitCode, String stdoutTail, String stderrTail, long costMs) { // 定义方法签名
    } // 结束当前代码块
} // 结束当前代码块
