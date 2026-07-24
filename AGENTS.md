# AI Coding 规范 — AGENTS.md

> 本文件是 Codex / Claude Code / Cursor / Copilot 等 AI 编程代理的统一工作手册。
> 所有 AI agent 在执行任务前必须阅读并严格遵守本规范。
> 本文件只约束行为方式，不修改业务代码本身。

---

## 1. 项目概况

| 属性 | 值 |
|------|------|
| 项目名称 | `dg-tools` (AI-powered Requirement Document Quality Checker) |
| 技术栈 | Java 17, Spring Boot 3.3.5, Apache POI 5.3.0, PDFBox 3.0.2, Tika 2.9.2 |
| 构建工具 | Maven |
| 包名 | `com.dg.tools.extractor` |
| 文档根目录 | `E:\silf\dg_tools` |
| 主规格文档 | `SPEC.md` (v6) |

### 核心功能
- **三阶段提取架构**: UNPACK → TRIAGE+IMAGE → PARSE
- **多格式解析**: .docx/.doc/.xlsx/.xls/.pdf/.zip/图片
- **嵌套递归**: 支持文档中嵌入文档的无限层级展开
- **结构化输出**: body.md + chunks/ + data/ + media/ + manifest.json
- **大表处理**: CSV 切片、合并单元格填充、多区域自动切分

### 源码结构
```
src/main/java/com/dg/tools/extractor/
├── model/           # 数据模型 (Element, ExtractionResult, UnpackResult, etc.)
├── handler/         # 文档处理器接口及实现
│   ├── DocumentHandler.java  (接口)
│   ├── DocxHandler.java
│   ├── DocHandler.java
│   ├── ExcelHandler.java
│   ├── PdfHandler.java
│   ├── ZipHandler.java
│   └── ImageHandler.java
├── excel/           # Excel 专项工具
│   ├── MergeCellResolver.java
│   ├── RegionSplitter.java
│   └── CsvSlicer.java
├── RecursiveExtractor.java    (Phase 1/2 统一入口)
├── TypeDetector.java          (Tika MIME 探测)
├── OleExtractor.java          (OLE2 二进制解包)
└── StoreWriter.java           (产物落盘)
```

---

## 2. 核心开发原则

### 2.1 SPEC 优先原则（最高优先级）
- **任何代码变更必须与 `SPEC.md` 保持一致**。
- 新增功能以 SPEC 中标记为"缺失"/"预留"的功能为第一优先级（如 TriageProcessor、ImageDescriber、CSV按需加载规则链）。
- 修改现有逻辑时，先确认 SPEC 对应章节的要求，再实施修改。
- 如果代码与 SPEC 存在不一致：
  - 若 SPEC 正确 → 修改代码
  - 若 SPEC 已过时 → 先更新 SPEC，再修改代码
  - 两者都存疑 → 询问用户，不可自行决定

### 2.2 增量修改原则
- **永远不要一次性重写大文件**。拆分修改为可验证的小步骤。
- 每次 PR/提交应该是一个独立的、可编译通过的最小功能单元。
- 修改导入/导出的 API 时，同步更新所有调用方和测试用例。

### 2.3 内存安全原则（针对 Phase 1）
- 本项目专门设计了**内存隔离**架构——每次只 hold 一个文件在内存中。
- 实现新功能时，绝不允许引入一次性加载整个文档到内存的反模式。
- ZIP 解压使用流式读取 → DOCX 解析使用 OPCPackage stream → 避免 DOM 级别的整包加载。
- `MAX_FILE_SIZE = 200MB`, `MAX_IMAGE_BYTES = 100MB`, `ZipHandler MAX_TOTAL_SIZE = 500MB`。

### 2.4 向后兼容原则
- Java 17 是唯一目标版本，不使用 21+ 特性（Record密封类、Pattern Matching for switch 高级用法、虚拟线程等）。
- 依赖版本锁定：POI 5.3.0 / PDFBox 3.0.2 / Tika 2.9.2，升级需评估兼容性影响。
- Handler 接口 `DocumentHandler` 是公共 API，新增方法必须加 default 实现。

