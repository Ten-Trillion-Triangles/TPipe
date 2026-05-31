# TPipe ABI Completion Workflow — Full Handoff Document

**File:** `/home/cage/Desktop/Workspaces/TPipe/TPipe/.claude/workflows/abi-completion.js`
**Branch:** `mcp-server` (NOT the `ABI` branch — the ABI work was merged into `mcp-server`)
**Key files modified:** `src/main/kotlin/com/TTT/Native/TPipeBootstrap.java`, `src/main/kotlin/com/TTT/Native/BinaryHandle.kt`
**Build command:** `./gradlew compileKotlin -x test -x javadoc --no-daemon`
**Spec files location:** `Abi/specs/` (15 spec files covering bootstrap, handles, types, pipes, pipelines, initialization, symbol export, reflection config, distribution grid, host requirements)

---

## WHAT THIS WORKFLOW DOES

Completes the TPipe GraalVM native image ABI — the C entry points that let external programs call into TPipe when compiled as a native image. The spec lives in `Abi/specs/graalvm-abi-*.md` files. The implementation lives in `src/main/kotlin/com/TTT/Native/TPipeBootstrap.java`.

---

## EXACT BRANCH AND GIT STATE

- Working directory: `/home/cage/Desktop/Workspaces/TPipe/TPipe`
- Branch: `mcp-server`
- Git worktrees: run `git worktree list` to find any ABI-related worktrees. The workflow checks `git worktree list` output AND hardcoded fallback paths: `/home/cage/Desktop/Workspaces/TPipe/ABI-worktree`, `/home/cage/Desktop/Workspaces/TPipe-ABI`, `/home/cage/ABI-worktree`
- Latest commit on mcp-server: `20613ff5` ("merge: incorporate ABI worktree") — the ABI work was merged in

---

## CURRENT KNOWN ISSUE

The workflow uses `parallel([function() { return agent(...) }])` which the Claude Code workflow engine parser rejects (even though Node.js parses it fine). The parser chokes on the deeply nested function-thunk structure inside parallel blocks.

**FIX REQUIRED:** Replace ALL 5 `parallel([function() { return agent(...) }])` blocks with sequential `await agent()` calls plus a manual array filter. Then verify brace balance is 0 (open === close).

### The 5 blocks to convert:

**Block 1 — Phase 4, Line ~189:** `const reviewResults = await parallel([...3 agents...])`
- `await` agent 1, store result
- `await` agent 2, store result  
- `await` agent 3, store result
- `const reviewResults = [r1, r2, r3].filter(Boolean)`

**Block 2 — Phase 7, Line ~281:** `const planCritics = await parallel([...3 critics...])`
- `await` agent 1 (logic-critic), store
- `await` agent 2 (spec-critic), store
- `await` agent 3 (style-critic), store
- `const critiques = [c1, c2, c3].filter(Boolean)`

**Block 3 — Phase 10, Line ~535:** `const finalReviews = await parallel([...3 final reviews...])`
- `await` agent 1 (final-code), store
- `await` agent 2 (final-security), store
- `await` agent 3 (final-compliance), store
- `const finalResults = [fr1, fr2, fr3].filter(Boolean)`

**Block 4 — Phase 10, Line ~555:** `const repairAgents = await parallel(finalResults.map(...))`
- This one is DYNAMIC — it maps each critical issue from the final reviews into a repair agent thunk
- Convert to: iterate over the mapped repair agents sequentially with `await`, collect results into array
- `const repairResults = []; for (const repairFn of repairAgentFns) { const r = await repairFn(); repairResults.push(r); }`

**Block 5 — Phase 2, Line ~103 (in BACKUP file):** Round 1 research also uses parallel — this is in the BACKUP file at line 103. If using the current `abi-completion.js` (not BACKUP), Phase 2 was already converted to sequential (lines 104-106 are separate await calls).

