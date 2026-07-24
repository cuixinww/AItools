# .agents/ 目录说明

本目录包含从业界成熟 AI Coding 实践（[agent-coding-playbook](https://github.com/bravekingzhang/agent-coding-playbook) 和 [ai-coding-rules](https://github.com/mritd97/ai-coding-rules)）中提炼的最佳实践，适配到 dg-tools Java/Spring Boot 项目。

## 目录结构

```
.agents/
├── ai-coding-task-template.md    # 任务 Prompt 模板（核心）
├── ai-coding-quick-reference.md   # 速查卡（打印用）
├── README.md                      # 本文件
├── checklists/                    # 检查清单
│   ├── pre-code-review.md         # 编码前检查
│   ├── pre-commit.md              # 提交前检查
│   └── high-risk-change.md        # 高风险变更检查
├── examples/                      # 示例
│   └── good-vs-bad-prompts.md     # Prompt 正反示例
└── skills/                        # Skill 工作流
    ├── acp-bug-fix/               # Bug Fix 标准化流程
    │   └── SKILL.md
    ├── acp-feature-add/           # 新功能开发流程
    │   └── SKILL.md
    ├── acp-code-review/           # Code Review 流程
    │   └── SKILL.md
    └── acp-refactor/              # 重构流程
        └── SKILL.md
```

## 设计理念

1. **不修改业务代码**：只增加行为规范和指南，不碰 `src/` 下的任何文件。
2. **SPEC 优先**：所有规范与 `SPEC.md` 保持一致，SPEC 是最高权威。
3. **增量式**：不是一次性重写，而是逐步丰富。每次只加一个新增的 Skill 或 Checklist。
4. **Java 适配**：外部 Playbook 原本针对 TS/Node.js，已适配为 Java/Maven/Spring Boot 风格。

## 与现有文件的关系

| 现有文件 | 关系 |
|---------|------|
| `AGENTS.md` | 总纲，本节新增的 11-13 章引用本目录文件 |
| `.cursorrules` | Cursor IDE 指令，新增了 Prompt 模板引用 |
| `.vscode/copilot-instructions.md` | Copilot 指令，不变（Copilot 本身不太受外部 prompt 影响） |
| `SPEC.md` | 需求规格，不变 |
| `pom.xml` | 依赖配置，不变 |

*最后更新: 2026-07-24*
