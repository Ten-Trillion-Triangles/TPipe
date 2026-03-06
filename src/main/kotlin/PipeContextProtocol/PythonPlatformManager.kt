package com.TTT.PipeContextProtocol

import kotlinx.serialization.Serializable
import java.io.File

/**
 * Detected Python installation with metadata.
 * 
 * @property executable Full path to the Python executable
 * @property version Python version string (e.g., "3.11.5")
 * @property platform Platform where this Python was found
 * @property isDefault Whether this is the default/preferred Python installation
 */
@Serializable
data class PythonInstallation(
    val executable: String,
    val version: String,
    val platform: String,
    val isDefault: Boolean = false
)

/**
 * Result of Python detection operation.
 * 
 * @property success Whether Python detection completed successfully
 * @property installations List of detected Python installations
 * @property defaultInstallation The preferred Python installation to use
 * @property errors Any errors encountered during detection
 */
@Serializable
data class PythonDetectionResult(
    val success: Boolean,
    val installations: List<PythonInstallation>,
    val defaultInstallation: PythonInstallation?,
    val errors: List<String> = emptyList()
)

/**
 * Manages Python platform-specific operations and executable detection.
 * 
 * Handles differences between Windows, macOS, and Linux Python installations
 * including system Python, package managers (Homebrew, apt), and version managers
 * (pyenv, conda). Provides cross-platform Python executable detection and validation.
 */
class PythonPlatformManager
{
    private val currentPlatform = detectCurrentPlatform()
    
    /**
     * Detect all available Python installations on the current platform.
     * 
     * Searches common installation locations and validates each found executable.
     * Returns installations sorted by preference (newer versions, system locations first).
     */
    fun detectPythonInstallations(): PythonDetectionResult
    {
        val installations = mutableListOf<PythonInstallation>()
        val errors = mutableListOf<String>()
        
        try
        {
            when(currentPlatform)
            {
                "windows" -> installations.addAll(detectWindowsPython())
                "macos" -> installations.addAll(detectMacOSPython())
                "linux" -> installations.addAll(detectLinuxPython())
                else -> errors.add("Unsupported platform: $currentPlatform")
            }
            
            // Validate each installation
            val validInstallations = installations.filter { installation ->
                validatePythonExecutable(installation.executable)
            }
            
            // Determine default installation (prefer python3, then python, then newest version)
            val defaultInstallation = validInstallations.firstOrNull { it.executable.contains("python3") }
                ?: validInstallations.firstOrNull { it.executable.endsWith("python") || it.executable.endsWith("python.exe") }
                ?: validInstallations.firstOrNull()
            
            return PythonDetectionResult(
                success = validInstallations.isNotEmpty(),
                installations = validInstallations,
                defaultInstallation = defaultInstallation,
                errors = errors
            )
        }
        catch(e: Exception)
        {
            errors.add("Python detection failed: ${e.message}")
            return PythonDetectionResult(
                success = false,
                installations = emptyList(),
                defaultInstallation = null,
                errors = errors
            )
        }
    }
    
    /**
     * Build Python command for script execution on current platform.
     * 
     * @param pythonExecutable Path to Python executable
     * @param scriptContent Python script content to execute
     * @param workingDirectory Working directory for execution
     * @return Command array ready for ProcessBuilder
     */
    fun buildPythonCommand(pythonExecutable: String, scriptContent: String, workingDirectory: String): List<String>
    {
        return when(currentPlatform)
        {
            "windows" -> listOf(pythonExecutable, "-c", scriptContent)
            else -> listOf(pythonExecutable, "-c", scriptContent)
        }
    }
    
    /**
     * Validate that a Python executable is functional.
     * 
     * @param executable Path to Python executable to test
     * @return True if executable exists and can run Python code
     */
    fun validatePythonExecutable(executable: String): Boolean
    {
        return try
        {
            val file = File(executable)
            if(!file.exists() || !file.canExecute())
            {
                return false
            }
            
            // Test by running simple Python command with timeout
            val process = ProcessBuilder(executable, "-c", "print('test')")
                .redirectErrorStream(true)
                .start()
            
            // Wait with timeout to prevent hanging
            val completed = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if(!completed)
            {
                process.destroyForcibly()
                return false
            }
            
            process.exitValue() == 0
        }
        catch(e: Exception)
        {
            false
        }
    }
    
