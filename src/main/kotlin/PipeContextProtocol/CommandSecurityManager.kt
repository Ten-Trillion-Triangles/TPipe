package com.TTT.PipeContextProtocol

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/**
 * Security levels for command classification.
 */
enum class SecurityLevel
{
    SAFE,        // Always allowed (ls, cat, echo)
    RESTRICTED,  // Requires explicit permission (ps, netstat, find)
    DANGEROUS,   // Requires elevated permission (chmod, chown, kill)
    FORBIDDEN    // Never allowed (rm, format, dd)
}

/**
 * Platform types for command classification.
 */
enum class Platform
{
    WINDOWS, LINUX, MACOS, UNIX_LIKE
}

/**
 * Command classification with security level and platform.
 */
@Serializable
data class CommandClassification(
    val level: SecurityLevel,
    val platform: Platform,
    val description: String = ""
)

/**
 * Resource validation result for session limits.
 */
@Serializable
data class ResourceValidation(
    val isValid: Boolean,
    val memoryUsage: Long,
    val cpuUsage: Double,
    val sessionCount: Int,
    val warnings: List<String>
)

/**
 * Validates and sanitizes shell commands for security with comprehensive multi-level classification.
 * Supports platform-specific rules and configurable security policies.
 */
class CommandSecurityManager
{
    private val commandDatabase = ConcurrentHashMap<String, CommandClassification>()
    private val customOverrides = ConcurrentHashMap<String, CommandClassification>()
    
    private val injectionPatterns = listOf(
        Regex("[;&|`\$\\(\\)]"),  // Command separators and substitution
        Regex("\\.\\."),          // Directory traversal
        Regex(">/dev/"),          // Device access
        Regex("\\|\\s*nc\\s"),    // Netcat piping
        Regex("\\|\\s*curl\\s"),  // Curl piping
        Regex("\\|\\s*wget\\s")   // Wget piping
    )
    
    init
    {
        initializeDefaultDatabase()
    }
    
