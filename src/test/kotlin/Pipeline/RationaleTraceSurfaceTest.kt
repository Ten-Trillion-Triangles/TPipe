package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RationaleTraceSurfaceTest
{
    @Test
    fun dispatchCompletedEventSurfacesRationaleViaPathRequest()
    {
        val rationale = "Picked research because the user asked about the history of Kotlin coroutines."
        val request = PathRequest(
            pathName = "research",
            pathSchema = "{}",
            pathSelectionRationale = rationale
        )
        val event = DispatchCompleted(
            runId = "test-run",
            turnIndex = 1,
            selectedPathName = "research",
            pathRequest = request
        )
        assertEquals("research", event.selectedPathName)
        assertEquals(
            rationale,
            event.pathRequest?.pathSelectionRationale,
            "Judge and trace consumers will read pathRequest.pathSelectionRationale — verify the wire-up."
        )
    }

    @Test
    fun dispatchCompletedEventWithRationaleNullDoesNotCrash()
    {
        val request = PathRequest(pathName = "research", pathSchema = "{}")
        val event = DispatchCompleted(
            runId = "test-run",
            turnIndex = 1,
            selectedPathName = "research",
            pathRequest = request
        )
        // No exception. The accessor is null-tolerant (this is the back-compat path for old
        // checkpoints that don't emit rationale; the trace events decode with rationale=null).
        assertEquals(null, event.pathRequest?.pathSelectionRationale)
    }
}
