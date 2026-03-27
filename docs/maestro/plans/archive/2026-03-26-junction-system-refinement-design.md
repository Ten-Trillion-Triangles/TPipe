---
design_depth: standard
task_complexity: medium
---

# Design Document: Junction System Refinement & Bug Fixes

## 1. Problem Statement
The `Junction` system requires several refinements to improve its security, ergonomics, and observability:

1.  **Authentication Bypass**: Current P2P authentication logic in `executeP2PRequest` depends on the presence of a `P2PDescriptor`. If the descriptor is missing, the authentication check is skipped even if a valid `authMechanism` is configured in `P2PRequirements`. This creates a security gap.
2.  **DSL Ergonomics**: The `JunctionDsl.build()` method uses `runBlocking` to call the asynchronous `init()` function. This can lead to deadlocks in certain runtime environments. A native asynchronous builder is needed.
3.  **Observability Logic**: In the workflow engine, the distinction between a successful "pass" and a requested "termination" (which signifies an error/hard stop in TPipe) needs to be carefully audited. We must ensure that a successful completion (where `passPipeline = true` is set) is not accidentally traced as a failure or termination.

## 2. Requirements
**Functional Requirements:**
- **REQ-1 (Security Fix):** In `Junction.executeP2PRequest`, authentication MUST be enforced if `p2pRequirements.authMechanism` is present, regardless of whether a `p2pDescriptor` is configured or its `requiresAuth` flag is set.
- **REQ-2 (Async DSL):** The `JunctionDsl` class MUST provide a `buildSuspend()` method that is a `suspend fun` and calls `junction.init()` directly without using `runBlocking`.
- **REQ-3 (DSL Guidance):** The existing `JunctionDsl.build()` method should be documented with KDoc explaining the potential deadlock risk in certain environments (like UI threads) when using `runBlocking`.
- **REQ-4 (Trace Audit):** Audit the workflow engine in `Junction.kt` to ensure that a successful completion (where `workflowState.completed = true` is set) correctly results in `passPipeline = true` and is traced with `JUNCTION_WORKFLOW_SUCCESS`. Any requested termination should be explicitly traced to avoid confusion.

**Non-Functional Requirements:**
- **NFR-1 (Pragmatism):** The solution should minimize changes to existing data models while addressing the identified bugs.
- **NFR-2 (Compatibility):** Existing usage of `JunctionDsl.build()` should remain functional for non-suspending contexts.

## 3. Approach
**Selected Approach: Harness & DSL Refinement**
We will implement authentication validation directly within the `Junction` class's `executeP2PRequest` method, using the presence of an `authMechanism` in `P2PRequirements` as the source of truth for enforcement. We will also add a `buildSuspend()` method to `JunctionDsl` to provide a native asynchronous builder for the harness.

**Key Design Decisions:**
- **Auth Enforcement** — *We will use the presence of `p2pRequirements.authMechanism` in the Junction's own requirements as the primary signal for enforcing authentication. If the mechanism is null, Junction will still respect `p2pDescriptor.requiresAuth` as a secondary flag.*
- **Async DSL** — *The `JunctionDsl` class will provide a `buildSuspend()` method that can be called from within a coroutine context, allowing the harness to initialize its P2P graph asynchronously.*
- **Trace Audit** — *The `executeWorkflow` method in `Junction.kt` will be audited to ensure that `passPipeline = true` is correctly mapped to `JUNCTION_WORKFLOW_SUCCESS` and that `terminatePipeline = true` is used only for actual errors or requested terminations.*

**Alternatives Considered:**
- **Strict Descriptor Enforcement** — *Considered strictly requiring a `P2PDescriptor` with `requiresAuth = true` to enable authentication, but rejected because it would still allow a misconfigured harness to "fail open" if the descriptor is accidentally omitted. Enforcing if the mechanism is present is more secure.*
- **Renaming `build()` to `buildSync()`** — *Considered renaming the existing synchronous builder, but rejected to avoid breaking existing code. Adding `buildSuspend()` as a first-class alternative is a safer path.*

## 4. Architecture
The `Junction` system serves as the core orchestration harness for multi-agent interactions in TPipe. It implements the `P2PInterface` and uses `P2PDescriptor` and `P2PRequirements` to define its remote capabilities and constraints.

**Key Components:**
- **Junction.executeP2PRequest(request)**: The method that will be updated to perform the authentication check if `p2pRequirements.authMechanism` is present.
- **JunctionDsl.buildSuspend()**: A new `suspend fun` that will allow asynchronous initialization of the harness.
- **Junction.executeWorkflow()**: The method that will be audited to ensure correct tracing of successful "pass" versus "terminate" outcomes.

## 5. Agent Team
For this task, the primary agent will be the **coder** to implement the authentication fix, the new DSL method, and the trace audit.

**Role assignments:**
- **Coder**: Responsible for updating `Junction.kt` and `JunctionDsl.kt` with the authentication check, the `buildSuspend()` method, and the tracing logic audit.
- **Tester**: Responsible for adding unit tests to `JunctionTest.kt` to verify that authentication is enforced when an `authMechanism` is present and that `buildSuspend()` initializes correctly.
- **Code Reviewer**: Responsible for a final security-focused review of the changes to ensure that the "fail-closed" requirement and trace logic are correct.

## 6. Risk Assessment
**Risk Table:**

| Risk | Description | Impact | Mitigation Strategy |
| :--- | :--- | :--- | :--- |
| **Breaking Existing Tests** | The new mandatory authentication check might break existing tests that send requests without an `authBody` but have an `authMechanism` configured. | Medium | Update existing tests to provide a valid `authBody` or use a mock `authMechanism` as needed. |
| **DSL Incompatibility** | Developers might use `build()` in an async context, potentially leading to deadlocks. | Medium | Provide a clear KDoc warning on `build()` to guide users toward `buildSuspend()` for suspending contexts. |
| **Trace Ambiguity** | If "pass" and "terminate" are not clearly distinguished, it can lead to confusion during debugging and monitoring. | Low | Audit the workflow engine in `Junction.kt` and use explicit trace event types for success versus failure. |
| **Security Failure (Fail-Open)** | If the `authMechanism` is null but auth is still expected by the user, the harness might still fail open. | High | In addition to enforcing if the mechanism is present, still respect `requiresAuth = true` as a secondary signal and throw an error if the mechanism is missing in that case. |

## 7. Success Criteria
**Measurable Success Criteria:**
1.  **SC-1 (Runtime Enforcement):** Calling `Junction.executeP2PRequest(request)` with a present `authMechanism` and a missing `authBody` results in an immediate rejection with a `SecurityException`.
2.  **SC-2 (Auth Mechanism Execution):** Calling `Junction.executeP2PRequest(request)` with a valid `authBody` correctly invokes the `authMechanism` lambda.
3.  **SC-3 (Async DSL):** Calling `JunctionDsl.buildSuspend()` asynchronously correctly initializes the harness's P2P graph.
4.  **SC-4 (Trace Accuracy):** Traces for `Junction` execution correctly distinguish between a successful "pass" and a requested "termination/failure".
5.  **SC-5 (DSL Guidance):** The `JunctionDsl.build()` method has a KDoc warning about the risk of `runBlocking` in certain environments.
