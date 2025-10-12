# MCP-to-PCP Bridge Module Implementation Plan

## 1. Module Structure and Location

### 1.1 Module Creation
- **Location**: `/TPipe/TPipe-MCP/`
- **Module Name**: `TPipe-MCP`
- **Purpose**: Bridge library to convert Model Context Protocol (MCP) JSON to Pipe Context Protocol (PCP)

### 1.2 Directory Structure
```
TPipe-MCP/
├── src/
│   ├── main/
│   │   ├── kotlin/
│   │   │   └── com/
│   │   │       └── TTT/
│   │   │           └── MCP/
│   │   │               ├── Bridge/
│   │   │               │   ├── McpToPcpConverter.kt
│   │   │               │   ├── McpJsonParser.kt
│   │   │               │   └── PcpBuilder.kt
│   │   │               ├── Models/
│   │   │               │   ├── McpModels.kt
│   │   │               │   └── ConversionResult.kt
│   │   │               └── Extensions/
│   │   │                   └── PipeExtensions.kt
│   │   └── resources/
│   │       └── mcp-schema-mappings.json
│   └── test/
│       └── kotlin/
│           └── com/
│               └── TTT/
│                   └── MCP/
│                       └── Bridge/
│                           ├── McpToPcpConverterTest.kt
│                           └── McpJsonParserTest.kt
├── build.gradle.kts
└── README.md
```

## 2. Gradle Configuration

### 2.1 Root settings.gradle.kts Update
```kotlin
include("TPipe-MCP")
```

### 2.2 TPipe-MCP/build.gradle.kts
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.serialization)
}

dependencies {
    implementation(project(":"))  // Main TPipe module
    implementation("io.modelcontextprotocol:kotlin-sdk:$mcpVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    
    testImplementation(libs.kotlin.test.junit)
}
```

## 3. MCP SDK Analysis and Integration

### 3.1 MCP JSON Structure Analysis
Based on MCP specification, identify key structures:
- **Tools**: Function definitions with parameters
- **Resources**: File/data access definitions  
- **Prompts**: Template definitions
- **Sampling**: LLM interaction patterns

### 3.2 MCP to PCP Mapping Strategy
| MCP Component | PCP Equivalent | Conversion Logic |
|---------------|----------------|------------------|
| `tools` | `TPipeContextOptions` | Map function name, params, description |
| `resources` | `StdioContextOptions` | Convert file operations to stdio commands |
| `prompts` | System/User prompts | Direct mapping to Pipe prompts |
| `sampling` | Pipe execution flow | Convert to pipeline configuration |

## 4. Core Implementation Classes

### 4.1 McpModels.kt
```kotlin
@Serializable
data class McpTool(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonObject
)

@Serializable
data class McpResource(
    val uri: String,
    val name: String,
    val description: String? = null,
    val mimeType: String? = null
)

@Serializable
data class McpRequest(
    val tools: List<McpTool> = emptyList(),
    val resources: List<McpResource> = emptyList(),
    val prompts: Map<String, String> = emptyMap()
)
```

### 4.2 McpJsonParser.kt
```kotlin
class McpJsonParser {
    fun parseJson(mcpJson: String): McpRequest
    fun validateMcpStructure(json: JsonObject): Boolean
    fun extractTools(json: JsonObject): List<McpTool>
    fun extractResources(json: JsonObject): List<McpResource>
}
```

### 4.3 McpToPcpConverter.kt
```kotlin
class McpToPcpConverter {
    fun convert(mcpRequest: McpRequest): PcpContext
    private fun convertTools(tools: List<McpTool>): List<TPipeContextOptions>
    private fun convertResources(resources: List<McpResource>): List<StdioContextOptions>
    private fun mapJsonSchemaToParamType(schema: JsonObject): ParamType
}
```

### 4.4 PcpBuilder.kt
```kotlin
class PcpBuilder {
    fun buildPcpContext(): PcpContext
    fun addTPipeFunction(name: String, description: String, params: Map<String, Triple<ParamType, String, List<String>>>): PcpBuilder
    fun addStdioCommand(command: String, args: List<String>, permissions: List<Permissions>): PcpBuilder
}
```

### 4.5 ConversionResult.kt
```kotlin
@Serializable
data class ConversionResult(
    val success: Boolean,
    val pcpContext: PcpContext? = null,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)
```

## 5. Pipe Integration Extensions

### 5.1 PipeExtensions.kt
```kotlin
fun Pipe.setMcpContext(mcpJson: String): Pipe {
    val converter = McpToPcpConverter()
    val parser = McpJsonParser()
    val mcpRequest = parser.parseJson(mcpJson)
    val pcpContext = converter.convert(mcpRequest)
    this.pcpContext = pcpContext
    return this
}

fun Pipe.convertAndApplyMcp(mcpJson: String): ConversionResult {
    // Implementation with error handling
}
```

## 6. JSON Schema Mapping Configuration

### 6.1 mcp-schema-mappings.json
```json
{
  "typeMapping": {
    "string": "String",
    "integer": "Int",
    "number": "Float",
    "boolean": "Bool",
    "array": "List",
    "object": "Map"
  },
  "commandMapping": {
    "file_read": "cat",
    "file_write": "tee",
    "file_list": "ls",
    "execute": "bash"
  }
}
```

## 7. Error Handling and Validation

### 7.1 Validation Strategy
- JSON structure validation against MCP schema
- Parameter type validation and conversion
- Permission mapping validation
- Resource accessibility validation

### 7.2 Error Recovery
- Graceful degradation for unsupported MCP features
- Default parameter assignment for missing values
- Warning generation for lossy conversions

## 8. Testing Strategy

### 8.1 Unit Tests
- `McpJsonParserTest.kt`: JSON parsing validation
- `McpToPcpConverterTest.kt`: Conversion logic testing
- Integration tests with sample MCP JSON files

### 8.2 Test Data
- Valid MCP JSON samples
- Invalid/malformed JSON samples
- Edge cases (empty tools, complex schemas)

## 9. Implementation Steps

### Phase 1: Foundation
1. Create module structure and gradle configuration
2. Implement basic MCP model classes
3. Create JSON parser with validation

### Phase 2: Core Conversion
1. Implement MCP to PCP converter
2. Add schema mapping logic
3. Create PCP builder utility

### Phase 3: Integration
1. Add Pipe extension methods
2. Implement error handling
3. Create comprehensive tests

### Phase 4: Documentation
1. Update module README
2. Add usage examples
3. Document conversion limitations

## 10. Usage Example

```kotlin
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

## 11. Dependencies and Constraints

### 11.1 Dependencies
- Main TPipe module (one-way dependency)
- MCP Kotlin SDK
- kotlinx.serialization

### 11.2 Constraints
- Must follow TPipe formatting rules
- Minimal code approach
- No modification of existing TPipe core
- Maintain compatibility with existing PCP structure