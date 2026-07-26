# AI Coding 规范 — AGENTS.md

> 本文件是 Codex / Claude Code / Cursor / Copilot 等 AI 编程代理的统一工作手册。
> 所有 AI agent 在执行任务前必须阅读并严格遵守本规范。
> 本文件只约束行为方式，不修改业务代码本身。

---

## 1. 项目概况

| 属性 | 值 |
|------|------|
| 项目名称 | `dg-tools` (AI-powered Requirement Document Quality Checker) |
| 技术栈 | Java 17, Spring Boot 3.3.5, Apache POI 5.3.0, PDFBox 3.0.2, Tika 2.9.2, **Spring AI Alibaba** |
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
├── model/           # 数据模型 (Element, ExtractionResult, EmbeddedFile, etc.)
├── handler/         # 文档处理器接口及实现
│   ├── AbstractHandler.java  (抽象基类)
│   ├── DocxHandler.java      (.docx/.docm — OOXML)
│   ├── DocHandler.java       (.doc — OLE2/HWPF，含表格/图片/标题/OLE)
│   ├── ExcelHandler.java     (.xlsx/.xls — 合并填充/多区域切分/大小表分流)
│   ├── PdfHandler.java       (.pdf — Y坐标图文混排)
│   ├── ZipHandler.java       (.zip — 递归解包+安全防护)
│   └── ImageHandler.java     (图片叶子节点)
├── excel/           # Excel 专项工具
│   ├── MergeCellResolver.java  (合并单元格向下+向右填充)
│   ├── RegionSplitter.java     (列填充率多区域自动切分)
│   └── CsvSlicer.java          (CSV切片，100行/chunk)
├── triage/          # Phase 1.5 文档过滤（可插拔接口）
│   ├── TriageProcessor.java    (接口)
│   ├── NoOpTriageProcessor.java (默认空实现)
│   └── RelevanceAssessment.java (模型)
├── image/           # Phase 1.5 图片描述（可插拔接口）
│   ├── ImageDescriber.java     (接口)
│   ├── NoOpImageDescriber.java  (默认空实现)
│   └── ImageDescription.java   (模型)
├── RecursiveExtractor.java    (Phase 1/2/1.5 三阶段统一编排层)
├── ExtractPipeline.java       (Spring 集成入口，委托 RecursiveExtractor)
├── TypeDetector.java          (Tika MIME 探测)
├── OleExtractor.java          (OLE2 二进制解包，完整 Ole10Native 格式)
├── StoreWriter.java           (产物落盘 + 标题感知分块)
└── util/
    └── StringUtils.java       (文件名清洗/CSV转义/字节读取)
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

### 3.5 Spring AI Alibaba 专项规范

本项目在 Phase 1.5（TRIAGE + IMAGE）中使用 **Spring AI Alibaba** 作为 LLM/视觉大模型的统一调用框架。所有 AI 相关的代码开发必须遵循以下规范：

#### 3.5.1 核心依赖与版本管理

- Spring AI Alibaba 的依赖版本变更必须经用户确认，不可自行升级。
- pom.xml 中新增 AI 相关依赖需注明对应 SPEC 章节（§5 TRIAGE / §6 IMAGE / §8 AI 检核层）。
- 禁止私自引入其他 LLM SDK（如 openai-java、anthropic-sdk），统一由 Spring AI Alibaba 抽象。

#### 3.5.2 ChatModel / ImageModel 使用规范

- **ChatModel**（LLM 快判 / AI 检核层对话）：
  - 通过 @Bean 或 @Autowired 注入，不在 Handler 内部直接 new。
  - Prompt 模板应外部化（支持配置切换），不可硬编码在 Java 字符串中。
  - 所有 API Key / Endpoint 必须从环境变量或配置中心读取，禁止硬编码。

- **ImageModel**（视觉理解 / 图片描述）：
  - 输入原始字节数组（InputStream），输出结构化 JSON（标题 + 描述），写入 media/{img}.json。
  - 必须设置超时和重试策略，避免视觉模型超时导致整个 Phase 1.5 阻塞。
  - 图片描述结果写失败的，记录到 ExtractionResult.errors 并标记 status: filtered。

#### 3.5.3 Tool / Function Callback 规范

- AI 检核层需要的工具调用（如 CSV 按需加载、文档级优先检索）通过 Spring AI FunctionCallback 注册。
- 每个 Tool 必须实现幂等性——多次调用不会产生副作用（因为 AI 可能重试）。
- Tool 的参数命名应与 SPEC 中的数据模型一致（Element / data_ref / chunk_NNNN）。

#### 3.5.4 Token 预算与上下文窗口管理

- AI 检核层的 prompt 构建必须考虑 token 预算：
  - 完整 body.md ≤ 300 元素时，直接全量注入。
  - 超过 300 元素时，按优先级采样 + chunks 增量注入。
- 不得一次性将整个 manifest.json 或所有 CSV 内容放入 prompt。
- 超出预算时必须降级为「规则链匹配」而非继续调用 LLM。

#### 3.5.5 错误处理与容错

- LLM 调用失败不应阻断整个提取流程（符合"阶段容错"原则）：
  - TRIAGE 调用失败 → 文件标记为 
elevant（保守放行）。
  - IMAGE 调用失败 → 保留原图不删除，标记 ``filtered`` 并记录原因。
  - AI 检核层调用失败 → 回退到规则链检核。
