# PCP Execution Pipeline Implementation Plan

## Executive Summary

This plan addresses the critical missing components in the TPipe PCP (Pipe Context Protocol) system. While the foundation (40% complete) includes function binding, type conversion, and schema generation, the execution pipeline that processes LLM-generated PCP requests is entirely missing. This document outlines the implementation of the complete execution system including stdio, HTTP, Python support, and the central request processing pipeline.

## Current State Analysis

### ✅ **Implemented (40% Complete)**
- PCP protocol definitions and data structures
- Native function binding with automatic signature detection
- Type conversion system (primitive, collection, object)
- Function registry with thread-safe operations
- Return value handling and storage
- JSON schema generation for LLM consumption
- Comprehensive testing infrastructure

### ❌ **Missing Critical Components (60% Incomplete)**
- **PCP Response Parser**: Extract PCP requests from LLM output
- **Execution Dispatcher**: Route requests to appropriate handlers
- **Stdio Executor**: Command execution and process management
- **HTTP Executor**: Web request handling and authentication
- **Python Executor**: Script execution and environment management
- **Result Integration**: Inject execution results back into conversation

## Architecture Overview

### Current Flow (Broken)
```
User Input → LLM → PCP JSON Response → ❌ NO PROCESSING ❌ → Raw JSON to User
```

### Target Flow (Complete)
```
User Input → LLM → PCP JSON Response → Parse Requests → Execute Functions/Commands → Integrate Results → Continue Conversation
```

## Implementation Plan

### Phase 1: Core Execution Pipeline (Priority: Critical)

#### 1.1 PCP Response Parser
**File**: `TPipe/src/main/kotlin/PipeContextProtocol/PcpResponseParser.kt`

```kotlin
/**
 * Parses LLM responses to extract and validate PCP requests.
 * Handles malformed JSON, multiple requests, and validation errors.
 */
class PcpResponseParser {
    fun extractPcpRequests(llmResponse: String): PcpParseResult
    fun validatePcpRequest(request: PcPRequest): ValidationResult
    fun sanitizeJsonResponse(response: String): String
}

data class PcpParseResult(
    val success: Boolean,
    val requests: List<PcPRequest>,
    val errors: List<String>,
    val originalResponse: String
)
```

**Key Features:**
- JSON extraction from mixed text/JSON responses
- Multiple PCP request handling in single response
- Malformed JSON recovery and repair
- Request validation against schema
- Error reporting with context

#### 1.2 Execution Dispatcher
**File**: `TPipe/src/main/kotlin/PipeContextProtocol/PcpExecutionDispatcher.kt`

```kotlin
/**
 * Central dispatcher that routes PCP requests to appropriate executors.
 * Manages execution order, error handling, and result aggregation.
 */
class PcpExecutionDispatcher {
    private val functionHandler = PcpFunctionHandler()
    private val stdioExecutor = StdioExecutor()
    private val httpExecutor = HttpExecutor()
    private val pythonExecutor = PythonExecutor()
    
    suspend fun executeRequests(requests: List<PcPRequest>): PcpExecutionResult
    suspend fun executeRequest(request: PcPRequest): PcpRequestResult
    private fun routeRequest(request: PcPRequest): PcpExecutor
}

data class PcpExecutionResult(
    val success: Boolean,
    val results: List<PcpRequestResult>,
    val executionTimeMs: Long,
    val errors: List<String>
)
```

**Key Features:**
- Automatic request routing based on transport type
- Parallel execution support for independent requests
- Sequential execution for dependent requests
- Comprehensive error handling and recovery
- Execution timing and performance metrics

### Phase 2: Stdio Execution System (Priority: High)

#### 2.1 Stdio Executor
**File**: `TPipe/src/main/kotlin/PipeContextProtocol/StdioExecutor.kt`

