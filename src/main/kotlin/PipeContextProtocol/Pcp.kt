package com.TTT.PipeContextProtocol


/**
 * Transport mechanism for PCP protocol.
 */
enum class Transport
{
    Auto,
    Stdio,
    Tpipe,
    Http,
    Python,
    Kotlin,
    JavaScript,
    Unknown
}

/**
 * Helps define what a given context action is able to do to the user's system.
 */
enum class Permissions
{
    Read,
    Write,
    Delete,
    Execute
}

/**
 * Denotes the parameters of a function call in PCP to help teach a llm exactly what TPipe function params typing
 * are.
 */
enum class ParamType
{
    String,
    Int,
    Bool,
    Float,
    Enum,
    List,
    Map,
    Object,
    Any
}

/**
 * Execution modes for stdio operations.
 */
enum class StdioExecutionMode
{
    ONE_SHOT,      // Execute command once and return result
    INTERACTIVE,   // Create persistent session for ongoing communication
    CONNECT,       // Connect to existing session
    BUFFER_REPLAY  // Replay from saved buffer
}

/**
 * Context options for any commands that run using stdio on a shell. Allows the LLM to be told how to use it
 * and what it can use it for.
 */
@kotlinx.serialization.Serializable
data class StdioContextOptions(@kotlinx.serialization.Transient val cinit : Boolean = false)
{
    /**
     * Stdio command/program to run.
     */
    @kotlinx.serialization.Serializable
    var command = ""

    /**
     * List of allowed arguments. If none are provided the llm can use any possible argument supported.
     * For custom commands, user created programs and other commands that might not be in the llm's training data
     * you will need to supply each argument.
     */
    @kotlinx.serialization.Serializable
    var args = mutableListOf<String>()

    /**
     * Instructs the llm on what it may do with this command to the user's system.
     */
    @kotlinx.serialization.Serializable
    var permissions = mutableListOf<Permissions>()

    /**
     * Command description for the llm. This is optional but may be required if you're asking it to employ a custom
     * command, shell script, or program not in it's training data. If so, use this to teach it what each argument
     * in the command does and what the command can be used for.
     */
    @kotlinx.serialization.Serializable
    var description = ""

    /**
     * Execution mode for this stdio operation.
     */
    @kotlinx.serialization.Serializable
    var executionMode = StdioExecutionMode.ONE_SHOT

    /**
     * Session ID for connecting to existing sessions.
     */
    @kotlinx.serialization.Serializable
    var sessionId: String? = null

    /**
     * Buffer ID for buffer operations.
     */
    @kotlinx.serialization.Serializable
    var bufferId: String? = null

    /**
     * Working directory for command execution.
     */
    @kotlinx.serialization.Serializable
    var workingDirectory: String? = null

    /**
     * Environment variables for command execution.
     */
    @kotlinx.serialization.Serializable
    var environmentVariables = mutableMapOf<String, String>()

    /**
     * Timeout in milliseconds.
     */
    @kotlinx.serialization.Serializable
    var timeoutMs: Long = 30000

    /**
     * Keep session alive after command execution.
     */
    @kotlinx.serialization.Serializable
    var keepSessionAlive = false

    /**
     * Enable buffer persistence for this session.
     */
    @kotlinx.serialization.Serializable
    var bufferPersistence = false

    /**
     * Maximum buffer size in bytes.
     */
    @kotlinx.serialization.Serializable
    var maxBufferSize = 1048576 // 1MB default
}



@kotlinx.serialization.Serializable
data class TPipeContextOptions(@kotlinx.serialization.Transient val cinit : Boolean = false)
{
    /**
     * Name of the function that the llm can call. If this blank the PCP constructor will discard this object
     * without adding it to the PCP context.
     */
    @kotlinx.serialization.Serializable
    var functionName = ""

    /**
     * Optional description to assist the llm in understanding what the function would do.
     */
    @kotlinx.serialization.Serializable
    var description = ""

    /**
     * List of all function parameters, containing the variable type of the param, an optional description,
     * and a list of all possible enum values if it's an enum type. If not, this value should not be applied
     * and will be discarded by the PCP constructor.
     */
    @kotlinx.serialization.Serializable
    var params = mutableMapOf<String, Triple<ParamType, String, List<String> > >()

}

