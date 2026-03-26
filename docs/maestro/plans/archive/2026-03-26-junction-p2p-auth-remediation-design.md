---
design_depth: standard
task_complexity: medium
---

# Design Document: Junction P2P Authentication Remediation

## 1. Problem Statement
The `Junction` harness in TPipe implements the `P2PInterface`, which provides an `executeP2PRequest` method as its primary entry point for remote calls. Currently, this method does not verify authentication credentials, even if the `requiresAuth` flag is set in the harness's `P2PDescriptor`. This vulnerability could allow an attacker to send unauthorized requests to a `Junction` instance, potentially exposing sensitive discussion topics or participant configurations.

To remediate this, we must:
1.  Implement mandatory authentication validation in `Junction.executeP2PRequest`.
2.  Ensure that if `requiresAuth` is enabled, a valid `authMechanism` lambda is provided in the `P2PRequirements`.
3.  Add DSL-time validation to catch misconfigurations before the harness is initialized.

## 2. Requirements
**Functional Requirements:**
- **REQ-1 (Runtime Validation):** When `Junction.executeP2PRequest(request)` is called, the harness must check if its `p2pDescriptor.requiresAuth` flag is set to `true`.
- **REQ-2 (Auth Body Check):** If `requiresAuth` is `true`, the harness must verify that the `request.authBody` is non-empty. If it's missing or empty, the request must be rejected.
- **REQ-3 (Auth Mechanism Execution):** If `requiresAuth` is `true`, the harness must verify that its `p2pRequirements.authMechanism` is non-null. If it is null, an `IllegalStateException` must be thrown. If non-null, the lambda must be called with the `authBody`.
- **REQ-4 (DSL Validation):** The `JunctionDsl.build()` method must verify that if `requiresAuth` is enabled in the moderator or junction descriptor, a corresponding `authMechanism` is provided in the requirements.

**Non-Functional Requirements:**
- **NFR-1 (Security):** The harness must fail-closed. If authentication is required but cannot be performed, the request must be rejected.
- **NFR-2 (Compatibility):** The implementation must use the existing `P2PRequirements` and `P2PDescriptor` classes standard to TPipe.
- **NFR-3 (Developer Experience):** Validation errors in the DSL should provide clear feedback using Kotlin's `require()` function.

## 3. Approach
**Selected Approach: Harness-Based Enforcement**
We will implement authentication validation directly within the `Junction` class's `executeP2PRequest` method. This approach is more direct than creating decorators and ensures that all entry points for remote P2P calls are secured using the existing TPipe `P2PDescriptor` and `P2PRequirements` metadata.

**Key Design Decisions:**
- **Junction Enforcement** — *We will use the junction's own `p2pDescriptor` and `p2pRequirements` properties to manage authentication configuration for remote calls to the harness.*
- **Runtime Validation** — *The `executeP2PRequest` method will verify that if `requiresAuth` is set, an `authBody` is present and the `authMechanism` lambda is called. A missing lambda will result in an `IllegalStateException`.*
- **DSL Builder Validation** — *The `JunctionDsl.build()` method will verify that any descriptor with `requiresAuth = true` has a corresponding `authMechanism` in its requirements using the standard Kotlin `require()` function.*

**Alternatives Considered:**
- **Decorator/Interceptor** — *Considered wrapping the junction in a decorator, but rejected because it would add complexity to the existing harness logic and instantiation paths.*
- **Local Context-Aware Auth** — *Considered handling authentication within each participant/moderator individually, but rejected as it doesn't solve the core concern about the `executeP2PRequest` entry point for the junction itself.*

**Decision Matrix:**

| Criterion | Weight | Harness Enforcement | Decorator Interceptor | Local Auth |
|-----------|--------|------------|------------|------------|
| **Security (Fail-Closed)** | 40% | 5: Direct enforcement at the entry point. | 4: Secure, but relies on correct wrapping. | 3: Decentralized, harder to audit. |
| **Pragmatism (Implementation)** | 30% | 5: Minimal changes to existing code. | 3: Requires new decorator and factory logic. | 4: Simple for individual components. |
| **Architectural Purity** | 30% | 4: Cohesive with `P2PInterface` implementation. | 5: Clean separation of security concerns. | 3: Mixes security with component logic. |
| **Weighted Total** | | **4.7** | **3.8** | **3.4** |