### After converting, verify:
```bash
node --check .claude/workflows/abi-completion.js && echo "SYNTAX OK"
node -e "const fs=require('fs'); const c=fs.readFileSync('.claude/workflows/abi-completion.js','utf8'); let d=0; for(const ch of c){if(ch==='{')d++;if(ch==='}')d--;} console.log('brace depth:',d);"
```
Brace depth MUST be 0.

---

## PHASE-BY-PHASE FLOWCHART

### PHASE 1 — SPEC AUDIT (Lines 42–93)
```
START
  |
  v
Find all .md files recursively in '.' (skip build/, .gradle/)
  |
  v
Filter files whose path contains 'spec' OR 'abi' OR 'graalvm' (case-insensitive)
  |
  v
Find graalvmSpec = the ONE file whose path contains BOTH 'graalvm' AND 'abi'
  |
  v
List files in src/main/kotlin/com/TTT/Native/
  |
  v
Read TPipeBootstrap.java into bootstrapContent
  |
  v
Extract publicFunctions using regex: /public\s+static\s+\w+\s+\w+\s*\([^)]*\)/g
  |
  v
IF graalvmSpec found:
    Read graalvmSpec content
    Extract specFunctions using regex: /TPipe[A-Za-z0-9_]+\s*\(/g
ELSE:
    specFunctions = []
  |
  v
Compare: for each specFunction, strip 'TPipe' prefix, check if it appears in bootstrapContent
  |
  v
gaps = specFunctions NOT found in bootstrapContent
  |
  v
Log gaps found count + first 10 missing function names
  |
  v
PHASE 2
```

---

### PHASE 2 — GAP RESEARCH + CONSENSUS VOTING (Lines 95–144)

```
PHASE 1 COMPLETE
  |
  v
IF gaps.length > 0:
  |YES                           |NO
  v                              v
Spawn 3 research agents      Log "No gaps found"
SEQUENTIALLY (not parallel): proceeding to Phase 3"
  |
  |  Agent 1: Research spec functions missing from bootstrap
  |  - Input: graalvmSpec path
  |  - Output: { missingFunctions: [...], proposals: [{function, implementation, rationale}] }
  |
  |  Agent 2: Map TPipe internal architecture
  |  - Input: Pipe.kt, Pipeline.kt, Context/
  |  - Output: { mappings: [{abiFunction, tpipeInternal, location}] }
  |
  |  Agent 3: Review TPipe code style for FFI/JNI/native patterns
  |  - Input: CLAUDE.md and source files
  |  - Output: { conventions: [...], patterns: [...] }
  |
  v
round1Results = [r1, r2, r3].filter(Boolean)
  |
  v
Spawn SPEC EXPANSION VOTE agent:
  - Analyzes all 3 research outputs together
  - Votes: "EXPAND_SPEC" | "SPEC_COMPLETE" | "NEED_MORE_RESEARCH"
  - IF EXPAND_SPEC: draft new function signatures -> save to .claude/abi-expanded-spec.md
  - IF NEED_MORE_RESEARCH: spawn 2 more research agents (in current js file these are sequential awaits)
  - Output: { voteResult, functionsToAdd, functionsToFix, confidence, reasoning }
  |
  v
additionalGaps = vote.functionsToAdd (if EXPAND_SPEC) else []
  |
  v
gapResearchResults = {
    round1: round1Results,
    vote: specExpansionVote,
    additionalGaps: additionalGaps,
    allGaps: [...gaps, ...additionalGaps]
  }
  |
  v
PHASE 3
```

---

### PHASE 3 — LOCATE WORKTREE (Lines 146–180)

```
PHASE 2 COMPLETE
  |
  v
Run: git worktree list
  |
  v
For each line in output:
    IF line contains 'abi' OR 'worktree':
        Extract first path token
        IF that path exists AND contains files with 'TPipe' OR 'Native':
            abiWorktreePath = that path
  |
  v
IF abiWorktreePath still null:
    Try hardcoded fallback paths IN ORDER:
    1. /home/cage/Desktop/Workspaces/TPipe/ABI-worktree
    2. /home/cage/Desktop/Workspaces/TPipe-ABI
    3. /home/cage/ABI-worktree
    IF exists AND has src/main/kotlin/com/TTT/Native/:
        abiWorktreePath = that path
  |
  v
Log: "ABI worktree: <path>" or "ABI worktree: NOT FOUND"
  |
  v
PHASE 4
```

