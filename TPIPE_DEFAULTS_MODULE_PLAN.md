# TPipe-Defaults Module Implementation Plan

## Overview
Create a central integration module `TPipe-Defaults` that provides seamless, out-of-the-box Manifold configurations for all supported providers while maintaining clean architectural separation.

## Architecture Goals
- **Zero coupling** between base TPipe module and provider modules
- **Seamless API** with single-function provider configuration
- **Type safety** - returns base types, never exposes provider internals
- **Optional usage** - manual configuration remains available
- **Provider independence** - providers can be added/removed without affecting base module

## Module Structure

### Directory Layout
```
TPipe-Defaults/
├── build.gradle.kts
├── src/
│   ├── main/
│   │   └── kotlin/
│   │       └── Defaults/
│   │           ├── ManifoldDefaults.kt
│   │           ├── ProviderConfiguration.kt
│   │           └── providers/
│   │               ├── BedrockDefaults.kt
│   │               ├── OllamaDefaults.kt
│   │               └── MCPDefaults.kt
│   └── test/
│       └── kotlin/
│           └── Defaults/
│               ├── ManifoldDefaultsTest.kt
│               └── ProviderConfigurationTest.kt
└── README.md
```

## Build Configuration

### build.gradle.kts
```kotlin
plugins {
    kotlin("jvm")
    `maven-publish`
}

dependencies {
    // Base module dependency
    implementation(project(":TPipe"))
    
    // All provider module dependencies
    implementation(project(":TPipe-Bedrock"))
    implementation(project(":TPipe-Ollama"))
    implementation(project(":TPipe-MCP"))
    
    // Test dependencies
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.junit.jupiter:junit-jupiter")
}

// Ensure consistent versioning with parent project
version = rootProject.version
group = rootProject.group
```

### Root settings.gradle.kts Update
```kotlin
include(":TPipe-Defaults")
```

## Core Implementation Design

### 1. Provider Configuration Interface
```kotlin
/**
 * Base configuration interface for provider-specific parameters.
 * Each provider implements this to define their required configuration.
 */
sealed class ProviderConfiguration {
    abstract fun validate(): Boolean
}

data class BedrockConfiguration(
    val region: String,
    val accessKey: String? = null,
    val secretKey: String? = null,
    val sessionToken: String? = null,
    val profileName: String? = null,
    val model: String = "anthropic.claude-3-sonnet-20240229-v1:0",
    val apiType: BedrockApiType = BedrockApiType.CONVERSE
) : ProviderConfiguration() {
    override fun validate(): Boolean = region.isNotBlank()
}

data class OllamaConfiguration(
    val host: String = "localhost",
    val port: Int = 11434,
    val model: String,
    val timeout: Long = 30000,
    val useHttps: Boolean = false
) : ProviderConfiguration() {
    override fun validate(): Boolean = host.isNotBlank() && model.isNotBlank()
}

data class MCPConfiguration(
    val serverPath: String,
    val serverArgs: List<String> = emptyList(),
    val timeout: Long = 30000
) : ProviderConfiguration() {
    override fun validate(): Boolean = serverPath.isNotBlank()
}
```

### 2. Main Defaults Interface
```kotlin
/**
 * Central factory for creating pre-configured Manifold instances with provider-specific defaults.
 * This module handles all provider integration while keeping the base TPipe module clean.
 */
object ManifoldDefaults {
    
    /**
     * Creates a Manifold instance configured for AWS Bedrock with optimized defaults.
     *
     * @param configuration Bedrock-specific configuration including region, credentials, and model settings
     * @return Fully configured Manifold instance ready for multi-agent orchestration
     * @throws IllegalArgumentException if configuration is invalid
     * @throws RuntimeException if Bedrock provider is not available
     */
    fun withBedrock(configuration: BedrockConfiguration): Manifold
    
    /**
     * Creates a Manifold instance configured for Ollama with optimized defaults.
     *
     * @param configuration Ollama-specific configuration including host, model, and connection settings
     * @return Fully configured Manifold instance ready for multi-agent orchestration
     * @throws IllegalArgumentException if configuration is invalid
     * @throws RuntimeException if Ollama provider is not available
     */
    fun withOllama(configuration: OllamaConfiguration): Manifold
    
    /**
     * Creates a Manifold instance configured for MCP (Model Context Protocol) with optimized defaults.
     *
     * @param configuration MCP-specific configuration including server path and connection settings
     * @return Fully configured Manifold instance ready for multi-agent orchestration
     * @throws IllegalArgumentException if configuration is invalid
     * @throws RuntimeException if MCP provider is not available
     */
    fun withMCP(configuration: MCPConfiguration): Manifold
    
    /**
     * Lists all available providers that can be used for Manifold configuration.
     *
     * @return List of provider names that have implementations available
     */
    fun getAvailableProviders(): List<String>
    
    /**
     * Checks if a specific provider is available for use.
     *
     * @param providerName Name of the provider to check
     * @return true if provider is available, false otherwise
     */
    fun isProviderAvailable(providerName: String): Boolean
}
```

