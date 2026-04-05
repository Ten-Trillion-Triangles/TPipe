# TodoList API

## Table of Contents
- [What is the TodoList System?](#what-is-the-todolist-system)
- [Understanding the Components](#understanding-the-components)
- [How the System Works](#how-the-system-works)
- [Using TodoLists with Pipes](#using-todolists-with-pipes)
- [Task Focus: Directing AI Attention](#task-focus-directing-ai-attention)
- [Complete Usage Guide](#complete-usage-guide)
- [Integration Reference](#integration-reference)

## What is the TodoList System?

The TodoList system is TPipe's built-in task management framework designed specifically for AI agents. It solves a common problem: how do you give an AI a structured list of tasks to complete while tracking what it's done?

### The Problem It Solves

When working with AI agents on multi-step projects, you typically face these challenges:

1. **Unclear task structure** - The AI doesn't know what tasks exist or their order
2. **No progress tracking** - You can't easily see what's been completed
3. **Lost context** - The AI forgets what it was working on between executions
4. **Manual coordination** - You have to manually tell the AI what to do next

### The Solution

TodoLists provide:

- **Structured task definitions** with clear completion requirements
- **Automatic injection** into AI prompts so the agent always knows what to do
- **Progress tracking** through completion flags and work history
- **Persistent storage** in ContextBank so tasks survive between runs
- **Task focusing** to direct AI attention to specific tasks when needed

### When to Use TodoLists

**Good use cases:**
- Multi-step analysis or generation tasks
- Code review workflows with specific checks
- Document processing with multiple stages
- Quality assurance with defined test criteria
- Any workflow where tasks must be completed in order or tracked

**Not ideal for:**
- Single-step tasks (just use a regular prompt)
- Highly dynamic workflows where tasks change constantly
- Tasks that don't have clear completion criteria

## Understanding the Components

The TodoList system has three main classes that work together.

### TodoListTask: A Single Task

This represents one item on your todo list.

```kotlin
data class TodoListTask(
    var taskNumber: Int = 0,
    var task: String = "",
    var completionRequirements: String = "",
    var isComplete: Boolean = false
)
```

**What each field means:**

- **`taskNumber`** - A unique number identifying this task (typically 1, 2, 3...). This is how you reference tasks and control their order.

- **`task`** - A clear description of what needs to be done. This should be specific enough that the AI understands the goal. Example: "Review the authentication code for SQL injection vulnerabilities"

- **`completionRequirements`** - Specific criteria that define when this task is complete. This helps the AI know when it's done and helps you verify completion. Example: "Must check all database queries and identify any unsafe string concatenation"

- **`isComplete`** - A boolean flag you set to `true` when the task is finished. The AI doesn't automatically set this - your code does after verifying completion.

**Example task:**
```kotlin
TodoListTask(
    taskNumber = 1,
    task = "Analyze the sales data for Q4 2024",
    completionRequirements = "Identify top 3 products, revenue trends, and any anomalies",
    isComplete = false
)
```

### TodoTaskArray: The Task Container

This is a simple wrapper around a list of tasks.

```kotlin
data class TodoTaskArray(
    var tasks: MutableList<TodoListTask> = mutableListOf()
)
```

You access the actual task list through `todoTaskArray.tasks`. This extra layer exists for serialization compatibility with external systems.

### TodoList: The Complete System

This is the main class you'll work with.

```kotlin
data class TodoList(
    var tasks: TodoTaskArray = TodoTaskArray(),
    var workHistory: ConverseHistory = ConverseHistory()
)
```

**The two key components:**

1. **`tasks`** - Contains your TodoTaskArray with all the tasks. Access individual tasks via `todoList.tasks.tasks`.

2. **`workHistory`** - A conversation history that records what work has been done. Each time the AI works on a task, you can add an entry here documenting what happened. This creates an audit trail and helps track progress over time.

**Key methods:**

- **`isEmpty(): Boolean`** - Returns `true` if there are no tasks in the list. Useful for checking before processing.

- **`find(taskNumber: Int): TodoListTask?`** - Searches for a task by its number. Returns the task if found, or `null` if no task with that number exists. This is faster than iterating through the list yourself.

- **`setTodoTaskNumber(taskNumber: Int, content: MultimodalContent)`** - A convenience method that sets which task the AI should focus on. This modifies the content object's metadata to highlight a specific task.

- **`toString(): String`** - Converts the task list to a JSON string (excludes work history). Useful for debugging or displaying the task list.

## How the System Works

Understanding the flow of how TodoLists work will help you use them effectively.

### The Complete Flow

```
1. Create TodoList → 2. Store in ContextBank → 3. Link to Pipe → 4. Execute → 5. Update & Save
```

Let's break down each step:

### Step 1: Creating a TodoList

You create a TodoList and populate it with tasks:

```kotlin
val todoList = TodoList()

// Add tasks to the list
todoList.tasks.tasks.add(TodoListTask(
    taskNumber = 1,
    task = "Review security vulnerabilities",
    completionRequirements = "Check all authentication and authorization code",
    isComplete = false
))

todoList.tasks.tasks.add(TodoListTask(
    taskNumber = 2,
    task = "Document findings",
    completionRequirements = "Create report with severity ratings and recommendations",
    isComplete = false
))
```

**Best practices:**
- Number tasks sequentially starting from 1
- Write clear, specific task descriptions
- Define measurable completion requirements
- Start with `isComplete = false`

### Step 2: Storing in ContextBank

TodoLists are stored in ContextBank using page keys. This makes them accessible across different pipes and pipelines:

```kotlin
ContextBank.emplaceTodoList(
    key = "security-review",           // Unique identifier
    todoList = todoList,                // Your todo list
    writeToDisk = true,                 // Save to disk for persistence
    overwrite = true                    // Replace if already exists
)
```

**What this does:**
- Stores the todo list in memory under the key "security-review"
- If `writeToDisk = true`, saves it to `~/.tpipe/TPipe-Default/memory/todo/security-review.todo`
- The todo list persists between program runs when saved to disk
- Other pipes and pipelines can access it using the same key

**Retrieving later:**
```kotlin
val todoList = ContextBank.getPagedTodoList("security-review")
```

### Step 3: Linking to a Pipe

You configure a pipe to automatically inject the todo list into its system prompt:

```kotlin
val pipe = BedrockPipe()
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setSystemPrompt("You are a security analyst.")
    .setTodoListPageKey("security-review")  // Links to ContextBank
    .applySystemPrompt()                     // Triggers injection
```

**What happens during `applySystemPrompt()`:**

1. The pipe checks if a todo list page key is set
2. It retrieves the todo list from ContextBank using that key
3. It adds default instructions explaining the todo list format to the AI
4. It serializes the todo list to JSON
5. It appends everything to the system prompt

**The AI receives something like:**
```
You are a security analyst.

You will be provided with a todo list that has a list of tasks you have been 
asked to complete. Each element on the list will contain a description of the 
task, the requirements to verify it has been completed, and whether it has been 
completed or not. The todo list is as follows:

{
  "tasks": [
    {
      "taskNumber": 1,
      "task": "Review security vulnerabilities",
      "completionRequirements": "Check all authentication and authorization code",
      "isComplete": false
    },
    {
      "taskNumber": 2,
      "task": "Document findings",
      "completionRequirements": "Create report with severity ratings and recommendations",
      "isComplete": false
    }
  ]
}
```

### Step 4: Executing the Pipe

When you execute the pipe, the AI automatically sees the todo list:

```kotlin
val result = runBlocking { 
    pipe.execute("Begin the security review") 
}
```

The AI now has full context about what tasks exist and can work on them accordingly.

### Step 5: Updating and Saving

After execution, you typically want to update task status and save progress:

```kotlin
// Get the current todo list
val todoList = ContextBank.getPagedTodoList("security-review")

// Find and update a task
val task = todoList.find(1)
if (task != null) {
    task.isComplete = true
    
    // Record what was done
    todoList.workHistory.add(
        ConverseRole.agent,
        MultimodalContent(text = "Completed security review - found 3 critical issues")
    )
}

// Save the updated list
ContextBank.emplaceTodoList("security-review", todoList, writeToDisk = true, overwrite = true)
```

This creates a complete cycle where tasks are tracked and progress is preserved.

## Using TodoLists with Pipes

Now that you understand the flow, let's look at practical usage patterns.

### Basic Setup

The simplest way to use a TodoList:

```kotlin
// 1. Create the list
val todoList = TodoList()
todoList.tasks.tasks.add(TodoListTask(
    taskNumber = 1,
    task = "Analyze customer feedback",
    completionRequirements = "Categorize into themes and identify top 3 issues",
    isComplete = false
))

// 2. Store it
ContextBank.emplaceTodoList("feedback-analysis", todoList, writeToDisk = true)

// 3. Create a pipe that uses it
val pipe = BedrockPipe()
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setSystemPrompt("You are a customer feedback analyst.")
    .setTodoListPageKey("feedback-analysis")
    .applySystemPrompt()

// 4. Execute
val result = runBlocking { pipe.execute("Analyze the feedback data") }

// 5. Update status
val updatedList = ContextBank.getPagedTodoList("feedback-analysis")
val task = updatedList.find(1)
task?.isComplete = true
ContextBank.emplaceTodoList("feedback-analysis", updatedList, writeToDisk = true, overwrite = true)
```

### Customizing Instructions

The default instructions might not fit your use case. You can customize them:

```kotlin
val pipe = BedrockPipe()
    .setSystemPrompt("You are a code reviewer.")
    .setTodoListPageKey("code-review-tasks")
    .setTodoListInstructions("""
        Below is your code review checklist. Each item must be thoroughly checked.
        Work through the list in order. For each task, provide specific examples
        of issues found or confirm that no issues exist. When you complete a task,
        explicitly state: "TASK [number] COMPLETE"
    """.trimIndent())
    .applySystemPrompt()
```

This replaces the default explanation with your custom instructions, giving you full control over how the AI interprets the todo list.

### Processing Tasks Sequentially

A common pattern is to work through tasks one at a time:

```kotlin
val todoList = ContextBank.getPagedTodoList("my-tasks")

for (task in todoList.tasks.tasks) {
    if (!task.isComplete) {
        println("Starting task ${task.taskNumber}: ${task.task}")
        
        // Execute the pipe for this task
        val result = runBlocking { 
            pipe.execute("Complete task ${task.taskNumber}") 
        }
        
        // Mark complete
        task.isComplete = true
        
        // Log the work
        todoList.workHistory.add(
            ConverseRole.agent,
            MultimodalContent(text = "Task ${task.taskNumber}: ${result.text}")
        )
        
        // Save progress after each task
        ContextBank.emplaceTodoList("my-tasks", todoList, writeToDisk = true, overwrite = true)
        
        println("Completed task ${task.taskNumber}")
    }
}

println("All tasks complete!")
```

This pattern ensures progress is saved after each task, so if something fails, you don't lose your work.

## Task Focus: Directing AI Attention

Sometimes you want the AI to concentrate on one specific task rather than choosing for itself. The task focus mechanism handles this.

### Why Use Task Focus?

Without focus, the AI sees all tasks and might:
- Work on tasks out of order
- Try to do multiple tasks at once
- Choose the easiest task instead of the next one

With focus, you explicitly tell the AI: "Work on THIS task right now."

### How Focus Works

When you set a focus task number, the pipe does two things:

1. **Injects the full todo list** - The AI still sees all tasks for context
2. **Highlights the focus task** - Adds extra instructions emphasizing that specific task

The AI receives something like:
```
[Your system prompt]
[Todo list instructions]
[Full todo list JSON]

The current task you must focus on from this todo list is:
{
  "taskNumber": 2,
  "task": "Write unit tests",
  "completionRequirements": "Coverage above 80%",
  "isComplete": false
}
```

### Setting Focus: Two Methods

**Method 1: Directly on MultimodalContent**

```kotlin
val content = MultimodalContent(text = "Work on the current task")
content.setTodoTaskNumber(2)  // Focus on task 2

val result = pipe.execute(content)
```

This is the most direct approach. You're modifying the content object to include focus information in its metadata.

**Method 2: Using TodoList Helper**

```kotlin
val content = MultimodalContent(text = "Work on the current task")
todoList.setTodoTaskNumber(2, content)  // Focus on task 2

val result = pipe.execute(content)
```

This is a convenience method that does the same thing as Method 1. Use whichever feels more natural in your code.

### Practical Focus Example

Here's a complete example showing focus in action:

```kotlin
// Setup
val todoList = TodoList()
todoList.tasks.tasks.add(TodoListTask(1, "Parse data", "Extract all records", false))
todoList.tasks.tasks.add(TodoListTask(2, "Validate data", "Check for errors", false))
todoList.tasks.tasks.add(TodoListTask(3, "Generate report", "Create summary", false))

ContextBank.emplaceTodoList("data-pipeline", todoList, writeToDisk = true)

val pipe = BedrockPipe()
    .setSystemPrompt("You are a data processor.")
    .setTodoListPageKey("data-pipeline")
    .applySystemPrompt()

// Process each task with focus
for (task in todoList.tasks.tasks) {
    if (!task.isComplete) {
        // Create content with focus on this specific task
        val content = MultimodalContent(text = "Complete the current task")
        content.setTodoTaskNumber(task.taskNumber)
        
        // Execute - AI focuses on just this task
        val result = runBlocking { pipe.execute(content) }
        
        // Update and save
        task.isComplete = true
        todoList.workHistory.add(
            ConverseRole.agent,
            MultimodalContent(text = "Task ${task.taskNumber} result: ${result.text}")
        )
        ContextBank.emplaceTodoList("data-pipeline", todoList, writeToDisk = true, overwrite = true)
    }
}
```

### When to Use Focus vs. No Focus

**Use focus when:**
- You want strict task ordering
- The AI should work on one thing at a time
- Tasks must be completed sequentially
- You're iterating through tasks programmatically

**Don't use focus when:**
- The AI should choose the best task to work on
- Tasks can be done in any order
- You want the AI to prioritize based on context
- Multiple tasks can be done simultaneously

## Complete Usage Guide

Let's walk through a real-world example from start to finish.

### Scenario: Code Review Workflow

You want an AI to review code with a specific checklist of items to verify.

### Step 1: Define Your Tasks

```kotlin
val reviewTasks = TodoList()

reviewTasks.tasks.tasks.add(TodoListTask(
    taskNumber = 1,
    task = "Check for SQL injection vulnerabilities",
    completionRequirements = "Review all database queries for unsafe string concatenation or missing parameterization",
    isComplete = false
))

reviewTasks.tasks.tasks.add(TodoListTask(
    taskNumber = 2,
    task = "Verify input validation",
    completionRequirements = "Ensure all user inputs are validated before processing",
    isComplete = false
))

reviewTasks.tasks.tasks.add(TodoListTask(
    taskNumber = 3,
    task = "Review error handling",
    completionRequirements = "Check that errors don't expose sensitive information",
    isComplete = false
))

reviewTasks.tasks.tasks.add(TodoListTask(
    taskNumber = 4,
    task = "Check authentication logic",
    completionRequirements = "Verify proper session management and access controls",
    isComplete = false
))
```

### Step 2: Store the TodoList

```kotlin
ContextBank.emplaceTodoList(
    key = "code-review-checklist",
    todoList = reviewTasks,
    writeToDisk = true,
    overwrite = true
)
```

### Step 3: Create the Review Pipe

```kotlin
val reviewPipe = BedrockPipe()
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setSystemPrompt("""
        You are an expert security code reviewer. You will be given a checklist
        of security items to verify. For each item, provide specific findings
        including file names, line numbers, and code snippets where relevant.
    """.trimIndent())
    .setTodoListPageKey("code-review-checklist")
    .setTodoListInstructions("""
        Below is your security review checklist. Work through each item carefully.
        For each task, either identify specific vulnerabilities or confirm the code
        is secure in that area. Be thorough and provide evidence for your conclusions.
    """.trimIndent())
    .applySystemPrompt()
```

### Step 4: Execute the Review

```kotlin
// Load the code to review
val codeToReview = File("src/main/AuthenticationService.kt").readText()

// Execute review with focus on each task
val reviewTasks = ContextBank.getPagedTodoList("code-review-checklist")

for (task in reviewTasks.tasks.tasks) {
    if (!task.isComplete) {
        println("\n=== Reviewing: ${task.task} ===")
        
        // Create focused content
        val content = MultimodalContent(text = """
            Review the following code for: ${task.task}
            
            Code:
            $codeToReview
        """.trimIndent())
        
        content.setTodoTaskNumber(task.taskNumber)
        
        // Execute review
        val result = runBlocking { reviewPipe.execute(content) }
        
        println("Findings: ${result.text}")
        
        // Mark complete and log
        task.isComplete = true
        reviewTasks.workHistory.add(
            ConverseRole.agent,
            MultimodalContent(text = """
                Task ${task.taskNumber} - ${task.task}
                Findings: ${result.text}
            """.trimIndent())
        )
        
        // Save progress
        ContextBank.emplaceTodoList(
            "code-review-checklist",
            reviewTasks,
            writeToDisk = true,
            overwrite = true
        )
    }
}

println("\n=== Review Complete ===")
println("Work history saved to ContextBank")
```

### Step 5: Generate Summary Report

```kotlin
// Create a summary from the work history
val reviewTasks = ContextBank.getPagedTodoList("code-review-checklist")

println("Security Review Summary")
println("=" * 50)

for (entry in reviewTasks.workHistory.history) {
    println("\n${entry.content.text}")
}

// Check completion status
val incomplete = reviewTasks.tasks.tasks.filter { !it.isComplete }
if (incomplete.isEmpty()) {
    println("\n✓ All review tasks completed")
} else {
    println("\n⚠ Incomplete tasks: ${incomplete.map { it.taskNumber }}")
}
```

## Integration Reference

### ContextBank Methods

**`getPagedTodoList(key: String, copy: Boolean = true): TodoList`**

Retrieves a todo list from ContextBank.

- **`key`**: The page key used when storing the list
- **`copy`**: If `true`, returns a copy; if `false`, returns direct reference
- Returns the TodoList, or an empty TodoList if not found

**`emplaceTodoList(key: String, todoList: TodoList, writeToDisk: Boolean, overwrite: Boolean)`**

Stores a todo list in ContextBank.

- **`key`**: Unique identifier for this todo list
- **`todoList`**: The TodoList object to store
- **`writeToDisk`**: If `true`, saves to `~/.tpipe/TPipe-Default/memory/todo/<key>.todo`
- **`overwrite`**: If `true`, replaces existing list with same key

**`emplaceTodoListWithMutex(key: String, todoList: TodoList, writeToDisk: Boolean, overwrite: Boolean)`**

Thread-safe version of `emplaceTodoList`. Use this when multiple threads might save simultaneously.

See [ContextBank API](context-bank.md#todolist-integration) for complete documentation.

### Pipe Methods

**`setTodoListPageKey(key: String): Pipe`**

Links this pipe to a todo list in ContextBank. The todo list will be injected when `applySystemPrompt()` is called.

**`setTodoListInstructions(instructions: String): Pipe`**

Overrides the default instructions that explain the todo list to the AI. Use this to customize how the AI should interpret and work with the tasks.

**Properties:**
- **`todoPageKey: String`**: The ContextBank page key for the todo list
- **`todoListInstructions: String`**: Custom instructions for todo list handling
- **`injectTodoList: Boolean`**: Internal flag controlling injection

See [Pipe API](pipe.md#todolist-integration) for complete documentation.

### MultimodalContent Methods

**`setTodoTaskNumber(taskNumber: Int)`**

Sets which task the AI should focus on. Stores the task number in `metadata["todoTaskNumber"]`.

See [MultimodalContent API](multimodal-content.md) for complete documentation.

## Best Practices

### Task Design

**Write clear task descriptions:**
```kotlin
// Good
task = "Review authentication code for SQL injection vulnerabilities"

// Too vague
task = "Check security"
```

**Define measurable requirements:**
```kotlin
// Good
completionRequirements = "Verify all database queries use parameterized statements"

// Too vague
completionRequirements = "Make sure it's secure"
```

**Use sequential numbering:**
```kotlin
// Good
tasks numbered 1, 2, 3, 4...

// Avoid
tasks numbered 1, 5, 10, 100...
```

### Storage and Persistence

**Always save after updates:**
```kotlin
task.isComplete = true
ContextBank.emplaceTodoList("my-tasks", todoList, writeToDisk = true, overwrite = true)
```

**Use descriptive page keys:**
```kotlin
// Good
"security-review-auth-module"

// Less clear
"tasks1"
```

**Enable disk persistence for important work:**
```kotlin
ContextBank.emplaceTodoList("critical-tasks", todoList, writeToDisk = true)
```

### Work History

**Record Meaningful Progress:**
```kotlin
todoList.workHistory.add(
    ConverseRole.agent,
    MultimodalContent(text = "Task 1 complete: Found 3 SQL injection points in UserService.kt lines 45, 67, 89")
)
```

**Use appropriate roles:**
- **`ConverseRole.agent`**: For AI-generated work
- **`ConverseRole.user`**: For human input or instructions
- **`ConverseRole.system`**: For automated system events

### Pipe Configuration

**Always call applySystemPrompt() after configuration:**
```kotlin
pipe.setTodoListPageKey("my-tasks")
    .setTodoListInstructions("Custom instructions")
    .applySystemPrompt()  // Must call this!
```

**Use focus for sequential processing:**
```kotlin
for (task in todoList.tasks.tasks) {
    content.setTodoTaskNumber(task.taskNumber)
    pipe.execute(content)
}
```

**Don't use focus when AI should choose:**
```kotlin
// Let AI pick the best task to work on
pipe.execute("Work on the most important task")
```
``
)
```
## Next Steps

- [Dictionary API](dictionary.md) - Continue into token counting and truncation helpers.
