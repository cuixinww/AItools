---
name: acp-bug-fix-dgtools
description: Use when the user asks to fix a bug, investigate an error, debug a crash, explain a stack trace, or make a failing test pass. Enforces reproduce -> isolate -> smallest fix -> verify, instead of guessing a cause and patching code. Specifically adapted for the dg-tools Java/Spring Boot project.
version: 1.0.0
---

# Bug Fix Skill (dg-tools 适配版)

Use this skill when the user asks you to fix a bug, investigate an error, or explain why something is failing.

The goal is not to guess the cause quickly.

The goal is to reproduce, isolate, fix, and verify.

---

## Workflow

### 1. Understand the Bug

Before changing code, identify:

- What is the observed behavior?
- What is the expected behavior (per SPEC.md)?
- When did it start happening?
- Is there an error message, log, stack trace, or failing test?
- Which handler / module is affected? (DocxHandler, ExcelHandler, PdfHandler, ZipHandler, ImageHandler, DocHandler)

If the bug report is vague, ask for the missing reproduction details.

**SPEC 优先**：对于行为差异类 bug，先对比 SPEC.md 对应章节，确认是代码问题还是 SPEC 过时。

### 2. Reproduce First

Do not fix a bug that has not been reproduced or clearly reasoned about.

Prefer one of these reproduction forms:

- A failing JUnit 5 unit test (`src/test/java/com/dg/tools/extractor/`).
- A failing integration test via `TestFileFactory`.
- A minimal manual reproduction with a real .docx/.xlsx/.pdf file.
- A stack trace that points to the failing path.

If reproduction is impossible in the current environment, state that clearly.

### 3. Isolate the Root Cause

Before editing, explain the likely root cause.

Good root cause statements look like:

```text
ExcelHandler.splitLargeTable() 在列数 == 空列阈值(2) 时未触发切分，因为 RegionSplitter 使用的是 > 而非 >=。
```

Bad root cause statements look like:

```text
可能是 Excel 处理逻辑有问题。
```

Be specific. Reference SPEC sections when applicable.

### 4. Make the Smallest Fix

Fix the bug with the smallest safe change.

Do not:

- Rewrite the whole handler.
- Rename unrelated variables.
- Reformat unrelated files.
- Add new abstractions unless necessary.
- Fix nearby issues unless they are part of the same bug.

Every changed line must be related to the bug.

### 5. Verify the Fix

Run the most relevant checks available:

```powershell
mvn test -Dtest=<AffectedHandler>Test    # Handler 级测试
mvn test -Dtest=<AffectedHandler>ExtraTest  # 补充测试
mvn verify                               # 完整测试 + 覆盖率
```

If checks cannot be run, explain why.

---

## Output Format

At the end, summarize:

```markdown
Root cause:
- ...

Changed:
- ...

Verified:
- ...

Not verified:
- ...

Risk:
- ...
```

---

## Guardrails (dg-tools 特化)

Stop and ask for human confirmation if the bug fix touches:

- `DocumentHandler.java` 接口签名变更
- `ExtractionResult` / `Element` 数据模型变更
- `RecursiveExtractor.java` 核心调度逻辑
- 内存隔离相关改动（如整包加载）
- 输出格式变更（manifest.json、body.md 结构）
