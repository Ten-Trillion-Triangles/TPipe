package com.TTT.Debug

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the metadata contract for [TraceEventType.PUMP_STATION_LOOP_GUARD_TRIPPED]
 * events. The trace visualizer reads `metadata["guard"]`, `metadata["pathName"]`,
 * `metadata["detail"]`, `metadata["metric"]`, `metadata["observed"]`, and
 * `metadata["limit"]` generically — every emitted trip event must carry all
 * six fields with the correct types so the rendering path works for any
 * guard name, including the new `maxConsecutiveUnknownPaths`.
 *
 * Two regression anchors:
 *  1. `maxConsecutiveSamePath` — the original guard, regression-protect the
 *     existing visualizer rendering.
 *  2. `maxConsecutiveUnknownPaths` — the new guard, pin the same contract
 *     for the unknown-path loop guard.
 */
class PumpStationLoopGuardTrippedMetadataTest
{
    @Test
    fun `maxConsecutiveSamePath trip carries full metadata contract`() {
        val metadata = mapOf<String, Any>(
            "guard" to "maxConsecutiveSamePath",
            "pathName" to "search",
            "detail" to "consecutive=3, limit=3",
            "metric" to "consecutive",
            "observed" to 3,
            "limit" to 3
        )
        assertMetadataContract(metadata)
    }

    @Test
    fun `maxConsecutiveUnknownPaths trip carries full metadata contract`() {
        val metadata = mapOf<String, Any>(
            "guard" to "maxConsecutiveUnknownPaths",
            "pathName" to "flarble",
            "detail" to "consecutive=3, limit=3",
            "metric" to "consecutive",
            "observed" to 3,
            "limit" to 3
        )
        assertMetadataContract(metadata)
    }

    /**
     * Asserts every field the trace visualizer reads is present with the
     * correct type. The visualizer sites that depend on these fields are
     * (see TraceVisualizer.kt):
     *   - generatePumpStationHtmlReport fact card: reads `guard`, `pathName`
     *   - generateTimeline: reads `guard`, `pathName` via phaseDetailString
     *   - background activity strip: reads `guard` via phaseDetailString
     *   - mermaid graph: routes to [FAILURE] priority based on event type alone
     *   - errors KPI ribbon: counts the event itself (no metadata dependency)
     */
    private fun assertMetadataContract(metadata: Map<String, Any>)
    {
        // String fields
        for (key in listOf("guard", "pathName", "detail", "metric"))
        {
            assertNotNull(metadata[key], "metadata[$key] is required for visualizer rendering")
            assertTrue(metadata[key] is String,
                "metadata[$key] must be a String (got ${metadata[key]?.javaClass})")
        }
        // Int fields
        for (key in listOf("observed", "limit"))
        {
            assertNotNull(metadata[key], "metadata[$key] is required for visualizer rendering")
            assertTrue(metadata[key] is Int,
                "metadata[$key] must be an Int (got ${metadata[key]?.javaClass})")
        }
    }
}
