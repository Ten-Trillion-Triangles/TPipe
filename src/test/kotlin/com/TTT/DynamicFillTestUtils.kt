package com.TTT

import com.TTT.Context.ContextWindow
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.TruncationSettings

class DynamicFillTestPipe : Pipe()
{
    fun addContextWindow(key: String, window: ContextWindow)
    {
        miniContextBank.contextMap[key] = window
    }

    override fun truncateModuleContext(): Pipe
    {
        return this
    }

    override suspend fun generateText(promptInjector: String): String
    {
        return promptInjector
    }
}

internal fun createContextWindowWithWords(wordCount: Int): ContextWindow
{
    val window = ContextWindow()
    if(wordCount > 0)
    {
        val tokens = List(wordCount) { "token" }.joinToString(" ")
        window.contextElements.add(tokens)
    }
    return window
}

internal fun Pipe.invokeCalculateDynamicFill(
    totalBudget: Int,
    pageKeys: List<String>,
    truncationSettings: TruncationSettings
): Map<String, Int>
{
    val method = Pipe::class.java.getDeclaredMethod(
        "calculateDynamicFill",
        Int::class.javaPrimitiveType,
        List::class.java,
        TruncationSettings::class.java
    )
    method.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    return method.invoke(this, totalBudget, pageKeys, truncationSettings) as Map<String, Int>
}

internal fun Pipe.invokeCalculatePriorityFill(
    totalBudget: Int,
    pageKeys: List<String>,
    truncationSettings: TruncationSettings
): Map<String, Int>
{
    val method = Pipe::class.java.getDeclaredMethod(
        "calculatePriorityFill",
        Int::class.javaPrimitiveType,
        List::class.java,
        TruncationSettings::class.java
    )
    method.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    return method.invoke(this, totalBudget, pageKeys, truncationSettings) as Map<String, Int>
}

internal fun Pipe.invokeRedistributeBudgetDynamically(
    initialAllocations: Map<String, Int>,
    simulatedUsage: Map<String, Int>,
    totalBudget: Int,
    truncationSettings: TruncationSettings
): Map<String, Int>
{
    val method = Pipe::class.java.getDeclaredMethod(
        "redistributeBudgetDynamically",
        Map::class.java,
        Map::class.java,
        Int::class.javaPrimitiveType,
        TruncationSettings::class.java
    )
    method.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    return method.invoke(this, initialAllocations, simulatedUsage, totalBudget, truncationSettings) as Map<String, Int>
}

internal fun Pipe.invokeSimulateTruncationUsage(
    allocations: Map<String, Int>,
    truncationSettings: TruncationSettings
): Map<String, Int>
{
    val method = Pipe::class.java.getDeclaredMethod(
        "simulateTruncationUsage",
        Map::class.java,
        TruncationSettings::class.java
    )
    method.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    return method.invoke(this, allocations, truncationSettings) as Map<String, Int>
}
