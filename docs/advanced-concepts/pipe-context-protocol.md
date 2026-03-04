# Pipe Context Protocol (PCP) - The Tool Belt

In the TPipe ecosystem, an agent isn't restricted to just generating text. **Pipe Context Protocol (PCP)** is the standard interface that equips an agent with a **Tool Belt**—a suite of approved functions that allow the model to interact with the world. Whether it's running shell commands, calling external HTTP APIs, executing Python scripts, or invoking native Kotlin functions, PCP provides a unified and highly secure framework for these interactions.

PCP acts as the universal connector that translates model intentions into safe, validated system actions.

## The PCP Architecture

PCP is built around **Transports**. When you define a tool, you are attaching a specific transport "fitting" to your pipe.

| Transport | Capability | Operational Use Case |
| :--- | :--- | :--- |
| **Stdio** | Shell Execution | Running CLIs, managing filesystems, executing local scripts. |
| **Http** | Network Access | Fetching data from REST APIs, triggering webhooks. |
| **Python** | Sandbox Scripting | Performing complex math, data analysis, or automation in Python. |
| **Tpipe** | Native Functions | Calling your own Kotlin/JVM application logic directly. |

---

## Building the Tool Belt (`PcpContext`)

You define exactly what is allowed in a `PcpContext`. This is where you establish the safety boundaries for your agent.

```kotlin
val belt = PcpContext()

// 1. Add a read-only shell tool
belt.addStdioOption(StdioContextOptions().apply {
    command = "ls"
    description = "List files in the current working directory."
    permissions.add(Permissions.Read)
})

// 2. Add an HTTP connection to a specific API
belt.addHttpOption(HttpContextOptions().apply {
    baseUrl = "https://api.maintenance-logs.com"
    allowedHosts.add("api.maintenance-logs.com")
    description = "Fetch maintenance records for specific valve IDs."
})

// 3. Attach the belt to a Pipe
pipe.setPcPContext(belt)
```

---

## The Operational Flow

1.  **Declaration**: You attach the `PcpContext` to your Pipe.
2.  **Assembly**: TPipe automatically serializes your tool definitions into the model's system prompt.
3.  **Request**: The model generates a structured JSON payload requesting a tool call (e.g., "Run `ls -la`").
4.  **Validation**: The **PCP Security Managers** intercept the request. They check if the tool is on the allow-list, if the arguments are safe, and if the permissions are sufficient.
5.  **Execution**: The **Dispatcher** executes the tool and captures the output.
6.  **Reply**: The output is pumped back into the model's context, allowing it to continue its reasoning with the new data.

---

## Industrial-Grade Security

PCP is wrapped in multiple protective layers to ensure an LLM can never "burst" its sandbox:

*   **CommandSecurityManager**: Enforces a strict allow-list of binaries and validates every argument to prevent injection attacks.
*   **HttpSecurityManager**: Blocks all private network access (SSRF protection) by default and enforces host and method restrictions.
*   **PythonSecurityManager**: Executes scripts in a restricted environment, blocking dangerous modules (like `os` or `subprocess`) and enforcing strict memory and timeout limits.
*   **Permissions Engine**: Every single tool call requires explicit permissions (Read, Write, Execute, Delete).

---

## Advanced: Interactive Tool Sessions

PCP supports more than just "one-shot" calls. It can manage **Interactive Stdio**, allowing a model to maintain a persistent terminal session across multiple turns of generation. This allows an agent to run a command, analyze the output, and send follow-up input to the same process.

```kotlin
val terminal = StdioContextOptions().apply {
    command = "bash"
    executionMode = StdioExecutionMode.INTERACTIVE
    keepSessionAlive = true
}
```

---

## Next Steps

Now that your agents have tools, learn how to use them for common tasks like gathering data from the web.

**→ [Basic PCP Usage](basic-pcp-usage.md)** - Getting started with shell and HTTP patterns.