```kotlin
/**
 * Executes shell commands with multiple interaction modes and buffer management.
 * Supports one-shot commands, persistent sessions, and direct stdio communication.
 */
class StdioExecutor : PcpExecutor {
    private val sessionManager = StdioSessionManager()
    private val bufferManager = StdioBufferManager()
    
    override suspend fun execute(request: PcPRequest): PcpRequestResult
    private fun executeOneShot(options: StdioContextOptions): ProcessResult
    private fun executeInteractive(options: StdioContextOptions): InteractiveResult
    private fun connectToSession(sessionId: String, input: String): SessionResult
    private fun validatePermissions(options: StdioContextOptions): ValidationResult
}

/**
 * Manages persistent stdio sessions for long-form communication.
 * Handles session lifecycle, buffer management, and cleanup.
 */
class StdioSessionManager {
    private val activeSessions = ConcurrentHashMap<String, StdioSession>()
    
    fun createSession(command: String, args: List<String>, workingDir: String?): StdioSession
    fun getSession(sessionId: String): StdioSession?
    fun sendInput(sessionId: String, input: String): SessionResponse
    fun readOutput(sessionId: String, timeoutMs: Long = 5000): String
    fun closeSession(sessionId: String): Boolean
    fun listActiveSessions(): List<String>
}

/**
 * Manages stdio buffers for capturing and replaying communication.
 * Supports buffer persistence, search, and interaction history.
 */
class StdioBufferManager {
    private val buffers = ConcurrentHashMap<String, StdioBuffer>()
    
    fun createBuffer(sessionId: String): StdioBuffer
    fun appendToBuffer(bufferId: String, data: String, direction: BufferDirection)
    fun getBuffer(bufferId: String): StdioBuffer?
    fun searchBuffer(bufferId: String, pattern: String): List<BufferMatch>
    fun saveBuffer(bufferId: String, filePath: String): Boolean
    fun loadBuffer(filePath: String): StdioBuffer?
    fun clearBuffer(bufferId: String): Boolean
}

data class StdioSession(
    val sessionId: String,
    val process: Process,
    val command: String,
    val args: List<String>,
    val workingDirectory: String?,
    val createdAt: Long,
    val bufferId: String,
    var isActive: Boolean = true
)

data class StdioBuffer(
    val bufferId: String,
    val sessionId: String,
    val entries: MutableList<BufferEntry> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis()
)

data class BufferEntry(
    val timestamp: Long,
    val direction: BufferDirection,
    val content: String,
    val metadata: Map<String, String> = emptyMap()
)

enum class BufferDirection {
    INPUT,    // Data sent to process
    OUTPUT,   // Data received from process stdout
    ERROR     // Data received from process stderr
}

data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val executionTimeMs: Long,
    val sessionId: String? = null,
    val bufferId: String? = null
)

data class InteractiveResult(
    val sessionId: String,
    val bufferId: String,
    val initialOutput: String,
    val isSessionActive: Boolean
)

data class SessionResult(
    val sessionId: String,
    val response: String,
    val isSessionActive: Boolean,
    val bufferId: String
)
```

**Key Features:**
- **One-shot execution**: Traditional command + args execution
- **Interactive sessions**: Persistent processes with ongoing communication
- **Buffer management**: Capture and store all stdio interactions
- **Session persistence**: Long-running processes that can be reconnected
- **Buffer search**: Find specific interactions in communication history
- **Buffer export/import**: Save and load communication sessions
- **Multi-mode support**: Automatic detection of execution mode needed

#### 2.2 Enhanced Stdio Context Options
**File**: `TPipe/src/main/kotlin/PipeContextProtocol/Pcp.kt` (Enhancement)

```kotlin
@kotlinx.serialization.Serializable
data class StdioContextOptions(@kotlinx.serialization.Transient val cinit : Boolean = false)
{
    // Existing fields
    @kotlinx.serialization.Serializable
    var command = ""
    @kotlinx.serialization.Serializable
    var args = mutableListOf<String>()
    @kotlinx.serialization.Serializable
    var permissions = mutableListOf<Permissions>()
    @kotlinx.serialization.Serializable
    var description = ""
    
    // New fields for enhanced stdio support
    @kotlinx.serialization.Serializable
    var executionMode = StdioExecutionMode.ONE_SHOT
    @kotlinx.serialization.Serializable
    var sessionId: String? = null
    @kotlinx.serialization.Serializable
    var bufferId: String? = null
    @kotlinx.serialization.Serializable
    var workingDirectory: String? = null
    @kotlinx.serialization.Serializable
    var environmentVariables = mutableMapOf<String, String>()
    @kotlinx.serialization.Serializable
    var timeoutMs: Long = 30000
    @kotlinx.serialization.Serializable
    var keepSessionAlive = false
    @kotlinx.serialization.Serializable
    var bufferPersistence = false
    @kotlinx.serialization.Serializable
    var maxBufferSize = 1048576 // 1MB default
}

enum class StdioExecutionMode {
    ONE_SHOT,      // Execute command once and return result
    INTERACTIVE,   // Create persistent session for ongoing communication
    CONNECT,       // Connect to existing session
    BUFFER_REPLAY  // Replay from saved buffer
}
```

