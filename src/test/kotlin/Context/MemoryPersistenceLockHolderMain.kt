package com.TTT.Context

import java.nio.file.Path

/**
 * Helper entry point used by [MemoryPersistenceTest] to hold an exclusive memory-file lock from another JVM.
 */
fun main(args: Array<String>)
{
    require(args.size == 2) { "Expected <filePath> <holdMillis>" }

    val filePath = Path.of(args[0])
    val holdMillis = args[1].toLong()

    MemoryPersistence.withLock(filePath, shared = false) {
        println("LOCKED")
        System.out.flush()
        Thread.sleep(holdMillis)
    }
}