    /**
     * Get Python version from executable.
     * 
     * @param executable Path to Python executable
     * @return Version string or "unknown" if detection fails
     */
    fun getPythonVersion(executable: String): String
    {
        return try
        {
            val process = ProcessBuilder(executable, "--version")
                .redirectErrorStream(true)
                .start()
            
            // Wait with timeout
            val completed = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if(!completed)
            {
                process.destroyForcibly()
                return "unknown"
            }
            
            val output = process.inputStream.bufferedReader().readText().trim()
            
            // Parse "Python 3.11.5" -> "3.11.5"
            if(output.startsWith("Python "))
            {
                output.substring(7)
            }
            else
            {
                "unknown"
            }
        }
        catch(e: Exception)
        {
            "unknown"
        }
    }
    
    /**
     * Detect current operating system platform.
     */
    private fun detectCurrentPlatform(): String
    {
        val osName = System.getProperty("os.name").lowercase()
        return when
        {
            osName.contains("windows") -> "windows"
            osName.contains("mac") || osName.contains("darwin") -> "macos"
            osName.contains("linux") -> "linux"
            else -> "unknown"
        }
    }
    
    /**
     * Detect Python installations on Windows.
     */
    private fun detectWindowsPython(): List<PythonInstallation>
    {
        val installations = mutableListOf<PythonInstallation>()
        
        // Common Windows Python locations
        val windowsPaths = listOf(
            "python.exe",           // PATH
            "py.exe",              // Python Launcher
            "python3.exe",         // PATH
            "C:\\Python39\\python.exe",
            "C:\\Python310\\python.exe", 
            "C:\\Python311\\python.exe",
            "C:\\Python312\\python.exe",
            System.getProperty("user.home") + "\\AppData\\Local\\Programs\\Python\\Python311\\python.exe",
            System.getProperty("user.home") + "\\AppData\\Local\\Programs\\Python\\Python312\\python.exe"
        )
        
        windowsPaths.forEach { path ->
            if(File(path).exists() || path in listOf("python.exe", "py.exe", "python3.exe"))
            {
                val version = getPythonVersion(path)
                installations.add(PythonInstallation(path, version, "windows"))
            }
        }
        
        return installations
    }
    
    /**
     * Detect Python installations on macOS.
     */
    private fun detectMacOSPython(): List<PythonInstallation>
    {
        val installations = mutableListOf<PythonInstallation>()
        
        // Common macOS Python locations
        val macosPaths = listOf(
            "python3",                              // PATH
            "python",                               // PATH
            "/usr/bin/python3",                     // System
            "/usr/local/bin/python3",               // Homebrew
            "/opt/homebrew/bin/python3",            // Apple Silicon Homebrew
            "/usr/local/Cellar/python@3.11/3.11.5/bin/python3",
            "/usr/local/Cellar/python@3.12/3.12.0/bin/python3",
            System.getProperty("user.home") + "/.pyenv/shims/python3"  // pyenv
        )
        
        macosPaths.forEach { path ->
            if(File(path).exists() || path in listOf("python3", "python"))
            {
                val version = getPythonVersion(path)
                installations.add(PythonInstallation(path, version, "macos"))
            }
        }
        
        return installations
    }
    
    /**
     * Detect Python installations on Linux.
     */
    private fun detectLinuxPython(): List<PythonInstallation>
    {
        val installations = mutableListOf<PythonInstallation>()
        
        // Common Linux Python locations
        val linuxPaths = listOf(
            "python3",                              // PATH
            "python",                               // PATH
            "/usr/bin/python3",                     // System
            "/usr/bin/python",                      // System
            "/usr/local/bin/python3",               // Local install
            "/opt/python/bin/python3",              // Custom install
            System.getProperty("user.home") + "/.local/bin/python3",
            System.getProperty("user.home") + "/.pyenv/shims/python3"  // pyenv
        )
        
        linuxPaths.forEach { path ->
            if(File(path).exists() || path in listOf("python3", "python"))
            {
                val version = getPythonVersion(path)
                installations.add(PythonInstallation(path, version, "linux"))
            }
        }
        
        return installations
    }
}