#### 2.3 Command Security Manager
**File**: `TPipe/src/main/kotlin/PipeContextProtocol/CommandSecurityManager.kt`

```kotlin
/**
 * Validates and sanitizes shell commands for security.
 * Prevents command injection and enforces permission boundaries.
 * Enhanced with session and buffer security validation.
 */
class CommandSecurityManager {
    fun validateCommand(command: String, allowedCommands: List<String>): Boolean
    fun sanitizeArguments(args: List<String>): List<String>
    fun checkPathPermissions(path: String, permissions: List<Permissions>): Boolean
    fun detectCommandInjection(input: String): Boolean
    fun validateSessionAccess(sessionId: String, userId: String): Boolean
    fun validateBufferAccess(bufferId: String, permissions: List<Permissions>): Boolean
    fun sanitizeSessionInput(input: String): String
    fun checkResourceLimits(sessionId: String): ResourceValidation
}

data class ResourceValidation(
    val isValid: Boolean,
    val memoryUsage: Long,
    val cpuUsage: Double,
    val sessionCount: Int,
    val warnings: List<String>
)
```

#### 2.4 Stdio Usage Examples

**One-Shot Command Execution:**
```kotlin
val stdioOptions = StdioContextOptions().apply {
    command = "ls"
    args = mutableListOf("-la", "/home/user")
    executionMode = StdioExecutionMode.ONE_SHOT
    permissions = mutableListOf(Permissions.Read)
}
```

**Interactive Session Creation:**
```kotlin
val stdioOptions = StdioContextOptions().apply {
    command = "python3"
    args = mutableListOf("-i")  // Interactive mode
    executionMode = StdioExecutionMode.INTERACTIVE
    keepSessionAlive = true
    bufferPersistence = true
    workingDirectory = "/home/user/projects"
}
```

**Session Communication:**
```kotlin
val stdioOptions = StdioContextOptions().apply {
    executionMode = StdioExecutionMode.CONNECT
    sessionId = "python-session-123"
    // Input sent to existing session via argumentsOrFunctionParams
}
```

**Buffer Replay:**
```kotlin
val stdioOptions = StdioContextOptions().apply {
    executionMode = StdioExecutionMode.BUFFER_REPLAY
    bufferId = "debug-session-buffer-456"
    // Replay previous session for analysis
}
```

### Phase 3: HTTP Execution System (Priority: High)

#### 3.1 HTTP Executor
**File**: `TPipe/src/main/kotlin/PipeContextProtocol/HttpExecutor.kt`

```kotlin
/**
 * Executes HTTP requests with authentication, timeout, and response handling.
 * Supports REST APIs, file downloads, and webhook calls.
 */
class HttpExecutor : PcpExecutor {
    private val httpClient = HttpClient()
    
    override suspend fun execute(request: PcPRequest): PcpRequestResult
    private fun buildHttpRequest(options: HttpContextOptions): HttpRequest
    private fun handleAuthentication(request: HttpRequest, headers: Map<String, String>): HttpRequest
    private fun processResponse(response: HttpResponse): String
}
```

**Key Features:**
- HTTP method support (GET, POST, PUT, DELETE, PATCH)
- Authentication header management
- Request/response body handling (JSON, form data, files)
- Timeout enforcement
- SSL/TLS certificate validation
- Response status code handling
- Content type detection and parsing

