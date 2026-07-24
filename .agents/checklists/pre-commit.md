# 提交前检查清单 (Pre-Commit Checklist)

> AI Agent 生成或修改代码后，提交前逐项确认。
> 源自 [agent-coding-playbook](https://github.com/bravekingzhang/agent-coding-playbook)。

---

## Diff 纪律

- [ ] Agent 只修改了必要的文件？
- [ ] 每一行变更都与任务相关？
- [ ] 有无无关的格式化改动？
- [ ] 有无 Opportunistic cleanup（顺手机会性清理）？
- [ ] 有无无新抽象引入？

## 行为

- [ ] 期望的行为变更是否清晰？
- [ ] 有无意外的行为变更？
- [ ] 边界条件是否处理？
- [ ] 向后兼容性是否保留（如果需要）？

## 验证

- [ ] `mvn test` 通过
- [ ] `mvn verify` 通过（含覆盖率）
- [ ] 如未运行，已记录原因

## 提交信息

良好的提交信息应解释**实际变更**，而不是只提及 AI：

```
✅ Good: fix(excel): handle merged cells in column header section of large tables
❌ Bad:    update code
❌ Bad:    fix bug
```

提交流量建议遵循 Conventional Commits：
```
<type>(<scope>): <description>

- feat: 新功能
- fix: 修复 bug
- docs: 文档变更
- refactor: 重构（不涉及功能变更）
- test: 测试相关
- chore: 构建/工具链变更
```

对于 AI 参与的工作，建议在 commit message 中添加：
```
AI-Assisted: [Codex/Claude Code/Cursor/etc.]
Prompt: See .agents/checklists/pre-commit.md task reference
Reviewed-by: @human-reviewer
```
