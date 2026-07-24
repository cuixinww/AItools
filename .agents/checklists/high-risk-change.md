# 高风险变更检查清单 (High-Risk Change Checklist)

> AI Agent 修改敏感或生产关键代码时使用。
> 源自 [agent-coding-playbook](https://github.com/bravekingzhang/agent-coding-playbook)。

---

## 高风险区域（dg-tools 项目特化）

本项目的高风险变更包括：

- [ ] 修改 `DocumentHandler.java` 接口签名
- [ ] 修改 `ExtractionResult` / `Element` / `UnpackResult` 数据模型
- [ ] 修改 `RecursiveExtractor.java` 核心调度逻辑
- [ ] 影响内存安全原则（如引入整包加载）
- [ ] 改变 `manifest.json` 输出格式
- [ ] Phase 1.5 LLM/视觉模型集成相关改动
- [ ] 依赖版本升级（POI/PDFBox/Tika）
- [ ] 大规模重构

## 必需的审核

- [ ] 已指定人工审核员
- [ ] Diff 已逐行审阅
- [ ] 行为变更已记录
- [ ] 回滚计划已文档化
- [ ] 测试已通过（mvn clean test + mvn verify）

## 决策

选择一项：

- [ ] Go（低风险，测试全覆盖）
- [ ] Go with caution（中风险，部分测试覆盖）
- [ ] No-go（高风险，需人工主导实现）

原因：
```text
...
```

## 规则

**AI 生成的总结不足以作为高风险变更的依据。必须阅读 Diff。**
