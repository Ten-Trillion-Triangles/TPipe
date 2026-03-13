package com.TTT.Context

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Configuration for memory introspection security ("the leash").
 * Defines what an agent is allowed to see and do within the memory system.
 * This is separate from core PCP context to keep introspection tools as a layered feature.
 */
data class MemoryIntrospectionConfig(
    /**
     * List of page keys the agent is allowed to access.
     * If this contains "*", all page keys are allowed (subject to ContextLock).
     */
    var allowedPageKeys: MutableSet<String> = mutableSetOf(),

    /**
     * Whether the agent is allowed to create new page keys in the ContextBank.
     */
    var allowPageCreation: Boolean = false,

    /**
     * Whether the agent has read access to the allowed memory resources.
     */
    var allowRead: Boolean = true,

    /**
     * Whether the agent has write access (add/update/delete) to the allowed memory resources.
     */
    var allowWrite: Boolean = false
)

/**
 * Singleton manager for memory introspection security and state.
 * Allows developers to "leash" agents by defining what memory they can introspect.
 */
object MemoryIntrospection
{
    private val configThreadLocal = ThreadLocal<MemoryIntrospectionConfig>()

    /**
     * Executes a block of code within a specific introspection scope.
     * All memory introspection tools called within this block will respect the provided config.
     *
     * @param config The security configuration to apply.
     * @param block The code to execute within the scope.
     * @return The result of the block.
     */
    fun <T> withScope(config: MemoryIntrospectionConfig, block: () -> T): T
    {
        val previous = configThreadLocal.get()
        configThreadLocal.set(config)
        try
        {
            return block()
        }
        finally
        {
            if(previous == null)
            {
                configThreadLocal.remove()
            }
            else
            {
                configThreadLocal.set(previous)
            }
        }
    }

    /**
     * Coroutine-safe version of withScope.
     */
    suspend fun <T> withCoroutineScope(config: MemoryIntrospectionConfig, block: suspend () -> T): T
    {
        return withContext(configThreadLocal.asContextElement(config))
        {
            block()
        }
    }

    /**
     * Gets the current introspection configuration for this thread.
     * @return The current config, or a default "deny-all" config if none is set.
     */
    fun getCurrentConfig(): MemoryIntrospectionConfig
    {
        return configThreadLocal.get() ?: MemoryIntrospectionConfig(allowRead = false, allowWrite = false)
    }

    /**
     * Checks if a specific page key is allowed under the current scope.
     * @param pageKey The key to check.
     * @return True if allowed, false otherwise.
     */
    fun isPageAllowed(pageKey: String): Boolean
    {
        val config = getCurrentConfig()
        if(!config.allowRead && !config.allowWrite)
        {
            return false
        }
        if(config.allowedPageKeys.contains("*"))
        {
            return true
        }
        return config.allowedPageKeys.contains(pageKey)
    }

    /**
     * Checks if read access is permitted for a page.
     * @param pageKey The key to check.
     * @return True if allowed, false otherwise.
     */
    fun canRead(pageKey: String): Boolean
    {
        return getCurrentConfig().allowRead && isPageAllowed(pageKey)
    }

    /**
     * Checks if write access is permitted for a page.
     * @param pageKey The key to check.
     * @return True if allowed, false otherwise.
     */
    fun canWrite(pageKey: String): Boolean
    {
        val config = getCurrentConfig()
        if(!config.allowWrite)
        {
            return false
        }

        // If it's a new page, check allowPageCreation
        val pageExists = ContextBank.getPageKeys().contains(pageKey)
        if(!pageExists && !config.allowPageCreation)
        {
            return false
        }

        // Check if the key itself is allowed
        if(config.allowedPageKeys.contains("*"))
        {
            return true
        }
        return config.allowedPageKeys.contains(pageKey)
    }

    /**
     * Suspend-safe version of [canWrite] for coroutine-heavy memory flows.
     *
     * @param pageKey The key to check.
     * @return True if allowed, false otherwise.
     */
    suspend fun canWriteSuspend(pageKey: String): Boolean
    {
        val config = getCurrentConfig()
        if(!config.allowWrite)
        {
            return false
        }

        val pageExists = ContextBank.getPageKeysSuspend().contains(pageKey)
        if(!pageExists && !config.allowPageCreation)
        {
            return false
        }

        if(config.allowedPageKeys.contains("*"))
        {
            return true
        }
        return config.allowedPageKeys.contains(pageKey)
    }
}
