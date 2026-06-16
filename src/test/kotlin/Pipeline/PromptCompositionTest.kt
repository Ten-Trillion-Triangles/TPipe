package com.TTT.Pipeline

import org.junit.Test
import kotlin.test.assertTrue

class PromptCompositionTest
{
    @Test
    fun testBuildJudgeSystemPromptFillsPlaceholders()
    {
        val station = PumpStation()
            .setPersonality("friendly")
            .setSystemTask("debug this")
            .setUserGuidelines("be concise")
            .setEntryUserPrompt("fix the bug")
        val prompt = station.buildJudgeSystemPrompt()
        assertTrue(prompt.contains("friendly"))
        assertTrue(prompt.contains("debug this"))
        assertTrue(prompt.contains("be concise"))
        assertTrue(prompt.contains("fix the bug"))
    }

    @Test
    fun testBuildDispatchSystemPromptFillsPlaceholders()
    {
        val station = PumpStation()
            .setSystemTask("research")
            .setEntryUserPrompt("find X")
        val prompt = station.buildDispatchSystemPrompt()
        assertTrue(prompt.contains("research"))
        assertTrue(prompt.contains("find X"))
    }

    @Test
    fun testBuildGoalSystemPromptFillsPlaceholders()
    {
        val station = PumpStation()
            .setEntryUserPrompt("the task")
        val prompt = station.buildGoalSystemPrompt()
        assertTrue(prompt.contains("the task"))
    }
}
