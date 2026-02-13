---
name: ttt-code-formatter
description: "Formats and styles code according to the TTT Code Formatter Workflow. Use this skill to apply specific bracing, spacing, and KDoc rules to Kotlin files."
---

# TTT Code Formatter Workflow

1.  **Understand Rules**: Read [TTT_STYLE_GUIDE.md](file:///home/cage/Desktop/Workspaces/Autogenesis/Autogenesis/md/TTT_STYLE_GUIDE.md) to refresh on specific bracing and spacing requirements.

2.  **Identify Scope**: Determine which files need formatting. If not specified by the user, ask for the list of files.

3.  **Apply Formatting**: For each file, apply the following rules (Prioritize these over standard auto-formatting):

    *   **Bracing (The "Parentheses Rule")**:
        *   IF the construct has `(...)` (e.g. `fun foo(x: Int)`, `if(...)`, `for(...)`, `class Foo(...)`), put `{` on the **NEXT LINE**.
        *   IF the construct has NO `(...)` (e.g. `init`, `get()`, `class Foo : Bar`), put `{` on the **SAME LINE**.
        *   **EXCEPTION**: DSL builders and scope functions (`.apply {`, `.map {`, `hPanel {`) ALWAYS keep `{` on the **SAME LINE**.

    *   **Spacing**:
        *   `if(condition)` -> No space between keyword and `(`.
        *   `fun foo(x: Int)` -> No space before `:`. Space after `:`.

    *   **Documentation**:
        *   Ensure public functions have KDoc.

4.  **Verify**: Check that the code compiles (if applicable/requested) and looks visually correct according to the guide.