### 3. Provider-Specific Default Builders

#### BedrockDefaults.kt
```kotlin
/**
 * Internal factory for creating Bedrock-optimized Manifold configurations.
 * Handles AWS-specific setup including credential management and optimal model settings.
 */
internal object BedrockDefaults {
    
    fun createManifold(config: BedrockConfiguration): Manifold {
        // Validate configuration
        require(config.validate()) { "Invalid Bedrock configuration: ${config}" }
        
        // Create manager pipeline with Bedrock-optimized settings
        val managerPipeline = createManagerPipeline(config)
        
        // Create worker pipe with Bedrock-optimized settings
        val workerPipe = createWorkerPipe(config)
        
        // Return configured Manifold with tracing enabled
        return Manifold(managerPipeline, workerPipe).apply {
            enableTracing()
        }
    }
    
    private fun createManagerPipeline(config: BedrockConfiguration): Pipeline {
        // Implementation creates Pipeline with Bedrock pipes
        // Optimized system prompts for orchestration
        // Proper JSON input/output configuration
        // Context window management
    }
    
    private fun createWorkerPipe(config: BedrockConfiguration): Pipe {
        // Implementation creates Pipe with Bedrock configuration
        // Optimized for task execution
        // Proper error handling and retry logic
    }
}
```

#### OllamaDefaults.kt
```kotlin
/**
 * Internal factory for creating Ollama-optimized Manifold configurations.
 * Handles local model setup and connection management.
 */
internal object OllamaDefaults {
    
    fun createManifold(config: OllamaConfiguration): Manifold {
        require(config.validate()) { "Invalid Ollama configuration: ${config}" }
        
        val managerPipeline = createManagerPipeline(config)
        val workerPipe = createWorkerPipe(config)
        
        return Manifold(managerPipeline, workerPipe).apply {
            enableTracing()
        }
    }
    
    private fun createManagerPipeline(config: OllamaConfiguration): Pipeline {
        // Implementation creates Pipeline with Ollama pipes
        // Model-specific optimizations
        // Local connection handling
    }
    
    private fun createWorkerPipe(config: OllamaConfiguration): Pipe {
        // Implementation creates Pipe with Ollama configuration
        // Timeout and connection management
    }
}
```

## Default Configuration Strategies

### Manager Pipeline Defaults
- **System Prompt**: Optimized for orchestration and task delegation
- **JSON I/O**: Structured input/output for agent communication
- **Context Management**: Automatic context sharing between agents
- **Error Handling**: Robust retry and fallback mechanisms
- **Tracing**: Full Manifold tracing integration

### Worker Pipe Defaults
- **System Prompt**: Optimized for task execution and response formatting
- **Validation**: Response validation for structured output
- **Transformation**: Output formatting for agent communication
- **Context Integration**: Automatic context updates
- **Performance**: Optimized token usage and response times

## Usage Examples

### Basic Usage
```kotlin
// Bedrock with minimal configuration
val manifold = ManifoldDefaults.withBedrock(
    BedrockConfiguration(
        region = "us-east-1",
        model = "anthropic.claude-3-sonnet-20240229-v1:0"
    )
)

// Ollama with custom host
val manifold = ManifoldDefaults.withOllama(
    OllamaConfiguration(
        host = "192.168.1.100",
        model = "llama3.1:8b"
    )
)
```