- 所有 AI 调用的错误信息必须记录日志（SLF4J），不可静默吞掉。

#### 3.5.6 内存安全

- Spring AI 的 Response<AiMessage> 和 GenerateResponse 对象只持有一次调用的结果。
- 不得将大量 AI 返回内容缓存为静态字段。
- AI 检核层的上下文窗口按 session 隔离，每轮独立分配。
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

## 8. 未来实现路线图（按 SPEC v7）

优先级按 SPEC 缺失程度排序：

| 优先级 | 功能模块 | SPEC 章节 | 备注 |
|--------|---------|-----------|------|
| P0 | TriageProcessor 接入真实 LLM | §3 Phase 1.5 | 接口已定义，需实现 LLM 调用 |
| P0 | ImageDescriber 接入视觉模型 | §3 Phase 1.5 | 接口已定义，需实现视觉模型调用 |
| P1 | CSV 按需加载规则链 | §8.4 | AI检核层 |
| P1 | 文档级优先检索 | §8.1 | AI检核层 |
| P1 | 系统层自动导航 | §8.2 | AI检核层 |
| P2 | 用户需求相关性粗评（LLM 打分） | §5.1 | 切分后先评再提取嵌套文件 |
| P3 | 按 MIME 类型路由 | §9 | 当前按扩展名路由，TypeDetector 未用于路由 |
| P3 | MemRay 内存分析工具集成 | §7 | 可选优化 |

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
| `acp-bug-fix` | 修复 bug、调查错误、调试崩溃 | .agents/skills/acp-bug-fix/SKILL.md |
| `acp-feature-add` | 新增功能、新增 Handler、新增 Phase | .agents/skills/acp-feature-add/SKILL.md |
| `acp-code-review` | Review PR、审查 Diff、审计 AI 代码 | .agents/skills/acp-code-review/SKILL.md |
| `acp-refactor` | 重构、清理、简化、模块化 | .agents/skills/acp-refactor/SKILL.md |

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

---

## 14. 完成度与代码质量红线（新增 v2.0）

### 14.1 禁止半成品代码

- **不允许任何未完成的实现**。包括：
  - 不含 TODO、FIXME、HACK、XXX 等标记（除非是明确标注为未来预留且经用户同意的占位符）。
  - 不包含只抛了 UnsupportedOperationException / RuntimeException 的空方法。
  - 不出现"逻辑写到一半就终止"的情况。每个方法必须完整实现，或干脆不写（等需求确认后再写）。
- **"Done"的定义**：一个方法/文件只有在满足以下条件时才叫完成：
  - 逻辑完整，无占位符
  - 参数校验完整（null / empty / 非法值均有处理）
  - 异常处理完整（try-catch + 错误记录，不吞异常）
  - 有对应的单元测试覆盖正常路径 + 边界条件 + 异常路径
  - mvn test 通过

### 14.2 日志输出规范

- **禁止使用 System.out.println()、System.err.println()、e.printStackTrace() 等直接输出语句**。
- **所有日志输出必须通过 SLF4J**：

Correct (SLF4J):

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

private static final Logger log = LoggerFactory.getLogger(DocxHandler.class);
log.info("Extracting document: {}", fileName);
log.error("Failed to extract file: {}", fileName, e);
```

Incorrect (直接输出 - 禁止):

```java
System.out.println("debug info");    // 禁止
System.err.println("error happened"); // 禁止
e.printStackTrace();                   // 禁止
```

### 14.3 TDD 驱动开发原则

- **测试先行（Test-First）**：每次追加或修正功能前，必须先写失败的测试用例，再实现功能。
  1. 分析需求，确定期望行为。
  2. 在 src/test/java 下编写 JUnit 5 测试，先确保测试失败（Red）。
  3. 编写最小可实现功能的代码（Green）。
  4. 重构，确保仍有测试覆盖（Refactor）。
  5. 运行全量测试 mvn clean test，确保无回归。

- **TDD 测试的设计原则**：
  - 测试是为"不通过"而写的，不是为了迎合已有代码而凑的。
  - 测试用例应覆盖：正常路径、边界条件（空输入、超大文档、嵌套过深）、异常场景（文件格式不支持、IO 失败）。
  - 测试断言必须验证可观察的外部行为（ExtractionResult 内容），而非内部实现细节。

- **变更后的回归保证**：
  - 每次功能追加或修正后，必须运行 mvn clean test 确保所有现有测试通过。
  - 禁止修改现有测试来"凑通过率"——测试失败意味着代码或需求有问题，不是测试的问题。

### 14.4 代码完成自检清单（每次提交前执行）

Agent 在提交任何代码前，必须逐项确认：

  [ ] 无 TODO/FIXME/HACK 等未完成标记
  [ ] 无 UnsupportedOperationException / RuntimeException("not implemented")
  [ ] 无 System.out.println / System.err.println / e.printStackTrace()
  [ ] 全部使用 SLF4J 日志输出
  [ ] 参数校验完整
  [ ] 异常处理完整（不吞异常，记录到 ExtractionResult.errors）
  [ ] 至少有一个失败的测试被"修复"为通过（TDD Red-Green）
  [ ] mvn clean test 全部通过
  [ ] mvn verify 覆盖率达标
  [ ] 无回归（现有测试未被修改来凑通过率）

