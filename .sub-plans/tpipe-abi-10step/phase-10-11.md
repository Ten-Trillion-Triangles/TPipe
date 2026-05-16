# Sub-Plan: Phase 10–11 — tpipe-abi-10step

## Covered Phases
- Phase 10: Automated Compliance Review
- Phase 11: Human Final Review

## Phase 10: Automated Compliance (6 Checks)
1. GOAL_COVERAGE — all requirements from spec implemented
2. PLAN_COMPLIANCE — all 15 tasks completed or skipped with reason
3. PLAN_REQUIREMENTS — hostile review [REVIEW_PASSED], testing mandate met
4. PHASE_EVIDENCE — all phases have required evidence
5. TEST_COVERAGE — tests exist and pass
6. CODE_QUALITY — no snake_case, no missing KDoc, no @ts-ignore, no secrets

PASS → Phase 11 (Human Final Review)
FAIL → Phase 12 (Rework Controller)

## Phase 11: Human Final Review
- Always blocks — human marks done to approve
- Human can "SEND BACK TO PHASE N" for rework
- Phase 10 PASS is prerequisite (automated gate cleared)

## Rework Handling
If Phase 10 fails, Phase 12 rework controller:
1. Analyses failure report
2. Determines target phase
3. Recreates target + subsequent phase cards
4. Executes forward through Phase 10 re-check
5. Loop until Phase 10 passes