    /**
     * Initialize comprehensive command database with platform-specific classifications.
     */
    private fun initializeDefaultDatabase()
    {
        // SAFE commands - basic file operations and info
        val safeCommands = mapOf(
            // Universal safe commands
            "echo" to "Display text",
            "cat" to "Display file contents",
            "head" to "Display first lines of file",
            "tail" to "Display last lines of file",
            "wc" to "Count lines/words/characters",
            "date" to "Display current date/time",
            "pwd" to "Print working directory",
            "whoami" to "Display current user",
            
            // Linux/Unix safe commands
            "ls" to "List directory contents",
            "grep" to "Search text patterns",
            "sort" to "Sort lines of text",
            "uniq" to "Remove duplicate lines",
            "cut" to "Extract columns from text",
            "awk" to "Text processing",
            "sed" to "Stream editor",
            "which" to "Locate command",
            "whereis" to "Locate binary/source/manual",
            "file" to "Determine file type",
            "stat" to "Display file statistics",
            "basename" to "Extract filename from path",
            "dirname" to "Extract directory from path"
        )
        
        safeCommands.forEach { (cmd, desc) ->
            commandDatabase[cmd] = CommandClassification(SecurityLevel.SAFE, Platform.UNIX_LIKE, desc)
        }
        
        // Windows safe commands
        val windowsSafeCommands = mapOf(
            "dir" to "List directory contents",
            "type" to "Display file contents",
            "more" to "Display file contents page by page",
            "find" to "Search for text in files",
            "findstr" to "Search for strings in files",
            "sort" to "Sort input",
            "fc" to "Compare files",
            "where" to "Locate files"
        )
        
        windowsSafeCommands.forEach { (cmd, desc) ->
            commandDatabase[cmd] = CommandClassification(SecurityLevel.SAFE, Platform.WINDOWS, desc)
        }
        
        // RESTRICTED commands - system info, network, process management
        val restrictedCommands = mapOf(
            // Process and system info
            "ps" to "List running processes",
            "top" to "Display running processes",
            "htop" to "Interactive process viewer",
            "jobs" to "List active jobs",
            "pgrep" to "Find processes by name",
            "pidof" to "Find process IDs",
            "uptime" to "System uptime and load",
            "free" to "Display memory usage",
            "df" to "Display filesystem usage",
            "du" to "Display directory usage",
            "lsof" to "List open files",
            "fuser" to "Show processes using files",
            
            // Network commands
            "ping" to "Send ICMP packets",
            "traceroute" to "Trace network route",
            "nslookup" to "DNS lookup",
            "dig" to "DNS lookup tool",
            "host" to "DNS lookup utility",
            "netstat" to "Display network connections",
            "ss" to "Display socket statistics",
            "arp" to "Display ARP table",
            "route" to "Display routing table",
            "ip" to "Network configuration tool",
            "ifconfig" to "Network interface configuration",
            
            // File system operations
            "find" to "Search for files and directories",
            "locate" to "Find files by name",
            "updatedb" to "Update locate database",
            "mount" to "Mount filesystems",
            "umount" to "Unmount filesystems",
            "lsblk" to "List block devices",
            "fdisk" to "Disk partitioning tool",
            "parted" to "Partition manipulation",
            
            // Archive and compression
            "tar" to "Archive files",
            "gzip" to "Compress files",
            "gunzip" to "Decompress files",
            "zip" to "Create ZIP archives",
            "unzip" to "Extract ZIP archives",
            "7z" to "7-Zip archiver"
        )
        
        restrictedCommands.forEach { (cmd, desc) ->
            commandDatabase[cmd] = CommandClassification(SecurityLevel.RESTRICTED, Platform.UNIX_LIKE, desc)
        }
        
        // Windows restricted commands
        val windowsRestrictedCommands = mapOf(
            "tasklist" to "List running processes",
            "netstat" to "Display network connections",
            "ping" to "Send ICMP packets",
            "tracert" to "Trace network route",
            "nslookup" to "DNS lookup",
            "ipconfig" to "Network configuration",
            "systeminfo" to "Display system information",
            "wmic" to "Windows Management Interface",
            "reg" to "Registry operations",
            "sc" to "Service control",
            "net" to "Network commands",
            "diskpart" to "Disk partitioning"
        )
        
        windowsRestrictedCommands.forEach { (cmd, desc) ->
            commandDatabase[cmd] = CommandClassification(SecurityLevel.RESTRICTED, Platform.WINDOWS, desc)
        }
        
        // DANGEROUS commands - system modification, user management
        val dangerousCommands = mapOf(
            // File operations
            "mv" to "Move/rename files",
            "cp" to "Copy files",
            "ln" to "Create links",
            "mkdir" to "Create directories",
            "rmdir" to "Remove empty directories",
            "touch" to "Create/update file timestamps",
            
            // Permissions and ownership
            "chmod" to "Change file permissions",
            "chown" to "Change file ownership",
            "chgrp" to "Change group ownership",
            "umask" to "Set default permissions",
            
            // Process management
            "kill" to "Terminate processes",
            "killall" to "Kill processes by name",
            "pkill" to "Kill processes by criteria",
            "nohup" to "Run commands immune to hangups",
            "bg" to "Put jobs in background",
            "fg" to "Bring jobs to foreground",
            
            // System control
            "crontab" to "Schedule tasks",
            "at" to "Schedule one-time tasks",
            "systemctl" to "Control systemd services",
            "service" to "Control system services",
            "chkconfig" to "Configure system services",
            
            // User management
            "passwd" to "Change password",
            "useradd" to "Add user account",
            "usermod" to "Modify user account",
            "userdel" to "Delete user account",
            "groupadd" to "Add group",
            "groupmod" to "Modify group",
            "groupdel" to "Delete group",
            "su" to "Switch user",
            "sudo" to "Execute as another user",
            
            // Network modification
            "iptables" to "Configure firewall",
            "ufw" to "Uncomplicated firewall",
            "firewall-cmd" to "Firewall management"
        )
        
        dangerousCommands.forEach { (cmd, desc) ->
            commandDatabase[cmd] = CommandClassification(SecurityLevel.DANGEROUS, Platform.UNIX_LIKE, desc)
        }
        
        // Windows dangerous commands
        val windowsDangerousCommands = mapOf(
            "copy" to "Copy files",
            "move" to "Move files",
            "ren" to "Rename files",
            "rename" to "Rename files",
            "md" to "Create directory",
            "mkdir" to "Create directory",
            "rd" to "Remove directory",
            "rmdir" to "Remove directory",
            "attrib" to "Change file attributes",
            "cacls" to "Change file permissions",
            "icacls" to "Change file permissions",
            "takeown" to "Take ownership of files",
            "taskkill" to "Terminate processes",
            "schtasks" to "Schedule tasks",
            "net" to "Network and user management",
            "runas" to "Run as different user"
        )
        
        windowsDangerousCommands.forEach { (cmd, desc) ->
            commandDatabase[cmd] = CommandClassification(SecurityLevel.DANGEROUS, Platform.WINDOWS, desc)
        }
        
        // FORBIDDEN commands - destructive operations
        val forbiddenCommands = mapOf(
            // Destructive file operations
            "rm" to "Remove files and directories",
            "shred" to "Securely delete files",
            "wipe" to "Securely delete files",
            "dd" to "Low-level disk operations",
            
            // Disk operations
            "mkfs" to "Create filesystem",
            "fsck" to "Check filesystem",
            "badblocks" to "Check for bad blocks",
            "hdparm" to "Hard disk parameters",
            
            // System modification
            "init" to "Change system runlevel",
            "telinit" to "Change system runlevel",
            "shutdown" to "Shutdown system",
            "halt" to "Halt system",
            "poweroff" to "Power off system",
            "reboot" to "Restart system",
            
            // Kernel and system
            "insmod" to "Insert kernel module",
            "rmmod" to "Remove kernel module",
            "modprobe" to "Load kernel module",
            "kexec" to "Load new kernel",
            
            // Security bypass
            "chroot" to "Change root directory",
            "unshare" to "Create new namespaces"
        )
        
        forbiddenCommands.forEach { (cmd, desc) ->
            commandDatabase[cmd] = CommandClassification(SecurityLevel.FORBIDDEN, Platform.UNIX_LIKE, desc)
        }
        
        // Windows forbidden commands
        val windowsForbiddenCommands = mapOf(
            "del" to "Delete files",
            "erase" to "Delete files",
            "format" to "Format disk",
            "fdisk" to "Disk partitioning",
            "diskpart" to "Advanced disk operations",
            "shutdown" to "Shutdown system",
            "restart" to "Restart system",
            "bcdedit" to "Boot configuration",
            "bootrec" to "Boot recovery",
            "sfc" to "System file checker",
            "dism" to "Deployment image management"
        )
        
        windowsForbiddenCommands.forEach { (cmd, desc) ->
            commandDatabase[cmd] = CommandClassification(SecurityLevel.FORBIDDEN, Platform.WINDOWS, desc)
        }
    }
    