---

### PHASE 4 — WORKTREE REVIEW (Lines 182–210)

```
PHASE 3 COMPLETE
  |
  v
IF abiWorktreePath exists AND src/main/kotlin/com/TTT/Native/ exists in it:
  |YES                          |NO
  v                              v
Spawn 3 review agents        Log "No ABI worktree found"
(via parallel, converted to   with Native directory"
sequential in fixed version): proceeding without it"
  |
  |  Agent 1 (bootstrap-review): Code review of TPipeBootstrap.java in worktree
  |  - Output: { rating: "1-10", issues: [...], memoryIssues: [...] }
  |
  |  Agent 2 (arch-review): Architecture review of all Native/ files
  |  - Output: { sound: boolean, concerns: [...], recommendations: [...] }
  |
  |  Agent 3 (compare-review): Compare worktree vs current branch
  |  - Output: { differences: [...], worktreeBetter: [...], ourBetter: [...] }
  |
  v
reviews = reviewResults.filter(Boolean)
  |
  v
totalIssues = flatMap all reviews' issues + concerns
  |
  v
avgRating = average of all numeric ratings
  |
  v
IF totalIssues > 20:   worktreeState.state = "hot-garbage"
IF totalIssues > 5:    worktreeState.state = "fixable"
IF totalIssues <= 5:   worktreeState.state = "good"
  |
  v
worktreeState.recommendation = 
    IF state === "hot-garbage": "skip-to-6"
    ELSE: "merge"
  |
  v
PHASE 5
```

---

### PHASE 5 — MERGE DECISION (Lines 212–253)

```
PHASE 4 COMPLETE
  |
  v
IF worktreeState.recommendation === "merge" AND abiWorktreePath exists:
  |YES                                    |NO
  v                                       v
Attempt merge:                       Log "Skipping merge" +
  |                                     worktreeState.state +
  | git fetch origin in worktree       worktreeState.recommendation
  | git merge worktreePath --no-commit --no-ff
  |                                      PHASE 6
  v
IF merge error (conflicts):
  |
  | Get list of conflict files
  | For each conflict file:
  |   IF filename contains 'Native' OR 'ABI' OR 'abi' OR 'graalvm':
  |       git checkout --theirs <file>   (take worktree version)
  |   ELSE:
  |       git checkout --ours <file>     (keep our version)
  |   git add <file>
  |
  v
IF merge success (no error OR conflicts resolved):
    git commit -m "merge: incorporate ABI worktree with intelligent conflict resolution"
    mergeResult.attempted = true
    mergeResult.success = true
  |
  v
PHASE 6
```

---

### PHASE 6 — STATE ASSESSMENT (Lines 255–276)

```
PHASE 5 COMPLETE
  |
  v
Read TPipeBootstrap.java (current branch, post-merge)
  |
  v
Count currentFunctions = public static functions in current bootstrap
  |
  v
Run: ./gradlew compileKotlin -x test -x javadoc --no-daemon
  |
  v
IF build exit code is 0 AND output contains 'BUILD':
    buildPasses = true
  |
  v
remainingGaps = specFunctions NOT in currentBootstrap
  |
  v
Log: build status, remaining gap count
  |
  v
PHASE 7
```

---

### PHASE 7 — HOSTILE PLAN REVIEW (Lines 278–293)

```
PHASE 6 COMPLETE
  |
  v
Spawn 3 critic agents (via parallel, convert to sequential):
  |
  |  Agent 1 (logic-critic): Logical flaws, missing error handling
  |  - Output: { flaws: [...], severity: [...] }
  |
  |  Agent 2 (spec-critic): Spec signature correctness, internal consistency
  |  - Output: { specIssues: [...], inconsistencies: [...] }
  |
  |  Agent 3 (style-critic): TPipe convention violations
  |  - Output: { violations: [...], suggestions: [...] }
  |
  v
critiques = planCritics.filter(Boolean)
  |
  v
allCritiqueIssues = flatMap of all flaws + specIssues + violations
  |
  v
Log critique count
  |
  v
PHASE 8
```

