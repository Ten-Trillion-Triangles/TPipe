# Log Investigation Techniques

Use these strategies to find and analyze logs in any project.

## 1. Locating Log Systems
If the log location is unknown, search for:
- **Configuration Files**: `logback.xml`, `log4j2.xml`, `logging.properties`, `application.yml`, `settings.json`.
- **Source Code**: Search for "Logger", "LogFactory", "println", "console.log", "LogWriter".
- **Common Directories**: `/var/log`, `~/.config/[app]/logs`, `~/.local/state/[app]/logs`, `./logs`, `./build/logs`.

## 2. Parsing Logs via CLI
Once logs are located, use these commands to extract data:
- **Filtering by Category**: `grep "\[NETWORK\]" path/to/log`
- **Filtering by Priority**: `grep "\[ERROR\]" path/to/log`
- **Recent Activity**: `tail -n 100 path/to/log`
- **Time Range**: `awk '$1 > "2026-02-12T14:00:00" && $1 < "2026-02-12T15:00:00"' path/to/log`
- **Context Search**: `grep -C 5 "Exception" path/to/log` (shows 5 lines before and after).

## 3. Validating Events
To prove something happened:
1. Identify the unique identifier (e.g., `transaction_id`, `session_id`, `playerId`).
2. Grep for that ID to see the full lifecycle of the event.
3. Check for the absence of "ERROR" or "WARN" tags following the initiation of the event.
