# Sub-Plan: Phase 4–5 — tpipe-abi-10step

## Covered Phases
- Phase 4: Load Relevant Skills
- Phase 5: Hostile Review

## Phase 4: Load Relevant Skills
Skills to load:
- writing-plans
- subagent-driven-development
- test-driven-development
- requesting-code-review
- systematic-debugging
- spike
- ttt-code-styler (required)

## Phase 5: Hostile Review
6 reviewers:
1. requirements-auditor — requirements completeness
2. architecture-critic — architecture soundness
3. edge-case-hunter — failure modes and edge cases
4. security-reviewer — auth, validation, secrets, attack surface
5. test-coverage-analyst — test coverage and regression risk
6. apex-standards-enforcer — snake_case, KDoc, builder patterns, TDD

AUTO-FIX LOOP: Up to 5 fix attempts per reviewer until pass.

## Focus Areas
- GraalVM native image constraints (Serial GC only, no JIT, AOT-only)
- C ABI boundary correctness (handle lifecycle, async handle management)
- Resource-config.json for ServiceLoader/META-INF/services
- No exposed P2PInterface — internal only