## 4. Architecture
The `Junction` class acts as the core orchestration harness for multi-agent interactions in TPipe. It implements the `P2PInterface` and uses `P2PDescriptor` and `P2PRequirements` to define its remote capabilities and constraints.

**Key Components:**
- **Junction.executeP2PRequest(request)**: The method that will be updated to perform the authentication check.
- **JunctionDsl.build()**: The method that will be updated to perform build-time validation.
- **P2PRequirements.authMechanism**: The existing TPipe standard lambda used for authentication validation.
- **P2PDescriptor.requiresAuth**: The existing TPipe standard flag used to declare that authentication is required.

**Data Flow:**
1.  **Incoming P2PRequest**: A `P2PRequest` is received by the `Junction`.
2.  **Auth Check**: The harness checks if `requiresAuth` is `true`.
3.  **Auth Validation**: If required, it ensures `authBody` is non-empty and calls `authMechanism`.
4.  **Harness Execution**: If validation succeeds, the request proceeds to the `execute` method.
5.  **Rejection**: If validation fails, the harness throws an exception and the request is rejected.

## 5. Agent Team
For this task, the primary agent will be the **coder** to implement the runtime validation and DSL builder changes.

**Role assignments:**
- **Coder**: Responsible for updating `Junction.kt` and `JunctionDsl.kt` with the authentication check and build-time validation.
- **Tester**: Responsible for adding unit tests to `JunctionTest.kt` to verify that unauthorized requests are rejected and that misconfigured DSL builds fail.
- **Code Reviewer**: Responsible for a final security-focused review of the changes to ensure that the "fail-closed" requirement is met and that no other vulnerabilities are introduced.

## 6. Risk Assessment
**Risk Table:**

| Risk | Description | Impact | Mitigation Strategy |
| :--- | :--- | :--- | :--- |
| **Breaking Existing Tests** | The new mandatory authentication check might break existing tests that send requests without an `authBody`. | Medium | Update existing tests to provide a valid `authBody` or use a mock `authMechanism` as needed. |
| **DSL Incompatibility** | Existing DSL-based initialization might fail if `requiresAuth` is enabled but the `authMechanism` is not bound. | Medium | Use `require()` to throw an informative `IllegalArgumentException` explaining the missing binding. |
| **Performance Overhead** | Calling a lambda on every remote P2P request might introduce minimal overhead. | Low | The `authMechanism` is a standard part of TPipe and is designed for this use case. Use a `suspend` function to avoid blocking. |
| **Security Failure (Fail-Open)** | A coding error could result in the harness failing to enforce authentication. | High | Use a "fail-closed" logic pattern where any missing configuration for a required auth results in a rejection. |

## 7. Success Criteria
**Measurable Success Criteria:**
1.  **SC-1 (Runtime Enforcement):** Calling `Junction.executeP2PRequest(request)` with `requiresAuth = true` and a missing `authBody` results in an immediate rejection with a specific security-related exception.
2.  **SC-2 (Auth Mechanism Execution):** Calling `Junction.executeP2PRequest(request)` with `requiresAuth = true` and a valid `authBody` results in a call to the `authMechanism` lambda. If the lambda returns `false`, the request is rejected.
3.  **SC-3 (DSL Builder Validation):** Calling `JunctionDsl.build()` with a moderator or junction descriptor that has `requiresAuth = true` but no `authMechanism` results in an `IllegalArgumentException` explaining the missing binding.
4.  **SC-4 (Standard TPipe Auth):** The implementation uses the standard `P2PDescriptor.requiresAuth` and `P2PRequirements.authMechanism` from the `com.TTT.P2P` package.
