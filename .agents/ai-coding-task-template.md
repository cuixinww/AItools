# AI Coding 任务 Prompt 模板

> 本模板用于给 AI Agent 下达编码任务，确保任务描述清晰、范围明确、可验证。
> 源自 [agent-coding-playbook](https://github.com/bravekingzhang/agent-coding-playbook) 的最佳实践。

---

## 标准任务格式

每次下达编码任务时，请至少包含以下六个字段：

```markdown
## Goal（目标）
- [要实现什么]
- [期望行为]

## Context（上下文）
- [当前行为是什么]
- [涉及哪些模块/文件]
- [已有的相关模式/代码]

## Scope（范围）
- [可以修改哪些文件]
- [不可以触碰哪些文件]

## Do Not Change（不变更项）
- [不能破坏的现有行为]
- [不能修改的公共接口]

## Verification（验证方式）
- [运行什么测试]
- [如何确认功能正常]

## Risk（风险等级）
- [是否触及高险区域，见下方定义]
```

---

## 风险级别定义

| 级别 | 定义 | AI 角色 | 人工审核 | 回滚计划 |
|------|------|---------|---------|---------|
| **低风险** | 新增内部工具类、补充测试、文档更新 | 直接实现 | 可选 | 可选 |
| **中风险** | 修改现有 Handler 逻辑、新增 Excel/PDF 处理分支 | 协助实现 | 推荐 | 可选 |
| **高风险** | 修改 AbstractHandler 基类契约、改变提取输出结构、内存隔离相关改动 | 仅辅助 | **强制** | **必需** |

高风险判定条件（满足其一即可）：
- 触及 `AbstractHandler.java` 基类签名变更
- 改变 `ExtractionResult` / `Element` 数据模型结构
- 修改 `RecursiveExtractor` 核心调度逻辑
- 影响内存安全原则（如整包加载）
- 变更 `manifest.json` 输出格式
- 涉及 Phase 1.5 TriageProcessor / ImageDescriber（LLM/视觉模型集成）

---

## 示例

### ✅ 好示例：添加 DocHandler 的某个解析分支

```markdown
## Goal
在 DocHandler.extract() 中增加对 OLE2 EmbeddedTable 段落类型的解析支持。

## Context
- 当前 DocHandler 只能解析普通段落和表格
- OLE2 嵌入表格存储在 `EmbeddedTable` 对象中，通过 Tika 的 POIXML 属性访问
- 相关文件: handler/DocHandler.java, model/Element.java

## Scope
- 可修改: handler/DocHandler.java 的 extractParagraphs() 方法
- 可修改: model/Element.java 新增 EMBEDDED_TABLE 枚举值

## Do Not Change
- 不修改 AbstractHandler.java 基类
- 不改变 extract() 方法的 InputStream 签名
- 不引入新的第三方依赖

## Verification
- 运行 mvn test -Dtest=DocHandlerExtraTest
- 手动创建一个含 OLE2 嵌入表格的 .doc 文件测试提取结果

## Risk
低风险 — 仅新增解析分支，不改动现有逻辑
```

### ❌ 差示例

```
帮我重构 DocHandler，让它更清晰一些。
```

问题：
- "更清晰"是模糊目标
- 没有说明范围
- 没有说明不可变动的部分
- 没有验证方式
- Agent 会自行决定重构范围，大概率过度改动

---

## 补充约束（Code Quality Blockers）

下达任务时还需确认以下红线已被覆盖：

- **完成度**：不允许任何未完成代码（TODO、空方法、未处理异常）
- **日志**：禁止 System.out.println，全部使用 SLF4J
- **TDD**：必须先写失败的测试用例，再实现功能
- **回归**：追加或修正后必须 mvn clean test 全量通过，禁止修改现有测试凑通过率
