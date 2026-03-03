# Managing Global Context with ContextBank

In a standard AI application, "memory" is often just a list of previous chat messages. But for complex, multi-agent systems, that's not enough. You need a way for different parts of your application to share knowledge, maintain long-term goals, and coordinate actions without losing track of what's important.

**ContextBank** is TPipe's centralized knowledge repository. It allows your agents to "drop off" information into named pages and "pick up" that information later, even in a different pipeline or a different session.

---

## The Mental Model: The Central Library

Think of your TPipe application as a research team:
- **Pipes** are individual researchers.
- **ContextWindows** are the specific notebooks they carry into a meeting.
- **ContextBank** is the **Central Library**.

Researchers can check books out of the library (load a page), add new findings to the library (save a page), or even reserve certain sections of the library so they don't get interrupted (locking).

---

## Organizing Knowledge into Pages

Everything in the `ContextBank` is organized by a **Page Key** (a simple string). This allows you to sandbox different types of data:

- `user_profile`: Facts about the person the agent is talking to.
- `current_tasks`: A shared `TodoList` for the swarm.
- `system_knowledge`: A `LoreBook` containing technical manuals.
- `temp_scratchpad`: Short-term observations from a specific pipeline run.

---

## Where does the data live? (Storage Modes)

Not all data is created equal. Some things need to be lightning-fast, while others need to survive a computer crash. You control this using **Storage Modes**:

| Mode | Where is it stored? | Why use it? |
|------|--------------------|-------------|
| **`MEMORY_ONLY`** | RAM | For high-speed, temporary data that you don't mind losing on restart. |
| **`MEMORY_AND_DISK`** | RAM + File | **Default**. Best for important data that needs to be fast and persistent. |
| **`DISK_ONLY`** | File Only | For massive archives that you rarely access. Saves your RAM. |
| **`DISK_WITH_CACHE`**| File + Small RAM Cache | The sweet spot for large datasets where you only need a few pages at a time. |
| **`REMOTE`** | Remote Server | For sharing data between multiple instances of TPipe. |

---

## Practical Scenarios

### Scenario 1: Collaborative Research
You have two agents: a **SearchAgent** and a **WriteAgent**.
1. **SearchAgent** finds facts on the web and saves them to a page called `research_facts`.
2. **WriteAgent** later starts its work, pulls the `research_facts` page, and uses that data to write a report.

```kotlin
// In the SearchAgent transformation function:
val searchResults = ContextWindow()
searchResults.addLoreBookEntry("Fact_1", "The sky is blue.")
ContextBank.emplaceWithMutex("research_facts", searchResults, mode = StorageMode.MEMORY_ONLY)

// In the WriteAgent pre-validation function:
val facts = ContextBank.getContextFromBank("research_facts")
contextWindow.merge(facts) // Bring the facts into the current generation
```

### Scenario 2: Preventing "Brain Fog" (Scalability)
If you have thousands of users, you can't keep every user's profile in RAM at once. Use `DISK_WITH_CACHE` to let TPipe manage memory for you:

```kotlin
// Set up a global "manager" for the library
ContextBank.configureCachePolicy(
    CacheConfig(
        maxMemoryBytes = 500 * 1024 * 1024, // Keep library size under 500MB
        evictionPolicy = EvictionPolicy.LRU // Put away the books that haven't been touched in a while
    )
)

// When a user logs in, TPipe pulls their notebook from the "shelf" (disk)
// and keeps it on the "table" (RAM) only while it's being used.
ContextBank.swapBankWithMutex("user_${userId}")
```

---

## Best Practices for a Healthy Library

1. **Use Mutexes**: Always use `emplaceWithMutex` or `swapBankWithMutex` inside pipes. This ensures that if two things try to update the library at once, they wait their turn instead of causing a mess.
2. **Keep it Clean**: If a piece of data is only useful for one specific run, use `MEMORY_ONLY`. Don't clutter your disk with temporary logs.
3. **Be Specific with Keys**: Instead of a page named `data`, use `session_123_intent_analysis`. This makes debugging and inspection much easier.

---

## Next Steps

- Learn about **[Remote Memory](../advanced-concepts/remote-memory.md)** to share your library across servers.
- Dive into the **[ContextBank API Reference](../api/context-bank.md)** for detailed technical signatures.
