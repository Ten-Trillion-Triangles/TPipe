package com.TTT.Context

import com.TTT.Config.TPipeConfig
import com.TTT.Util.deepCopy
import com.TTT.Util.deleteFile
import com.TTT.Util.deserialize
import com.TTT.Util.readStringFromFile
import com.TTT.Util.serialize
import com.TTT.Util.writeStringToFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File


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
     * Stores todo lists as page keys to active todo lists. Allows for multiple agents to access this and
     * update, or view the todo lists as sandboxed, or global tasks.
     */
    @Volatile
    private var todoList = mutableMapOf<String, TodoList>()

    /**
     * Mutex object for managing swapping the banked context window.
     */
    val swapMutex = Mutex()

    /**
     * Mutex used for locking access to the bank so that multiple coroutines can safely update the bank.
     */
    val bankMutex = Mutex()

    /**
     * Mutex for accessing the todo list system in this context bank.
     */
    val todoMutex = Mutex()


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
    fun emplace(key: String, window: ContextWindow, persistToDisk: Boolean = false)
    {
        bank[key] = window
        val bankDir = "${TPipeConfig.getLorebookDir()}/${key}.bank"

        if(persistToDisk || File(bankDir).exists())
        {
            val value = serialize(window)
            writeStringToFile(bankDir, value)
        }
    }


    /**
     * Safely emplace a context window back using the mutex. This is the recommended way to emplace when possible.
     * This should always be used over the regular emplace if you are updating the context inside a pipe or pipeline.
     */
    suspend fun emplaceWithMutex(key: String, window: ContextWindow, persistToDisk: Boolean = false)
    {
        bankMutex.withLock {
            emplace(key, window, persistToDisk)
        }
    }

    /**
     * Delete the key file that is holding a persisting context bank key.
     */
    fun deletePersistingBankKey(key: String) : Boolean
    {
        val bankDir = "${TPipeConfig.getLorebookDir()}/${key}.bank"
        return deleteFile(bankDir)
    }

    /**
     * Delete the key file that is holding a persisting context bank key, and lock with the bank mutex for thread
     * safety.
     */
    suspend fun deletePersistingBankKeyWithMutex(key: String) : Boolean
    {
        bankMutex.withLock {
            return deletePersistingBankKey(key)
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
        var context = bank[key] ?: ContextWindow()

        /**
         * Automatically read from disk if this key is persisted. Triggers if the key is not loaded into memory,
         * but is found on disk.
         */
        val diskPath = "${TPipeConfig.getLorebookDir()}/${key}.bank"
        if(File(diskPath).exists() && !bank.containsKey(key))
        {
            val contextJson = readStringFromFile(diskPath)
            context = deserialize<ContextWindow>(contextJson) ?: ContextWindow()
        }

        if(copy)
        {
            return context.deepCopy()
        }

        return context
    }

    /**
     * Access function to get all the pages that are stored inside the context bank.
     */
    fun getPageKeys() : List<String>
    {
        return bank.keys.toList()
    }

    /**
     * Clear all banked context. Useful when some code is checking if this contains data or not and applies logic
     * if it does.
     */
    fun clearBankedContext()
    {
        bankedContextWindow = ContextWindow()
    }

    /**
     * Get a todo list by it's page key.
     */
    fun getPagedTodoList(key: String, copy: Boolean = true) : TodoList
    {
        /**
         * Check if a todo list has been cached to disk. If it has been and we have not yet loaded it. Tread this
         * read attempt as a request to load it from disk.
         */
        if(!todoList.containsKey(key))
        {
            val diskPath = TPipeConfig.getTodoListDir()
            val fullFilePath = "${diskPath}/${key}.todo"
            val fileContents = readStringFromFile(fullFilePath)

            if(fileContents.isNotEmpty())
            {
                val result = deserialize<TodoList>(fileContents) ?: TodoList()
                return result
            }

            return TodoList()
        }

        //Handle default copy behavior for thread saftey.
        if(copy)
        {
            val list = todoList[key] ?: TodoList()
            val listCopy = list.deepCopy()
            return listCopy
        }

        //Return reference if user requested a direct reference.
        val list = todoList[key] ?: TodoList()
        return list
    }

    /**
     * Emplace a new todo list into the context bank. Adding if it does not exist, or overwriting it if it does.
     * @param key Bank key to write into.
     * @param todoList [TodoList] to write into the page.
     * @param  allowUpdatesOnly If true, only existing tasks on the list can be modified, no new tasks can be added.
     * Does not apply if the page is empty or does not exist yet.
     * @param allowCompletionsOnly If true, any existing tasks can only allow the isCompleted checkbox to be marked
     * true or false. No other changes to the task are allowed. Does not affect tasks that do not exist yet in the
     * task list.
     * @param persistToDisk If true, this task will be written directly to disk as well as memory. If a task is found
     * by this name on disk. That will be overwritten regardless of weather this true or not.
     */
    fun emplaceTodoList(
        key: String,
        todoList: TodoList,
        allowUpdatesOnly: Boolean = true,
        allowCompletionsOnly: Boolean = false,
        persistToDisk: Boolean = false
    )
    {
        //Declare array for testing valid tasks. Also cache our banked tasks to compare if required.
        val validTaskNumbers = mutableListOf<Int>()
        val bankedTasks = ContextBank.todoList[key]

        /**
         * Ignore both write protect flags because there's nothing banked at this key right now.
         * Instead, just right into it. Because we need to return early we need to adress writing
         * to the file here resulting in us having to frustratingly duplicate this. However, given that we
         * won't be writing to that file path anywhere else in this entire codebase it's not justifiable
         * making that step into its own function.
         */
        if(bankedTasks == null)
        {
            ContextBank.todoList[key] = todoList

            val todoPath = TPipeConfig.getTodoListDir()
            val fullFilePath = "${todoPath}/key.todo"

            if(persistToDisk || File(fullFilePath).exists())
            {
                val todoAsString = serialize(todoList)
                writeStringToFile(fullFilePath, todoAsString)
                return
            }
        }

        //Write protect: Disallow the llm adding any new items to the checklist.
        if(allowUpdatesOnly)
        {
            for(task in todoList.tasks.tasks)
            {
                val isValidTaskNumber = bankedTasks?.find(task.taskNumber)
                if(isValidTaskNumber != null)
                {
                    validTaskNumbers.add(task.taskNumber)
                }
            }
        }

        var todoListToEmplace = TodoList()

        //Enforce our write protect on only allowing existing tasks to be updated.
        if(validTaskNumbers.isNotEmpty())
        {
            for(number in validTaskNumbers)
            {
                val task = todoList.find(number)
                if(task != null) todoListToEmplace.tasks.tasks.add(task)
            }
        }

        else todoListToEmplace = todoList

        /**
         * Write protect to only allow the completion status of a task to be updated.
         * This only applies to tasks already in the array. Any new tasks will just be added since the prior
         * write protect guard will already prevent adding new tasks that weren't there to begin with.
         */
        if(allowCompletionsOnly)
        {
            for(task in todoListToEmplace.tasks.tasks)
            {
                bankedTasks?.find(task.taskNumber)?.isComplete = task.isComplete
            }
        }

        //Otherwise allow the entire task to be written over.
        else
        {
            for(task in todoListToEmplace.tasks.tasks)
            {
                if(bankedTasks?.tasks?.tasks!!.contains(task))
                {
                    bankedTasks.tasks.tasks[bankedTasks.tasks.tasks.indexOf(task)] = task
                }

                bankedTasks.tasks.tasks.add(task)
            }
        }

        ContextBank.todoList[key] = bankedTasks as TodoList

        val todoPath = TPipeConfig.getTodoListDir()
        val fullFilePath = "${todoPath}/key.todo"

        //Required here too because we can't justify a full function call over this small snippet of code.
        if(persistToDisk || File(fullFilePath).exists())
        {
            val todoAsString = serialize(bankedTasks)
            writeStringToFile(fullFilePath, todoAsString)
        }
    }

    /**
     * Thread safe emplace call to emplace a todo list. Calls [emplaceTodoList] under the hood while invoking a mutex
     * lock for safety. Shares the same params as [emplaceTodoList].
     */
    suspend fun emplaceWithMutex(
        key: String,
        todoList: TodoList,
        allowUpdatesOnly: Boolean = true,
        allowCompletionsOnly: Boolean = false,
        persistToDisk: Boolean = false
    )
    {
        todoMutex.withLock {
            emplaceTodoList(key,
                todoList,
                allowUpdatesOnly,
                allowCompletionsOnly,
                persistToDisk)
        }
    }
}