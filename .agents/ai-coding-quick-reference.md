# AI Coding 速查卡 (Quick Reference)

> 打印出来贴在显示器旁边。综合自 [agent-coding-playbook](https://github.com/bravekingzhang/agent-coding-playbook) 和 [ai-coding-rules](https://github.com/mritd97/ai-coding-rules)。

---

## 🏆 黄金规则 (Top 10)

| # | 规则 | ❌ 不要 | ✅ 要 |
|---|------|---------|-------|
| 1 | **先理解再编码** | "帮我重构" | "修复 ExcelHandler 合并单元格问题，SPEC §8" |
| 2 | **最小变更** | 一次性重写整个 Handler | 只改有问题的 5 行代码 |
| 3 | **SPEC 优先** | 自行假设 API 行为 | 先读 SPEC.md §X，确认后再动手 |
| 4 | **内存安全** | `File.readAllBytes()` | 流式处理 `InputStream` |
| 5 | **增量修改** | 一个 PR 改 10 个文件 | 一个 PR = 一个可编译的最小单元 |
| 6 | **测试覆盖** | 部署没有测试的代码 | 每个新增方法必须有对应测试 |
| 7 | **人类审核** | AI 代码直接合入 | 高风险变更必须人工 review diff |
| 8 | **精准 Prompt** | "优化性能" | "DocxHandler extractParagraphs 在 10万字时 OOM" |
| 9 | **不引入无必要依赖** | 加一个新库解决一个小问题 | 先用现有 POI/PDFBox/Tika 能力 |
| 10 | **诚实报告** | "已完成"（没跑测试） | "测试未跑——环境限制" |

---

## 📝 Prompt 模板

每次给 Agent 下达任务时，使用这个结构：

```markdown
Goal: ...
Context: (当前行为 + 相关文件)
Scope: (可以改什么 / 不能改什么)
Do Not Change: ...
Verification: (mvn test -Dtest=...)
Risk: (低/中/高)
```

详见 [.agents/ai-coding-task-template.md](./ai-coding-task-template.md)

---

## ✅ 评审检查清单

### 自动化检查
```powershell
mvn clean test                    # 全部测试
mvn verify                        # 测试 + 覆盖率
mvn clean compile                 # 编译通过
```

### 手动审查
```
规范合规:
☐ 与 SPEC.md 一致?
☐ Handler 接口契约不变?
☐ 输出格式(manifest.json, body.md)不变?

安全性:
☐ 无整包加载反模式?
☐ 流式处理 preserved?
☐ 文件大小阈值 respected? (200MB/100MB/500MB)

代码质量:
☐ 函数 < 80 行
☐ 边界条件已处理 (null input, empty doc, oversized file)
☐ 错误记录到 ExtractionResult.errors 而非吞异常
☐ 测试覆盖正常路径 + 边界 + 异常
```

---

## ⚠️ 绝不让 AI 独立完成的事

```
❌ 修改 AbstractHandler.java 基类契约
❌ 设计系统架构
❌ Phase 1.5 LLM/视觉模型集成策略
❌ 数据库 Schema 设计（未来）
❌ 安全策略决策
❌ 生产发布
```

## ✅ AI 擅长做的事

```
✅ CRUD Handler 实现（docx/doc/xls/pdf/zip/image）
✅ 单元测试编写
✅ Bug 复现和定位
✅ 文档注释生成
✅ 工具类提取
✅ CSV 切片/合并单元格填充 等具体功能
✅ 配置和阈值调整
```

---

## 🔄 标准工作流

```
1. 写 Prompt (用模板) → 明确目标/范围/约束
2. Agent 分析 → 列出涉及文件和假设
3. Agent 实施 → 小步提交
4. 自动化检查 → mvn test + mvn verify
5. 人工 Review → 阅读 diff，对照 SPEC
6. 审核通过 → 合入
7. 审核后移入 main (或 PR merge)
```

---

## 🔑 核心文件索引

| 文件 | 用途 |
|------|------|
| `AGENTS.md` | 总行为规范（必读） |
| `SPEC.md` | 需求规格（最高优先级） |
| `.cursorrules` | Cursor IDE 专用指令 |
| `.vscode/copilot-instructions.md` | Copilot 专用指令 |
| `.agents/ai-coding-task-template.md` | 任务 Prompt 模板 |
| `.agents/checklists/pre-code-review.md` | 编码前检查 |
| `.agents/checklists/pre-commit.md` | 提交前检查 |
| `.agents/checklists/high-risk-change.md` | 高风险变更 |
| `.agents/skills/acp-bug-fix/SKILL.md` | Bug Fix 工作流 |
| `.agents/skills/acp-feature-add/SKILL.md` | 新功能工作流 |
| `.agents/skills/acp-code-review/SKILL.md` | Code Review 工作流 |
| `.agents/skills/acp-refactor/SKILL.md` | 重构工作流 |

---

> **"AI 写代码。人类确保它是对的。"**
>
> *最后更新: 2026-07-24*