    /**
     * Get current platform.
     */
    private fun getCurrentPlatform(): Platform
    {
        val os = System.getProperty("os.name").lowercase()
        return when
        {
            os.contains("windows") -> Platform.WINDOWS
            os.contains("mac") -> Platform.MACOS
            else -> Platform.LINUX
        }
    }
    
    /**
     * Override default classification for a command.
     */
    fun setCommandClassification(command: String, classification: CommandClassification)
    {
        customOverrides[command] = classification
    }
    
    /**
     * Get classification for a command.
     */
    fun getCommandClassification(command: String): CommandClassification?
    {
        val currentPlatform = getCurrentPlatform()
        
        // Check custom overrides first
        customOverrides[command]?.let { return it }
        
        // Check platform-specific classification
        commandDatabase[command]?.let { classification ->
            if (classification.platform == currentPlatform || 
                classification.platform == Platform.UNIX_LIKE ||
                (currentPlatform == Platform.MACOS && classification.platform == Platform.UNIX_LIKE))
            {
                return classification
            }
        }
        
        return null
    }
    
    /**
     * Validate command against security policies with level-based checking.
     */
    fun validateCommand(
        command: String, 
        allowedCommands: List<String> = emptyList(),
        maxSecurityLevel: SecurityLevel = SecurityLevel.RESTRICTED
    ): Boolean
    {
        // If specific commands are allowed, check that first
        if (allowedCommands.isNotEmpty())
        {
            return allowedCommands.contains(command)
        }
        
        val classification = getCommandClassification(command)
        
        // Unknown commands default to RESTRICTED level
        val commandLevel = classification?.level ?: SecurityLevel.RESTRICTED
        
        // Check if command level is within allowed range
        return when (maxSecurityLevel)
        {
            SecurityLevel.SAFE -> commandLevel == SecurityLevel.SAFE
            SecurityLevel.RESTRICTED -> commandLevel in listOf(SecurityLevel.SAFE, SecurityLevel.RESTRICTED)
            SecurityLevel.DANGEROUS -> commandLevel != SecurityLevel.FORBIDDEN
            SecurityLevel.FORBIDDEN -> true // Allow everything (not recommended)
        }
    }
    
