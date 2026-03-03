# Command-Line Reference

The TPipe executable supports several flags to control its lifecycle and hosting mode. These are essential for deploying TPipe as a standalone service in distributed environments.

---

## Technical Specifications

| Flag | Mode | Technical Description |
|------|------|-----------------------|
| `--http` | **Ktor Host** | Starts the Netty engine on port 8080. Enables `/p2p`, `/pcp`, and `/context` endpoints. |
| `--remote-memory` | **Memory Server** | Equivalent to `--http` but explicitly flags the instance as a state repository. |
| `--stdio-once` | **P2P One-Shot** | Reads one `P2PRequest` line from `stdin`, executes, and exits. |
| `--stdio-loop` | **P2P Daemon** | Reads `P2PRequest` lines continuously from `stdin` until "exit" is received. |
| `--pcp-stdio-once` | **PCP One-Shot** | Reads one `PcPRequest` line from `stdin`, executes, and exits. |
| `--pcp-stdio-loop` | **PCP Daemon** | Reads `PcPRequest` lines continuously from `stdin` until "exit" is received. |

---

## Deployment Scenarios

### 1. The Secure Tool Gateway
If you want to provide a shared set of secure terminal/web tools to your infrastructure:
```bash
java -jar tpipe.jar --pcp-stdio-loop
```

### 2. The Collaborative Agent Cluster
To host a specialist agent (e.g., "Analyst") accessible via HTTP:
```bash
java -jar tpipe.jar --http
```

### 3. Cross-Language Tooling
Use TPipe's AST-based security from a Python or Node.js app:
```bash
# From Python:
import subprocess, json
request = {"stdioContextOptions": {"command": "ls"}, "argumentsOrFunctionParams": ["-la"]}
result = subprocess.check_output(["java", "-jar", "tpipe.jar", "--pcp-stdio-once"], input=json.dumps(request))
```

---

## See Also
- [Advanced Concept: Pipe Context Protocol](../advanced-concepts/pipe-context-protocol.md)
- [Advanced Concept: P2P Overview](../advanced-concepts/p2p/p2p-overview.md)
