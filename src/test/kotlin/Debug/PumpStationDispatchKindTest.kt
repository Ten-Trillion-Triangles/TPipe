package com.TTT.Debug

import org.junit.jupiter.api.Test

class PumpStationDispatchKindTest {

    @Test
    fun dispatchTraceAcceptsKindArgumentWithoutThrowing() {
        // Smoke: the new `kind` arg compiles + accepts a string. Real wiring
        // lives in the live test (Task 6).
        // No remote URL is set → dispatcher no-ops (return at RemoteTraceDispatcher.kt:49).
        // Just assert it didn't throw a "no such param" error.
        RemoteTraceDispatcher.dispatchTrace(
            pipelineId = "ps-stub",
            name = "ps-stub",
            status = "SUCCESS",
            kind = "pumpstation",
        )
    }
}