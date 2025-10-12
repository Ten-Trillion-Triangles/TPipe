package com.TTT.PipeContextProtocol

/**
 * Centralized Python constants for cross-platform support and security validation.
 */
object PythonConstants
{
    /**
     * Common Python executable names across platforms.
     */
    val PYTHON_EXECUTABLES = listOf("python3", "python", "python.exe", "py.exe")
    
    /**
     * Dangerous imports that should be blocked by default.
     */
    val DANGEROUS_IMPORTS = setOf("os", "subprocess")
    
    /**
     * Dangerous function calls that should be blocked by default.
     */
    val DANGEROUS_FUNCTIONS = setOf("os.system", "subprocess.call", "subprocess.run", "subprocess.Popen")
    
    /**
     * Dangerous patterns that should be blocked by default.
     */
    val DANGEROUS_PATTERNS = listOf(
        "eval\\s*\\(",                // Code injection
        "exec\\s*\\(",                // Code injection
        "__import__\\s*\\("           // Dynamic imports
    )
    
    /**
     * Common safe packages that should always be allowed.
     */
    val SAFE_PACKAGES = setOf(
        "numpy", "pandas", "matplotlib", "requests", "json", "csv", 
        "datetime", "math", "random", "re", "urllib", "http", "sqlite3",
        "collections", "itertools", "functools", "typing", "pathlib"
    )
}
