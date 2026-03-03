# Command-Line Reference

TPipe provides several command-line flags to control how the application starts and which hosting modes are active. These flags are useful for running TPipe as a standalone service in containers or across distributed systems.

## Basic Usage

```bash
java -jar tpipe.jar [FLAGS]
```

## Available Flags

| Flag | Description |
|------|-------------|
| `--http` | Starts the TPipe host with the Ktor Netty engine on port 8080 (default). Enables POST endpoints for P2P and PCP. |
| `--remote-memory` | Enables the memory server and starts the HTTP host. Equivalent to setting `TPipeConfig.remoteMemoryEnabled = true`. |
| `--stdio-once` | Starts a P2P Stdio host that processes a single `P2PRequest` JSON from standard input, prints the `P2PResponse` to standard output, and exits. |
| `--stdio-loop` | Starts a P2P Stdio host that processes multiple `P2PRequest` JSON objects in a loop until the string "exit" is received on standard input. |
| `--pcp-stdio-once` | Starts a PCP Stdio host that processes a single `PcPRequest` JSON from standard input, prints the `PcpExecutionResult` to standard output, and exits. |
| `--pcp-stdio-loop` | Starts a PCP Stdio host that processes multiple `PcPRequest` JSON objects in a loop until the string "exit" is received on standard input. |

## Combination and Precedence

- If no flags are provided, TPipe defaults to `--http` mode.
- If both `--http` and any of the `--stdio` flags are provided, the application will prioritize starting the HTTP server.
- The `--remote-memory` flag automatically enables the HTTP host to serve the memory REST endpoints.

## Examples

### Running as a P2P HTTP Server
```bash
java -jar tpipe.jar --http
```

### Running as a Remote Memory Host
```bash
java -jar tpipe.jar --remote-memory
```

### Calling an Agent from a Shell Script (One-Shot)
```bash
echo '{"agentName": "my-agent", "prompt": {"elements": [{"text": "Hello"}]}}' | java -jar tpipe.jar --stdio-once
```

### Interactive Stdio Session
```bash
java -jar tpipe.jar --stdio-loop
# Input JSON on each line...
# Results will be printed as JSON strings...
```
