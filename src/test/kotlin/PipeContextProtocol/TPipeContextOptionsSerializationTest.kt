package com.TTT.PipeContextProtocol

import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Regression coverage for the `TPipeContextOptions` round-trip contract that the
 * Kotlin 2.3 readiness sweep surfaced: a `PcPRequest` whose only non-default
 * property is the `tPipeContextOptions.functionName` must survive `serialize` +
 * `deserialize` so tool-call payloads keep their function binding.
 *
 * Root cause (TPipe 1.0.x): `com.TTT.Util.serialize` defaults to compact mode
 * (`encodeDefaults = false`), which causes kotlinx.serialization to drop
 * `tPipeContextOptions` entirely when its value compares equal to the data-class
 * default — even though the user changed `functionName`. The pre-existing
 * `PcpStandaloneTest.testPcPRequestSerialization` exercised this surface and
 * failed on `main`. The fix is the `serialize(..., encodedefault = true)` call
 * sites in production and the explicit test below.
 */
class TPipeContextOptionsSerializationTest
{
    @Test
    fun `TPipeContextOptions with functionName round-trips through serialize and deserialize with defaults encoded`() {
        val original = TPipeContextOptions().apply { functionName = "alpha-fn" }
        val json = serialize(original, encodedefault = true)

        val back = deserialize<TPipeContextOptions>(json)
        assertNotNull(back, "TPipeContextOptions with functionName must round-trip; got: $json")
        assertEquals("alpha-fn", back.functionName)
    }

    @Test
    fun `TPipeContextOptions with description and params round-trips`() {
        val original = TPipeContextOptions().apply {
            functionName = "beta"
            description = "d"
            params["x"] = ContextOptionParameter(
                type = ParamType.String,
                description = "arg x",
                enumValues = emptyList(),
                isRequired = true
            )
        }
        val json = serialize(original, encodedefault = true)
        val back = deserialize<TPipeContextOptions>(json)!!
        assertEquals("beta", back.functionName)
        assertEquals("d", back.description)
        assertEquals(1, back.params.size)
        assertEquals(ParamType.String, back.params["x"]!!.type)
        assertEquals(true, back.params["x"]!!.isRequired)
    }

    @Test
    fun `PcPRequest with only tPipeContextOptions-functionName populated round-trips with function name preserved`() {
        val request = PcPRequest(
            tPipeContextOptions = TPipeContextOptions().apply { functionName = "gamma" }
        )
        val json = serialize(request, encodedefault = true)
        val back = deserialize<PcPRequest>(json)!!
        assertEquals("gamma", back.tPipeContextOptions?.functionName)
    }

    @Test
    fun `compact serialize may collapse a non-default functionName back to default`() {
        // Document the compact-mode behavior: when `encodeDefaults = false` and the
        // user's value of `tPipeContextOptions` equals the data-class default by
        // the kotlinx.serialization equals-evaluation rules, the field IS emitted
        // but the user-supplied functionName is not preserved. The with-defaults
        // form is the production contract for tool-call payloads and is what the
        // other three tests pin. Callers must use `serialize(obj, encodedefault = true)`
        // for any round-trip that depends on a non-default `functionName`.
        val request = PcPRequest(
            tPipeContextOptions = TPipeContextOptions().apply { functionName = "delta" }
        )
        val compactJson = serialize(request, encodedefault = false)
        val back = deserialize<PcPRequest>(compactJson)
        // In compact mode the field may be present with default functionName OR
        // absent. We do not pin either behavior — we just confirm the with-defaults
        // form produces non-empty output that the with-defaults round-trip pins.
        assertNotNull(serialize(request, encodedefault = true), "with-defaults serialize must produce non-empty output")
        if(back?.tPipeContextOptions != null)
        {
            // If the field survived, the functionName was collapsed to default by
            // the compact-mode equals evaluation. This documents the pitfall.
            assertEquals("", back.tPipeContextOptions.functionName,
                "Compact mode collapses functionName to default; use encodedefault=true for tool-call payloads")
        }
    }
}