---

### PHASE 8 — TASK DECOMPOSITION (Lines 293–340)

```
PHASE 7 COMPLETE
  |
  v
Create tasks array = []
  |
  v
FOR EACH gap in remainingGaps:
    tasks.push({
      title: "Implement missing: <gap>",
      description: "Implement <gap> in TPipeBootstrap.java",
      type: "implementation",
      status: "pending",
      priority: "high"
    })
  |
  v
tasks.push({
  title: "Verify build passes",
  description: "./gradlew compileKotlin -x test -x javadoc --no-daemon",
  type: "verification",
  status: "pending",
  priority: "critical"
})
  |
  v
tasks.push({
  title: "ABI unit tests",
  description: "Write mock-free unit tests for all public ABI functions",
  type: "testing",
  status: "pending",
  priority: "high"
})
  |
  v
tasks.push({
  title: "Native image compatibility",
  description: "Verify ABI works with GraalVM native image compilation",
  type: "verification",
  status: "pending",
  priority: "high"
})
  |
  v
tasks.push({
  title: "Security audit",
  description: "Memory safety, JNI vulnerabilities, buffer overflows",
  type: "security",
  status: "pending",
  priority: "critical"
})
  |
  v
Log total task count
  |
  v
PHASE 9
```

---

### PHASE 9 — ENFORCEMENT EXECUTION LOOP (Lines 342–516)

