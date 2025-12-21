package com.TTT.Context

import kotlinx.coroutines.sync.Mutex

data class KeyBundle(
    var keys: MutableList<String> = mutableListOf(),
    var pages: MutableList<String> = mutableListOf(),
    var isGlobal: Boolean = false,
    var isLocked: Boolean = false,
    var isPageKey: Boolean = false,
    var passthroughFunction: (suspend () -> Boolean)? = null
)

/**
 * Core class for providing locking mechanisms to the TPipe context system. Provides the ability to lock [ContextBank]
 * page keys, as well as [LoreBook] keys. Either globally or by page key.
 */
object ContextLock
{
    /**
     * Map of keys, to the locks bound to them. This allows for the tracking of alias keys, any pages being locked,
     * and the passthrough function.
     */
    private val locks = mutableMapOf<String, KeyBundle>()

    /**
     * Mutex used for ensuring thread safety in any locking, or unlocking operations occurring in coroutines.
     */
    val lockMutex = Mutex()

    fun addLock(key: String,
                pageKeys: String,
                isPageKey: Boolean,
                lockState: Boolean = true,
                passthroughFunction: (suspend () -> Boolean)? = null
                )
    {
        /**
         * Declare new key bundle, then scope into it because fuck boilerplate.
         */
        val newKeyBundle = KeyBundle().apply {
            //If global, we need to search every window in the context bank.
            val isGlobal = pageKeys.isEmpty() //Reach out into the main function scope and grab our param.
            this.isGlobal = isGlobal //Set isGlobal in the scoped data class to the above local val.
            this.passthroughFunction = passthroughFunction
            /**
             * If isPageKey the function param is true, then isPageKey the class var of KeyBundle becomes true.
             */
            if(isPageKey)
            {
                this.isPageKey = true
                this.isLocked = lockState
                return //No need to do anything further to lock page keys themselves.
            }

            /**
             * Check if we're global and seek out all instances of which page has the key and issue locks
             * into it's context object that can be checked against during [ContextWindow.selectAndTruncateContext]
             */
            if(isGlobal)
            {
                for(page in ContextBank.getPageKeys())
                {
                    //Get as a reference to avoid pointless copies when we're only doing a read to locate keys.
                    val pagedWindow = ContextBank.getContextFromBank(page, false)
                    val lorebook = pagedWindow.findLoreBookEntry(key)

                    if(lorebook != null)
                    {
                        keys.add(lorebook.key) //Save the lorebook key as written to it to ensure case safety.
                    }

                    /**Bind that we're locked or not so we can read this later
                     * during [ContextWindow.selectAndTruncateContext] calls.*/
                    val isGlobalMetadataForWindow = true
                    pagedWindow.metaData["isLocked"] = lockState

                    this.pages.add(page)
                }


            }

            /**
             * We're not in a global state. So now we need to grab the exact page and seek out if we can find the key
             * we're locking. If we can't, we just exit and bind nothing. If we can we mark it up and manage the lock
             * state that was requested.
             */
            else
            {
                val pagedWindow = ContextBank.getContextFromBank(key, false)
                val lorebook = pagedWindow.findLoreBookEntry(key)
                if(lorebook != null)
                {
                    this.keys.add(lorebook.key) //Ensure case safety.
                    this.isLocked = lockState

                    //Mark this context window to indicate a lock has been placed upon it.
                    pagedWindow.metaData["isLocked"] = lockState
                }

                else
                {
                    return //If we can't find the key there's nothing to lock.
                }
            }
        }

        //Finally save our new key bundle to our ContextLock system.
        locks[key.lowercase()] = newKeyBundle
    }

    
}