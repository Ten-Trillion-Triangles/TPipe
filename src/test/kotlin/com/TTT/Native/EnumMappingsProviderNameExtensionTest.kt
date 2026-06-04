package com.TTT.Native

import com.TTT.Enums.ProviderName as KotlinProviderName
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the C ABI [EnumMappings.ProviderName] extension that adds the
 * OPENROUTER (10) and GENERIC_OPENAI (11) entries and the @JvmStatic
 * `toIntGenericOpenAI()` helper.
 *
 * The C ABI `ProviderName` enum is the cross-language bridge exposed to the
 * GraalVM native-image C ABI shim. These tests pin down:
 *  - the new C ABI integer assignments (10 / 11) and the round-trip
 *    `fromInt` mapping,
 *  - the regression that UNKNOWN remains at 9,
 *  - the updated Kotlin-to-ABI mapper that now resolves
 *    `KotlinProviderName.OpenRouter` to 10 instead of the previous
 *    UNKNOWN sentinel (9), and
 *  - the @JvmStatic helper that exposes the GenericOpenAI value to the
 *    C ABI shim (the Kotlin [KotlinProviderName] enum does not have a
 *    `GenericOpenAI` entry, so the shim calls this helper directly).
 */
class EnumMappingsProviderNameExtensionTest {

    //==========================================================================
    // fromInt round-trip
    //==========================================================================

    @Test
    fun fromIntTenResolvesToOpenRouter() {
        assertEquals(EnumMappings.ProviderName.OPENROUTER, EnumMappings.ProviderName.fromInt(10))
    }

    @Test
    fun fromIntElevenResolvesToGenericOpenAI() {
        assertEquals(EnumMappings.ProviderName.GENERIC_OPENAI, EnumMappings.ProviderName.fromInt(11))
    }

    @Test
    fun fromIntNineStillResolvesToUnknown() {
        // Regression: UNKNOWN must remain at 9 to preserve the
        // forward-compatibility sentinel for unmapped providers.
        assertEquals(EnumMappings.ProviderName.UNKNOWN, EnumMappings.ProviderName.fromInt(9))
    }

    //==========================================================================
    // cValue assignments
    //==========================================================================

    @Test
    fun unknownCValueIsNine() {
        // Regression: UNKNOWN must remain at 9 alongside the new OPENROUTER/GENERIC_OPENAI entries.
        assertEquals(EnumMappings.ProviderName.UNKNOWN, EnumMappings.ProviderName.fromInt(9))
    }

    @Test
    fun openRouterCValueIsTen() {
        assertEquals(EnumMappings.ProviderName.OPENROUTER, EnumMappings.ProviderName.fromInt(10))
    }

    @Test
    fun genericOpenAICValueIsEleven() {
        assertEquals(EnumMappings.ProviderName.GENERIC_OPENAI, EnumMappings.ProviderName.fromInt(11))
    }

    //==========================================================================
    // Kotlin -> C ABI mapper
    //==========================================================================

    @Test
    fun toIntMapsOpenRouterToTen() {
        // KotlinProviderName.OpenRouter must now resolve to OPENROUTER (10), not the previous UNKNOWN (9) fallback.
        assertEquals(10, EnumMappings.ProviderName.toInt(KotlinProviderName.OpenRouter))
    }

    @Test
    fun toIntMapsAwsToBedrockThree() {
        // Regression: Aws must continue to map to BEDROCK (3).
        assertEquals(3, EnumMappings.ProviderName.toInt(KotlinProviderName.Aws))
    }

    //==========================================================================
    // @JvmStatic helper for GenericOpenAI
    //==========================================================================

    @Test
    fun toIntGenericOpenAIIsEleven() {
        // The C ABI shim calls this @JvmStatic helper to resolve provider=11
        // because the Kotlin ProviderName enum has no GenericOpenAI entry.
        assertEquals(11, EnumMappings.ProviderName.toIntGenericOpenAI())
    }
}
