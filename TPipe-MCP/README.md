# TPipe-MCP

Bidirectional bridge library for converting between Model Context Protocol (MCP) and Pipe Context Protocol (PCP) in the TPipe framework.

## Features

- Convert MCP JSON to PCP context
- Convert PCP context to MCP JSON
- Support for MCP tools, resources, and prompts
- Seamless integration with existing Pipe classes
- Error handling and validation

## Usage

### MCP to PCP Conversion
```kotlin
import com.TTT.MCP.Extensions.setMcpContext

val pipe = BedrockPipe()
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setMcpContext("""
        {
            "tools": [
                {
                    "name": "file_reader",
                    "description": "Read file contents",
                    "inputSchema": {
                        "type": "object",
                        "properties": {
                            "path": {"type": "string"}
                        }
                    }
                }
            ]
        }
    """)

pipe.init()
val result = pipe.execute("Read the config file")
```

### PCP to MCP Conversion
```kotlin
import com.TTT.MCP.Extensions.exportToMcp

// Configure pipe with PCP context
val pipe = BedrockPipe()
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")

// Export PCP context as MCP JSON
val mcpJson = pipe.exportToMcp()
println(mcpJson)
```

## Dependencies

- Main TPipe module
- MCP Kotlin SDK
- kotlinx.serialization