/**
 * Context options for HTTP requests. Allows the LLM to make HTTP calls with specified constraints.
 */
@kotlinx.serialization.Serializable
data class HttpContextOptions(@kotlinx.serialization.Transient val cinit : Boolean = false)
{
    /**
     * Base URL for HTTP requests.
     */
    @kotlinx.serialization.Serializable
    var baseUrl = ""

    /**
     * Endpoint path (appended to baseUrl).
     */
    @kotlinx.serialization.Serializable
    var endpoint = ""

    /**
     * HTTP method. See HttpConstants.ALL_METHODS for supported values.
     */
    @kotlinx.serialization.Serializable
    var method = "GET"

    /**
     * Request body content.
     */
    @kotlinx.serialization.Serializable
    var requestBody = ""

    /**
     * Allowed HTTP methods.
     */
    @kotlinx.serialization.Serializable
    var allowedMethods = mutableListOf<String>()

    /**
     * Request headers to include.
     */
    @kotlinx.serialization.Serializable
    var headers = mutableMapOf<String, String>()

    /**
     * Authentication type (NONE, BASIC, BEARER, API_KEY).
     */
    @kotlinx.serialization.Serializable
    var authType = ""

    /**
     * Authentication credentials (username, password, token, key, etc.).
     */
    @kotlinx.serialization.Serializable
    var authCredentials = mutableMapOf<String, String>()

    /**
     * Allowed hosts for security (empty = any host allowed).
     */
    @kotlinx.serialization.Serializable
    var allowedHosts = mutableListOf<String>()

    /**
     * Follow HTTP redirects.
     */
    @kotlinx.serialization.Serializable
    var followRedirects = true

    /**
     * Timeout in milliseconds.
     */
    @kotlinx.serialization.Serializable
    var timeoutMs = 30000

    /**
     * Permissions for HTTP operations.
     */
    @kotlinx.serialization.Serializable
    var permissions = mutableListOf<Permissions>()

    /**
     * Description of the HTTP endpoint for the LLM.
     */
    @kotlinx.serialization.Serializable
    var description = ""
}

@kotlinx.serialization.Serializable
data class PythonContext(@kotlinx.serialization.Transient val cinit : Boolean = false)
{
    /**
     * List of installed packages that can be used for python scripts on the host machine.
     */
    @kotlinx.serialization.Serializable
    var availablePackages = mutableListOf<String>()

    /**
     * Python version (e.g., "3.11.5")
     */
    @kotlinx.serialization.Serializable
    var pythonVersion = ""

    /**
     * Python executable path
     */
    @kotlinx.serialization.Serializable
    var pythonPath = ""

    /**
     * Working directory for Python execution
     */
    @kotlinx.serialization.Serializable
    var workingDirectory = ""

    /**
     * Environment variables available to Python
     */
    @kotlinx.serialization.Serializable
    var environmentVariables = mutableMapOf<String, String>()

    /**
     * Maximum execution time in milliseconds
     */
    @kotlinx.serialization.Serializable
    var timeoutMs = 30000

    /**
     * Whether to capture stdout/stderr
     */
    @kotlinx.serialization.Serializable
    var captureOutput = true

    /**
     * Permissions for Python operations
     */
    @kotlinx.serialization.Serializable
    var permissions = mutableListOf<Permissions>()
}

@kotlinx.serialization.Serializable
data class KotlinContext(val cinit : Boolean = false)
{
    /**
     * List of allowed imports for the Kotlin script.
     */
    @kotlinx.serialization.Serializable
    var allowedImports = mutableListOf<String>()

    /**
     * Maximum execution time in milliseconds
     */
    @kotlinx.serialization.Serializable
    var timeoutMs = 30000

    /**
     * Whether to allow introspection of TPipe internal objects.
     */
    @kotlinx.serialization.Serializable
    var allowIntrospection = true

    /**
     * Permissions for Kotlin operations
     */
    @kotlinx.serialization.Serializable
    var permissions = mutableListOf<Permissions>()
}

@kotlinx.serialization.Serializable
data class JavaScriptContext(val cinit : Boolean = false)
{
    /**
     * List of allowed npm packages/modules.
     */
    @kotlinx.serialization.Serializable
    var allowedModules = mutableListOf<String>()