---

## 3. 编码规范

### 3.1 命名约定
| 类型 | 规则 | 示例 |
|------|------|------|
| 类名 | PascalCase | `DocxHandler`, `MergeCellResolver` |
| 接口名 | PascalCase (形容词性最佳) | `DocumentHandler` |
| 方法名 | camelCase (动宾结构) | `extract()`, `unpack()`, `split()` |
| 常量 | UPPER_SNAKE_CASE | `CHUNK_SIZE`, `MAX_DEPTH` |
| 私有字段 | camelCase (无下划线前缀) | `session`, `handlers` |
| 变量 | camelCase (简短有含义) | `pos`, `i`, `row` |
| 包名 | 全小写点分隔 | `com.dg.tools.extractor.handler` |

### 3.2 注释规范
- **类级 Javadoc 必须包含**：职责描述、关键设计决策说明、与 SPEC 章节的关联。
- **方法级 Javadoc 至少包含**：参数说明、返回值说明、异常说明。
- **行内注释解释"为什么"而非"是什么"**——代码能表达的不要写注释。
- 中文注释允许且推荐（本项目为中文项目），但关键 API 注解使用英文。

### 3.3 错误处理
- **解析层不吞异常**：Handler 遇到无法解析的内容时，记录到 `ExtractionResult.errors` 后继续处理，不应直接抛 `RuntimeException`（除非输入为 null 等编程错误）。
- **阶段容错**：Phase 1 单个文件处理失败 → 记录错误 → 跳过该文件 → 继续处理队列剩余项。
- **使用自定义异常**（未来）：为不同类型错误创建层次化异常体系（`ParseException`, `UnpackException`, `SecurityException`）。

### 3.4 并发安全
- `RecursiveExtractor` 使用 `Session` 内部类隔离每次调用的可变状态。
- 静态字段只能是不可变常量或线程安全的共享对象（如 `ObjectMapper`）。
- 多线程场景下：Handler 必须是函数式的（无状态），所有状态封装在 `ExtractionResult` 中。

---

## 4. 文件修改边界规则

### 4.1 可修改的文件（核心业务代码）
- `src/main/java/com/dg/tools/extractor/` — 所有已存在的源文件
- `src/test/java/com/dg/tools/extractor/` — 测试文件
- `pom.xml` — 依赖配置（需说明理由和影响）
- `SPEC.md` — 规格文档（仅在与实际业务需求不符时可更新）

### 4.2 建议但非强制的文件（文档/配置）
- `AGENTS.md`（本文件）— AI coding 规范，可迭代更新
- `.cursorrules` — Cursor 专用规则（可选）
- `.vscode/copilot-instructions.md` — Copilot 指令（可选）

### 4.3 绝对禁止的行为
- ~~修改 `.gitignore` 排除 `target/`、`.class` 文件~~（这是合理做法，见下方）
- 删除已存在的源码文件（仅当经用户明确同意时才可删除）
- 在未理解 SPEC 的情况下自行假设 API 行为
- 创建新的根包或破坏现有的包结构

---

## 5. 任务执行流程

### 5.1 标准开发流程（任何功能开发必须遵循）

```
Step 1: 阅读 SPEC 对应章节 ← 理解需求和期望
Step 2: 定位相关代码       ← 查看当前实现
Step 3: 差异分析           ← 列出 gap
Step 4: 设计方案           ← 给出修改方案，等待用户确认
Step 5: 逐步实现           ← 小步提交，每步可验证
Step 6: 运行测试           ← mvn test 必须通过
Step 7: 回归检查          ← 确认没有影响其他模块
```

### 5.2 新增 Handler 流程
当需要添加新文件格式支持时：
1. 在 `DocumentHandler` 接口确认签名（不可变）
2. 新建 `XxxHandler.java` 实现 `DocumentHandler`
3. 实现 `supports(String)` — 匹配扩展名
4. 实现 `extract(InputStream, String)` — Phase 2 解析
5. 实现 `unpack(InputStream, String)` — Phase 1 拆包（默认实现可用）
6. 在 `RecursiveExtractor` 构造器的 handlers 列表中注册
7. 编写单元测试
8. 更新 SPEC §9 路由表和 §11 模块列表