### Advanced Configuration
```kotlin
// Bedrock with full AWS configuration
val manifold = ManifoldDefaults.withBedrock(
    BedrockConfiguration(
        region = "us-west-2",
        accessKey = System.getenv("AWS_ACCESS_KEY_ID"),
        secretKey = System.getenv("AWS_SECRET_ACCESS_KEY"),
        model = "anthropic.claude-3-opus-20240229-v1:0",
        apiType = BedrockApiType.INVOKE_MODEL
    )
)

// Execute multi-agent task
val result = manifold.execute("Analyze this document and create a summary")
```

## Testing Strategy

### Unit Tests
- Configuration validation tests
- Provider availability tests
- Default pipeline creation tests
- Error handling tests

### Integration Tests
- End-to-end Manifold execution tests
- Provider-specific functionality tests
- Tracing system integration tests
- Performance benchmarks

### Test Structure
```kotlin
class ManifoldDefaultsTest {
    
    @Test
    fun `withBedrock creates valid Manifold instance`()
    
    @Test
    fun `withOllama creates valid Manifold instance`()
    
    @Test
    fun `invalid configuration throws appropriate exception`()
    
    @Test
    fun `getAvailableProviders returns correct list`()
    
    @Test
    fun `created Manifold has tracing enabled by default`()
}
```

## Error Handling

### Configuration Validation
- Validate all required parameters before pipeline creation
- Provide clear error messages for missing or invalid configuration
- Fail fast with descriptive exceptions

### Provider Availability
- Check provider module availability at runtime
- Graceful degradation when providers are missing
- Clear error messages indicating missing dependencies

### Runtime Errors
- Proper exception propagation from provider modules
- Consistent error handling across all providers
- Detailed logging for troubleshooting

## Documentation Requirements

### README.md
- Quick start guide with examples
- Configuration reference for each provider
- Troubleshooting common issues
- Migration guide from manual configuration

### KDoc Documentation
- Comprehensive parameter documentation
- Usage examples in documentation
- Cross-references to related classes
- Performance considerations and best practices

## Migration Path

### For Existing Users
1. **Optional adoption** - existing manual configuration continues to work
2. **Gradual migration** - can migrate one provider at a time
3. **Compatibility** - no breaking changes to existing APIs
4. **Documentation** - clear migration examples and benefits

### For New Users
1. **Default recommendation** - guide users to TPipe-Defaults first
2. **Fallback documentation** - manual configuration for advanced use cases
3. **Examples** - comprehensive examples for common scenarios

## Implementation Phases

### Phase 1: Core Infrastructure
- Create module structure and build configuration
- Implement base interfaces and configuration classes
- Set up testing framework

### Phase 2: Provider Integration
- Implement Bedrock defaults
- Implement Ollama defaults
- Implement MCP defaults
- Add provider availability checking

### Phase 3: Testing and Documentation
- Comprehensive unit and integration tests
- Performance benchmarking
- Documentation and examples
- Migration guides

### Phase 4: Optimization and Polish
- Performance optimizations
- Error message improvements
- Additional provider support
- Community feedback integration

## Success Criteria

### Functional Requirements
- ✅ Single-function provider configuration
- ✅ Zero coupling between base and provider modules
- ✅ Type-safe API returning base types only
- ✅ Comprehensive error handling and validation
- ✅ Full tracing system integration

### Non-Functional Requirements
- ✅ Performance equivalent to manual configuration
- ✅ Memory usage optimization
- ✅ Clear and comprehensive documentation
- ✅ Backward compatibility with existing code
- ✅ Easy extensibility for new providers

## Future Considerations

### Additional Providers
- OpenAI integration
- Anthropic direct API integration
- Azure OpenAI integration
- Custom provider plugin system

### Advanced Features
- Configuration profiles and presets
- Dynamic provider switching
- Load balancing across multiple providers
- Provider health monitoring and failover

This comprehensive plan ensures the TPipe-Defaults module provides seamless integration while maintaining the architectural integrity and modularity of the TPipe ecosystem.
