with open("src/main/kotlin/Context/ContextBank.kt", "r") as f:
    content = f.read()

target2 = """    suspend fun emplaceWithMutex(key: String, window: ContextWindow, persistToDisk: Boolean = false, skipRemote: Boolean = false)
    {
        bankMutex.withLock {
            val writeBackFunction = writeBackFunctions[key]
            if(writeBackFunction != null)
            {
                writeBackFunction(key, window)
                return@withLock
            }

            val mode = if(persistToDisk) StorageMode.MEMORY_AND_DISK else StorageMode.MEMORY_ONLY
            emplace(key, window, mode, skipRemote)
            }
    }"""
replacement2 = """    suspend fun emplaceWithMutex(key: String, window: ContextWindow, persistToDisk: Boolean = false, skipRemote: Boolean = false)
    {
        val writeBackFunction = writeBackFunctions[key]
        if(writeBackFunction != null)
        {
            writeBackFunction(key, window)
            return
        }

        bankMutex.withLock {
            val mode = if(persistToDisk) StorageMode.MEMORY_AND_DISK else StorageMode.MEMORY_ONLY
            emplace(key, window, mode, skipRemote)
        }
    }"""
content = content.replace(target2, replacement2)

with open("src/main/kotlin/Context/ContextBank.kt", "w") as f:
    f.write(content)