### 5.3 修复 Bug 流程
1. 阅读 SPEC 对应章节，确认期望行为
2. 复现 bug，写最小测试用例
3. 定位根因，提出修复方案
4. 实施修复 + 补充测试
5. 运行完整测试套件 `mvn test`
6. 确认无回归

---

## 6. 测试规范

### 6.1 测试框架
- JUnit 5 (`org.junit.jupiter`)
- AssertJ (`org.assertj.core`)
- Jacoco 覆盖率插件已配置

### 6.2 测试要求
- **每个新增/修改的方法必须有对应的测试**。
- 测试类命名为 `<被测试类>Test.java` 或 `<被测试类>ExtraTest.java`（当主测试已饱和时）。
- 测试覆盖：正常路径 + 边界条件 + 异常路径。
- **集成测试**使用 `TestFileFactory` 构造真实文档内容进行端到端测试。
- 禁止使用 Mockito 做过度 mock —— Handler 解析涉及 POI/PDFBox 真实 API，mock 无意义。

### 6.3 运行测试命令
```powershell
mvn clean test                    # 完整测试
mvn test -Dtest=DocxHandlerTest   # 单类测试
mvn test -Dtest=ExcelHandler*     # 通配符
mvn verify                        # 测试 + 覆盖率报告
```

---

## 7. 常见陷阱与注意事项

### 7.1 ExcelHandler 特别注意
- `MergeCellResolver.readRows()` 返回**矩形化**的二维列表，后续所有操作基于此。
- `RegionSplitter.split()` 按列填充率切分，空列阈值 ≥2。
- 大小表分流阈值 `LARGE_TABLE_THRESHOLD = 50` 行，列数阈值 `10` 列。
- CSV 切片触发阈值 `CsvSlicer.SLICE_THRESHOLD = 500`，每块 `CHUNK_SIZE = 100`。

### 7.2 DocxHandler OLE 处理
- OLE 对象的提取通过 `OleExtractor` 二次解析，不是直接嵌入。
- `unpackOleEmbeddings` 和 `extractOleEmbeddings` 分别处理 Phase 1 和 Phase 2。
- 如果 OLE 无法解包，保留原始字节作为 fallback。

### 7.3 图片处理
- 图片在 body.md 中仅以 `data_ref` 或 `image` 引用存在，**不内联 base64**。
- 图片原始字节写入 `media/` 目录。
- Phase 1.5 预留了视觉大模型描述回填接口（目前未实现）。

### 7.4 manifest.json 结构
- Phase 1 后: `"status": "unpacked"`
- Phase 2 后: `"status": "done"` + 回填 element_count / image_count 等
- Filtered 状态: `"status": "filtered"` + `filter_reason` + `filter_confidence`
- 父级引用: `"parent": "目录名 pos=N"`

---

## 8. 未来实现路线图（按 SPEC v6）

优先级按 SPEC 缺失程度排序：

| 优先级 | 功能模块 | SPEC 章节 | 备注 |
|--------|---------|-----------|------|
| P0 | `TriageProcessor` (LLM快判) | §3 Phase 1.5 | 核心差异化能力 |
| P0 | `ImageDescriber` (视觉模型) | §3 Phase 1.5 | 图文回填 |
| P1 | CSV 按需加载规则链 | §8.4 | AI检核层 |
| P1 | 文档级优先检索 | §8.1 | AI检核层 |
| P1 | 系统层自动导航 | §8.2 | AI检核层 |
| P2 | PDF Y坐标排序 | §12 v6变更 | 当前仅按页面顺序 |
| P2 | 删除线标记 ~~~...~~~ 处理 | §12 v6变更 | 文本清洗 |
| P3 | MemRay 内存分析工具集成 | §7 | 可选优化 |
| P3 | MapReduce AI检核架构 | §5.2 | 可选优化 |

