# Architecture Patterns Reference

Use these patterns when suggesting implementations to the user. Consider the project's existing use of Kotlin and pipeline-based architectures.

## 1. Pipeline Pattern (TPipe)
- **Use Case**: Complex data processing, multi-agent orchestration, or sequential transformations.
- **Project Context**: The project already uses `TPipe`. Suggest this for AI processing or complex workflows where logic can be decoupled into discrete 'Pipes'.

## 2. Command Pattern / Action System
- **Use Case**: Undo/Redo functionality, logging actions, or decoupling request from execution.
- **Project Context**: Check `ActionHistory` implementation. Useful for game logic or system state changes.

## 3. Observer / Event Bus
- **Use Case**: Decoupling components that need to respond to state changes without direct dependencies.
- **Project Context**: Useful for UI updates or broadcast systems (e.g., `agent-work-stream-broadcast-fix.md`).

## 4. State Machine
- **Use Case**: Managing complex object lifecycles or UI states.
- **Project Context**: Good for turn-based logic or multi-step processes like the `turn-harness-plan.md`.

## 5. Repository Pattern
- **Use Case**: Abstracting data access (DB, API, Local Storage).
- **Project Context**: Useful for managing `sharedModel` or external SDK integrations like `accelbyteSdk`.

## 6. Facade Pattern
- **Use Case**: Providing a simplified interface to a complex set of classes or a subsystem.
- **Project Context**: See `FACADE_DOCUMENTATION_PLAN.md`. Use this when integrating complex external SDKs.