#### 3.2 HTTP Security Manager
**File**: `TPipe/src/main/kotlin/PipeContextProtocol/HttpSecurityManager.kt`

```kotlin
/**
 * Validates HTTP requests for security compliance.
 * Prevents SSRF attacks and enforces URL whitelisting.
 */
class HttpSecurityManager {
    fun validateUrl(url: String, allowedHosts: List<String>): Boolean
    fun checkSsrfProtection(url: String): Boolean
    fun validateHeaders(headers: Map<String, String>): ValidationResult
    fun sanitizeRequestBody(body: String, contentType: String): String
}
```

### Phase 4: Python Execution System (Priority: Medium)

#### 4.1 Python Executor
**File**: `TPipe/src/main/kotlin/PipeContextProtocol/PythonExecutor.kt`

```kotlin
/**
 * Executes Python scripts with cross-platform environment management and security controls.
 * Handles package validation, virtual environments, and output capture across Windows, macOS, and Linux.
 */
class PythonExecutor : PcpExecutor {
    private val platformManager = PythonPlatformManager()
    private val environmentManager = PythonEnvironmentManager()
    
    override suspend fun execute(request: PcPRequest): PcpRequestResult
    private fun validatePythonEnvironment(context: PythonContext): ValidationResult
    private fun executeScript(script: String, context: PythonContext): PythonResult
    private fun validatePackages(packages: List<String>): List<String>
    private fun detectPlatform(): PythonPlatform
}

/**
 * Manages Python platform-specific operations and executable detection.
 * Handles differences between Windows, macOS, and Linux Python installations.
 */
class PythonPlatformManager {
    fun detectPythonExecutables(): List<PythonExecutable>
    fun validatePythonVersion(executable: String, requiredVersion: String?): Boolean
    fun buildPythonCommand(executable: String, script: String, args: List<String>): List<String>
    fun getPlatformSpecificPaths(): PlatformPaths
    fun createVirtualEnvironment(path: String, pythonExecutable: String): VenvResult
    fun activateVirtualEnvironment(venvPath: String): EnvironmentVariables
}

/**
 * Manages Python virtual environments across different platforms.
 * Handles venv creation, activation, and package management.
 */
class PythonEnvironmentManager {
    fun createEnvironment(name: String, pythonVersion: String?, basePath: String?): VenvResult
    fun activateEnvironment(envName: String): EnvironmentVariables
    fun installPackages(envName: String, packages: List<String>): PackageInstallResult
    fun listEnvironments(): List<PythonEnvironment>
    fun deleteEnvironment(envName: String): Boolean
    fun getEnvironmentInfo(envName: String): PythonEnvironment?
}

data class PythonExecutable(
    val path: String,
    val version: String,
    val platform: PythonPlatform,
    val architecture: String,
    val isVirtualEnv: Boolean = false,
    val parentEnv: String? = null
)

data class PlatformPaths(
    val pythonExecutables: List<String>,
    val pipExecutables: List<String>,
    val venvBasePath: String,
    val scriptsPath: String,
    val sitePackagesPath: String,
    val pathSeparator: String,
    val scriptExtension: String
)

data class PythonEnvironment(
    val name: String,
    val path: String,
    val pythonVersion: String,
    val platform: PythonPlatform,
    val packages: List<String>,
    val isActive: Boolean,
    val createdAt: Long
)

enum class PythonPlatform {
    WINDOWS,
    MACOS,
    LINUX
}

data class VenvResult(
    val success: Boolean,
    val environmentPath: String,
    val activationScript: String,
    val error: String? = null
)

data class EnvironmentVariables(
    val variables: Map<String, String>,
    val pathAdditions: List<String>
)

data class PackageInstallResult(
    val success: Boolean,
    val installedPackages: List<String>,
    val failedPackages: List<String>,
    val errors: List<String>
)

data class PythonResult(
    val exitCode: Int,
    val output: String,
    val error: String,
    val executionTimeMs: Long,
    val platform: PythonPlatform,
    val pythonVersion: String,
    val environmentUsed: String?
)
```