```
PHASE 8 COMPLETE
  |
  v
iteration = 0
maxIterations = 200
stuckCount = 0
STUCK_THRESHOLD = 3
  |
  v
WHILE iteration < maxIterations:
  |
  v
iteration++
  |
  v
pendingTasks = tasks with status === "pending"
failedTasks = tasks with status === "failed"
  |
  v
IF pendingTasks.length === 0 AND failedTasks.length === 0:
    Log "ALL TASKS COMPLETE!"
    BREAK -> PHASE 10
  |
  v
retryTasks = failedTasks.slice(0, 4)     (up to 4 failed to retry)
newTasks = pendingTasks filtered by priority === "critical" OR "high", slice(0, 4 - retryTasks.length)
batch = [...retryTasks, ...newTasks]
  |
  v
IF batch.length === 0:
    Wait 1 second, CONTINUE to next iteration
  |
  v
batchProgress = false
  |
  v
FOR EACH task in batch:
  |
  v
  IF task.type === "implementation":
  |   implAttempts = 0
  |   maxImplAttempts = 10
  |   implSuccess = false
  |   |
  |   v
  |   WHILE implAttempts < maxImplAttempts AND NOT implSuccess:
  |   |   implAttempts++
  |   |   |
  |   |   v
  |   |   Spawn agent to implement the function
  |   |   |
  |   |   v
  |   |   IF implResult.implemented === true:
  |   |   |   implSuccess = true
  |   |   |   task.status = "complete"
  |   |   |   batchProgress = true
  |   |   |
  |   |   v
  |   |   ELSE:
  |   |   |   Run partial build check
  |   |   |   IF build passes:
  |   |   |   |   implSuccess = true
  |   |   |   |   task.status = "complete"
  |   |   |   |   batchProgress = true
  |   |   |   ELSE:
  |   |   |       retry (loop back)
  |   |   |
  |   v
  |   IF implAttempts >= maxImplAttempts AND NOT implSuccess:
  |   |   task.status = "failed"
  |
  v
  ELSE IF task.type === "verification":
  |   Run build command
  |   |
  |   v
  |   IF build passes:
  |   |   task.status = "complete"
  |   |   batchProgress = true
  |   |
  |   v
  |   ELSE (build fails):
  |   |   Spawn build-fix agent
  |   |   |
  |   |   v
  |   |   IF buildFixResult.fixed:
  |   |   |   task.status = "complete"
  |   |   |   batchProgress = true
  |
  v
  ELSE IF task.type === "testing":
  |   Run ./gradlew :test --tests "*ABI*" --no-daemon
  |   |
  |   v
  |   IF tests pass:
  |   |   task.status = "complete"
  |   |   batchProgress = true
  |   |
  |   v
  |   ELSE (tests fail):
  |   |   Spawn test-fix agent
  |   |   |
  |   |   v
  |   |   IF testFixResult.fixed:
  |   |   |   task.status = "complete"
  |   |   |   batchProgress = true
  |
  v
  ELSE IF task.type === "security":
  |   Spawn security-check agent
  |   |
  |   v
  |   IF securityCheck.safe === true AND no vulnerabilities:
  |   |   task.status = "complete"
  |   |   batchProgress = true
  |   |
  |   v
  |   ELSE IF vulnerabilities found:
  |   |   FOR EACH vulnerability:
  |   |   |   Spawn security-fix agent to fix it
  |   |   |
  |   |   v
  |   |   Spawn recheck agent
  |   |   |
  |   |   v
  |   |   IF recheck.allFixed === true:
  |   |   |   task.status = "complete"
  |   |   |   batchProgress = true
  |
  v
  (END FOR EACH task in batch)
  |
  v
IF batchProgress === false:
|   stuckCount++
|   |
|   v
|   IF stuckCount >= STUCK_THRESHOLD:
|   |   Log "STUCK DETECTED"
|   |   stuckCount = 0
|   |   |
|   |   v
|   |   Spawn DIAGNOSIS agent:
|   |   |   - Input: all task states
|   |   |   - Output: { diagnosis, blockedTasks: [{task, whyBlocked, alternativeApproach, actionPlan}], resolution }
|   |   |
|   |   v
|   |   IF blockedTasks found:
|   |   |   FOR EACH blocked task:
|   |   |   |   Find matching task in tasks array
|   |   |   |   Spawn RE-TOOL agent with alternative approach
|   |   |   |   |
|   |   |   |   v
|   |   |   |   IF retoolResult.implemented:
|   |   |   |   |   task.status = "complete"
|   |   |   |   |   batchProgress = true
|   |   |   |
|   |   |   v
|   |   |   IF resolution directive:
|   |   |       Spawn resolution-exec agent
|   |   |
|   |   v
|   |   ELSE (no blocked tasks returned):
|   |       Spawn EMERGENCY FRESH START agent
|   |       (completely different approach, don't repeat failed strategies)
|   |
|   v
ELSE:
    stuckCount = 0
    batchProgress = false
  |
  v
IF iteration % 5 === 0:
|   Run periodic health check (build command)
|   |
|   v
|   IF build fails:
|   |   Force-build-fix agent to fix compile errors
  |
  v
(END WHILE iteration < maxIterations)
  |
  v
Log final task statuses
  |
  v
PHASE 10
```

---

### PHASE 10 — FINAL VERIFICATION ENFORCEMENT LOOP (Lines 524–612)

