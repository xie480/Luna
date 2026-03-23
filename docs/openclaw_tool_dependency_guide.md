# OpenClaw Tool 外部依赖说明（系统环境依赖项）

## 1. 目的

本文档说明当前暂不在代码内直接实现、或需要额外系统环境依赖的 Tool，包括：

- 需要什么外部依赖
- 怎么安装
- 如何与当前 Luna 工程对接
- 何时可将文档中的“占位/未启用”状态切换为“已实现”

---

## 2. 涉及 Tool 清单

以下 Tool 属于“系统环境依赖”：

1. `scan_dependency_vulnerabilities`（SCA 漏洞扫描）
2. `capture_desktop_screenshot`（桌面截图）
3. `detect_ui_elements`（UI 元素识别）

> 说明：其余占位 Tool（如锁、patch、git、test report、symbol 引用）已在 Java 侧完成可运行实现，不在本文件范围内。

---

## 3. scan_dependency_vulnerabilities（SCA）

## 3.1 依赖项

至少需要安装一款 SCA 工具，建议优先顺序：

1. `osv-scanner`（推荐）
2. `trivy`
3. `dependency-check`（Java 生态常见）

## 3.2 安装示例

### A) OSV-Scanner
- 官方仓库安装二进制，确保命令 `osv-scanner` 可在 PATH 中执行。

### B) Trivy
- 安装后确保 `trivy` 在 PATH 可执行。

### C) OWASP Dependency-Check
- 安装 CLI 并准备 NVD 数据更新策略。

## 3.3 对接建议

在 `CodeOpsTools.scanDependencyVulnerabilities` 中按 ecosystem 自动分流：

- maven：`osv-scanner --lockfile=...` 或 `trivy fs`
- npm：扫描 `package-lock.json`
- pip：扫描 `requirements.txt` / `poetry.lock`

并统一返回：

```json
{
  "status": "success",
  "data": {
    "vulnCount": 5,
    "bySeverity": {"LOW":1,"MEDIUM":2,"HIGH":2},
    "itemsSample": []
  }
}
```

---

## 4. capture_desktop_screenshot（桌面截图）

## 4.1 依赖项

- 运行环境需支持 AWT（非 headless）
- Linux 服务器场景通常需要虚拟显示（Xvfb）或真实桌面会话

## 4.2 运行前检查

Java 启动时检查：

- `GraphicsEnvironment.isHeadless() == false`
- 至少存在一个可用屏幕设备

## 4.3 对接建议

实现步骤：

1. 使用 `java.awt.Robot` 截图
2. 支持 `monitorIndex` 与 `region` 裁剪
3. 图片落盘到 `./data/screenshots/`
4. 返回 `imagePath/width/height`

失败时返回明确错误：

- `DESKTOP_NOT_SUPPORTED`
- `INVALID_MONITOR_INDEX`
- `SCREENSHOT_FAILED`

---

## 5. detect_ui_elements（UI识别）

## 5.1 依赖项（可选方案）

方案A（OCR）：
- Tesseract OCR 引擎
- Java 封装可用 Tess4J

方案B（系统控件树）：
- Windows UIAutomation（需 JNI 或外部桥接）
- Linux AT-SPI（实现复杂）

方案C（混合）：
- OCR + 坐标启发式规则

## 5.2 推荐落地路径

第一阶段先做 OCR：

1. 输入 `imagePath`
2. 调 OCR 引擎输出文本块及坐标
3. 规范化输出 `elements[]`

第二阶段再接入 UIAutomation，提升控件语义识别。

---

## 6. 配置建议（application.yaml）

建议新增配置段：

```yaml
openclaw:
  deps:
    sca:
      enabled: false
      provider: "osv"
    desktop:
      screenshot-enabled: false
      ui-detect-enabled: false
```

当环境依赖就绪后，切换为 `true` 并完成对应 Tool 开关逻辑。

---

## 7. 验收标准

各 Tool 从“未启用”切换“已实现”的标准：

1. 有真实外部依赖检查（启动或调用时）
2. 有可执行主流程（非占位）
3. 有稳定错误码和错误信息
4. 有最小集成测试（至少 1 条成功 + 1 条失败）
5. 文档状态同步更新到 `docs/openclaw_skill_tool_gap_analysis.md`