**Key Features:**
- **Cross-platform executable detection**: Find Python on Windows (`python.exe`, `py.exe`), macOS/Linux (`python3`, `python`)
- **Virtual environment management**: Create and manage venvs across all platforms
- **Platform-specific path handling**: Handle Windows vs Unix path differences
- **Package management**: pip installation with platform-specific considerations
- **Version compatibility**: Support Python 2.7, 3.x across platforms
- **Architecture detection**: Handle x86, x64, ARM differences
- **Environment activation**: Platform-specific activation scripts

#### 4.2 Enhanced Python Context Options
**File**: `TPipe/src/main/kotlin/PipeContextProtocol/Pcp.kt` (Enhancement)

```kotlin
@kotlinx.serialization.Serializable
data class PythonContext(@kotlinx.serialization.Transient val cinit : Boolean = false)
{
    // Existing fields
    @kotlinx.serialization.Serializable
    var availablePackages = mutableListOf<String>()
    @kotlinx.serialization.Serializable
    var pythonVersion = ""
    @kotlinx.serialization.Serializable
    var pythonPath = ""
    @kotlinx.serialization.Serializable
    var workingDirectory = ""
    @kotlinx.serialization.Serializable
    var environmentVariables = mutableMapOf<String, String>()
    @kotlinx.serialization.Serializable
    var timeoutMs = 30000
    @kotlinx.serialization.Serializable
    var captureOutput = true
    @kotlinx.serialization.Serializable
    var permissions = mutableListOf<Permissions>()
    
    // New cross-platform fields
    @kotlinx.serialization.Serializable
    var preferredPlatform: PythonPlatform? = null
    @kotlinx.serialization.Serializable
    var virtualEnvironmentName: String? = null
    @kotlinx.serialization.Serializable
    var createVenvIfMissing = false
    @kotlinx.serialization.Serializable
    var requiredPackages = mutableListOf<String>()
    @kotlinx.serialization.Serializable
    var pythonExecutablePreference = mutableListOf<String>() // e.g., ["python3", "python", "py"]
    @kotlinx.serialization.Serializable
    var architecture: String? = null // "x86", "x64", "arm64"
    @kotlinx.serialization.Serializable
    var allowSystemPython = true
    @kotlinx.serialization.Serializable
    var venvBasePath: String? = null
    @kotlinx.serialization.Serializable
    var pipInstallTimeout = 120000 // 2 minutes for package installation
}
```

#### 4.3 Python Security Manager
**File**: `TPipe/src/main/kotlin/PipeContextProtocol/PythonSecurityManager.kt`

```kotlin
/**
 * Validates Python scripts for security compliance across platforms.
 * Prevents dangerous imports and enforces execution boundaries with platform-specific considerations.
 */
class PythonSecurityManager {
    fun validateScript(script: String, platform: PythonPlatform): ValidationResult
    fun checkDangerousImports(script: String, platform: PythonPlatform): List<String>
    fun sanitizeScript(script: String): String
    fun validatePackageAccess(packages: List<String>, platform: PythonPlatform): ValidationResult
    fun checkPlatformSpecificRisks(script: String, platform: PythonPlatform): List<SecurityRisk>
    fun validateExecutablePath(pythonPath: String, platform: PythonPlatform): Boolean
    fun checkVirtualEnvironmentSecurity(venvPath: String): SecurityValidation
}

data class SecurityRisk(
    val type: RiskType,
    val description: String,
    val severity: RiskSeverity,
    val platform: PythonPlatform,
    val mitigation: String
)

enum class RiskType {
    DANGEROUS_IMPORT,
    FILE_SYSTEM_ACCESS,
    NETWORK_ACCESS,
    PROCESS_EXECUTION,
    PLATFORM_SPECIFIC
}

enum class RiskSeverity {
    LOW, MEDIUM, HIGH, CRITICAL
}

data class SecurityValidation(
    val isSecure: Boolean,
    val risks: List<SecurityRisk>,
    val recommendations: List<String>
)
```

#### 4.4 Platform-Specific Implementation Details