    /**
     * Sanitize command arguments to prevent injection.
     */
    fun sanitizeArguments(args: List<String>): List<String>
    {
        return args.map { arg ->
            var sanitized = arg
            
            // Remove dangerous patterns
            injectionPatterns.forEach { pattern ->
                sanitized = sanitized.replace(pattern, "")
            }
            
            // Escape special characters
            sanitized = sanitized.replace("\"", "\\\"")
            sanitized = sanitized.replace("'", "\\'")
            
            sanitized
        }
    }
    
    /**
     * Check if path access is allowed based on permissions.
     */
    fun checkPathPermissions(path: String, permissions: List<Permissions>): Boolean
    {
        val normalizedPath = path.replace("\\", "/").lowercase()
        
        // Block system directories
        val systemPaths = when (getCurrentPlatform())
        {
            Platform.WINDOWS -> listOf("c:/windows", "c:/system32", "c:/program files")
            else -> listOf("/etc", "/sys", "/proc", "/dev", "/boot", "/root")
        }
        
        if (systemPaths.any { normalizedPath.startsWith(it) })
        {
            return permissions.contains(Permissions.Execute) // Only allow with explicit execute permission
        }
        
        return true
    }
    
    /**
     * Detect potential command injection attempts.
     */
    fun detectCommandInjection(input: String): Boolean
    {
        return injectionPatterns.any { it.containsMatchIn(input) }
    }
    
    /**
     * Validate session access permissions.
     */
    fun validateSessionAccess(sessionId: String, userId: String): Boolean
    {
        return sessionId.isNotEmpty() && userId.isNotEmpty()
    }
    
    /**
     * Validate buffer access permissions.
     */
    fun validateBufferAccess(bufferId: String, permissions: List<Permissions>): Boolean
    {
        return bufferId.isNotEmpty() && permissions.contains(Permissions.Read)
    }
    
    /**
     * Sanitize input for interactive sessions.
     */
    fun sanitizeSessionInput(input: String, maxLength: Int = 4096): String
    {
        var sanitized = input
        
        // Remove control characters except newline and tab
        sanitized = sanitized.replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"), "")
        
        // Limit length
        if (sanitized.length > maxLength)
        {
            sanitized = sanitized.take(maxLength)
        }
        
        return sanitized
    }
    
    /**
     * Check resource limits for sessions.
     */
    fun checkResourceLimits(sessionId: String): ResourceValidation
    {
        return ResourceValidation(
            isValid = true,
            memoryUsage = 0,
            cpuUsage = 0.0,
            sessionCount = 1,
            warnings = emptyList()
        )
    }
    
    /**
     * Get all commands at or below specified security level.
     */
    fun getCommandsBySecurityLevel(maxLevel: SecurityLevel): Map<String, CommandClassification>
    {
        val currentPlatform = getCurrentPlatform()
        
        return (commandDatabase + customOverrides).filter { (_, classification) ->
            val isCompatiblePlatform = classification.platform == currentPlatform || 
                classification.platform == Platform.UNIX_LIKE ||
                (currentPlatform == Platform.MACOS && classification.platform == Platform.UNIX_LIKE)
            
            val isWithinLevel = when (maxLevel)
            {
                SecurityLevel.SAFE -> classification.level == SecurityLevel.SAFE
                SecurityLevel.RESTRICTED -> classification.level in listOf(SecurityLevel.SAFE, SecurityLevel.RESTRICTED)
                SecurityLevel.DANGEROUS -> classification.level != SecurityLevel.FORBIDDEN
                SecurityLevel.FORBIDDEN -> true
            }
            
            isCompatiblePlatform && isWithinLevel
        }
    }
}
