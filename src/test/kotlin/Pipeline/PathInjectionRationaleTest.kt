package com.TTT.Pipeline

import com.TTT.Pipe.buildDefaultPathInjection
import com.TTT.Config.TPipeConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PathInjectionRationaleTest
{
    @Test
    fun defaultInjectionIncludesRationaleDirectiveWhenFlagIsTrue()
    {
        val text = buildDefaultPathInjection(
            pathDescriptorJson = "[]",
            pathRequestSchema = "{}",
            requireRationale = true
        )
        assertTrue(text.contains("pathSelectionRationale"),
            "Injection MUST name the field so the LLM knows what to fill.")
        assertTrue(text.contains("MUST"),
            "Injection MUST use firm MUST language per operator direction.")
        assertTrue(text.contains("rationale"),
            "Injection MUST use the word 'rationale' as a clear natural-language noun.")
    }

    @Test
    fun defaultInjectionOmitsRationaleDirectiveWhenFlagIsFalse()
    {
        val text = buildDefaultPathInjection(
            pathDescriptorJson = "[]",
            pathRequestSchema = "{}",
            requireRationale = false
        )
        assertFalse(text.contains("pathSelectionRationale"),
            "When the policy is off, the field must not be advertised in the prompt.")
        assertFalse(text.contains("rationale"),
            "When the policy is off, the word 'rationale' must not appear.")
    }

    @Test
    fun defaultInjectionStillIncludesPathNameMustLanguageRegressionPin()
    {
        val text = buildDefaultPathInjection(
            pathDescriptorJson = "[]",
            pathRequestSchema = "{}",
            requireRationale = false
        )
        assertTrue(text.contains("MUST use the paths below"),
            "Existing pathName MUST contract must remain after the helper extraction.")
    }

    @Test
    fun traceDirResolvesNonBlank()
    {
        val traceDir = TPipeConfig.getTraceDir()
        assertTrue(traceDir.isNotBlank(),
            "TPipeConfig.getTraceDir() must return a non-blank trace dir.")
    }
}