```
PHASE 9 COMPLETE
  |
  v
enforcementIteration = 0
MAX_ENFORCEMENT_ITERATIONS = 100
  |
  v
WHILE enforcementIteration < MAX_ENFORCEMENT_ITERATIONS:
  |
  v
enforcementIteration++
  |
  v
Spawn 3 final review agents (via parallel, convert to sequential):
  |
  |  Agent 1 (final-code): Full code review, rate quality 1-10, list issues
  |  - Output: { rating: number, issues: [...], verdict: string }
  |
  |  Agent 2 (final-security): Security audit final pass
  |  - Output: { vulnerabilities: [...], safe: boolean, severity: string }
  |
  |  Agent 3 (final-compliance): 100% spec compliance check
  |  - Output: { compliance: number, deviations: [...], fullyCompliant: boolean }
  |
  v
finalResults = [fr1, fr2, fr3].filter(Boolean)
  |
  v
criticalIssues = flatMap of all issues + vulnerabilities + deviations
                 FILTERED to only those containing 'critical' OR 'security' OR 'missing'
  |
  v
allSafe = finalResults.every(r => r.safe !== false && r.fullyCompliant !== false)
  |
  v
IF criticalIssues.length === 0 AND allSafe === true:
|   Log "All critical issues resolved - ABI is compliant!"
|   BREAK -> Final Summary
  |
  v
ELSE:
    Log "Critical issues remain - REPAIRING..."
    |
    v
    Map each critical issue from finalResults into a repair agent
    (each issue becomes a function() { return agent('URGENT REPAIR...') })
    |
    v
    Spawn repair agents SEQUENTIALLY:
    |   FOR EACH repairFn in repairAgentFns:
    |   |   await repairFn()
    |   |   Collect result
    |
    v
    Count fixesApplied = repairResults where fixed === true
    |
    v
    Run build check after repairs
    |
    v
    IF build fails:
    |   Spawn build-fix agent to resolve compile errors
    |
    v
    Log "Continuing enforcement loop if issues remain..."
    |
    v
    (LOOP BACK to WHILE condition)
  |
  v
(END WHILE)
  |
  v
IF enforcementIteration >= MAX_ENFORCEMENT_ITERATIONS:
    Log "WARNING: Reached max enforcement iterations - manual intervention may be required"
  |
  v
Final Summary (Lines 599–613):
    Log: spec audit results, worktree state, merge status, gaps remaining,
         tasks completed, final review count, critical issues, final build status,
         enforcement iterations, compliant verdict
  |
  v
callback({
    specAudit: { specFiles, specFunctions, graalvmSpec },
    worktree: { found, path, state, rating },
    merge: mergeResult,
    gaps: { remaining, list },
    tasks: { total, completed },
    finalReviews: { count, allSafe },
    criticalIssues: count,
    finalBuildPasses: boolean,
    enforcementIterations: count,
    compliant: boolean,
    verdict: "ABI_COMPLETE" | "ENFORCEMENT_LOOP_RUNNING"
})
  |
  v
DONE
```

---

## KEY DECISION POINTS SUMMARY

| Decision | Path A | Path B |
|----------|--------|--------|
| Phase 2: gaps > 0? | Research + vote | Skip to Phase 3 |
| Phase 2 vote = EXPAND_SPEC? | Write expanded spec, add to gaps | Continue |
| Phase 2 vote = NEED_MORE_RESEARCH? | Spawn 2 more research agents | Continue |
| Phase 4: worktree found with Native/? | Run 3 review agents | Skip to Phase 6 |
| Phase 4: totalIssues > 20? | hot-garbage, skip merge | continue to check >5 or <=5 |
| Phase 4: totalIssues > 5? | fixable, recommendation=merge | good, recommendation=merge |
| Phase 5: recommendation=merge? | Attempt merge | Skip |
| Phase 5: merge has conflicts? | Intelligent resolve (Native=theirs, else=ours) | Auto-success |
| Phase 9: batchProgress? | stuckCount=0 | stuckCount++ |
| Phase 9: stuckCount >= 3? | Diagnose + retool | Continue |
| Phase 9: iteration % 5 == 0? | Health check + force build fix | Continue |
| Phase 10: criticalIssues==0 AND allSafe? | BREAK - compliant | Repair loop |
| Phase 10: max iterations reached? | Log warning | Continue to summary |

---

## FILES TO WORK ON

- **Main implementation:** `src/main/kotlin/com/TTT/Native/TPipeBootstrap.java` — 59 public static functions
- **Secondary:** `src/main/kotlin/com/TTT/Native/BinaryHandle.kt`
- **Specs:** `Abi/specs/graalvm-abi-*.md` (15 files)
- **Workflow:** `.claude/workflows/abi-completion.js`

## HOW TO VERIFY SUCCESS

- Build passes: `./gradlew compileKotlin -x test -x javadoc --no-daemon`
- All spec functions implemented (remainingGaps === 0)
- No critical security issues
- Phase 10 exits only via BREAK (all issues resolved) or max iterations
- brace balance: 0 (equal { and })
