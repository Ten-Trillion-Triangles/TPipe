package com.TTT

import com.TTT.Enums.PipeRole
import com.TTT.Structs.PipeSettings
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for the [PipeRole] enum and the [PipeSettings.pipeRole] field.
 *
 * `PipeRole` is the second of the three signals the pump station uses to figure
 * out which pipe in an agent's pipeline is the "decision pipe" (the one whose
 * output is the actual decision). The three signals are, in priority order:
 *
 *   1. Manual override (`Pipeline.decisionPipeName`)
 *   2. `Pipe.isDecisionPipe == true`
 *   3. `pipeSettings.pipeRole == PipeRole.Decision`
 *   4. Heuristic scoring
 *
 * `pipeRole` is a nullable field on [PipeSettings] (null = "not configured
 * yet", which is distinct from `PipeRole.Other` = "I considered it, it's
 * not a decision role").
 */
class PipeRoleEnumTest
{
    @Test
    fun pipeRoleEnumHasExpectedValues()
    {
        val expected = setOf(
            "Decision",
            "Preprocessor",
            "Postprocessor",
            "ContextLoader",
            "Validator",
            "Other"
        )
        val actual = PipeRole.values().map { it.name }.toSet()
        assertEquals(expected, actual, "PipeRole enum must have all six documented values")
    }

    @Test
    fun pipeRoleValueOfReturnsExpectedValue()
    {
        assertEquals(PipeRole.Decision, PipeRole.valueOf("Decision"))
        assertEquals(PipeRole.Preprocessor, PipeRole.valueOf("Preprocessor"))
        assertEquals(PipeRole.Other, PipeRole.valueOf("Other"))
    }

    @Test
    fun pipeSettingsPipeRoleDefaultsToNull()
    {
        val settings = PipeSettings()
        assertNull(settings.pipeRole, "pipeRole should default to null (not configured)")
    }

    @Test
    fun pipeSettingsPipeRoleIsSettable()
    {
        val settings = PipeSettings()
        settings.pipeRole = PipeRole.Decision
        assertEquals(PipeRole.Decision, settings.pipeRole)
    }
}