**Windows Considerations:**
- Python executable detection: `python.exe`, `py.exe`, `python3.exe`
- Virtual environment activation: `Scripts\activate.bat` or `Scripts\Activate.ps1`
- Path separators: `\` instead of `/`
- Environment variables: `%PYTHONPATH%` syntax
- Package installation: Handle Windows-specific compilation requirements
- Permission model: Windows ACL considerations

**macOS Considerations:**
- Python executable detection: System Python vs Homebrew vs pyenv
- Framework vs Unix Python installations
- Code signing and Gatekeeper compatibility
- M1/M2 ARM architecture support
- Virtual environment paths: `bin/activate`
- Package compilation: Xcode command line tools requirements

**Linux Considerations:**
- Distribution-specific Python locations: `/usr/bin/python3`, `/usr/local/bin/python`
- Package manager integration: apt, yum, pacman installed packages
- Virtual environment activation: `bin/activate`
- Permission handling: sudo requirements for system packages
- Container compatibility: Docker/Podman considerations

#### 4.5 Cross-Platform Usage Examples

**Auto-detect Platform and Python:**
```kotlin
val pythonContext = PythonContext().apply {
    // Let system auto-detect best Python installation
    pythonExecutablePreference = mutableListOf("python3", "python", "py")
    allowSystemPython = true
    createVenvIfMissing = true
}
```

**Windows-Specific Configuration:**
```kotlin
val pythonContext = PythonContext().apply {
    preferredPlatform = PythonPlatform.WINDOWS
    pythonExecutablePreference = mutableListOf("py", "python.exe")
    venvBasePath = "C:\\Users\\%USERNAME%\\venvs"
    requiredPackages = mutableListOf("requests", "numpy")
}
```

**macOS with Homebrew:**
```kotlin
val pythonContext = PythonContext().apply {
    preferredPlatform = PythonPlatform.MACOS
    pythonPath = "/opt/homebrew/bin/python3"
    architecture = "arm64" // For M1/M2 Macs
    virtualEnvironmentName = "ml-project"
}
```

**Linux Container Environment:**
```kotlin
val pythonContext = PythonContext().apply {
    preferredPlatform = PythonPlatform.LINUX
    pythonPath = "/usr/bin/python3"
    workingDirectory = "/app"
    allowSystemPython = false // Force virtual environment
    virtualEnvironmentName = "container-env"
}
```

### Phase 5: Result Integration System (Priority: High)

#### 5.1 Result Formatter
**File**: `TPipe/src/main/kotlin/PipeContextProtocol/PcpResultFormatter.kt`

```kotlin
/**
 * Formats execution results for integration back into conversation flow.
 * Handles different result types and provides consistent formatting.
 */
class PcpResultFormatter {
    fun formatResults(results: List<PcpRequestResult>): String
    fun formatFunctionResult(result: PcpFunctionResponse): String
    fun formatStdioResult(result: ProcessResult): String
    fun formatHttpResult(result: HttpResponse): String
    fun formatPythonResult(result: PythonResult): String
}
```

#### 5.2 Context Integration Manager
**File**: `TPipe/src/main/kotlin/PipeContextProtocol/ContextIntegrationManager.kt`

```kotlin
/**
 * Manages integration of execution results into context windows and conversation flow.
 * Handles result storage, retrieval, and context window updates.
 */
class ContextIntegrationManager {
    fun integrateResults(originalResponse: String, results: PcpExecutionResult): String
    fun updateContextWindow(results: List<PcpRequestResult>): Map<String, String>
    fun storeResultsForLaterUse(results: List<PcpRequestResult>): List<String>
}
```

## File Structure

```
TPipe/src/main/kotlin/PipeContextProtocol/
├── Pcp.kt (existing - enhanced with StdioExecutionMode and PythonPlatform)
├── PcpFunctionHandler.kt (existing)
├── PcpFunctionExtensions.kt (existing)
├── FunctionRegistry.kt (existing)
├── FunctionInvoker.kt (existing)
├── TypeConverter.kt (existing)
├── ReturnValueHandler.kt (existing)
├── PcpResponseParser.kt (new)
├── PcpExecutionDispatcher.kt (new)
├── PcpExecutor.kt (new - interface)
├── StdioExecutor.kt (new - enhanced with sessions/buffers)
├── StdioSessionManager.kt (new)
├── StdioBufferManager.kt (new)
├── HttpExecutor.kt (new)
├── PythonExecutor.kt (new - enhanced with cross-platform support)
├── PythonPlatformManager.kt (new)
├── PythonEnvironmentManager.kt (new)
├── CommandSecurityManager.kt (new - enhanced with session security)
├── HttpSecurityManager.kt (new)
├── PythonSecurityManager.kt (new - enhanced with platform-specific validation)
├── PcpResultFormatter.kt (new)
└── ContextIntegrationManager.kt (new)

