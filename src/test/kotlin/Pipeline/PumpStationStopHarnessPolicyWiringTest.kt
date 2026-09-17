package com.TTT.Pipeline

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for the stop-on-invalid-dispatch policy wiring.
 *
 * These tests intentionally configure the stop flag without also configuring
 * the whole failure policy, so a disconnected PumpStation field or DSL field
 * cannot be masked by a later policy assignment.
 */
class PumpStationStopHarnessPolicyWiringTest
{
    @Test
    fun directSetterWritesTheRuntimeFailurePolicyAndCanBeTurnedOff()
    {
        val station = PumpStation()

        station.setStopHarnessOnInvalidPathRequest(true)
        assertTrue(station.failurePolicy.stopHarnessOnInvalidPathRequest)

        station.setStopHarnessOnInvalidPathRequest(false)
        assertFalse(station.failurePolicy.stopHarnessOnInvalidPathRequest)
    }

    @Test
    fun stationFailurePolicyDefaultsToFalse()
    {
        assertFalse(PumpStation().failurePolicy.stopHarnessOnInvalidPathRequest)
    }

    @Test
    fun dslStopFlagWritesTheRuntimeFailurePolicy()
    {
        val station = pumpStation("stop-policy-dsl") {
            dispatchAgent = Pipeline()
            stopHarnessOnInvalidPathRequest = true
            path("test") {
                description = "test path"
            }
        }

        assertTrue(station.failurePolicy.stopHarnessOnInvalidPathRequest)
    }
}
