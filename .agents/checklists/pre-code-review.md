# 编码前检查清单 (Pre-Coding Checklist)

> 在让 AI Agent 修改代码前，逐项确认。适用于所有风险的编码任务。
> 源自 [agent-coding-playbook/checklists](https://github.com/bravekingzhang/agent-coding-playbook)。

---

## 任务理解

- [ ] 用户目标明确吗？
- [ ] 期望行为描述清楚了吗？
- [ ] 当前行为是什么？
- [ ] 关键约束条件已列出？
- [ ] 非目标（Non-goals）已写明？

## 范围

- [ ] 涉及哪些文件或模块？
- [ ] 哪些文件不应触碰？
- [ ] 这是小修 / 功能新增 / 重构 / 发布任务？
- [ ] 预期的变更量合理吗？

## 风险

- [ ] 是否触及高风险区域？（见 ai-coding-task-template.md 风险定义）
- [ ] 是否需要人工审核？
- [ ] 是否需要回滚计划？

## 验证

- [ ] 是否有现有测试可复用？
- [ ] 是否需要先写一个失败的测试？
- [ ] 变更后需要运行哪些检查？(`mvn test`, `mvn verify`)
- [ ] 需要手动验证的步骤？

---

## AI Agent 自检项

在开始编码之前，Agent 必须输出以下自检结果：

```markdown
我理解的目标是：...
我将要修改的文件：...
我不应该修改的文件：...
我做出的假设：...
潜在风险：...
```

如果以上任何一项无法回答，暂停并询问用户。

---

## Code Quality Blockers（新增 v2.0）

在代码审查时，以下问题**直接拒绝合并**：

- [ ] 无 TODO/FIXME/HACK/XXX 未完成标记
- [ ] 无 UnsupportedOperationException / RuntimeException("not implemented") 空方法
- [ ] 无 System.out.println / System.err.println / e.printStackTrace()
- [ ] 全部使用 SLF4J 日志输出
- [ ] 测试为"不通过而写"（先 Red，再 Green），而非为迎合已有代码而凑
- [ ] mvn clean test 全部通过，无回归
