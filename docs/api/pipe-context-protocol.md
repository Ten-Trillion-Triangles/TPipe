# Pipe Context Protocol (PCP) API Reference

PCP is the language your agents use to "talk" to the physical world. It handles the mapping of LLM reasoning to actual code execution.

---

## Core Infrastructure

### `PcpContext`
The "Tool Belt" definition.
- `stdioOptions`: A list of allowed terminal commands.
- `httpOptions`: A list of allowed web API endpoints.
- `pythonOptions`: Configuration for the Python sandbox.

### `PcPRequest`
The "Tool Call" itself. This is what the LLM generates when it wants to act.
- `argumentsOrFunctionParams`: The list of strings (arguments) the agent wants to pass to the tool.

---

## Security Managers

These are the "Bodyguards" that protect your system from malicious or buggy agent behavior.

### `HttpSecurityManager` (The Firewall)
- **Purpose**: Prevents SSRF and DNS Rebinding.
- **Key Config**: `HttpSecurityLevel` (`STRICT` to `DISABLED`).

### `PythonSecurityManager` (The Sandbox)
- **Purpose**: Uses AST analysis to block dangerous scripts.
- **Key Config**: `PythonSecurityLevel`.

---

## Tool Hosting (`PcpStdioHost`)

Allows your "Tool Belt" to be used as a standalone service.

- `runOnce()`: Executes a single `PcPRequest` from standard input.
- `runLoop()`: Continuously listens for tool requests.

---

## See Also
- [Conceptual Guide: Teaching Your Agent to Use Tools](../advanced-concepts/pipe-context-protocol.md)