---

## 9. Agent 自检清单

在执行任何代码变更前，Agent 必须自检：

```
□ 我已阅读 SPEC.md 吗？
□ 我理解这个修改影响哪些模块吗？
□ 修改是增量式的（不是一次性重写）吗？
□ 新代码符合现有命名约定吗？
□ 是否需要修改测试用例？
□ 测试能通过吗？(mvn test)
□ 会影响 Handler 接口契约吗？
□ 是否遵守了内存隔离原则？
□ 是否更新了相关的注释/Javadoc？
□ 是否有遗漏的 import 或依赖？
```

---

## 10. 沟通协议

1. **先说结论，再说细节**：回答用户问题时，先给明确的判断（✅/❌/⚠️），再解释原因。
2. **代码变更附带影响分析**：每次提供代码变更时，说明影响范围。
3. **不确定时问，不要猜**：如果 SPEC 和用户描述矛盾，或者信息不足，先询问。
4. **使用本中文**：用户用中文提问时，全程中文回复。

---

*最后更新: 2026-07-24*
*版本: 1.0*

---

## 11. Prompt 质量要求

每个编码任务必须使用结构化的 Prompt。模糊的 prompt 是导致 AI Agent 行为失控的首要原因。

### 最低要求
每个任务 Prompt 至少包含以下字段：
- **Goal**: 要实现什么 / 期望行为
- **Context**: 当前行为 / 涉及文件
- **Scope**: 可以改什么 / 不能碰什么
- **Verification**: 如何验证（mvn test -Dtest=...）
- **Risk**: 低/中/高风险等级

### 参考模板
详细模板请见 [.agents/ai-coding-task-template.md](./.agents/ai-coding-task-template.md)

### 正反示例
好的 vs 差的 Prompt 示例见 [.agents/examples/good-vs-bad-prompts.md](./.agents/examples/good-vs-bad-prompts.md)

---

## 12. Skill 工作流

本项目定义了四类 Agent 工作流 Skill，用于特定场景下的标准化操作：

| Skill | 触发场景 | 路径 |
|-------|---------|------|
| cp-bug-fix | 修复 bug、调查错误、调试崩溃 | .agents/skills/acp-bug-fix/SKILL.md |
| cp-feature-add | 新增功能、新增 Handler、新增 Phase | .agents/skills/acp-feature-add/SKILL.md |
| cp-code-review | Review PR、审查 Diff、审计 AI 代码 | .agents/skills/acp-code-review/SKILL.md |
| cp-refactor | 重构、清理、简化、模块化 | .agents/skills/acp-refactor/SKILL.md |

使用时，Agent 应严格遵循对应 Skill 中的 Workflow 步骤。

---

## 13. 文件索引

| 文件 | 用途 |
|------|------|
| AGENTS.md (本文件) | 总行为规范 |
| SPEC.md | 需求规格（最高优先级） |
| .cursorrules | Cursor IDE 专用指令 |
| .vscode/copilot-instructions.md | Copilot 专用指令 |
| .agents/ai-coding-quick-reference.md | 速查卡（打印用） |
| .agents/ai-coding-task-template.md | 任务 Prompt 模板 |
| .agents/checklists/pre-code-review.md | 编码前检查清单 |
| .agents/checklists/pre-commit.md | 提交前检查清单 |
| .agents/checklists/high-risk-change.md | 高风险变更检查清单 |
| .agents/examples/good-vs-bad-prompts.md | Prompt 正反示例 |
| .agents/skills/acp-bug-fix/SKILL.md | Bug Fix 工作流 Skill |
| .agents/skills/acp-feature-add/SKILL.md | Feature Add 工作流 Skill |
| .agents/skills/acp-code-review/SKILL.md | Code Review 工作流 Skill |
| .agents/skills/acp-refactor/SKILL.md | Refactor 工作流 Skill |

---

*最后更新: 2026-07-24*
*版本: 2.0*
