sed -i '/suspend fun emplaceWithMutex(key: String, window: ContextWindow, mode: StorageMode, skipRemote: Boolean = false)/,/}/c\
    suspend fun emplaceWithMutex(key: String, window: ContextWindow, mode: StorageMode, skipRemote: Boolean = false)\
    {\
        val writeBackFunction = writeBackFunctions[key]\
        if(writeBackFunction != null)\
        {\
            writeBackFunction(key, window)\
            return\
        }\
\
        bankMutex.withLock {\
            emplace(key, window, mode, skipRemote)\
        }\
    }' src/main/kotlin/Context/ContextBank.kt

sed -i '/suspend fun emplaceWithMutex(key: String, window: ContextWindow, persistToDisk: Boolean = false, skipRemote: Boolean = false)/,/}/c\
    suspend fun emplaceWithMutex(key: String, window: ContextWindow, persistToDisk: Boolean = false, skipRemote: Boolean = false)\
    {\
        val writeBackFunction = writeBackFunctions[key]\
        if(writeBackFunction != null)\
        {\
            writeBackFunction(key, window)\
            return\
        }\
\
        bankMutex.withLock {\
            val mode = if(persistToDisk) StorageMode.MEMORY_AND_DISK else StorageMode.MEMORY_ONLY\
            emplace(key, window, mode, skipRemote)\
        }\
    }' src/main/kotlin/Context/ContextBank.kt
