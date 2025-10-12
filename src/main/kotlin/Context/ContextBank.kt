package com.TTT.Context

import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


/**
 * Singleton that holds TPipe's global context window system. Each pipe has its own context window object which
 * allows the pipe to control and manipulate the context the llm sees when injecting data into a prompt. Pipes can then
 * write to this global context bank to update the global state of whatever job the pipeline is doing. The pipelines
 * themselves, can also write to this ContextBank to allow multiple pipelines to share context in parallel.
 *
 * @see ContextWindow
 * @see LoreBook
 * @see Dictionary
 * @see Pipe
 * @see com.TTT.Pipeline.Pipeline
 *
 */
object ContextBank
{
    /**
     * Currently loaded context window from the bank. This is intended to make it easy for the coder to
     * address and manipulate current context without having to fiddle with the map keys.
     */
    @Volatile
    private var bankedContextWindow = ContextWindow()

    /**
     * Banked context windows to allow for TPipe to manage multiple separate and distinct context windows at once.
     */
    @Volatile
    private var bank = mutableMapOf<String, ContextWindow>()

    /**
     * Mutex object for managing swapping the banked context window.
     */
    val swapMutex = Mutex()

    /**
     * Mutex used for locking access to the bank so that multiple coroutines can safely update the bank.
     */
    val bankMutex = Mutex()


    /**
     * Retrieve the existing banked context window reference.
     * Warning: Do not call this if you are updating the context window inside of pipes or coroutines. Use copy
     * instead to collect the window for safety reasons.
     */
    fun getBankedContextWindow() : ContextWindow
    {
        return bankedContextWindow
    }

    /**
     * Get a copy of the existing banked context window. This should be used when inside a coroutine or alternate
     * thread.
     */
    fun copyBankedContextWindow() : ContextWindow?
    {
        val json = serialize(bankedContextWindow)
        return deserialize<ContextWindow>(json) as? ContextWindow
    }

    /**
     * replace or add a context window to the bank.
     *
     * Warning: Do not call this inside a coroutine without locking the mutex or using the withMutex version of this
     * function instead.
     *
     * @param key map key to replace
     * @param window Context window to replace the map key with.
     *
     *
     */
    fun emplace(key: String, window: ContextWindow)
    {
        bank[key] = window
    }


    /**
     * Safely emplace a context window back using the mutex. This is the recommended way to emplace when possible.
     * This should always be used over the regular emplace if you are updating the context inside a pipe or pipeline.
     */
    suspend fun emplaceWithMutex(key: String, window: ContextWindow)
    {
        bankMutex.withLock {
            bank[key] = window
        }
    }


    /**
     * Update the banked context window with a new context.
     */
    fun updateBankedContext(newContext: ContextWindow)
    {
        bankedContextWindow = newContext
    }

    /**
     * Safely update the banked context window using mutex.
     */
    suspend fun updateBankedContextWithMutex(newContext: ContextWindow)
    {
        bankMutex.withLock {
            bankedContextWindow = newContext
        }
    }


    /**
     * Bank swap the context window for one that is on another page.
     * Warning: Do not call this inside a coroutine or outside the main thread. Use the WithMutex version instead.
     *
     * @param key page key for the banked context window we want to pull into visibility.
     *
     * @see swapBankWithMutex
     */
    fun swapBank(key: String, copy: Boolean = true)
    {
        val context = bank[key] ?: ContextWindow()

        /**
         * By default, we want to copy it for safety, though this can be a much slower operation. If we do,
         * we'll use serialization to perform a deep copy and pass that to the swapped bank variable.
         */
        if(copy)
        {
            val json = serialize(context)
            val copyContext = deserialize<ContextWindow>(json)
            bankedContextWindow = copyContext ?: ContextWindow()
            return
        }

        //Otherwise, the banked window becomes a reference.
        bankedContextWindow = context
    }

    /**
     * Function to safely bank swap inside a coroutine or multithreaded environment.
     * @see swapBank
     */
    suspend fun swapBankWithMutex(key: String)
    {
        bankMutex.withLock {
            swapMutex.withLock {
                swapBank(key)
            }
        }
    }


    /**
     * Retrieve a banked context window directly. By default, this returns a copy for safety but can also return
     * a direct reference.
     *
     * @param key The page key for the bank
     * @param copy If true, a deep copy will be made using serialization. Otherwise, return the reference directly.
     * Defaults to true.
     */
    fun getContextFromBank(key: String, copy: Boolean = true) : ContextWindow
    {
        val context = bank[key] ?: ContextWindow()
        if(copy)
        {
            val json = serialize(context)
            val copyContext = deserialize<ContextWindow>(json)
            return copyContext ?: ContextWindow()
        }

        return context
    }

    /**
     * Clear all banked context. Useful when some code is checking if this contains data or not and applies logic
     * if it does.
     */
    fun clearBankedContext()
    {
        bankedContextWindow = ContextWindow()
    }
}