    /**
     * Path to Node.js executable.
     */
    @kotlinx.serialization.Serializable
    var nodePath = ""

    /**
     * Working directory for execution.
     */
    @kotlinx.serialization.Serializable
    var workingDirectory = ""

    /**
     * Environment variables.
     */
    @kotlinx.serialization.Serializable
    var environmentVariables = mutableMapOf<String, String>()

    /**
     * Maximum execution time in milliseconds.
     */
    @kotlinx.serialization.Serializable
    var timeoutMs = 30000

    /**
     * Permissions for JavaScript operations.
     */
    @kotlinx.serialization.Serializable
    var permissions = mutableListOf<Permissions>()
}


@kotlinx.serialization.Serializable
data class PcpContext(@kotlinx.serialization.Transient val cinit : Boolean = false)
{
    /**
     * Transport mechanism for this context.
     */
    @kotlinx.serialization.Serializable
    var transport = Transport.Auto

    /**
     * Stdio context options.
     */
    @kotlinx.serialization.Serializable
    var stdioOptions = mutableListOf<StdioContextOptions>()

    /**
     * TPipe function context options.
     */
    @kotlinx.serialization.Serializable
    var tpipeOptions = mutableListOf<TPipeContextOptions>()

    /**
     * HTTP context options.
     */
    @kotlinx.serialization.Serializable
    var httpOptions = mutableListOf<HttpContextOptions>()

    /**
     * List of available actions in python that can be taken.
     */
    var pythonOptions = PythonContext()

    /**
     * Options for Kotlin script execution.
     */
    var kotlinOptions = KotlinContext()

    /**
     * Options for JavaScript execution.
     */
    var javascriptOptions = JavaScriptContext()

    /**
     * Defines allowed folders that can be read or written into. All folders are allowed if this is empty.
     */
    @kotlinx.serialization.Serializable
    var allowedDirectoryPaths = mutableListOf<String>()

    /**
     * Defines folders that may not be written into, or deleted. Does not apply if empty.
     */
    var forbiddenDirectoryPaths = mutableListOf<String>()

    /**
     * Defines allowed files to modify, delete, or write into. Does not apply if empty allowing any file.
     */
    var allowedFiles = mutableListOf<String>()

    /**
     * Defines files that may not be written into, or deleted. Does not apply if empty.
     */
    var forbiddenFiles = mutableListOf<String>()

    /**
     * Enable session access control validation (optional security).
     */
    var enableSessionAccessControl: Boolean = false

    /**
     * Enable buffer access control validation (optional security).
     */
    var enableBufferAccessControl: Boolean = false

    /**
     * Current user ID for access control validation.
     */
    var currentUserId: String = System.getProperty("user.name")



    /**
     * Add stdio context option.
     */
    fun addStdioOption(option: StdioContextOptions)
    {
        stdioOptions.add(option)
    }

    /**
     * Add TPipe function context option.
     */
    fun addTPipeOption(option: TPipeContextOptions)
    {
        if (option.functionName.isNotBlank())
        {
            tpipeOptions.add(option)
        }
    }

    /**
     * Add HTTP context option.
     */
    fun addHttpOption(option: HttpContextOptions)
    {
        httpOptions.add(option)
    }
}

/**
 * Callback from the llm to request PcP usage. Must be sent back as an array of each value. Intended to be used as an example
 * for prompt injection only.
 */
@kotlinx.serialization.Serializable
data class PcPRequest(var stdioContextOptions: StdioContextOptions = StdioContextOptions(),
                      var tPipeContextOptions: TPipeContextOptions = TPipeContextOptions(),
                      var httpContextOptions: HttpContextOptions = HttpContextOptions(),
                      var pythonContextOptions: PythonContext = PythonContext(),
                      var kotlinContextOptions: KotlinContext = KotlinContext(),
                      var javascriptContextOptions: JavaScriptContext = JavaScriptContext(),
                      var argumentsOrFunctionParams : List<String> = mutableListOf<String>()
                    )


/**
 * List of PcPRequests. This is used as the actual object to deserialize when an llm makes a Pipe Context Protocol request.
 */
data class PcPRequestList(val requests: List<PcPRequest> = mutableListOf())

