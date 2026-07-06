package com.TTT.Pipeline

import kotlinx.serialization.json.Json
import com.TTT.Pipeline.PathRequest
import com.TTT.Pipeline.PumpStationFailurePolicy
import com.TTT.Config.TPipeConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PathRequestRationaleTest
{
    @Test
    fun pathRequestRationaleFieldIsNullByDefault()
    {
        val request = PathRequest(pathName = "research", pathSchema = "{}")
        assertNull(request.pathSelectionRationale,
            "Rationale should default to null for back-compat with old callers.")
    }

    @Test
    fun pathRequestSerializesWithRationaleField()
    {
        val request = PathRequest(
            pathName = "research",
            pathSchema = "{}",
            pathSelectionRationale = "Picked research because the user asked for the history of X."
        )
        val json = Json.encodeToString(PathRequest.serializer(), request)
        assertTrue(json.contains("pathSelectionRationale"),
            "Rationale must be serialized into the JSON body")
        assertTrue(json.contains("Picked research because"),
            "Rationale text must be preserved verbatim")
    }

    @Test
    fun pathRequestDeserializesOldShapeWithRationaleNull()
    {
        val oldShape = """{"pathName":"research","pathSchema":"{}"}"""
        val decoded = Json.decodeFromString(PathRequest.serializer(), oldShape)
        assertEquals("research", decoded.pathName)
        assertNull(decoded.pathSelectionRationale,
            "Old JSON without the field must decode with rationale=null (back-compat).")
    }

    @Test
    fun failurePolicyDefaultsRationaleRequirementToTrue()
    {
        val policy = PumpStationFailurePolicy()
        assertEquals(true, policy.requirePathSelectionRationale,
            "Default MUST be true per operator direction. Off-switch available via setter.")
        val traceDir = TPipeConfig.getTraceDir()
        assertTrue(traceDir.isNotBlank(),
            "TPipeConfig.getTraceDir() must return a non-blank trace dir so subsequent tests can write traces.")
    }
}
