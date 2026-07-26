---
name: acp-code-review-dgtools
description: Use when the user asks to review a diff, review changes on a branch, or audit code written by an AI agent. Reviews the diff (not the summary) for unrelated changes, missing verification, behavior risks, and over-engineering. Adapted for dg-tools Java/Spring Boot project.
version: 1.0.0
---

# Code Review Skill (dg-tools 适配版)

Use this skill when reviewing code written by humans or AI coding agents.

The goal is not to praise the implementation. The goal is to find behavior changes, risks, missing verification, and unnecessary complexity.

## Review Mindset

Review the diff, not the summary. An agent's explanation may be useful, but it is not evidence. Evidence lives in: the git diff output, test results, build output, SPEC.md requirements, and runtime behavior with real document files.

## Review Workflow

### 1. Understand the Intended Change
- What problem is this change trying to solve?
- What behavior should change vs remain unchanged?
- Which SPEC.md section does this relate to?

If the intent is unclear, ask before approving.

### 2. Trace Every Meaningful Diff
For each meaningful changed block, ask: "Why is this change necessary for the stated goal?" Flag:
- Unrelated changes
- Formatting-only noise
- Accidental behavior changes
- Opportunistic cleanup
- Premature abstraction (new utility class with one caller)

### 3. Check SPEC Compliance (dg-tools specific)
- [ ] Aligned with SPEC.md requirements?
- [ ] Output formats unchanged (body.md, manifest.json)?
- [ ] Memory isolation preserved (stream-based only)?
- [ ] Handler interface contract maintained?
- [ ] Constants/Thresholds consistent?
- [ ] Backward compatible with Java 17?

### 4. Check Behavior and Edge Cases
Look for: null InputStream, error swallowing, large file > 200MB, nested recursion depth, memory pressure, manifest.json format changes.

### 5. Check Verification
- [ ] JUnit 5 unit tests added/updated
- [ ] Integration tests via TestFileFactory
- [ ] mvn test passing
- [ ] mvn verify passing (Jacoco coverage)

## Output Format

```markdown
Summary: - ...
SPEC Compliance: - ...
Main risks: - ...
Required changes: - ...
Suggested improvements: - ...
Verification gaps: - ...
```

## AI-Generated Code Warning Signs

Be extra careful when the diff contains:
- Large unrelated handler rewrites
- New abstractions for small tasks
- Changed AbstractHandler.java base class without updating callers
- Tests that only verify implementation details
- Confident summaries with no test evidence
