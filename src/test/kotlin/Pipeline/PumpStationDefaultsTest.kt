package com.TTT.Pipeline

import org.junit.Test
import kotlin.test.assertTrue

class PumpStationDefaultsTest
{
    @Test
    fun testDefaultJudgePromptMentionsJudge()
    {
        assertTrue(DEFAULT_JUDGE_PROMPT.contains("judge", ignoreCase = true))
    }

    @Test
    fun testDefaultDispatchPromptMentionsPath()
    {
        assertTrue(DEFAULT_DISPATCH_PROMPT.contains("path", ignoreCase = true))
    }

    @Test
    fun testDefaultGoalPromptMentionsVerify()
    {
        assertTrue(DEFAULT_GOAL_PROMPT.contains("verif", ignoreCase = true))
    }
}
