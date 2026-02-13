---
name: log-investigator
description: Specialized in locating, parsing, and analyzing project log files. Use this skill to debug issues, verify system behavior, or find specific data points within application logs.
---

# Log Investigator

## Overview
The Log Investigator skill allows Gemini CLI to navigate complex logging systems. It doesn't just look for files; it understands the logging configuration, identifies where the "truth" is stored, and uses precise CLI tools to extract meaning from large log volumes.

## Workflow

### 1. Discovery
If the log location isn't in your memory:
- **Analyze Configuration**: Look for `LogWriter` or `Logger` implementations (e.g., in `sharedModel/src/jvmMain/kotlin/org/ttt/autogenesis/logging/LogWriter.jvm.kt`).
- **Check Home Directories**: In this project, logs are typically at `~/.autogenesis/logs/`.
- **Identify Latest Log**: Use `ls -t` to find the most recently modified log file.

### 2. Extraction
Use shell commands to pinpoint data:
- **Search**: Use `grep` with specific categories (e.g., `LLM`, `NETWORK`, `UI`).
- **Trace**: Follow a specific ID or timestamp.
- **Truncate**: Don't read whole log files into context. Use `tail`, `grep`, or `awk` to extract only the relevant lines.

### 3. Analysis & Verification
- **Validate State**: "The logs show the connection was established at T1 and dropped at T2 due to a timeout."
- **Identify Root Cause**: Look for `ERROR` or `WARN` tags preceding a failure.
- **Confirm Logic**: Check if expected logs (e.g., "Added border...") appear after a user action.

## Guidelines
- **Be Token Efficient**: NEVER read a large log file directly with `read_file`. Use `grep` or `tail` to bring only the needles into the context.
- **Identify Server Types**: Distinguish between different logs (e.g., `autogenesis-*.log` vs others).
- **Cross-Reference**: Match timestamps in logs with user-reported issue times.

## Resources
- [parsing-techniques.md](references/parsing-techniques.md): A guide to efficient log discovery and CLI-based parsing.
