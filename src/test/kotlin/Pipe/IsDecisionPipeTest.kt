package com.TTT

import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [com.TTT.Pipe.Pipe.isDecisionPipe]. The default value must be
 * `false` (most pipes are NOT the decision pipe) and the field must be
 * overridable in subclasses (the test pipe declares `override val`).
 */
class IsDecisionPipeTest
{
    private class DefaultPipe : Pipe()
    {
        override suspend fun generateText(promptInjector: String): String = "x"
        override fun truncateModuleContext(): Pipe = this
    }

    private class DecisionPipe : Pipe()
    {
        override val isDecisionPipe: Boolean = true
        override suspend fun generateText(promptInjector: String): String = "x"
        override fun truncateModuleContext(): Pipe = this
    }

    @Test
    fun isDecisionPipeDefaultsToFalse()
    {
        val pipe = DefaultPipe()
        assertFalse(pipe.isDecisionPipe, "Default pipe.isDecisionPipe must be false")
    }

    @Test
    fun isDecisionPipeIsOverridableInSubclass()
    {
        val pipe = DecisionPipe()
        assertTrue(pipe.isDecisionPipe, "Subclass override of isDecisionPipe must be honored")
    }
}
