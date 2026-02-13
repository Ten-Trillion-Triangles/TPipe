---
name: refactor
description: "Provides robust long-running complex task completion for various coding tasks, following a structured workflow of plan loading, step execution, compilation, validation, and error correction."
---

# Refactor Skill

This skill is designed to provide robust long-running complex task completion for various coding tasks. It follows a structured workflow to ensure reliable and verifiable code modifications.

## Workflow

### Step 1: Plan Loading
- Load the requested plan. This can be from:
    - Existing context provided by the user.
    - A specified file path.
    - Direct user instructions.

### Step 2: To-Do List Creation
- Deconstruct the loaded plan into a clear, actionable to-do list.
- Each item in the list represents a distinct step required to complete the task.

### Step 3: Step-by-Step Execution
- Execute each step from the to-do list sequentially, one at a time.

### Step 4: Compile and Fix
- After the completion of each step, compile the project.
- Automatically fix all compiler errors that arise.

### Step 5: Validation
- Invoke the sub-agent responsible for validation/verification.
- Provide the sub-agent with:
    - The original plan.
    - Information on any Git diffs.
    - A list of files that were changed.
- The validator sub-agent will check if the task was completed correctly according to the plan and requirements.

### Step 6: Issue Resolution
- If the validator sub-agent identifies any problematic issues, address and fix them.
- Re-run validation if necessary until all issues are resolved.

### Step 7: Final Explanation
- Once the task is successfully completed and validated, provide a comprehensive explanation of all the work that was done.

## Leveraging Sub-Agents
- This skill should leverage other sub-agents when needed to gain more information, support, or perform specific sub-tasks that fall outside its direct capabilities. This includes invoking the validator sub-agent as described in Step 5.
