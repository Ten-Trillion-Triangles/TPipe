# Teaching Your Agent to Use Tools (PCP)

Pipes are great at processing text, but on their own, they're trapped inside their own code. They can't read your files, check the weather, or run a database query. **Pipe Context Protocol (PCP)** is how you give your agents "hands"—allowing them to call external tools and functions securely.

---

## Tools vs. Skills

In TPipe, there's a distinction between what an agent *is* and what an agent can *do*:
- **PCP (Tools)**: These are low-level actions. Running a shell command, fetching a URL, or calling a specific Kotlin function.
- **P2P (Collaboration)**: This is calling *another agent*. We'll cover that in the **[P2P Overview](p2p/p2p-overview.md)**.

---

## How it Works: The Tool Belt

When you attach a `PcpContext` to a pipe, you're giving the agent a "tool belt." Each tool has:
1. **A Name**: How the LLM identifies it (e.g., `list_files`).
2. **A Description**: How the LLM knows *when* to use it.
3. **Parameters**: What the tool needs to work (e.g., `directory_path`).

---

## Building a Tool Belt

Here's how you define some common tools:

```kotlin
val myTools = PcpContext().apply {
    // 1. Give it a shell tool
    addStdioOption(StdioContextOptions().apply {
        command = "ls"
        description = "List files in a directory"
        permissions.add(Permissions.Read)
    })

    // 2. Give it a web-fetching tool
    addHttpOption(HttpContextOptions().apply {
        baseUrl = "https://api.weather.com"
        description = "Check the local weather"
        allowedHosts.add("api.weather.com")
        permissions.add(Permissions.Read)
    })
}

// Hand the tool belt to your pipe
pipe.setPcPContext(myTools)
```

---

## Running PCP as a Standalone Service

Sometimes you want your tools to be available to agents written in other languages, or running in separate containers. TPipe can host its tool dispatcher as a standalone service.

### The Stdio "Loop" Mode
You can start TPipe and have it wait for tool requests on `stdin`:
```bash
java -jar tpipe.jar --pcp-stdio-loop
```
*Your agent sends a JSON request, and TPipe instantly prints the result.*

### The HTTP Mode
Launch the host with `--http` and send POST requests to `/pcp`. This is perfect for building a centralized "Tool Server" for your entire infrastructure.

---

## Safety Rails: Protecting Your System

Giving an LLM access to your shell or network is powerful, but dangerous. TPipe includes several "Safety Rails" to keep you in control:

### 1. The "Sandbox" (Python AST Validation)
When an agent writes a Python script to solve a problem, TPipe doesn't just run it blindly. The **PythonSecurityManager** analyzes the script's structure (its AST) to block dangerous imports (like `os` or `subprocess`) and functions (like `eval`) before they ever touch your CPU.

### 2. The "Firewall" (SSRF Protection)
If your agent tries to fetch a URL, the **HttpSecurityManager** steps in.
- It blocks requests to internal IP addresses (like `127.0.0.1` or `192.168.1.1`).
- It prevents **DNS Rebinding** by resolving the hostname once and using that exact IP for the request, ensuring the target doesn't change mid-validation.

### 3. Permissions
Every tool in TPipe requires explicit permissions (`Read`, `Write`, `Execute`, `Delete`). If a tool is marked as `Read` and the agent tries to use it to delete a file, the request is rejected immediately.

---

## Summary

PCP turns your LLM from a talker into a **doer**. By defining clear tool sets and robust security policies, you can build agents that interact with the real world without compromising your system's integrity.
