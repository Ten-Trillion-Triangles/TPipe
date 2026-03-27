---
design_depth: standard
task_complexity: medium
---

# Design Document: Manifold P2P Authentication Remediation & DSL Refinement

## 1. Problem Statement
The `Manifold` harness in TPipe implements the `P2PInterface`, providing `executeP2PRequest` as its primary entry point for remote agentic calls. Currently, this method completely ignores security settings, allowing unauthenticated requests even if an `authMechanism` is configured or `requiresAuth` is true. This vulnerability mirrors a loophole recently closed in the `Junction` harness.

Furthermore, the `ManifoldDsl` lacks build-time validation for security settings and relies on `runBlocking` for initialization, which can cause deadlocks in certain coroutine environments. Parity with the improved `JunctionDsl` is needed to provide a secure and ergonomic developer experience.

## 2. Requirements
**Functional Requirements:**
- **REQ-1 (Runtime Validation):** When `Manifold.executeP2PRequest(request)` is called, it must enforce authentication if `requiresAuth` is true OR if `p2pRequirements.authMechanism` is non-null.
- **REQ-2 (Fail-Closed Auth):** If auth is required, the harness must verify `authBody` is present and valid via the `authMechanism`. Missing or invalid credentials must throw a `SecurityException`.
- **REQ-3 (Async DSL):** `ManifoldDsl` must provide a `suspend fun buildSuspend(): Manifold` that calls `init()` directly without `runBlocking`.
- **REQ-4 (Build-Time Validation):** Both `ManifoldDsl.build()` and `Manifold.init()` must verify that if `requiresAuth` is enabled, an `authMechanism` is provided.

**Non-Functional Requirements:**
- **NFR-1 (Security):** The system must fail-closed. Presence of a security mechanism automatically triggers enforcement.
- **NFR-2 (Consistency):** The implementation must mirror the logic and patterns established in the `Junction` harness remediation.
- **NFR-3 (Developer Guidance):** The existing `build()` method must include KDoc warning about `runBlocking` and recommending `buildSuspend()`.

## 3. Approach
**Selected Approach: Integrated Harness & DSL Refinement**
We will implement authentication validation directly within the `Manifold` class's `executeP2PRequest` method, using the presence of an `authMechanism` in `P2PRequirements` or the `requiresAuth` flag as the source of truth for enforcement. We will also add a `buildSuspend()` method to `ManifoldDsl` to provide a native asynchronous builder.

**Key Design Decisions:**
- **Auth Enforcement** — *We will use the presence of `p2pRequirements.authMechanism` as the primary signal for enforcing authentication, mirroring the Junction fix.*
- **Async DSL** — *`ManifoldDsl` will provide `buildSuspend()` to allow asynchronous initialization within coroutine contexts.*
- **Dual Validation** — *Validation will be enforced in both the DSL builder and the `init()` method to protect both assembly paths.*

## 4. Architecture
The `Manifold` harness serves as a multi-agent orchestration container in TPipe. It implements the `P2PInterface` and uses `P2PDescriptor` and `P2PRequirements` to define its remote constraints.

**Key Components:**
- **Manifold.executeP2PRequest(request)**: Entry point for remote P2P calls.
- **Manifold.init()**: Core initialization method.
- **ManifoldDsl.buildSuspend()**: New asynchronous DSL builder.
- **ManifoldDsl.build()**: Existing synchronous DSL builder (updated with validation).

**Data Flow:**
1.  **Remote P2PRequest**: A request is received by `Manifold`.
2.  **Auth Check**: The harness verifies if `requiresAuth` is true or if an `authMechanism` is present.
3.  **Validation**: If required, `authBody` is validated via the mechanism.
4.  **Enforcement**: On success, the request proceeds to the execution loop. On failure, a `SecurityException` is thrown.

## 5. Agent Team
The implementation will be carried out by specialized agents:
- **Coder**: Responsible for implementing the authentication logic in `Manifold.kt` and the DSL refinements in `ManifoldDsl.kt`.
- **Tester**: Responsible for adding regression tests to verify authentication enforcement, build-time validation, and async DSL functionality.
- **Code Reviewer**: Responsible for a final security audit of the implementation to ensure no "fail-open" paths remain.

## 6. Risk Assessment
**Risk Table:**

| Risk | Description | Impact | Mitigation Strategy |
| :--- | :--- | :--- | :--- |
| **Breaking Existing Remote Calls** | Remote clients sending requests without an `authBody` to a Manifold with a mechanism will now be rejected. | Medium | Ensure documentation reflects the need for credentials and update tests. |
| **DSL Deadlock Risk** | Continued use of `build()` in coroutines might still cause deadlocks. | Medium | Add clear KDoc warnings and promote `buildSuspend()` as the primary choice for async work. |
| **Security Failure (Fail-Open)** | A coding error could lead to auth being bypassed in certain configurations. | High | Use the "fail-closed" pattern where mechanism presence *always* triggers enforcement. |

## 7. Success Criteria
**Measurable Success Criteria:**
1.  **SC-1 (Auth Enforcement):** `Manifold.executeP2PRequest` rejects requests with missing or invalid credentials when an `authMechanism` is present or `requiresAuth` is true.
2.  **SC-2 (Build-Time Validation):** `ManifoldDsl.build()` and `Manifold.init()` throw an `IllegalArgumentException` if `requiresAuth` is enabled but no `authMechanism` is provided.
3.  **SC-3 (Async DSL):** `ManifoldDsl.buildSuspend()` correctly initializes the manifold asynchronously without using `runBlocking`.
4.  **SC-4 (Security Posture):** A final security review confirms that the `Manifold` harness now matches the security posture of the `Junction` harness.
