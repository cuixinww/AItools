---
name: acp-refactor-dgtools
description: Use when the user asks to refactor, clean up, simplify, modularize, or improve the structure of existing code WITHOUT changing behavior. If the user wants new behavior, do not use this skill. Enforces small reversible steps and behavior preservation. Adapted for dg-tools Java/Spring Boot project.
version: 1.0.0
---

# Refactor Skill (dg-tools 适配版)

Use this skill when the user asks for refactoring, cleanup, simplification, or architecture improvement.

The goal of refactoring is to improve structure while preserving behavior. If behavior changes, it is not just refactoring anymore.

## Refactor Rules

### 1. Define the Refactor Goal
Before editing, state the exact goal:
- Reduce duplication (e.g., common error handling across handlers)
- Improve naming clarity
- Extract a utility function
- Split a large method (> 80 lines)
- Remove dead code
- Improve testability

Do not refactor just because code looks old or ugly.

### 2. Preserve Behavior
Public behavior must remain unchanged unless explicitly asked for a behavior change.
- `DocumentHandler` interface contract is immutable for refactor scope.
- `ExtractionResult` output format must be preserved.
- `manifest.json` structure must not change.

### 3. Refactor in Small Steps
Prefer small, reversible changes. Good steps:
- Rename one concept / method
- Extract one private helper method
- Move one utility between packages
- Remove one block of dead code
- Add one missing unit test before restructuring

Bad steps:
- Rewrite the whole handler
- Change naming, structure, and error handling together
- Introduce a new framework or library

### 4. Run Checks Frequently
After each meaningful step:
```powershell
mvn test -Dtest=<AffectedClass>Test
```

If tests fail, revert immediately before proceeding.

## Output Format

```markdown
Refactor goal:
- ...

Behavior preserved:
- ...

Changed:
- ...

Verified:
- ...

Risk:
- ...
```

## Stop Conditions
Stop and ask for confirmation if:
- The refactor requires public API or interface changes.
- The diff becomes larger than expected (> 200 lines).
- You discover the code needs redesign rather than refactor. Exit refactor mode and propose a plan first.
