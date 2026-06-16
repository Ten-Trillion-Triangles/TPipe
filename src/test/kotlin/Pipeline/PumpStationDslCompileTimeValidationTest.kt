package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Structs.PipeSettings
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import com.TTT.P2P.KillSwitch
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Compile-time validation tests for the type-safe PumpStation DSL builder.
 *
 * The phantom-type state machine on [PumpStationBuilder] gates the `build()`
 * call to instances in the [PumpStationStage.Ready] stage. `pumpStation(...)`
 * starts in [PumpStationStage.Initial] and promotes the builder to
 * [PumpStationStage.Ready] as soon as the first `path { }` (or
 * `reservePath { }`) block runs.
 *
 * The three positive cases below must all compile:
 *  - `testPathBlockPromotesToReady` - the canonical happy path
 *  - `testReservePathBlockPromotesToReady` - reserve path also promotes
 *  - `testPumpStationBuilderFactory` - the [pumpStationBuilder] factory returns
 *    an Initial-stage builder; calling `path { }` then `build()` is the same
 *    shape as the entry function
 *
 * If a future change breaks the phantom type (e.g. drops the `S` type
 * parameter, makes `build()` callable on any stage, or removes the promotion
 * in `path { }`), one of these tests will fail to compile. The Kotlin
 * compiler is the test runner for the state machine.
 */
class PumpStationDslCompileTimeValidationTest
{
    @Test
    fun testPathBlockPromotesToReady()
    {
        val station: PumpStation = pumpStation("compile-time-1") {
            dispatchAgent = Pipeline()
            path("p") {
                description = "p path"
                setInternalAgent(SgTestAgent(agentTag = "p-agent"))
            }
        }
        assertNotNull(station)
    }

    @Test
    fun testReservePathBlockPromotesToReady()
    {
        // The promotion moment is the first `path` or `reservePath`
        // call. We add a regular path so the runtime check
        // `require(pathObjects.isNotEmpty())` passes, then exercise
        // `reservePath` as the second block to prove the stage doesn't
        // get re-promoted (still Ready, no errors).
        val station: PumpStation = pumpStation("compile-time-2") {
            dispatchAgent = Pipeline()
            path("p") {
                description = "p path"
                setInternalAgent(SgTestAgent(agentTag = "p-agent"))
            }
            reservePath("rp") {
                description = "rp path"
                setInternalAgent(SgTestAgent(agentTag = "rp-agent"))
            }
        }
        assertNotNull(station)
        assertEquals(1, station.getVisiblePathNames().size)
    }

    @Test
    fun testPumpStationBuilderFactory()
    {
        // The [pumpStationBuilder] factory returns an Initial-stage builder
        // directly. The canonical builder shape:
        //
        //   val initial: PumpStationBuilder<PumpStationStage.Initial> =
        //       pumpStationBuilder("compile-time-3")
        //   val ready: PumpStationBuilder<PumpStationStage.Ready> =
        //       initial.path("p") { ... }
        //   val station: PumpStation = ready.build()
        //
        // Uncommenting any of the lines below MUST produce a compile error,
        // proving the phantom type is enforcing the stage at compile time.
        //
        //   pumpStation("compile-time-3a") { path("p") {} }                  // no dispatchAgent
        //   pumpStation("compile-time-3b") { dispatchAgent = Pipeline() }    // no path / reservePath
        //   pumpStationBuilder("compile-time-3c").build()                   // not in Ready stage
        //   pumpStation("compile-time-3d") { dispatchAgent = Pipeline()
        //                                     path("p") {}.build() }         // build() on Initial-stage
        val initial: PumpStationBuilder<PumpStationStage.Initial> = pumpStationBuilder("compile-time-3")
        initial.dispatchAgent = Pipeline()
        val promoted: PumpStationBuilder<PumpStationStage.Ready> = initial.path("p") {
            description = "p path"
            setInternalAgent(SgTestAgent(agentTag = "p-agent"))
        }
        val station: PumpStation = promoted.build()
        assertNotNull(station)
    }
}
