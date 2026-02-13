---
name: doc-expert
description: Expert in codebase documentation and KDoc. Improves code comments, explains methods, and ensures strict cross-linking using [] for all referenced or interacting symbols.
---

# Doc Expert

## Overview
This skill focuses on making codebases self-documenting and navigable. It audits existing comments, upgrades them to proper KDoc standards, and meticulously adds square-bracket `[ ]` links to ensure the IDE and developers can easily jump between related components.

## Workflow

### Phase 1: Context Gathering
1.  **Read the Code**: Analyze the target file and its imports.
2.  **Identify Dependencies**: Look for methods called within the target functions and external classes referenced in signatures.
3.  **Find the "Why"**: Look for usages of the class/method elsewhere in the codebase to understand its role in the larger system.

### Phase 2: KDoc Implementation
Follow the [kdoc-standards.md](references/kdoc-standards.md) for every modification.
- **Param & Return**: Ensure every `@param` and `@return` is present and accurate.
- **Square Bracket Linking**: 
    - Link to every parameter in the description.
    - Link to every method used inside the function body if it's a significant interaction.
    - Link to the return type class if it's a custom model.

### Phase 3: Prose Improvement
- Remove "obvious" comments (e.g., `// set the name` -> `@param name the new name`).
- Explain side effects (e.g., "This method triggers a [SharedFlow] emission").
- Add `@see` links to high-level documentation or related implementations.

## Guidelines
- **Be Idiomatic**: Use Kotlin-style documentation for Kotlin files, Javadoc for Java, etc.
- **Link Everything**: If a method `A` interacts with method `B`, `A`'s documentation MUST contain a link to `[B]`.
- **Preserve Logic**: Never change the code itself, only the comments and KDoc strings.
- **Verify Imports**: If linking to an external class, ensure it is imported or use the fully qualified name if necessary (though preference is for imported names).

## Resources
- [kdoc-standards.md](references/kdoc-standards.md): Detailed rules for KDoc formatting and linking.
