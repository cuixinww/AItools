---
name: acp-feature-add-dgtools
description: Use when the user asks to add a new feature, such as a new AbstractHandler subclass, new extraction capability (e.g., TriageProcessor, ImageDescriber), or extend existing functionality with new behavior. Forces a plan-before-code loop, scoping non-goals, and verifying the new behavior end-to-end. Adapted for dg-tools Java/Spring Boot project.
version: 1.0.0
---

# Feature Add Skill (dg-tools 适配版)

Use this skill when the user asks to build something new — a new handler, a new phase (Phase 1.5/2), or a capability extension.

The goal is not to start typing as fast as possible.

The goal is to clarify the contract, plan the smallest viable implementation, and verify the new behavior actually works before claiming done.

---

## Workflow

### 1. Clarify the Contract

Before writing code, write down:

- What is the new behavior?
- What is the input? (InputStream + fileName)
- What is the output? (ExtractionResult / UnpackResult)
- Who calls this? (RecursiveExtractor via handler dispatch)
- What is explicitly out of scope (non-goals)?
- What existing behavior must remain unchanged?

**SPEC 检查**：新功能必须在 SPEC.md 中有对应章节。如果 SPEC 中该功能是"缺失"/"预留"，优先实现。

### 2. Plan Before Coding

For anything beyond a trivial 1-file change, enter **Plan Mode** before editing.

A good plan answers:

- Which files will be created or modified?
- What is the minimum set of changes that satisfies the contract?
- Are there existing patterns in the codebase to follow? (Check Handler template in .cursorrules)
- What is the verification strategy?

Get the plan approved before writing code.

For multi-step features, also create a step-by-step list so progress is visible and each step ends in a verifiable state.

### 3. Reuse Before Creating

Before adding a new abstraction:

- Search the codebase for an existing utility class or pattern that already solves part of the problem.
  - `MergeCellResolver` → merged cell handling
  - `RegionSplitter` → column splitting
  - `CsvSlicer` → large table chunking
  - `OleExtractor` → OLE2 embedding extraction
- Prefer extending an existing thing over creating a new generic one.
- Do not introduce a new dependency unless the alternative is clearly worse.

A new generic helper with one caller is a smell, not a feature.

### 4. Implement the Smallest Viable Version

Build the smallest version that satisfies the contract.

Do not:

- Add configuration options that no one asked for.
- Add fields that "might be useful later".
- Add abstractions for hypothetical second callers.
- Wrap existing libraries unless there is a concrete reason.

If you find yourself wanting to generalize, stop and finish the concrete case first.

### 5. Verify End-to-End

A new feature is not done when the file compiles.

Run, in order of preference:

- A JUnit test that exercises the new behavior.
- `mvn clean test` for the full suite.
- A manual reproduction with a real document file.

If verification cannot be run in the current environment, say so explicitly.

---

## Output Format

```markdown
Contract:
- ...

Plan:
- ...

Changed:
- ...

Verified:
- ...

Not verified:
- ...

Risks:
- ...
```

---

## Guardrails (dg-tools 特化)

Stop and ask for confirmation if:

- The feature touches the `AbstractHandler` base class signature.
- The feature requires changing public API data models (`ExtractionResult`, `Element`).
- The plan grows beyond the user's stated scope.
- The implementation requires a new framework or major Maven dependency.
- The feature affects memory safety principles (stream-based processing only).