TPipe/src/test/kotlin/PipeContextProtocol/
├── NativeFunctionBindingTest.kt (existing)
├── PcpResponseParserTest.kt (new)
├── PcpExecutionDispatcherTest.kt (new)
├── StdioExecutorTest.kt (new - enhanced with session/buffer tests)
├── StdioSessionManagerTest.kt (new)
├── StdioBufferManagerTest.kt (new)
├── HttpExecutorTest.kt (new)
├── PythonExecutorTest.kt (new - enhanced with cross-platform tests)
├── PythonPlatformManagerTest.kt (new)
├── PythonEnvironmentManagerTest.kt (new)
├── SecurityManagerTest.kt (new)
└── PcpIntegrationTest.kt (new)
```

## Implementation Standards

### Code Style Compliance
- All functions must follow TPipe formatting standards with opening braces on new lines
- Comprehensive KDoc strings for all public functions and classes
- Function body comments explaining complex logic
- Proper error handling with meaningful exception messages

### Security Requirements
- Input validation for all external commands and requests
- Permission enforcement based on PCP context settings
- Command injection prevention
- SSRF protection for HTTP requests
- Sandboxed execution environments
- Timeout enforcement for all operations

### Error Handling Strategy
- Graceful degradation when executors fail
- Detailed error reporting with context
- Execution rollback for failed operations
- Comprehensive logging for debugging
- User-friendly error messages

### Performance Requirements
- Async execution for independent requests
- Connection pooling for HTTP requests
- Process reuse where possible
- Memory management for large outputs
- Execution timeout enforcement (default: 30 seconds)

## Testing Strategy

### Unit Tests
- Individual executor testing with mock environments
- Security manager validation testing
- Parser testing with malformed inputs
- Type conversion edge cases
- **Session management testing** with lifecycle validation
- **Buffer management testing** with persistence and search
- **Interactive session testing** with long-form communication
- **Cross-platform Python testing** with platform-specific mocks

### Integration Tests
- End-to-end PCP request processing
- Multi-executor request handling
- Error recovery and rollback testing
- Performance and timeout testing
- **Session persistence across restarts**
- **Buffer export/import functionality**
- **Multi-session concurrent handling**
- **Python environment creation and management across platforms**

### Security Tests
- Command injection prevention
- SSRF attack prevention
- Permission boundary enforcement
- Malicious script detection
- **Session hijacking prevention**
- **Buffer access control validation**
- **Resource exhaustion protection**
- **Platform-specific Python security validation**

### Performance Tests
- **Session creation/destruction overhead**
- **Buffer memory usage with large communications**
- **Concurrent session handling limits**
- **Buffer search performance with large datasets**
- **Python environment creation time across platforms**
- **Package installation performance**

### Cross-Platform Tests
- **Windows**: Python.exe, py.exe, PowerShell activation scripts
- **macOS**: System Python, Homebrew, pyenv, M1/M2 ARM support
- **Linux**: Distribution-specific Python locations, package managers
- **Virtual environment compatibility** across all platforms
- **Architecture-specific testing**: x86, x64, ARM64

## Implementation Timeline

### Phase 1: Core Pipeline (Week 1-2) - ✅ COMPLETE
1. ✅ PcpResponseParser implementation and testing
2. ✅ PcpExecutionDispatcher implementation and testing  
3. ✅ Manual PCP execution tools for developer control (no automatic integration for security)

### Phase 2: Stdio Support (Week 3-4)
1. **Week 3**: Core StdioExecutor implementation
   - One-shot command execution
   - Basic security validation
   - Process management and timeout handling
2. **Week 4**: Enhanced stdio capabilities
   - StdioSessionManager for persistent sessions
   - StdioBufferManager for communication history
   - Interactive session support and buffer persistence
   - Session security and resource management
   - Comprehensive testing of all modes

### Phase 3: HTTP Support (Week 4)
1. HttpExecutor implementation
2. HttpSecurityManager implementation
3. Authentication and SSL handling
4. Response processing and testing

### Phase 4: Python Support (Week 5-6)
1. **Week 5**: Core Python execution and platform detection
   - PythonExecutor implementation with basic script execution
   - PythonPlatformManager for cross-platform executable detection
   - Platform-specific path and command handling
   - Basic virtual environment support
2. **Week 6**: Advanced Python features and cross-platform testing
   - PythonEnvironmentManager for full venv lifecycle management
   - Package installation and validation across platforms
   - Platform-specific security validation
   - Comprehensive testing on Windows, macOS, and Linux
   - Architecture-specific handling (x86, x64, ARM)

### Phase 5: Integration & Polish (Week 6)
1. Result formatting and integration
2. Context window management
3. Performance optimization
4. Documentation and examples

## Success Criteria

### Functional Requirements
- LLM-generated PCP requests are automatically parsed and executed
- Native functions are called with proper parameter conversion
- **Shell commands execute in multiple modes**: one-shot, interactive, and session-based
- **Persistent stdio sessions** maintain state across multiple interactions
- **Communication buffers** capture and store all stdio interactions
- **Buffer search and replay** functionality for debugging and analysis
- HTTP requests complete with authentication and error handling
- **Python scripts execute across Windows, macOS, and Linux** with platform-specific optimizations
- **Virtual environment management** works consistently across all platforms
- **Package installation and validation** handles platform-specific requirements
- Results are integrated back into conversation flow

### Performance Requirements
- PCP request parsing: < 100ms
- Function execution: < 5ms overhead
- Command execution: < 30s timeout
- **Session creation**: < 500ms
- **Buffer operations**: < 50ms for append, < 200ms for search
- **Concurrent sessions**: Support 50+ active sessions
- HTTP requests: < 30s timeout
- **Python environment detection**: < 1s across all platforms
- **Virtual environment creation**: < 10s on any platform
- **Package installation**: < 2 minutes per package
- Memory usage: < 100MB per execution, < 10MB per session

### Cross-Platform Requirements
- **Windows compatibility**: Support Python.exe, py.exe, PowerShell activation
- **macOS compatibility**: Support System Python, Homebrew, pyenv, M1/M2 ARM
- **Linux compatibility**: Support distribution-specific Python installations
- **Architecture support**: x86, x64, ARM64 across all platforms
- **Path handling**: Correct path separators and environment variables per platform
- **Permission models**: Handle Windows ACL, Unix permissions appropriately

### Security Requirements
- No command injection vulnerabilities
- No SSRF attack vectors
- Proper permission enforcement
- Sandboxed execution environments
- Comprehensive input validation

## Risk Mitigation

### Security Risks
- **Command Injection**: Comprehensive input sanitization and validation
- **SSRF Attacks**: URL validation and host whitelisting
- **Privilege Escalation**: Strict permission enforcement
- **Resource Exhaustion**: Timeout and resource limits

### Performance Risks
- **Memory Leaks**: Proper resource cleanup and monitoring
- **Blocking Operations**: Async execution with timeouts
- **Process Accumulation**: Process lifecycle management

### Integration Risks
- **Breaking Changes**: Comprehensive testing and gradual rollout
- **Compatibility Issues**: Version compatibility testing
- **Data Loss**: Result backup and recovery mechanisms

## Post-Implementation

### Monitoring and Observability
- Execution metrics and performance monitoring
- Error rate tracking and alerting
- Security event logging and analysis
- Resource usage monitoring

### Maintenance and Updates
- Regular security updates and patches
- Performance optimization based on usage patterns
- Feature enhancements based on user feedback
- Documentation updates and examples

This implementation plan provides a complete roadmap for transforming the TPipe PCP system from a 40% complete foundation into a fully functional execution pipeline capable of handling native functions, shell commands, HTTP requests, and Python scripts with comprehensive security and error handling.
