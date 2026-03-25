# DistributionGrid Progress

Date: 2026-03-25
Last Updated: 2026-03-25

## Overview

This file is the living implementation record for the `DistributionGrid` workstream. It is intended to survive compaction and let a future session resume from the current truth without re-deriving status from the source tree.

Steering-set ownership:

- Keep architecture, interfaces, and guardrails in [`md/distributiongrid-design.md`](./distributiongrid-design.md).
- Keep the single current task in [`md/distributiongrid-plan.md`](./distributiongrid-plan.md).
- Keep this file focused on current implementation truth, approved decisions, evidence, and revision history.

## Current Status

- `DistributionGrid` remains a partial stub as of 2026-03-25.
- The current implementation still contains only the stub-era structural models `DistributionGridTask` and `DistributionGridJudgement`.
- `setEntryPipeline()` remains the only implemented runtime method and only performs schema validation plus assignment.
- Node routing, worker execution, peer discovery, durability, tracing, privacy policy, auth policy, DITL orchestration hooks, and memory policy are not implemented yet.
- The steering-doc set now contains the approved full node-based architecture specification for the future runtime.

## Plan At A Glance

- [x] Create the compaction-safe steering set for `DistributionGrid`.
- [x] Approve and record the full node-based architecture spec.
- [ ] Implement the new model layer for envelopes, directives, policy, memory, and durability.
- [ ] Replace the stub-era `DistributionGrid` API with router and worker node configuration.
- [ ] Add init validation, hop-loop guards, tracing events, and pause/resume scaffolding.
- [ ] Add DSL and Defaults-friendly runtime structure.
- [ ] Add focused tests for routing, policy, tracing, privacy, memory, and durability behavior.
- [ ] Synchronize public docs with the new runtime once implementation exists.

## Completed Work

- `DistributionGridTask` exists in the runtime source.
- `DistributionGridJudgement` exists in the runtime source.
- `setEntryPipeline()` validates the required JSON schema before storing the entry pipeline.
- Internal docs already describe `DistributionGrid` as incomplete rather than production-ready.
- The `DistributionGrid` steering set now has dedicated design, progress, and plan files.
- The design doc now records the approved remote node architecture, the envelope-first public contract, and the TPipe standards integration surface for the future runtime.

## Approved Decisions

- One `DistributionGrid` instance represents one node.
- Every node requires both a router and a local worker harness.
- The router owns completion validation and next-hop decisions.
- One task has one active downstream hop at a time, though separate top-level requests may still run independently.
- Completion routing is policy-driven rather than hardcoded to immediate sender behavior.
- The new runtime will use a new envelope-first public contract.
- `DistributionGridTask` and `DistributionGridJudgement` are treated as legacy stub-era models, not the long-term runtime contract.
- Peer discovery is hybrid by design.
- Remote transports are first-class in the design target.
- The grid must use least-privilege outbound memory sharing by default.
- The grid must expose hybrid DITL support: orchestration hooks at the container layer and normal DITL inside child pipelines.
- The grid must meet full harness tracing standards.
- Requester trace and privacy policy is enforced, not advisory.
- The grid must support a credential-routing DSL rather than blind auth forwarding.
- The grid must define a pluggable durable store contract.
- The grid must support raw API, DSL, and Defaults-friendly ergonomics.
- Grid nodes must be explicitly marked through grid metadata rather than inferred from generic P2P descriptor text or skills.
- Registry discovery uses bootstrap trust anchors plus trusted registry advertisements.
- Both dedicated registries and mixed node-registry roles are first-class deployment models.
- Node registration is lease-based with renewal and expiry semantics.
- Registry queries return signed or attested node advertisements rather than raw descriptor lists alone.
- First contact between grid nodes requires a mandatory versioned handshake.
- Protocol compatibility requires major-version match and minor-version negotiation.
- Policy negotiation is intersection-or-reject.
- Successful handshakes create cached session records that later handoffs may reference.

## Risks And Guardrails

- Risk: future sessions may assume the old dispatcher or judge placeholder model still governs the design.
- Guardrail: the design doc is now the architectural source of truth, and the old stub models are explicitly marked as legacy.

- Risk: the public container doc still reflects the earlier placeholder architecture too strongly.
- Guardrail: keep public docs explicitly scoped to current shipped behavior until the runtime catches up, and point design-specific decisions to the steering set.

- Risk: node-to-node routing could accidentally bypass normal TPipe privacy, auth, or PCP constraints.
- Guardrail: the router is required to mediate trace policy, credential policy, PCP forwarding, and outbound memory redaction.

- Risk: a generic P2P agent could be mistaken for a grid node if the runtime relies on names or free-form descriptions.
- Guardrail: grid identity now requires explicit grid metadata plus trusted advertisement and handshake success.

- Risk: open or weak discovery could let untrusted registries or stale nodes steer task routing.
- Guardrail: registry discovery must begin from trust anchors, use lease freshness, and return verifiable advertisements.

- Risk: the steering set can become stale if only code changes are tracked.
- Guardrail: update this file whenever implementation truth or approved design decisions materially change.

## Verification Log

- Reviewed `src/main/kotlin/Pipeline/DistributionGrid.kt` to confirm the currently implemented models and `setEntryPipeline()` behavior.
- Reviewed `src/main/kotlin/Pipeline/Manifold.kt` and `src/main/kotlin/Pipeline/Junction.kt` to align the grid with existing TPipe harness standards.
- Reviewed `src/main/kotlin/P2P/P2PRegistry.kt`, `P2PDescriptor.kt`, `P2PRequest.kt`, and `P2PRequirements.kt` to ground the remote transport, auth, and request-template design.
- Reviewed `src/main/kotlin/P2P/P2PHost.kt` and P2P docs to ground stdio hosting, descriptor transport semantics, and the current lack of built-in grid handshake support.
- Reviewed `docs/core-concepts/developer-in-the-loop.md` and `docs/core-concepts/developer-in-the-loop-pipes.md` to ground the DITL surface.
- Reviewed `docs/core-concepts/tracing-and-debugging.md` to ground the tracing and privacy surface.
- Reviewed `docs/advanced-concepts/memory-introspection.md` and `docs/advanced-concepts/remote-memory.md` to ground optional memory integrations and durable-state expectations.

Commands used during the architecture pass:

- `rg -n "DistributionGrid|Manifold|Junction|P2PRegistry|ConverseHistory|TokenBudget|enableTracing|PcPRequest" ...`
- `sed -n '1,260p' src/main/kotlin/Pipeline/DistributionGrid.kt`
- `sed -n '1,260p' src/main/kotlin/Pipeline/Manifold.kt`
- `sed -n '1,260p' src/main/kotlin/Pipeline/Junction.kt`
- `sed -n '1,320p' src/main/kotlin/P2P/P2PRegistry.kt`
- `sed -n '1,260p' src/main/kotlin/P2P/P2PHost.kt`
- `sed -n '1,260p' docs/containers/distributiongrid.md`
- `sed -n '1,240p' docs/advanced-concepts/p2p/p2p-descriptors-and-transport.md`
- `sed -n '1,260p' docs/core-concepts/developer-in-the-loop.md`
- `sed -n '1,260p' docs/core-concepts/tracing-and-debugging.md`

## Revision History

- 2026-03-25: Initial `DistributionGrid` steering set created with dedicated design, progress, and plan files.
- 2026-03-25: `md/tpipe-context-starting-point.md` updated to point future sessions at the new steering set.
- 2026-03-25: Full node-based `DistributionGrid` architecture spec approved and written into the steering set.
- 2026-03-25: Registry discovery, node marking, handshake, and session protocol requirements were added to the approved design.
